package dampf.lir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A loop whose code needs A in 8 bits only runs in 8 bits from its entry to its exits (B5 of
 * docs/current-compiler-flaws.md, for loops): in {@code SEP #$20 / STZ t,X / REP #$20 / INX /
 * CPX #n / BCC} the {@code SEP} moves before the loop and the {@code REP} to where it is left,
 * 6 cycles a round. Every block boundary of dampf's code runs with 16 bits; inside the loop that
 * no longer holds, and {@link LVerifier} follows the width across the blocks.
 *
 * <p>The loop: a header and the blocks that reach a back edge to it without passing it. It is
 * entered only at the header, from blocks that go nowhere else; the blocks it is left to are
 * reached from the loop only. Between a {@code REP #$20} and the next {@code SEP #$20} of the
 * loop there are instructions that do not care for the width (branches, the index registers, the
 * carry) and instructions whose low byte is the same in 8 bits, as {@link ByteStretches} takes
 * them ({@code LDA src,X / SEP / STA dst,X}): they run in 8 bits too, where nothing reads the
 * flags they set, an {@code ADC}/{@code SBC} gets its carry from {@code CLC}/{@code SEC}, nothing
 * in the loop reads the high byte of A ({@code TAX}, {@code TAY}, {@code XBA}) and A is not read
 * where the loop is left. No call, no return, no other {@code REP}/{@code SEP}. An immediate of A
 * gets its low byte and {@code .a8}/{@code .a16} around it; the other directives stay where they
 * are, as they size the immediates only.
 */
public final class ByteLoops {
    /** Instructions whose effect does not depend on the accumulator's width (index registers 16 bit). */
    private static final Set<String> ANY_WIDTH = Set.of("bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "brl",
            "jmp", "ldx", "ldy", "stx", "sty", "inx", "iny", "dex", "dey", "cpx", "cpy", "tax", "tay", "txy", "tyx", "clc",
            "sec", "nop", "xba", "phx", "phy", "plx", "ply");
    /** Instructions whose low byte of A is the same in 8 bits (given the carry), as in {@link ByteStretches}. */
    private static final Set<String> SAME_LOW_BYTE = Set.of("lda", "and", "ora", "eor", "adc", "sbc", "asl", "inc", "dec",
            "txa", "tya");
    /** Instructions that read the high byte of A whatever the width. */
    private static final Set<String> READ_HIGH_BYTE = Set.of("tax", "tay", "xba", "tcd", "tcs");

    private ByteLoops() {}

    /** Returns how many loops now run in 8 bits; {@code resultInA}: the function returns a value in A. */
    public static int run(LFunction f, boolean resultInA) {
        RedundantLoads a = new RedundantLoads(resultInA);
        int changed = 0;
        for (int round = 0; round < 64 && once(f, a); round++) {
            changed++;   // one at a time: the blocks and their widths change with each
        }
        return changed;
    }

    private static boolean once(LFunction f, RedundantLoads a) {
        if (f.blocks().isEmpty()) {
            return false;
        }
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            for (LBlock t : b.successors()) {
                preds.computeIfAbsent(t, k -> new ArrayList<>()).add(b);
            }
        }
        List<Set<LBlock>> loops = loops(f, preds);
        loops.sort((x, y) -> Integer.compare(y.size(), x.size()));   // the outermost first
        Map<LBlock, Integer> flagsOut = null;
        Map<LBlock, Boolean> aLive = null;
        for (Set<LBlock> loop : loops) {
            if (flagsOut == null) {
                flagsOut = RedundantLoads.flagsLiveOut(f);
                aLive = a.aLiveOut(f);
            }
            if (convert(f, loop, preds, flagsOut, aLive, a)) {
                f.rebuild(f.items());
                return true;
            }
        }
        return false;
    }

    /** The natural loops of f, one per header (the blocks of its back edges merged). */
    static List<Set<LBlock>> loops(LFunction f, Map<LBlock, List<LBlock>> preds) {
        Map<LBlock, Set<LBlock>> byHeader = new IdentityHashMap<>();
        Set<LBlock> done = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<LBlock> onStack = Collections.newSetFromMap(new IdentityHashMap<>());
        // iterative depth-first search: a stack of (block, index of the next successor)
        ArrayDeque<Object[]> stack = new ArrayDeque<>();
        LBlock first = f.blocks().getFirst();
        stack.push(new Object[] { first, 0 });
        onStack.add(first);
        while (!stack.isEmpty()) {
            Object[] top = stack.peek();
            LBlock b = (LBlock) top[0];
            int k = (int) top[1];
            List<LBlock> succ = b.successors();
            if (k == succ.size()) {
                stack.pop();
                onStack.remove(b);
                done.add(b);
                continue;
            }
            top[1] = k + 1;
            LBlock s = succ.get(k);
            if (onStack.contains(s)) {
                Set<LBlock> body = byHeader.computeIfAbsent(s, h -> {
                    Set<LBlock> n = Collections.newSetFromMap(new IdentityHashMap<>());
                    n.add(h);
                    return n;
                });
                ArrayDeque<LBlock> work = new ArrayDeque<>();
                if (body.add(b)) {
                    work.add(b);
                }
                while (!work.isEmpty()) {
                    for (LBlock p : preds.getOrDefault(work.pop(), List.of())) {
                        if (body.add(p)) {
                            work.add(p);
                        }
                    }
                }
            } else if (!done.contains(s)) {
                stack.push(new Object[] { s, 0 });
                onStack.add(s);
            }
        }
        return new ArrayList<>(byHeader.values());
    }

    private static boolean convert(LFunction f, Set<LBlock> loop, Map<LBlock, List<LBlock>> preds, Map<LBlock, Integer> flagsOut,
            Map<LBlock, Boolean> aLive, RedundantLoads a) {
        LBlock header = null;
        List<LBlock> entries = new ArrayList<>();
        for (LBlock b : loop) {
            for (LBlock p : preds.getOrDefault(b, List.of())) {
                if (!loop.contains(p)) {
                    if (header != null && header != b) {
                        return false;   // entered at two places
                    }
                    header = b;
                    entries.add(p);
                }
            }
        }
        if (header == null) {
            return false;
        }
        int switches = 0;
        boolean readsHigh = false;
        Set<LInstr> narrow = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LBlock b : loop) {
            boolean m16 = true;
            List<LInstr> code = b.instrs();
            for (int k = 0; k < code.size(); k++) {
                LInstr in = code.get(k);
                if (in.m16() != m16) {
                    return false;   // the block does not start with 16 bits
                }
                String m = in.mnemonic();
                readsHigh |= READ_HIGH_BYTE.contains(m);
                if (m.equals("rep") || m.equals("sep")) {
                    if (!isWidth(in)) {
                        return false;
                    }
                    m16 = m.equals("rep");
                    switches++;
                } else if (m16 && !ANY_WIDTH.contains(m)) {
                    if (!narrows(code, k)) {
                        return false;   // A used in 16 bits (a call, a return, a store, a compare)
                    }
                    narrow.add(in);
                }
            }
            if (!m16) {
                return false;       // nor end with them
            }
        }
        if (switches == 0) {
            return false;
        }
        for (LBlock p : entries) {
            LInstr last = p.last();
            if (p.successors().size() != 1 || last == null || !last.fallsThrough() && !last.isJump()
                    || !endsIn16(p)) {
                return false;
            }
        }
        List<LBlock> exits = new ArrayList<>();
        for (LBlock b : loop) {
            for (LBlock t : b.successors()) {
                if (!loop.contains(t) && !exits.contains(t)) {
                    for (LBlock p : preds.getOrDefault(t, List.of())) {
                        if (!loop.contains(p)) {
                            return false;   // the exit is reached from outside too
                        }
                    }
                    if (!t.instrs().isEmpty() && !t.instrs().getFirst().m16()) {
                        return false;
                    }
                    exits.add(t);
                }
            }
        }
        if (!narrow.isEmpty()) {
            if (readsHigh) {
                return false;
            }
            for (LBlock t : exits) {
                if (a.aLiveAtStart(t, aLive)) {
                    return false;   // the high byte of A is another after the loop
                }
            }
            for (LBlock b : loop) {
                int live = flagsOut.getOrDefault(b, RedundantLoads.N | RedundantLoads.Z | RedundantLoads.C | RedundantLoads.V);
                List<LInstr> code = b.instrs();
                for (int k = code.size() - 1; k >= 0; k--) {
                    LInstr in = code.get(k);
                    if (narrow.contains(in) && (RedundantLoads.writes(in) & live) != 0) {
                        return false;   // a flag of 8 bits is another
                    }
                    live = (live & ~RedundantLoads.writes(in)) | RedundantLoads.reads(in);
                }
            }
        }
        if (!readsBack(f, loop, entries, exits)) {
            return false;
        }
        // the loop's code in 8 bits, without its switches
        for (LBlock b : loop) {
            List<LItem> items = b.items();
            for (int k = 0; k < items.size(); k++) {
                if (items.get(k) instanceof LInstr in) {
                    if (isWidth(in)) {
                        items.remove(k--);
                    } else if (narrow.contains(in) && in.op() instanceof LOperand.Imm imm) {
                        items.set(k, new LInstr(in.mnemonic(), String.format("#$%02X", imm.value() & 0xFF), in.comment(), false,
                                in.x16(), in.relaxable()));
                        items.add(k, directive(LDirective.Kind.A8, ".a8"));
                        items.add(k + 2, directive(LDirective.Kind.A16, ".a16"));
                        k += 2;
                    } else if (in.m16()) {
                        items.set(k, new LInstr(in.mnemonic(), in.op(), in.comment(), false, in.x16(), in.relaxable()));
                    }
                }
            }
        }
        for (LBlock p : entries) {
            List<LItem> items = p.items();
            LInstr last = p.last();
            int at = last.fallsThrough() ? items.size() : items.lastIndexOf(last);
            items.add(at, new LInstr("sep", "#$20", null, true, last.x16(), false));
            dropPairs(p);
        }
        for (LBlock t : exits) {
            List<LItem> items = t.items();
            if (t.instrs().isEmpty()) {
                items.add(new LInstr("rep", "#$20", null, false, true, false));   // a block of its label only
            } else {
                LInstr first = t.instrs().getFirst();
                items.add(items.indexOf(first), new LInstr("rep", "#$20", null, false, first.x16(), false));
            }
            dropPairs(t);
        }
        return true;
    }

    /**
     * Removes a switch and the opposite one after it where only instructions that do not care for
     * the width lie between (the {@code REP / LDX / SEP} a switch moved before a loop meets).
     */
    private static void dropPairs(LBlock b) {
        List<LItem> items = b.items();
        for (int k = 0; k < items.size(); k++) {
            if (!(items.get(k) instanceof LInstr w) || !isWidth(w)) {
                continue;
            }
            for (int j = k + 1; j < items.size(); j++) {
                if (!(items.get(j) instanceof LInstr in)) {
                    continue;
                }
                if (isWidth(in)) {
                    if (!in.mnemonic().equals(w.mnemonic())) {
                        for (int i = k + 1; i < j; i++) {   // they run in the width before the pair now
                            if (items.get(i) instanceof LInstr x) {
                                items.set(i, new LInstr(x.mnemonic(), x.op(), x.comment(), w.m16(), x.x16(), x.relaxable()));
                            }
                        }
                        items.remove(j);
                        items.remove(k);
                        k--;
                    }
                    break;
                }
                if (!ANY_WIDTH.contains(in.mnemonic())) {
                    break;
                }
            }
        }
    }

    /**
     * Whether the 16-bit instruction at k gives the same low byte in 8 bits: one of
     * {@link #SAME_LOW_BYTE} on A, not a hardware register (a 16-bit read touches two), an
     * immediate known, and for {@code ADC}/{@code SBC} a carry set by {@code CLC}/{@code SEC} in the
     * block before it (an 8-bit carry out is another bit).
     */
    private static boolean narrows(List<LInstr> code, int k) {
        LInstr in = code.get(k);
        String m = in.mnemonic();
        LOperand op = in.op();
        if (!SAME_LOW_BYTE.contains(m)) {
            return false;
        }
        if ((m.equals("asl") || m.equals("inc") || m.equals("dec")) && !(op instanceof LOperand.Acc)) {
            return false;
        }
        if (op instanceof LOperand.Mem mem && mem.base().startsWith("$")) {
            return false;
        }
        if (op instanceof LOperand.Imm imm && imm.value() == null) {
            return false;   // an immediate written as an expression: its low byte is not known here
        }
        if (m.equals("adc") || m.equals("sbc")) {
            for (int j = k - 1; j >= 0; j--) {
                String p = code.get(j).mnemonic();
                if (p.equals("clc") || p.equals("sec")) {
                    return true;
                }
                if ((RedundantLoads.writes(code.get(j)) & RedundantLoads.C) != 0) {
                    return false;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Whether the text says the width of every instruction that depends on it after the change:
     * {@link LParser} (and ca65) follow {@code REP}/{@code SEP} in the order of the text, not of
     * the blocks' edges, so the loop's blocks must come after its entry or one another in the text,
     * and what follows them in the text must start with a switch (an exit) or not care.
     */
    private static boolean readsBack(LFunction f, Set<LBlock> loop, List<LBlock> entries, List<LBlock> exits) {
        boolean m16 = true;
        for (LBlock b : f.blocks()) {
            boolean in = loop.contains(b);
            if (exits.contains(b)) {
                m16 = true;    // the REP at its start
            }
            for (LInstr i : b.instrs()) {
                String m = i.mnemonic();
                if (m.equals("rep") || m.equals("sep")) {
                    if (!in) {
                        m16 = m.equals("rep");
                    }
                } else if (!ANY_WIDTH.contains(m) && (in ? false : i.m16()) != m16) {
                    return false;
                }
            }
            if (entries.contains(b)) {
                m16 = false;   // the SEP at its end
            }
        }
        return true;
    }

    /** Whether the block's code ends with 16 bits (its last width switch is a REP, or it has none and starts so). */
    private static boolean endsIn16(LBlock b) {
        List<LInstr> code = b.instrs();
        if (code.isEmpty()) {
            return false;
        }
        boolean m16 = code.getFirst().m16();
        for (LInstr in : code) {
            if (in.mnemonic().equals("rep") || in.mnemonic().equals("sep")) {
                if (!isWidth(in)) {
                    return false;
                }
                m16 = in.mnemonic().equals("rep");
            }
        }
        return m16;
    }

    private static LDirective directive(LDirective.Kind kind, String name) {
        return new LDirective(kind, null, List.of(), null, "        " + name);
    }

    private static boolean isWidth(LInstr i) {
        return (i.mnemonic().equals("rep") || i.mnemonic().equals("sep")) && i.op() instanceof LOperand.Imm k
                && k.expr().equals("$20");
    }
}
