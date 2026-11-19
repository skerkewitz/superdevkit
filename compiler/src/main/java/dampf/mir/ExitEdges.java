package dampf.mir;

import java.util.List;

/**
 * Loop exits that carry φ values get a block of their own. The code generator writes a block's
 * φ values before the branch of a predecessor that branches (no extra jump on the way there), so
 * on a loop's exit edge they were written in every round although the loop is left once. With a
 * block on the edge, they are written there, once; the cost is one jump when the loop ends. Run
 * just before code generation (the passes would merge the block away again).
 */
public final class ExitEdges {
    private ExitEdges() {}

    /** Returns whether any edge was split. */
    public static boolean split(MFunction f) {
        boolean changed = false;
        for (Loops.Loop loop : Loops.find(f)) {
            for (Block p : List.copyOf(loop.blocks())) {
                if (!(p.terminator() instanceof Instr.Branch br) || br.ifTrue == br.ifFalse) {
                    continue;
                }
                for (Block s : List.of(br.ifTrue, br.ifFalse)) {
                    if (loop.blocks().contains(s) || !(s.instrs().getFirst() instanceof Instr.Phi)) {
                        continue;
                    }
                    Block e = f.newBlock("exit");
                    Cfg.retarget(p, s, e);
                    e.add(new Instr.Jump(s, br.pos()));
                    for (Instr i : s.instrs()) {
                        if (!(i instanceof Instr.Phi phi)) {
                            break;
                        }
                        for (int k = 0; k < phi.from.size(); k++) {
                            if (phi.from.get(k) == p) {
                                phi.from.set(k, e);
                            }
                        }
                    }
                    changed = true;
                    break;   // p's terminator changed; one exit per block is enough
                }
            }
        }
        return changed;
    }
}
