package dampf.lir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes loads of a value the register holds already, by what {@link RegContents} finds on every
 * way to the load. A load also sets N and Z: where a later instruction reads them (a branch), an
 * {@code LDA} becomes {@code CMP #0}, which sets them the same for the value in A, unless the carry
 * it changes is read too; then the load stays. {@code LDX} and {@code LDY} go only where their
 * flags are not read.
 */
public final class RedundantLoads {
    public static final int N = 1;
    public static final int Z = 2;
    static final int C = 4;
    static final int V = 8;
    private static final int ALL = N | Z | C | V;

    private final RegContents contents;
    /** Whether the function returns a value in A (RTL reads A); true where unknown. */
    private final boolean resultInA;

    public RedundantLoads() {
        this(true);
    }

    public RedundantLoads(boolean resultInA) {
        this.contents = new RegContents();
        this.resultInA = resultInA;
    }

    /** Removes (or turns into CMP #0) the redundant loads; returns how many changed. */
    public int run(LFunction f) {
        int hoisted = 0;
        for (int round = 0; round < 64 && hoistOverTest(f) > 0; round++) {
            hoisted++;   // one at a time: a load moved changes what the registers hold after it
        }
        hoisted += hoist(f);
        return hoisted + remove(f);
    }

    /**
     * A load of A that starts a loop's body, whose only way in is the loop's test (B6 of
     * docs/archive/compiler-flaws.md): where the test reads and writes no A, and A is not read
     * where the loop is left, the load can be thought of at the test's start, and moves to the
     * ways into the test that do not hold it already (the entry), as {@link #hoist} does. The
     * back edge holds it when the body leaves A as it was: a constant stored every round is
     * loaded once, before the loop. The body lies on the loop (it reaches the test again) and the
     * ways that get the load do not (the test does not reach them): a load after the loop never
     * moves into it. Moves one load; returns 1, or 0 where there is none.
     */
    int hoistOverTest(LFunction f) {
        Map<LBlock, RegContents.State> entries = contents.entries(f);
        Map<LBlock, Integer> flagsOut = flagsLiveOut(f);
        Map<LBlock, Boolean> aLive = aLiveOut(f);
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            for (LBlock t : b.successors()) {
                preds.computeIfAbsent(t, k -> new ArrayList<>()).add(b);
            }
        }
        for (LBlock b : f.blocks()) {
            List<LBlock> in = preds.getOrDefault(b, List.of());
            List<LInstr> code = b.instrs();
            if (in.size() != 1 || code.isEmpty() || b == f.blocks().getFirst()) {
                continue;
            }
            LBlock h = in.getFirst();
            LBlock via = b;
            if (h.successors().size() == 1 && h.instrs().size() == 1 && !h.instrs().getFirst().fallsThrough()
                    && preds.getOrDefault(h, List.of()).size() == 1) {
                via = h;               // the long form of the test's branch: a block that only jumps to b
                h = preds.get(h).getFirst();
            }
            LInstr load = code.getFirst();
            LOperand op = load.op();
            if (h == b || h.successors().size() != 2 || !h.successors().contains(via) || !load.mnemonic().equals("lda")
                    || !load.m16() || op == null
                    || !(op instanceof LOperand.Imm || op instanceof LOperand.Mem m && m.index() == LOperand.Index.NONE && !m.indirect())) {
                continue;
            }
            LBlock exit = h.successors().get(0) == via ? h.successors().get(1) : h.successors().get(0);
            if (exit == b || aLiveAtStart(exit, aLive) || !leavesA(h) || !reaches(b, h)) {
                continue;
            }
            // the flags of the load in b are not read, nor N and Z where the test starts
            int live = flagsOut.getOrDefault(b, 0);
            for (int k = code.size() - 1; k > 0; k--) {
                live = (live & ~writes(code.get(k))) | reads(code.get(k));
            }
            int atTest = flagsOut.getOrDefault(h, 0);
            for (int k = h.instrs().size() - 1; k >= 0; k--) {
                atTest = (atTest & ~writes(h.instrs().get(k))) | reads(h.instrs().get(k));
            }
            if ((live & (N | Z)) != 0 || (atTest & (N | Z)) != 0) {
                continue;
            }
            List<LBlock> missing = new ArrayList<>();
            boolean someHold = false;
            boolean ok = true;
            for (LBlock p : preds.getOrDefault(h, List.of())) {
                RegContents.State st = entries.get(p);
                if (st == null) {
                    ok = false;
                    break;
                }
                for (LInstr i : p.instrs()) {
                    st = contents.after(i, st);
                }
                RegContents.State loaded = contents.after(new LInstr("lda", op, null, true, load.x16(), false), st);
                for (LInstr i : h.instrs()) {
                    st = contents.after(i, st);   // (the test may write the loaded place)
                    loaded = contents.after(i, loaded);
                }
                if (st.a().contains(op)) {
                    someHold = true;
                    continue;
                }
                if (!loaded.a().contains(op)) {
                    ok = false;            // the test writes the loaded place: a load before it is stale in b
                    break;
                }
                LInstr last = p.last();
                boolean jumps = last != null && switch (last.mnemonic()) {
                    case "jmp", "bra", "brl" -> true;
                    default -> false;
                };
                if (p.successors().size() != 1 || longBranch(p, preds) || last != null && !jumps && !last.fallsThrough()
                        || last != null && (last.m16() != load.m16() || last.x16() != load.x16())) {
                    ok = false;
                    break;
                }
                if (reaches(h, p)) {
                    ok = false;            // p is on the loop: the load would run every round
                    break;
                }
                missing.add(p);
            }
            if (!ok || !someHold || missing.isEmpty()) {
                continue;
            }
            for (LBlock p : missing) {
                List<LItem> items = p.items();
                LInstr last = p.last();
                boolean jumps = last != null && !last.fallsThrough();
                items.add(jumps ? items.lastIndexOf(last) : items.size(), new LInstr("lda", op, null, true, load.x16(), false));
            }
            b.items().remove(load);
            f.rebuild(f.items());
            return 1;
        }
        return 0;
    }

    /** Whether a path of one edge or more leads from a to b. */
    private static boolean reaches(LBlock a, LBlock b) {
        java.util.Set<LBlock> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<LBlock> work = new java.util.ArrayDeque<>(a.successors());
        while (!work.isEmpty()) {
            LBlock x = work.pop();
            if (x == b) {
                return true;
            }
            if (seen.add(x)) {
                work.addAll(x.successors());
            }
        }
        return false;
    }

    /** Whether the test block's code leaves A alone: it neither reads nor writes it. */
    private static boolean leavesA(LBlock h) {
        for (LInstr i : h.instrs()) {
            switch (i.mnemonic()) {
                case "cpx", "cpy", "ldx", "ldy", "inx", "iny", "dex", "dey", "stx", "sty",
                     "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "brl", "jmp", "clc", "sec" -> { }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    /** Instructions that read no byte of A. */
    private static final java.util.Set<String> NO_A = java.util.Set.of("ldx", "ldy", "stx", "sty", "stz", "inx", "iny", "dex",
            "dey", "cpx", "cpy", "txy", "tyx", "tsx", "txs", "clc", "sec", "cli", "sei", "clv", "rep", "sep", "bra", "brl", "jmp",
            "jml", "beq", "bne", "bcc", "bcs", "bmi", "bpl", "bvc", "bvs", "nop", "phx", "phy", "plx", "ply", "txa", "tya", "pla",
            "tdc", "tsc");

    private boolean readsA(LInstr i) {
        String m = i.mnemonic();
        if (m.equals("rtl") || m.equals("rts")) {
            return resultInA;
        }
        if (m.equals("jsl") || m.equals("jsr")) {
            return !(i.op() instanceof LOperand.Label l) || l.name().startsWith("dampf_");
        }
        if (m.equals("lda")) {
            return !i.m16();
        }
        if (Set.of("inc", "dec", "asl", "lsr", "rol", "ror").contains(m)) {
            return i.op() == null || i.op() instanceof LOperand.Acc;
        }
        return !NO_A.contains(m);
    }

    private static boolean writesA(LInstr i) {
        return switch (i.mnemonic()) {
            case "lda", "txa", "tya", "pla", "tdc", "tsc" -> i.m16();
            default -> false;
        };
    }

    /** Whether A is read at the end of each block before it is written. */
    Map<LBlock, Boolean> aLiveOut(LFunction f) {
        Map<LBlock, Boolean> in = new IdentityHashMap<>();
        Map<LBlock, Boolean> out = new IdentityHashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int k = f.blocks().size() - 1; k >= 0; k--) {
                LBlock b = f.blocks().get(k);
                boolean live = false;
                for (LBlock s : b.successors()) {
                    live |= in.getOrDefault(s, false);
                }
                out.put(b, live);
                List<LInstr> code = b.instrs();
                for (int j = code.size() - 1; j >= 0; j--) {
                    if (writesA(code.get(j))) {
                        live = false;
                    }
                    if (readsA(code.get(j))) {
                        live = true;
                    }
                }
                if (in.getOrDefault(b, false) != live) {
                    in.put(b, live);
                    changed = true;
                }
            }
        }
        return out;
    }

    boolean aLiveAtStart(LBlock b, Map<LBlock, Boolean> aLiveOut) {
        boolean live = aLiveOut.getOrDefault(b, true);
        List<LInstr> code = b.instrs();
        for (int j = code.size() - 1; j >= 0; j--) {
            if (writesA(code.get(j))) {
                live = false;
            }
            if (readsA(code.get(j))) {
                live = true;
            }
        }
        return live;
    }

    /**
     * A load that starts a block (its flags not read) and is redundant on some ways in, not on
     * others that only jump there: it moves to the end of those, so it is redundant on all (a
     * loop head that loads its counter, which the back edge holds in A already and the entry
     * does not). No way gets a load more; the ways that held it lose one.
     */
    int hoist(LFunction f) {
        Map<LBlock, RegContents.State> entries = contents.entries(f);
        Map<LBlock, Integer> flagsOut = flagsLiveOut(f);
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            for (LBlock t : b.successors()) {
                preds.computeIfAbsent(t, k -> new ArrayList<>()).add(b);
            }
        }
        int changed = 0;
        for (LBlock b : f.blocks()) {
            List<LBlock> in = preds.getOrDefault(b, List.of());
            List<LInstr> code = b.instrs();
            if (in.size() < 2 || code.isEmpty() || in.contains(b) || b == f.blocks().getFirst()) {
                continue;
            }
            LInstr load = code.getFirst();
            LOperand op = load.op();
            if (op == null || !(load.mnemonic().equals("lda") && load.m16() || load.mnemonic().equals("ldx")
                    || load.mnemonic().equals("ldy"))) {
                continue;
            }
            int live = flagsOut.getOrDefault(b, 0);
            for (int k = code.size() - 1; k > 0; k--) {
                live = (live & ~writes(code.get(k))) | reads(code.get(k));
            }
            if ((live & (N | Z)) != 0) {
                continue;
            }
            List<LBlock> missing = new ArrayList<>();
            boolean someHold = false;
            boolean ok = true;
            for (LBlock p : in) {
                RegContents.State st = entries.get(p);
                if (st == null) {
                    ok = false;
                    break;
                }
                for (LInstr i : p.instrs()) {
                    st = contents.after(i, st);
                }
                boolean held = switch (load.mnemonic()) {
                    case "lda" -> st.a().contains(op);
                    case "ldx" -> st.x().contains(op);
                    default -> st.y().contains(op);
                };
                if (held) {
                    someHold = true;
                    continue;
                }
                LInstr last = p.last();
                boolean jumps = last != null && switch (last.mnemonic()) {
                    case "jmp", "bra", "brl" -> true;
                    default -> false;
                };
                if (p.successors().size() != 1 || longBranch(p, preds)
                        || last != null && !jumps && !last.fallsThrough()
                        || last != null && (last.m16() != load.m16() || last.x16() != load.x16())) {
                    ok = false;
                    break;
                }
                missing.add(p);
            }
            if (!ok || !someHold || missing.isEmpty()) {
                continue;
            }
            for (LBlock p : missing) {
                List<LItem> items = p.items();
                LInstr last = p.last();
                boolean jumps = last != null && !last.fallsThrough();
                int at = jumps ? items.lastIndexOf(last) : items.size();
                items.add(at, new LInstr(load.mnemonic(), op, null, load.m16(), load.x16(), false));
                changed++;
            }
        }
        if (changed > 0) {
            f.rebuild(f.items());
        }
        return changed;
    }

    /**
     * A block that only jumps and that a conditional branch falls through into: the long form of
     * that branch, which BranchRelax folds back into it unless something is put in between.
     */
    private static boolean longBranch(LBlock p, Map<LBlock, List<LBlock>> preds) {
        if (p.instrs().size() != 1 || p.last().fallsThrough()) {
            return false;
        }
        for (LBlock q : preds.getOrDefault(p, List.of())) {
            if (q.last() != null && q.last().isConditionalBranch()) {
                return true;
            }
        }
        return false;
    }

    private int remove(LFunction f) {
        Map<LBlock, RegContents.State> entries = contents.entries(f);
        Map<LBlock, Integer> flagsOut = flagsLiveOut(f);
        int changed = 0;
        for (LBlock b : f.blocks()) {
            RegContents.State s = entries.get(b);
            if (s == null) {
                continue;   // not reached
            }
            List<LItem> items = b.items();
            // the flags live after each instruction of the block
            Map<LInstr, Integer> liveAfter = new IdentityHashMap<>();
            int live = flagsOut.getOrDefault(b, 0);
            for (int k = items.size() - 1; k >= 0; k--) {
                if (items.get(k) instanceof LInstr in) {
                    liveAfter.put(in, live);
                    live = (live & ~writes(in)) | reads(in);
                }
            }
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof LInstr in)) {
                    continue;
                }
                LOperand op = in.op();
                int flags = liveAfter.get(in);
                boolean held = op != null && switch (in.mnemonic()) {
                    case "lda" -> in.m16() && s.a().contains(op);
                    case "ldx" -> s.x().contains(op);
                    case "ldy" -> s.y().contains(op);
                    default -> false;
                };
                if (held && (flags & (N | Z)) == 0) {
                    items.remove(k--);
                    changed++;
                    continue;   // the registers hold what they held
                }
                if (held && in.mnemonic().equals("lda") && (flags & C) == 0) {
                    LInstr cmp = new LInstr("cmp", "#$0000", in.comment(), true, in.x16(), false);
                    items.set(k, cmp);
                    changed++;
                    s = contents.after(cmp, s);
                    continue;
                }
                // a load of what another register holds: a transfer, which sets N and Z the same
                LInstr transfer = held ? null : transfer(in, s);
                if (transfer != null) {
                    items.set(k, transfer);
                    changed++;
                    s = contents.after(transfer, s);
                    continue;
                }
                // LDA x / ADC y (or AND, ORA, EOR) with A holding y: ADC x (the operation is
                // commutative and sets the flags itself; the carry an ADC reads is not touched by LDA)
                int n = nextInstr(items, k);
                if (op != null && in.mnemonic().equals("lda") && in.m16() && n >= 0
                        && items.get(n) instanceof LInstr next && next.m16() && next.op() != null
                        && COMMUTATIVE.contains(next.mnemonic()) && s.a().contains(next.op())
                        && !next.op().equals(op)) {
                    LInstr fused = next.withOperand(op.text());
                    liveAfter.put(fused, liveAfter.get(next));
                    items.set(n, fused);
                    items.remove(k--);
                    changed++;
                    continue;
                }
                s = contents.after(in, s);
            }
        }
        if (changed > 0) {
            f.rebuild(f.items());
        }
        return changed;
    }

    /**
     * {@code LDA p} as {@code TXA} or {@code TYA} where X or Y holds p, {@code LDX p} as {@code TAX}
     * or {@code TYX}, {@code LDY p} as {@code TAY} or {@code TXY}; all at 16 bits (a transfer to
     * an 8-bit A leaves the high byte, as the load does, but the registers' contents are known at
     * 16 bits only). Null where no register holds it.
     */
    private static LInstr transfer(LInstr in, RegContents.State s) {
        LOperand op = in.op();
        if (op == null || !in.m16() || !in.x16()) {
            return null;
        }
        String t = switch (in.mnemonic()) {
            case "lda" -> s.x().contains(op) ? "txa" : s.y().contains(op) ? "tya" : null;
            case "ldx" -> s.a().contains(op) ? "tax" : s.y().contains(op) ? "tyx" : null;
            case "ldy" -> s.a().contains(op) ? "tay" : s.x().contains(op) ? "txy" : null;
            default -> null;
        };
        return t == null ? null : new LInstr(t, (String) null, in.comment(), true, true, false);
    }

    private static final java.util.Set<String> COMMUTATIVE = java.util.Set.of("adc", "and", "ora", "eor");

    /** The next instruction after k in the block (comments skipped), -1 if none. */
    private static int nextInstr(List<LItem> items, int k) {
        for (int j = k + 1; j < items.size(); j++) {
            if (items.get(j) instanceof LInstr) {
                return j;
            }
            if (items.get(j) instanceof LLabel) {
                return -1;
            }
        }
        return -1;
    }

    /** The flags live at the end of each block. */
    public static Map<LBlock, Integer> flagsLiveOut(LFunction f) {
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            for (LBlock s : b.successors()) {
                preds.computeIfAbsent(s, k -> new ArrayList<>()).add(b);
            }
        }
        Map<LBlock, Integer> in = new IdentityHashMap<>();
        Map<LBlock, Integer> out = new IdentityHashMap<>();
        ArrayDeque<LBlock> work = new ArrayDeque<>(f.blocks().reversed());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            int live = 0;
            for (LBlock s : b.successors()) {
                live |= in.getOrDefault(s, 0);
            }
            out.put(b, live);
            List<LInstr> code = b.instrs();
            for (int k = code.size() - 1; k >= 0; k--) {
                LInstr i = code.get(k);
                live = (live & ~writes(i)) | reads(i);
            }
            Integer was = in.get(b);
            if (was == null || was != live) {
                in.put(b, live);
                for (LBlock p : preds.getOrDefault(b, List.of())) {
                    if (!work.contains(p)) {
                        work.add(p);
                    }
                }
            }
        }
        return out;
    }

    /** The flags an instruction reads. */
    public static int reads(LInstr i) {
        return switch (i.mnemonic()) {
            case "beq", "bne" -> Z;
            case "bmi", "bpl" -> N;
            case "bcc", "bcs", "adc", "sbc", "rol", "ror" -> C;
            case "bvc", "bvs" -> V;
            case "php" -> ALL;
            case "lda", "ldx", "ldy", "sta", "stx", "sty", "stz", "and", "ora", "eor", "cmp", "cpx", "cpy", "bit",
                 "inc", "dec", "inx", "iny", "dex", "dey", "asl", "lsr", "tax", "tay", "txa", "tya", "txy", "tyx", "tsx", "txs",
                 "tcd", "tdc", "xba", "pha", "phx", "phy", "pla", "plx", "ply", "clc", "sec", "clv", "rep", "sep",
                 "jmp", "jml", "bra", "brl", "jsl", "jsr", "rtl", "rts", "trb", "tsb", "nop" -> 0;
            default -> ALL;   // not known here: may read anything
        };
    }

    /** The flags an instruction sets for sure. */
    public static int writes(LInstr i) {
        return switch (i.mnemonic()) {
            case "lda", "ldx", "ldy", "and", "ora", "eor", "inc", "dec", "inx", "iny", "dex", "dey", "tax", "tay",
                 "txa", "tya", "txy", "tyx", "tsx", "tcd", "tdc", "xba", "pla", "plx", "ply" -> N | Z;
            case "cmp", "cpx", "cpy", "asl", "lsr", "rol", "ror" -> N | Z | C;
            case "adc", "sbc" -> ALL;
            case "bit" -> i.op() instanceof LOperand.Imm ? Z : N | V | Z;
            case "clc", "sec" -> C;
            case "clv" -> V;
            case "trb", "tsb" -> Z;
            case "jsl", "jsr" -> ALL;   // the callee leaves them as it likes; nothing after a call reads them
            default -> 0;
        };
    }
}
