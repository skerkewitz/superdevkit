package dampf.mir;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * A block that only returns, with φs (its φs, then {@code ret} of one of them or of a value from
 * elsewhere), is copied into each of its predecessors: one that jumps there returns itself, a
 * branch or switch gets a block of its own for the edge, which returns. Each path then returns
 * its own value: no φ copies before the branches, no jump to the shared return. Inlining leaves
 * this shape behind (a function returning the value of an inlined function that returns from
 * several places). Run just before code generation: earlier, the passes would lose the φs they
 * work on (`BitSelect` chooses between constants of a φ), and inlining would count the copies.
 */
public final class Returns {
    private Returns() {}

    /** Returns whether any block was copied. */
    public static boolean duplicate(MFunction f) {
        boolean changed = false;
        for (Block b : List.copyOf(f.blocks())) {
            if (!(b.terminator() instanceof Instr.Ret r) || !(b.instrs().getFirst() instanceof Instr.Phi)
                    || b.instrs().size() - 1 != phis(b)) {
                continue;
            }
            Value v = r.operands().isEmpty() ? null : r.op(0);
            for (Block p : List.copyOf(new LinkedHashSet<>(b.preds()))) {
                // the value along p (the same along every edge from p: one value per predecessor)
                Value along = v instanceof Instr.Phi ph && ph.block() == b ? ph.op(ph.from.indexOf(p)) : v;
                Block at = p;
                if (!(p.terminator() instanceof Instr.Jump)) {
                    at = f.newBlock("ret");
                    if (Cfg.retarget(p, b, at) == 0) {
                        throw new IllegalStateException("cannot move the edges " + p + " -> " + b);
                    }
                } else {
                    b.preds().remove(p);
                    p.instrs().removeLast().block = null;
                }
                at.add(new Instr.Ret(along, r.pos()));
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Phi ph) {
                        for (int k = ph.from.size() - 1; k >= 0; k--) {
                            if (ph.from.get(k) == p) {
                                ph.from.remove(k);
                                ph.operands().remove(k);
                            }
                        }
                    }
                }
            }
            changed = true;
        }
        if (changed) {
            Cfg.removeUnreachable(f);
        }
        return changed;
    }

    private static int phis(Block b) {
        int n = 0;
        while (b.instrs().get(n) instanceof Instr.Phi) {
            n++;
        }
        return n;
    }
}
