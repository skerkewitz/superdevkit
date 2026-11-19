package dampf.mir;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes the checks of hints out before code generation. A hint such as {@code DAMPF_RANGE(i, 0,
 * 15)} is lowered to comparisons that branch to an {@link Instr.Unreachable} block: the range
 * analysis learns from the branches what the hint says, and the interpreter fails if it does not
 * hold. The SNES code must not pay for them, so the ranges are computed first, with the checks,
 * and then every branch to such a block becomes a jump to its other side. The ranges stay true:
 * the code that remains is the same, only without the checks.
 */
public final class Hints {
    private Hints() {}

    /** Removes the checks; returns every function's ranges, computed while they were there. */
    public static Map<MFunction, Ranges> strip(MModule m) {
        Map<MFunction, Ranges> out = new IdentityHashMap<>();
        for (MFunction f : m.functions()) {
            out.put(f, new Ranges(f));
            boolean changed = false;
            for (Block b : List.copyOf(f.blocks())) {
                if (b.terminator() instanceof Instr.Branch br && br.ifTrue != br.ifFalse) {
                    if (unreachable(br.ifFalse)) {
                        Cfg.jumpTo(b, br.ifTrue);
                        changed = true;
                    } else if (unreachable(br.ifTrue)) {
                        Cfg.jumpTo(b, br.ifFalse);
                        changed = true;
                    }
                }
            }
            if (changed) {
                Cfg.removeUnreachable(f);
                DeadCode.run(f);
            }
        }
        return out;
    }

    private static boolean unreachable(Block b) {
        return b.instrs().size() == 1 && b.terminator() instanceof Instr.Unreachable;
    }
}
