package dampf.mir;

/**
 * A comparison that a branch in another block uses is computed again right before that branch,
 * so that the code generator tests and branches in one ({@code LDA x / BMI}) instead of keeping a
 * truth value (0 or 1) in a place from the comparison's block on and testing it later. Value
 * numbering merges equal comparisons (the {@code v < 0} of an inlined {@code abs} and the same
 * test in the caller), which made one truth value for several branches; this pass runs after it,
 * once, before the instructions are scheduled.
 *
 * <p>The copy uses the comparison's own operands: they dominate its block, which dominates every
 * block that uses it, so they are there at the branch. The original goes when nothing else uses
 * it (DeadCode).
 */
public final class SinkCompares {
    private SinkCompares() {}

    /** Returns whether a comparison was copied. */
    public static boolean run(MFunction f) {
        boolean changed = false;
        for (Block b : f.blocks()) {
            Instr t = b.terminator();
            if (!(t instanceof Instr.Branch br) || !(br.op(0) instanceof Instr.Cmp c) || c.block() == b) {
                continue;
            }
            Instr.Cmp copy = new Instr.Cmp(c.pred, c.op(0), c.op(1), c.pos());
            b.insertBefore(t, copy);
            br.setOp(0, copy);
            changed = true;
        }
        if (changed) {
            DeadCode.run(f);
        }
        return changed;
    }
}
