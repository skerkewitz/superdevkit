package dampf.lir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 16-bit code between 8-bit code goes to 8 bits where the high byte of A (B) is known to be 0
 * (M12 row 3): in 8 bits an instruction leaves B as it is, so where B is 0 before and the 16-bit
 * code would leave a high byte of 0 too, the value in A is the same in both widths. Then a byte
 * load needs no {@code AND #$00FF} ({@code LDA b / AND #$00FF / TAX} is {@code LDA b / TAX} in 8
 * bits), and a compare of a byte with a constant below 256 gives the same carry and zero flag.
 *
 * <p>The high byte is known to be 0 after a 16-bit {@code AND} with a constant below 256 or with
 * a value kept zero-extended (a byte's place, {@code zeroExtended}), a 16-bit load of such a
 * value or of a constant below 256, and after {@code LSR A}, {@code ORA}/{@code EOR} of such
 * values and every instruction that leaves A alone, if it was before, and after {@code TXA} or
 * {@code TYA} of an index register whose high byte is 0 (a load of such a value, {@code TAX} or
 * {@code TAY} where B is 0); 8-bit code keeps it, but {@code XBA}, {@code TDC}, {@code TSC}, a
 * block move and a call; a function starts without it (a data flow over the blocks, all ways in).
 *
 * <p>A stretch of 16-bit code in a block goes to 8 bits whole (its {@code REP} and {@code SEP}
 * go), at its start (the {@code REP} moves down) or at its end (the {@code SEP} moves up), where
 * B is 0 where the stretch's part starts and the 16-bit code leaves a high byte of 0 where it
 * ends, and every instruction of the part gives the same low byte and the flags that are read:
 * {@code LDA}, {@code AND}, {@code ORA}, {@code EOR}, {@code ADC}, {@code SBC}, {@code TXA},
 * {@code TYA}, {@code ASL A}, {@code INC A}, {@code DEC A} (not from a hardware register, an
 * immediate known; N, C and V not read, Z only where the 16-bit result's high byte is 0),
 * {@code LSR A}, {@code CMP} with a constant below 256 or a zero-extended value (N not read), and
 * the instructions that do not care for the width ({@code TAX} and {@code TAY} where B is 0, as
 * they copy it). {@code AND #$00FF} in 8 bits does nothing to A: it goes where its flags are not
 * read or are those of the 8-bit instruction before it. No path gets a cycle more, so no worst
 * case grows.
 */
public final class HighByteZero {
    private static final Set<String> SAME_LOW_BYTE = Set.of("lda", "and", "ora", "eor", "adc", "sbc", "asl", "inc", "dec",
            "txa", "tya");
    /** Width-independent instructions that read all of C (B and A). */
    private static final Set<String> READ_C = Set.of("tax", "tay", "xba", "tcd", "tcs", "mvn", "mvp");
    /** Instructions whose effect and cycles do not depend on the accumulator's width (index registers 16 bit). */
    private static final Set<String> ANY_WIDTH = Set.of("bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "brl",
            "jmp", "jml", "ldx", "ldy", "stx", "sty", "inx", "iny", "dex", "dey", "cpx", "cpy", "tax", "tay", "txy", "tyx", "tsx",
            "txs", "tcd", "tdc", "tcs", "tsc", "xba", "clc", "sec", "clv", "cli", "sei", "nop", "phx", "phy", "plx", "ply", "phb",
            "plb", "phd", "pld", "phk", "mvn", "mvp");

    private HighByteZero() {}

    /** Returns how many stretches changed. {@code zeroExtended}: a place whose value is a byte kept in two bytes. */
    public static int run(LFunction f, Predicate<LOperand.Owner> zeroExtended) {
        int changed = 0;
        for (int round = 0; round < 64 && once(f, zeroExtended); round++) {
            changed++;
        }
        return changed;
    }

    private static boolean once(LFunction f, Predicate<LOperand.Owner> zeroExtended) {
        if (f.blocks().isEmpty()) {
            return false;
        }
        Map<LInstr, Integer> zeroBefore = zeroBefore(f, zeroExtended);
        if (zeroBefore == null) {
            return false;
        }
        Map<LBlock, Integer> flagsOut = RedundantLoads.flagsLiveOut(f);
        for (LBlock b : f.blocks()) {
            List<LInstr> code = b.instrs();
            if (code.isEmpty() || !zeroBefore.containsKey(code.getFirst())) {
                continue;   // not reached
            }
            int[] liveAfter = new int[code.size()];
            int live = flagsOut.getOrDefault(b, ALL_FLAGS);
            for (int k = code.size() - 1; k >= 0; k--) {
                liveAfter[k] = live;
                live = (live & ~RedundantLoads.writes(code.get(k))) | RedundantLoads.reads(code.get(k));
            }
            // the 16-bit stretches: [from, to) of code, with the REP before from (or the block's start)
            // and the SEP at to (or the block's end)
            int k = 0;
            while (k < code.size()) {
                LInstr i = code.get(k);
                if (!i.m16() || isWidth(i)) {
                    k++;
                    continue;
                }
                int from = k;
                int to = k;
                while (to < code.size() && code.get(to).m16() && !isWidth(code.get(to))) {
                    to++;
                }
                boolean rep = from > 0 && isWidth(code.get(from - 1)) && code.get(from - 1).mnemonic().equals("rep");
                boolean sep = to < code.size() && isWidth(code.get(to)) && code.get(to).mnemonic().equals("sep");
                if (convert(f, b, code, from, to, rep, sep, zeroBefore, zeroExtended, liveAfter)) {
                    return true;
                }
                k = to + 1;
            }
        }
        return false;
    }

    private static final int ALL_FLAGS = RedundantLoads.N | RedundantLoads.Z | RedundantLoads.C | RedundantLoads.V;

    /** Tries to take [from, to) of b's code to 8 bits, whole, its start or its end; true if something changed. */
    private static boolean convert(LFunction f, LBlock b, List<LInstr> code, int from, int to, boolean rep, boolean sep,
                                   Map<LInstr, Integer> zeroBefore, Predicate<LOperand.Owner> zeroExtended, int[] liveAfter) {
        // which instructions go to 8 bits, which may be dropped
        boolean[] ok = new boolean[to - from];
        boolean[] drop = new boolean[to - from];
        int firstBad = to;
        int lastBad = from - 1;
        for (int k = from; k < to; k++) {
            ok[k - from] = narrows(code, k, zeroBefore, zeroExtended, liveAfter[k]);
            if (!ok[k - from]) {
                firstBad = Math.min(firstBad, k);
                lastBad = k;
            }
        }
        // the end of a block that branches or jumps: its last instruction keeps its place
        int end = to;
        if (!sep && to == code.size() && code.get(to - 1).endsBlock()) {
            end = to - 1;
            if (firstBad == to - 1) {
                firstBad = to;
            }
        }
        // whole: the REP and the SEP go; down: the REP moves down to stop; up: the SEP moves up to start
        int start = -1;
        int stop = -1;
        String how = null;
        if (rep && sep && firstBad == to && zero(code, from - 1, zeroBefore) && zero(code, to, zeroBefore)) {
            start = from;
            stop = to;
            how = "whole";
        } else if (rep && zero(code, from - 1, zeroBefore) && firstBad > from && at(code, firstBad == to ? end : firstBad, zeroBefore)) {
            start = from;
            stop = firstBad == to ? end : firstBad;
            how = "down";
        } else if (sep && lastBad < to - 1 && zero(code, to, zeroBefore)
                && (lastBad >= from ? zeroAfter(code, lastBad, zeroBefore, zeroExtended) : !rep && from == 0 && (zeroBefore.get(code.getFirst()) & A) != 0)) {
            start = lastBad + 1;
            stop = to;
            how = "up";
        }
        if (how == null || start >= stop) {
            return false;
        }
        // the gain: the cycles of the part in 8 bits (and the dropped masks), the switches whole
        int gain = how.equals("whole") ? 6 : 0;
        for (int k = start; k < stop; k++) {
            LInstr i = code.get(k);
            drop[k - from] = dropped(code, k, start, liveAfter[k]);
            gain += drop[k - from] ? i.cycles() : i.cycles() - as8(i).cycles();
        }
        if (gain <= 0) {
            return false;
        }
        // the block's items again
        List<LItem> items = b.items();
        List<LItem> out = new ArrayList<>();
        LInstr repAt = rep ? code.get(from - 1) : null;
        LInstr sepAt = sep ? code.get(to) : null;
        for (LItem it : items) {
            if (!(it instanceof LInstr i)) {
                out.add(it);
                continue;
            }
            int k = code.indexOf(i);
            if (i == repAt && !how.equals("up") || i == sepAt && !how.equals("down")) {
                continue;   // the switch that goes or moves
            }
            if (k == stop && how.equals("down")) {
                out.add(new LInstr("rep", "#$20", null, false, true, false));
            }
            if (k == start && how.equals("up")) {
                out.add(new LInstr("sep", "#$20", null, true, true, false));
            }
            if (k < start || k >= stop) {
                out.add(i);
            } else if (!drop[k - from]) {
                LInstr n = as8(i);
                boolean imm = n != i && n.op() instanceof LOperand.Imm && !ANY_WIDTH.contains(i.mnemonic());
                if (imm) {
                    out.add(directive(LDirective.Kind.A8, ".a8"));
                }
                out.add(n);
                if (imm) {
                    out.add(directive(LDirective.Kind.A16, ".a16"));
                }
            }
        }
        List<LItem> old = f.items();
        int at = 0;
        for (LBlock x : f.blocks()) {
            if (x == b) {
                break;
            }
            at += x.items().size();
        }
        List<LItem> all = new ArrayList<>(old.subList(0, at));
        all.addAll(out);
        all.addAll(old.subList(at + items.size(), old.size()));
        f.rebuild(all);
        return true;
    }

    /** Whether B is 0 right after the switch at k (before the instruction after it). */
    private static boolean zero(List<LInstr> code, int k, Map<LInstr, Integer> zeroBefore) {
        return k >= 0 && k < code.size() && (zeroBefore.getOrDefault(code.get(k), 0) & A) != 0;
    }

    /** Whether B is 0 before the instruction at k, or at the block's end where k is past it. */
    private static boolean at(List<LInstr> code, int k, Map<LInstr, Integer> zeroBefore) {
        if (k < code.size()) {
            return (zeroBefore.getOrDefault(code.get(k), 0) & A) != 0;
        }
        return false;   // the end of a block that falls through: not followed here
    }

    private static boolean zeroAfter(List<LInstr> code, int k, Map<LInstr, Integer> zeroBefore, Predicate<LOperand.Owner> ext) {
        return ((k + 1 < code.size() ? zeroBefore.getOrDefault(code.get(k + 1), 0)
                : after(code.get(k), zeroBefore.getOrDefault(code.get(k), 0), ext)) & A) != 0;
    }

    /**
     * Whether the 16-bit instruction at k may run in 8 bits where B is 0 before the stretch: the
     * same low byte, the same flags where they are read, the same high byte for what reads it.
     */
    private static boolean narrows(List<LInstr> code, int k, Map<LInstr, Integer> zeroBefore, Predicate<LOperand.Owner> ext,
                                   int liveAfter) {
        LInstr i = code.get(k);
        String m = i.mnemonic();
        LOperand op = i.op();
        int state = zeroBefore.getOrDefault(i, 0);
        boolean zb = (state & A) != 0;
        boolean za = (after(i, state, ext) & A) != 0;
        if (READ_C.contains(m)) {
            return zb;   // it copies B, which is 0 then in both widths
        }
        if (ANY_WIDTH.contains(m) || i.isConditionalBranch()) {
            return true;
        }
        if (op instanceof LOperand.Mem mem && mem.base().startsWith("$")) {
            return false;   // a hardware register: reading 8 bits instead of 16 reads one register less
        }
        if (op instanceof LOperand.Imm imm && imm.value() == null) {
            return false;
        }
        int writes = RedundantLoads.writes(i) & liveAfter;
        if (m.equals("lsr") && op instanceof LOperand.Acc) {
            return zb;   // B 0: the same bits shift, the same flags
        }
        if (m.equals("cmp")) {
            boolean small = op instanceof LOperand.Imm imm && imm.value() <= 0xFF || zeroExtended(op, ext);
            return zb && small && (writes & (RedundantLoads.N | RedundantLoads.V)) == 0;
        }
        if (!SAME_LOW_BYTE.contains(m) || (m.equals("asl") || m.equals("inc") || m.equals("dec")) && !(op instanceof LOperand.Acc)) {
            return false;
        }
        int allowed = za ? RedundantLoads.Z : 0;
        if (m.equals("and") && op instanceof LOperand.Imm imm && imm.value() <= 0x7F) {
            allowed |= RedundantLoads.N;   // bit 7 and bit 15 both 0
        }
        return (writes & ~allowed) == 0;
    }

    /** Whether the narrowed AND #$00FF at k can go: its flags are not read, or are those the 8-bit instruction before it set. */
    private static boolean dropped(List<LInstr> code, int k, int start, int liveAfter) {
        LInstr i = code.get(k);
        if (!i.mnemonic().equals("and") || !(i.op() instanceof LOperand.Imm imm) || imm.value() != 0xFF) {
            return false;
        }
        if ((liveAfter & (RedundantLoads.N | RedundantLoads.Z)) == 0) {
            return true;
        }
        if (k == 0) {
            return false;
        }
        LInstr p = code.get(k - 1);
        boolean eight = k - 1 >= start || !p.m16();
        return eight && Set.of("lda", "and", "ora", "eor", "adc", "sbc", "txa", "tya", "pla").contains(p.mnemonic())
                && (RedundantLoads.writes(p) & RedundantLoads.Z) != 0;
    }

    /** The instruction in 8 bits (an immediate of A with its low byte). */
    private static LInstr as8(LInstr i) {
        if (!i.m16()) {
            return i;
        }
        if (i.op() instanceof LOperand.Imm imm && imm.value() != null && !ANY_WIDTH.contains(i.mnemonic())) {
            return new LInstr(i.mnemonic(), String.format("#$%02X", imm.value() & 0xFF), i.comment(), false, i.x16(), i.relaxable());
        }
        return new LInstr(i.mnemonic(), i.op(), i.comment(), false, i.x16(), i.relaxable());
    }

    private static boolean zeroExtended(LOperand op, Predicate<LOperand.Owner> ext) {
        return op instanceof LOperand.Mem m && m.owner() != null && ext.test(m.owner()) && m.index() == LOperand.Index.NONE
                && !m.indirect() && m.owner().word() == 0;
    }

    /** The bits of the state: the high byte of A (B), of X, of Y is known to be 0. */
    static final int A = 1;
    static final int X = 2;
    static final int Y = 4;

    /** Which high bytes are 0 after i, given those before. */
    static int after(LInstr i, int state, Predicate<LOperand.Owner> ext) {
        String m = i.mnemonic();
        LOperand op = i.op();
        boolean small = op instanceof LOperand.Imm imm && imm.value() != null && imm.value() <= 0xFF || zeroExtended(op, ext);
        int a = state & A;
        int x = state & X;
        int y = state & Y;
        switch (m) {
            case "xba", "tdc", "tsc" -> a = 0;
            case "mvn", "mvp", "jsl", "jsr" -> {
                return 0;
            }
            case "ldx" -> x = small ? X : 0;
            case "ldy" -> y = small ? Y : 0;
            case "tax" -> x = a != 0 ? X : 0;   // all of C, in either width
            case "tay" -> y = a != 0 ? Y : 0;
            case "txy" -> y = x != 0 ? Y : 0;
            case "tyx" -> x = y != 0 ? X : 0;
            case "inx", "dex", "plx", "tsx" -> x = 0;
            case "iny", "dey", "ply" -> y = 0;
            default -> {
                if (i.m16()) {
                    a = switch (m) {
                        case "lda" -> small ? A : 0;
                        case "and" -> small ? A : a;
                        case "ora", "eor" -> small ? a : 0;
                        case "lsr" -> op instanceof LOperand.Acc ? a : a;
                        case "asl", "rol", "ror", "inc", "dec" -> op instanceof LOperand.Acc ? 0 : a;
                        case "txa" -> x != 0 ? A : 0;
                        case "tya" -> y != 0 ? A : 0;
                        case "adc", "sbc", "pla" -> 0;
                        default -> a;   // stores, compares, branches: A as it was
                    };
                }   // 8 bits: B stays
            }
        }
        return a | x | y;
    }

    /** Which high bytes are 0 before each instruction of the blocks a way reaches. */
    private static Map<LInstr, Integer> zeroBefore(LFunction f, Predicate<LOperand.Owner> ext) {
        Map<LBlock, Integer> in = new IdentityHashMap<>();
        ArrayDeque<LBlock> work = new ArrayDeque<>();
        in.put(f.blocks().getFirst(), 0);
        work.add(f.blocks().getFirst());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            int z = in.get(b);
            for (LInstr i : b.instrs()) {
                z = after(i, z, ext);
            }
            for (LBlock s : b.successors()) {
                Integer was = in.get(s);
                int now = was == null ? z : was & z;
                if (was == null || now != was) {
                    in.put(s, now);
                    work.add(s);
                }
            }
        }
        Map<LInstr, Integer> out = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            Integer z = in.get(b);
            if (z == null) {
                continue;
            }
            int zz = z;
            for (LInstr i : b.instrs()) {
                out.put(i, zz);
                zz = after(i, zz, ext);
            }
        }
        return out;
    }

    private static LDirective directive(LDirective.Kind kind, String name) {
        return new LDirective(kind, null, List.of(), null, "        " + name);
    }

    private static boolean isWidth(LInstr i) {
        return (i.mnemonic().equals("rep") || i.mnemonic().equals("sep")) && i.op() instanceof LOperand.Imm k
                && k.expr().equals("$20");
    }
}
