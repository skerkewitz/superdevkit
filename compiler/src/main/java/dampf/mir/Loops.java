package dampf.mir;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The natural loops of a function (for the code generator), innermost first. */
public final class Loops {
    private Loops() {}

    /** A loop: its head, its blocks (head included) and the blocks that jump back to the head. */
    public record Loop(Block head, Set<Block> blocks, List<Block> latches) {}

    public static List<Loop> find(MFunction f) {
        Map<Block, Block> idom = Cfg.idoms(f);
        List<Loop> loops = new ArrayList<>();
        for (Block h : f.blocks()) {
            List<Block> latches = new ArrayList<>();
            for (Block p : h.preds()) {
                if (idom.containsKey(p) && dominates(h, p, idom) && !latches.contains(p)) {
                    latches.add(p);
                }
            }
            if (latches.isEmpty()) {
                continue;
            }
            Set<Block> blocks = new LinkedHashSet<>();
            blocks.add(h);
            Deque<Block> work = new ArrayDeque<>(latches);
            while (!work.isEmpty()) {
                Block b = work.pop();
                if (blocks.add(b)) {
                    work.addAll(b.preds());
                }
            }
            loops.add(new Loop(h, blocks, latches));
        }
        loops.sort((a, b) -> Integer.compare(a.blocks().size(), b.blocks().size()));
        return loops;
    }

    public static boolean dominates(Block a, Block b, Map<Block, Block> idom) {
        for (Block x = b; x != null; x = idom.get(x) == x ? null : idom.get(x)) {
            if (x == a) {
                return true;
            }
        }
        return false;
    }
}
