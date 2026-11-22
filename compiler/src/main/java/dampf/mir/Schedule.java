package dampf.mir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orders the instructions of each block for the 65816's single accumulator: an instruction whose
 * value only one later instruction of the same block uses moves right before that user. Then the
 * code generator can hand the value over in A without storing it (MirCodeGen's "forwarding"), and
 * the value's place is free for less time.
 *
 * <p>A store through a pointer plus a variable, whose value is a load, has that load right before
 * it, after its address ({@link #loadedValue}).
 *
 * <p>A value that only φs read (a loop's step) goes to the end of its block, so that a counter
 * read before it stays the counter for the whole block.
 *
 * <p>Arithmetic, comparisons, casts and ptradds can move anywhere after their operands. A load
 * does not move past a store, a copy, a call or a volatile access (any of them may change what
 * it reads). Instructions with an effect never move.
 */
public final class Schedule {
    private Schedule() {}

    /** Returns whether anything moved. */
    public static boolean run(MFunction f) {
        Map<Instr, Integer> uses = new HashMap<>();
        Map<Instr, Instr> user = new HashMap<>();
        Map<Instr, Boolean> onlyPhis = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    if (v instanceof Instr d) {
                        uses.merge(d, 1, Integer::sum);
                        user.put(d, i);
                        onlyPhis.merge(d, i instanceof Instr.Phi, Boolean::logicalAnd);
                    }
                }
            }
        }
        boolean changed = false;
        for (Block b : f.blocks()) {
            List<Instr> is = b.instrs();
            boolean moved = true;
            for (int round = 0; moved && round < 4 * is.size() + 1; round++) {
                moved = false;
                for (int k = is.size() - 2; k >= 0; k--) {
                    Instr i = is.get(k);
                    if (!movable(i) || uses.getOrDefault(i, 0) != 1) {
                        continue;
                    }
                    Instr u = user.get(i);
                    if (u.block() != b || u instanceof Instr.Phi) {
                        continue;
                    }
                    int at = is.indexOf(u);
                    if (u instanceof Instr.Store st) {
                        Instr value = loadedValue(st, b, uses);
                        if (value != null && i != value) {
                            continue;   // the loaded value has the place before the store
                        }
                    }
                    int target = at;
                    if (target == k + 1) {
                        continue;   // in its place already
                    }
                    if (i instanceof Instr.Load && !clear(is, k + 1, target)) {
                        continue;
                    }
                    is.remove(k);
                    is.add(target - 1, i);
                    moved = true;
                    changed = true;
                }
            }
            changed |= toEnd(is, onlyPhis);
        }
        return changed;
    }

    /**
     * A value read only by φs (a loop's step, read on the way back) goes to the end of its block,
     * before the comparison the branch reads: nothing in the block reads it, and a counter whose
     * step comes last is still the counter for everything before (the code generator keeps such a
     * counter in X).
     */
    private static boolean toEnd(List<Instr> is, Map<Instr, Boolean> onlyPhis) {
        int end = is.size() - 1;   // the terminator
        if (end > 0 && is.get(end - 1) instanceof Instr.Cmp c && is.get(end).operands().contains(c)) {
            end--;
        }
        boolean changed = false;
        for (int k = end - 1; k >= 0; k--) {
            Instr i = is.get(k);
            if (!(i instanceof Instr.Bin) || !onlyPhis.getOrDefault(i, false)) {
                continue;
            }
            if (k == end - 1) {
                end--;   // in place: the next one goes before it
                continue;
            }
            is.remove(k);
            is.add(end - 1, i);
            end--;
            changed = true;
        }
        return changed;
    }

    /**
     * A store's value, if it is a load of this block only the store reads and the store's address
     * is a pointer plus a variable, whose sum is computed in A: the load then goes right before the
     * store, after the address, and is handed to the store in A. Without this rule the value and
     * the address took the place before the store in turns, and the loaded value could end up
     * stored and loaded again while the address was computed. An address into a global or a frame
     * slot needs no A, and there the order decides which register stays free for a counter.
     */
    private static Instr loadedValue(Instr.Store s, Block b, Map<Instr, Integer> uses) {
        boolean sumInA = s.op(0) instanceof Instr.PtrAdd p && !(p.op(1) instanceof Value.Const)
                && !(p.op(0) instanceof Value.GlobalAddr) && !(p.op(0) instanceof Value.SlotAddr);
        return sumInA && s.op(1) instanceof Instr.Load l && l.block() == b && movable(l) && uses.getOrDefault(l, 0) == 1
                ? l : null;
    }

    private static boolean movable(Instr i) {
        return switch (i) {
            case Instr.Bin x -> true;
            case Instr.Cmp x -> true;
            case Instr.Cast x -> true;
            case Instr.PtrAdd x -> true;
            case Instr.Load l -> !l.isVolatile;
            default -> false;
        };
    }

    /** No instruction in [from, to) may change memory a load reads. */
    private static boolean clear(List<Instr> is, int from, int to) {
        for (int k = from; k < to; k++) {
            Instr i = is.get(k);
            if (i instanceof Instr.Store || i instanceof Instr.Copy || i instanceof Instr.Call
                    || i instanceof Instr.Load l && l.isVolatile) {
                return false;
            }
        }
        return true;
    }
}
