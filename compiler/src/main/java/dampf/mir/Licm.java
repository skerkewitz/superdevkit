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
 *
 * <p>A load moves too when nothing in the loop may write what it reads (C7 of
 * docs/archive/compiler-flaws.md: a loop's bound in a global, {@code i < count}): no store or
 * copy that may overlap it ({@link Gvn}'s rule: two different globals or frame slots never
 * overlap; an address with a variable offset stays in its global or slot), no call of a function
 * that may write memory, and it is not volatile. It must run whenever the loop is entered (in
 * the loop's head), or read a global or a known place of a frame slot, where an extra read
 * changes nothing.
 */
public final class Licm {
    private Licm() {}

    /** Returns whether anything moved (no loads: the module's writers are not known). */
    public static boolean run(MFunction f) {
        return run(null, f, null);
    }

    /**
     * @param writes which functions may write memory ({@link Gvn#writesMemory}); null: loads stay
     */
    public static boolean run(MModule m, MFunction f, Map<MFunction, Boolean> writes) {
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
                        boolean load = i instanceof Instr.Load l && writes != null && invariant(i, loop)
                                && loadMovable(l, b, loop, m, writes);
                        if (load || movable(i) && invariant(i, loop)) {
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

    /** Whether nothing in the loop may change what l reads, and reading it once before is safe. */
    private static boolean loadMovable(Instr.Load l, Block b, Loops.Loop loop, MModule m, Map<MFunction, Boolean> writes) {
        Gvn.Location at = Gvn.locate(l.op(0));
        // outside the head the loop may not run, so the load may read where the program does
        // not: in a global (bank $7E, all RAM), or at a known place of a frame slot (bank 0, whose
        // registers a wild index could reach)
        if (l.isVolatile || b != loop.head() && !(at.base() instanceof Value.GlobalAddr || at.named() && at.exact())) {
            return false;
        }
        int size = l.type().size;
        for (Block x : loop.blocks()) {
            for (Instr i : x.instrs()) {
                boolean writesIt = switch (i) {
                    case Instr.Store s -> Gvn.mayOverlap(s.op(0), s.op(1).type().size, l.op(0), size);
                    case Instr.Copy c -> true;
                    case Instr.Call c -> {
                        MFunction callee = m.function(c.callee);
                        yield callee == null || writes.getOrDefault(callee, true);
                    }
                    case Instr.CallPtr c -> c.targets.stream().anyMatch(n -> m.function(n) == null
                            || writes.getOrDefault(m.function(n), true));
                    default -> false;
                };
                if (writesIt) {
                    return false;
                }
            }
        }
        return true;
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
