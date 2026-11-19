package dampf.mir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loop-invariant code: an instruction whose operands are all defined outside a loop gives the
 * same value in every round, so it moves to the block before the loop (catalogue F). Only cheap
 * instructions without side effects (arithmetic but no multiplication or division, casts, pointer
 * additions; comparisons stay with their branches) and only from blocks every round passes, so
 * no work moves out of a branch that rarely runs. The loop needs a block before it that jumps
 * straight to its head and is its only way in from outside.
 */
public final class Licm {
    private Licm() {}

    /** Returns whether anything moved. */
    public static boolean run(MFunction f) {
        Map<Block, Block> idom = Cfg.idoms(f);
        boolean changed = false;
        for (Loops.Loop loop : Loops.find(f)) {
            Block pre = preheader(loop);
            if (pre == null) {
                continue;
            }
            Set<Block> every = new HashSet<>();
            for (Block b : loop.blocks()) {
                boolean all = true;
                for (Block latch : loop.latches()) {
                    all &= Loops.dominates(b, latch, idom);
                }
                if (all) {
                    every.add(b);
                }
            }
            boolean moved = true;
            while (moved) {
                moved = false;
                for (Block b : loop.blocks()) {
                    if (!every.contains(b)) {
                        continue;
                    }
                    for (Instr i : new ArrayList<>(b.instrs())) {
                        if (movable(i) && invariant(i, loop)) {
                            b.remove(i);
                            pre.insertBefore(pre.terminator(), i);
                            moved = true;
                            changed = true;
                        }
                    }
                }
            }
        }
        return changed;
    }

    private static Block preheader(Loops.Loop loop) {
        Block pre = null;
        for (Block p : loop.head().preds()) {
            if (loop.blocks().contains(p)) {
                continue;
            }
            if (pre != null) {
                return null;   // two ways in
            }
            pre = p;
        }
        return pre != null && pre.terminator() instanceof Instr.Jump j && j.target == loop.head() ? pre : null;
    }

    private static boolean movable(Instr i) {
        return switch (i) {
            case Instr.Bin b -> switch (b.op) {
                case ADD, SUB, AND, OR, XOR -> true;
                case SHL, LSHR, ASHR -> b.op(1) instanceof Value.Const;
                default -> false;   // multiplication and division call the runtime
            };
            case Instr.Cast c -> true;
            case Instr.PtrAdd p -> true;
            default -> false;
        };
    }

    private static boolean invariant(Instr i, Loops.Loop loop) {
        List<Value> ops = i.operands();
        for (Value v : ops) {
            if (v instanceof Instr d && loop.blocks().contains(d.block())) {
                return false;
            }
        }
        return true;
    }
}
