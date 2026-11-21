package dampf.backend;

import dampf.lir.LBlock;
import dampf.lir.LFunction;
import dampf.lir.LItem;
import dampf.lir.LLabel;
import dampf.mir.Block;
import dampf.mir.Loops;
import dampf.mir.MFunction;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * How often a block runs, for decisions that weigh cycles (K1 of docs/current-compiler-flaws.md):
 * the profile's count where there is one ({@code --block-counts}), else 8 to the power of the
 * block's loop depth. A LIR block weighs what the MIR block weighs whose code it is part of (the
 * last MIR block label above it); the cycles of an instruction are {@link dampf.lir.LInstr#cycles}.
 */
final class Weights {
    private Weights() {}

    /** The weight of each MIR block, by name. {@code counts}: the profile's count of a block name, or null. */
    static Map<String, Double> of(MFunction f, Function<String, Long> counts) {
        Map<Block, Integer> depth = new IdentityHashMap<>();
        for (Loops.Loop l : Loops.find(f)) {
            for (Block b : l.blocks()) {
                depth.merge(b, 1, Integer::sum);
            }
        }
        Map<String, Double> out = new HashMap<>();
        for (Block b : f.blocks()) {
            Long n = counts == null ? null : counts.apply(b.name());
            out.put(b.name(), n != null ? (double) n : Math.pow(8, depth.getOrDefault(b, 0)));
        }
        return out;
    }

    /** The weight of each LIR block of lf, from the MIR blocks' weights by name. */
    static Map<LBlock, Double> of(LFunction lf, Map<String, Double> byName) {
        Map<LBlock, Double> out = new IdentityHashMap<>();
        double current = 1;
        for (LBlock b : lf.blocks()) {
            for (LItem it : b.items()) {
                if (it instanceof LLabel l && byName.containsKey(l.name())) {
                    current = byName.get(l.name());
                }
            }
            out.put(b, current);
        }
        return out;
    }
}
