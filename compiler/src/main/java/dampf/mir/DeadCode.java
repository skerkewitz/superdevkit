package dampf.mir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes instructions whose result nobody needs. Live are the instructions with an effect
 * (stores, copies, calls, volatile loads, terminators) and everything they use, transitively;
 * so φs that only feed each other go too.
 */
public final class DeadCode {
    private DeadCode() {}

    /** Returns whether anything was removed. */
    public static boolean run(MFunction f) {
        Set<Instr> live = new HashSet<>();
        Deque<Instr> work = new ArrayDeque<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (hasEffect(i)) {
                    live.add(i);
                    work.add(i);
                }
            }
        }
        while (!work.isEmpty()) {
            for (Value v : work.removeLast().operands()) {
                if (v instanceof Instr d && live.add(d)) {
                    work.add(d);
                }
            }
        }
        boolean changed = false;
        for (Block b : f.blocks()) {
            for (Instr i : new ArrayList<>(b.instrs())) {
                if (!live.contains(i)) {
                    b.remove(i);
                    changed = true;
                }
            }
        }
        return changed;
    }

    static boolean hasEffect(Instr i) {
        return switch (i) {
            case Instr.Store s -> true;
            case Instr.Copy c -> true;
            case Instr.Call c -> true;
            case Instr.Load l -> l.isVolatile;
            default -> i.isTerminator();
        };
    }

    /** How often each instruction is used as an operand. */
    static java.util.Map<Instr, Integer> useCounts(MFunction f) {
        java.util.Map<Instr, Integer> uses = new java.util.HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    if (v instanceof Instr d) {
                        uses.merge(d, 1, Integer::sum);
                    }
                }
            }
        }
        return uses;
    }

    /** Replaces operands by the values a pass decided to use instead (following chains). */
    static void replaceUses(MFunction f, java.util.Map<Instr, Value> replaced) {
        if (replaced.isEmpty()) {
            return;
        }
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                List<Value> ops = i.operands();
                for (int k = 0; k < ops.size(); k++) {
                    Value v = ops.get(k);
                    Value r = v;
                    while (r instanceof Instr d && replaced.containsKey(d)) {
                        r = replaced.get(d);
                    }
                    if (r != v) {
                        i.setOp(k, r);
                    }
                }
            }
        }
    }
}
