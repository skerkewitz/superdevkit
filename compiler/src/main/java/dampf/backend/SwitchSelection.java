package dampf.backend;

import dampf.lir.LOperand;
import dampf.mir.Block;
import dampf.mir.Instr;
import dampf.mir.MType;
import dampf.mir.Ranges;
import dampf.mir.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Instruction selection for a {@code switch}: a chain of comparisons, or a jump table where that
 * costs fewer cycles (ADR-6FM7MWGX).
 *
 * <p>The chain compares the value with one case after the other: {@code CMP #c / BEQ case}, 5
 * cycles for every case before the one taken and 6 for that one (after {@link
 * dampf.lir.BranchRelax}), so the k-th case, counted from 0, costs 5k + 6 cycles.
 *
 * <p>The table holds one entry for every value from {@code lo} to {@code hi}, the address of its
 * case or of {@code default} for a value that is no case. The dispatch doubles the value and
 * jumps through the table, {@code ASL A / TAX / JMP (.loword(table-2*lo),X)}, 10 cycles; a value
 * the range analysis does not keep within the table first goes to {@code default} by {@code CMP
 * #lo / BCC} and {@code CMP #hi+1 / BCS} (5 cycles each), and a table that starts far from 0 or
 * holds negative values subtracts {@code lo} first ({@code SEC / SBC #lo}, then one {@code CMP
 * #size / BCS} for both ends). The table lies in the function's own segment, after its code:
 * {@code JMP (abs,X)} reads it from the program bank, and a function never crosses a bank
 * (dampf.snes.RomBuilder).
 *
 * <p>A first case 0 is tested before the table ({@code BEQ} right after the load, 2 cycles for
 * the other ways): it is often the common one, the empty slot of an array of objects.
 *
 * <p>The table is used where its dispatch costs fewer cycles than the chain does on average over
 * the cases, and where it has at most {@link #SPREAD} entries per case (holes go to
 * {@code default}). Measured in the emulator, that is from 4 cases without a check, 6 from 0 with
 * an upper check, 7 with two checks or a subtraction. It needs X: not in a loop whose counter
 * lives in X ({@link Selection#xTaken}); a successor whose φ lives in X gets its {@code LDX} in a
 * stub of its own behind the jump.
 */
final class SwitchSelection {
    private final Selection sel;

    SwitchSelection(Selection sel) {
        this.sel = sel;
    }

    /** At most this many table entries per case: a table is then about as large as the chain. */
    static final int SPREAD = 4;
    /** At most this many entries (8 KB). */
    static final int MAX_ENTRIES = 0x1000;
    /** ASL A, TAX, JMP (abs,X). */
    static final int JUMP_CYCLES = 2 + 2 + 6;
    /** CMP #imm and a branch not taken; SEC and SBC #imm. */
    static final int CHECK_CYCLES = 3 + 2;
    static final int SUBTRACT_CYCLES = 2 + 3;
    /** Below this the base of the table, {@code table-2*lo}, cannot wrap below the bank's $8000. */
    static final int BASE_LIMIT = 0x2000;

    /**
     * A jump table: entry k for the 16-bit value {@code lo + k}. {@code subtract}: the dispatch
     * subtracts lo first (else it offsets the table's address); {@code below}, {@code above}: the
     * value may lie below lo or above hi, and a check sends it to default; {@code zeroFirst}: the
     * first case is 0 and is tested before the table ({@code BEQ}, no compare after the load).
     * {@code cycles}: the dispatch through the table.
     */
    record Table(int lo, int size, boolean subtract, boolean below, boolean above, boolean zeroFirst, int cycles) {
        int hi() {
            return (lo + size - 1) & 0xFFFF;
        }

        /** The average over n cases: with zeroFirst 3 for the first (a BEQ taken), the others 2 more (not taken). */
        double average(int n) {
            return zeroFirst ? (ZERO_TAKEN + (n - 1) * (cycles + ZERO_NOT_TAKEN)) / (double) n : cycles;
        }
    }

    /** The BEQ of a first case 0: taken, and not taken on the way to the table. */
    static final int ZERO_TAKEN = 3;
    static final int ZERO_NOT_TAKEN = 2;

    /**
     * The average cost of the chain over n cases: 5k + 6 for the k-th, k from 0; 3 less for all
     * of them when the first case is 0, which needs no CMP after the load.
     */
    static double chainCycles(int n, boolean firstIsZero) {
        return 5.0 * (n - 1) / 2 + 6 - (firstIsZero ? 3 : 0);
    }

    /**
     * The cheapest table for these 16-bit case values (in the order of the source), or null where
     * the chain costs no more on average over the cases.
     *
     * @param u the value's range as an unsigned 16-bit number, null if unknown
     * @param s the value's range as a signed 16-bit number, null if unknown
     */
    static Table plan(List<Integer> cases, Ranges.Range u, Ranges.Range s) {
        int n = cases.size();
        if (n < 2) {
            return null;
        }
        boolean zeroFirst = cases.getFirst() == 0;
        Ranges.Range ur = u == null ? new Ranges.Range(0, 0xFFFF) : u;
        Ranges.Range sr = s == null ? new Ranges.Range(-0x8000, 0x7FFF) : s;
        long umin = Long.MAX_VALUE;
        long umax = Long.MIN_VALUE;
        long smin = Long.MAX_VALUE;
        long smax = Long.MIN_VALUE;
        for (int c : cases) {
            umin = Math.min(umin, c);
            umax = Math.max(umax, c);
            long sc = (short) c;
            smin = Math.min(smin, sc);
            smax = Math.max(smax, sc);
        }
        List<Table> candidates = new ArrayList<>();
        // the cases' own interval, unsigned or signed, and each widened to the value's range
        // where that drops a check
        candidates.add(table(umin, umax, ur, ur, zeroFirst));
        candidates.add(table(Math.min(umin, ur.lo()), Math.max(umax, ur.hi()), ur, ur, zeroFirst));
        candidates.add(table(smin, smax, sr, ur, zeroFirst));
        candidates.add(table(Math.min(smin, sr.lo()), Math.max(smax, sr.hi()), sr, ur, zeroFirst));
        Table best = null;
        for (Table t : candidates) {
            if (t.size() <= SPREAD * n && t.size() <= MAX_ENTRIES && (best == null || t.cycles() < best.cycles()
                    || t.cycles() == best.cycles() && t.size() < best.size())) {
                best = t;
            }
        }
        return best != null && best.average(n) < chainCycles(n, zeroFirst) ? best : null;
    }

    /**
     * The table for the values lo..hi, read as r is (signed or unsigned); {@code ur}: the value's
     * unsigned range, which the compares of the form without subtraction test.
     */
    private static Table table(long lo, long hi, Ranges.Range r, Ranges.Range ur, boolean zeroFirst) {
        int size = (int) (hi - lo + 1);
        // subtracting lo: one unsigned compare with the size tests both ends
        boolean outside = r.lo() < lo || r.hi() > hi;
        int withSubtract = JUMP_CYCLES + SUBTRACT_CYCLES + (outside ? CHECK_CYCLES : 0);
        // offsetting the table's address instead: the doubled value must not overflow, the
        // table's base not wrap, and each end is an unsigned compare of its own
        if (lo >= 0 && lo <= BASE_LIMIT && hi < 0x8000) {
            boolean below = ur.lo() < lo;
            boolean above = ur.hi() > hi;
            int withOffset = JUMP_CYCLES + (below ? CHECK_CYCLES : 0) + (above ? CHECK_CYCLES : 0);
            if (withOffset <= withSubtract) {
                return new Table((int) lo, size, false, below, above, zeroFirst, withOffset);
            }
        }
        return new Table((int) lo & 0xFFFF, size, true, outside, outside, zeroFirst, withSubtract);
    }

    /**
     * The table for switch s, or null for the chain: a value of one word, a table that pays,
     * X free, and at most one successor whose φ lives in X.
     */
    Table plan(Instr.Switch s, Block b) {
        MType t = s.op(0).type();
        if (Selection.words(t) != 1 || t == MType.I1 || sel.xTaken != null || sel.m8 || xInits(b, s).size() > 1) {
            return null;
        }
        List<Integer> cases = new ArrayList<>();
        for (long c : s.cases) {
            int w = (int) t.wrap(c);
            if (!cases.contains(w)) {
                cases.add(w);
            }
        }
        Ranges.Range u = sel.ranges.atUnsigned(s.op(0), b);
        // an i8 is kept zero-extended in its word: as a signed word it is the unsigned byte
        Ranges.Range sg = t == MType.I8 ? u : sel.ranges.at(s.op(0), b);
        return plan(cases, u, sg);
    }

    /** The successors whose φ lives in X (a loop counter), with the value X starts with there. */
    Map<Block, Value> xInits(Block b, Instr terminator) {
        Map<Block, Value> out = new LinkedHashMap<>();
        for (Block s : new java.util.LinkedHashSet<>(terminator.successors())) {
            for (Instr i : s.instrs()) {
                if (!(i instanceof Instr.Phi p)) {
                    break;
                }
                Value v = p.op(p.from.indexOf(b));
                if (sel.xResident.containsKey(p) && v != sel.xResident.get(p)) {
                    out.put(s, v);
                }
            }
        }
        return out;
    }

    /**
     * The chain: every case compared in turn, then default. Each case in the long form ({@code
     * BNE next / JMP case}), which {@link dampf.lir.BranchRelax} makes a {@code BEQ} where that
     * reaches.
     */
    void chain(Instr.Switch s, Block next) {
        MType t = s.op(0).type();
        for (int k = 0; k < s.cases.size(); k++) {
            long c = t.wrap(s.cases.get(k));
            String nextCase = sel.label();
            for (int w = 0; w < Selection.words(t); w++) {
                sel.ins("lda", sel.operand(s.op(0), w));
                sel.ins("cmp", Selection.imm(c >> (16 * w)));
                sel.ins("bne", nextCase, Selection.words(t) == 1);
            }
            sel.ins("jmp", s.targets.get(k).name(), true);
            sel.place(nextCase);
        }
        if (s.otherwise != next) {
            sel.ins("jmp", s.otherwise.name(), true);
        }
    }

    /**
     * The dispatch through table t, after the φ copies ({@code xInit}: the successor whose φ lives
     * in X and its start value, loaded behind the jump on the way there). Returns the table's
     * label and entries, which the function writes after its code.
     */
    Written table(Instr.Switch s, Block b, Table t, Map<Block, Value> xInit) {
        MType type = s.op(0).type();
        String name = b.name() + "_table";
        // where each successor is entered: its block, or a stub that loads X first
        Map<Block, String> entry = new LinkedHashMap<>();
        Map<Block, String> stubs = new LinkedHashMap<>();
        for (Block x : xInit.keySet()) {
            String stub = b.name() + "_x" + stubs.size();
            stubs.put(x, stub);
            entry.put(x, stub);
        }
        String otherwise = entry.getOrDefault(s.otherwise, s.otherwise.name());
        LOperand value = sel.operand(s.op(0), 0);
        if (t.zeroFirst()) {
            // the first case, 0, before the table: often the common one ("nothing to do here")
            sel.flagsOf(value);
            String other = sel.label();
            sel.ins("bne", other, true);
            sel.ins("jmp", entry.getOrDefault(s.targets.getFirst(), s.targets.getFirst().name()), true);
            sel.place(other);   // (A still holds the value)
        } else {
            sel.ins("lda", value);
        }
        if (t.subtract()) {
            sel.ins("sec");
            sel.ins("sbc", Selection.imm(t.lo()));
            if (t.below() || t.above()) {
                sel.ins("cmp", Selection.imm(t.size()));
                toDefault("bcs", otherwise);
            }
        } else {
            if (t.below()) {
                sel.ins("cmp", Selection.imm(t.lo()));
                toDefault("bcc", otherwise);
            }
            if (t.above()) {
                sel.ins("cmp", Selection.imm(t.hi() + 1));
                toDefault("bcs", otherwise);
            }
        }
        sel.ins("asl", Selection.A);
        sel.ins("tax");
        int base = t.subtract() ? 0 : 2 * t.lo();
        sel.ins("jmp", new LOperand.Raw("(.loword(" + name + (base == 0 ? "" : "-" + base) + "),x)"));
        for (Map.Entry<Block, String> e : stubs.entrySet()) {
            sel.place(e.getValue());
            sel.ins("ldx", sel.operand(xInit.get(e.getKey()), 0));
            sel.branch.jumpTo(e.getKey());
        }
        // the entries: the case of each value, default for the holes
        List<String> entries = new ArrayList<>();
        for (int k = 0; k < t.size(); k++) {
            entries.add(otherwise);
        }
        for (int c = s.cases.size() - 1; c >= 0; c--) {   // (the first of equal cases wins, as in the chain)
            int k = ((int) type.wrap(s.cases.get(c)) - t.lo()) & 0xFFFF;
            Block target = s.targets.get(c);
            entries.set(k, entry.getOrDefault(target, target.name()));
        }
        return new Written(name, entries, s.pos());
    }

    /** A branch to default when the condition holds, in the long form BranchRelax shortens. */
    private void toDefault(String branch, String otherwise) {
        String inside = sel.label();
        sel.ins(BranchSelection.inverse(branch), inside, true);
        sel.ins("jmp", otherwise, true);
        sel.place(inside);
    }

    /** A table to write after the function's code: its label, its entries, the switch's position. */
    record Written(String label, List<String> entries, dampf.frontend.SourcePos pos) {}
}
