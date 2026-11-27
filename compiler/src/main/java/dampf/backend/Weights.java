package dampf.backend;

import dampf.lir.LBlock;
import dampf.lir.LFunction;
import dampf.lir.LItem;
import dampf.lir.LLabel;
import dampf.mir.Block;
import dampf.mir.Loops;
import dampf.mir.MFunction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * How often a block runs, for decisions that weigh cycles (K1 of docs/archive/compiler-flaws.md):
 * the profile's count where there is one ({@code --block-counts}), else 8 to the power of the
 * block's loop depth ({@link #of}), or an estimate where a loop's head runs 8 times per entry and
 * a branch's ways share equally ({@link #estimated}, for the parts of loops in RegisterChoice). A LIR block weighs what the MIR block weighs whose code it is part of (the
 * last MIR block label above it); the cycles of an instruction are {@link dampf.lir.LInstr#cycles}.
 */
final class Weights {
    private Weights() {}

    /**
     * The weight of each MIR block, by name: the profile's count, else 8 to the power of its loop
     * depth. {@code counts}: the profile's count of a block name, or null.
     */
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

    /** As {@link #of}, with the {@link #estimate} in place of the loop depth. */
    static Map<String, Double> estimated(MFunction f, Function<String, Long> counts) {
        Map<Block, Double> estimate = estimate(f);
        Map<String, Double> out = new HashMap<>();
        for (Block b : f.blocks()) {
            Long n = counts == null ? null : counts.apply(b.name());
            out.put(b.name(), n != null ? (double) n : estimate.getOrDefault(b, 1.0));
        }
        return out;
    }

    /** How often a loop's head runs per entry, without a profile. */
    static final double TRIPS = 8;

    /**
     * How often each block runs per call, without a profile: a loop's head TRIPS times per entry,
     * and every branch shared equally among its ways (a loop inside a region counts as one block
     * whose exits share its runs). Each region (the function, each loop) is a graph without
     * cycles once its back edges are left out and its inner loops are one block each; a block's
     * share is the sum of what its predecessors in the region pass on. Where a region has a cycle
     * all the same (a loop with two entries), its blocks keep the loop depth's weight.
     */
    static Map<Block, Double> estimate(MFunction f) {
        List<Loops.Loop> loops = Loops.find(f);   // innermost first
        Map<Block, Loops.Loop> innermost = new IdentityHashMap<>();
        for (Loops.Loop l : loops) {
            for (Block b : l.blocks()) {
                innermost.putIfAbsent(b, l);
            }
        }
        Map<Loops.Loop, Loops.Loop> parent = new IdentityHashMap<>();
        for (int i = 0; i < loops.size(); i++) {
            for (int j = i + 1; j < loops.size(); j++) {
                Loops.Loop o = loops.get(j);
                if (o.blocks().size() > loops.get(i).blocks().size() && o.blocks().containsAll(loops.get(i).blocks())) {
                    parent.put(loops.get(i), o);
                    break;
                }
            }
        }
        // the share of each block (or inner loop's head) in its region, per run of the region's start
        Map<Object, Map<Block, Double>> share = new HashMap<>();
        Object root = new Object();
        List<Object> regions = new ArrayList<>();
        regions.add(root);
        regions.addAll(loops);
        for (Object r : regions) {
            Loops.Loop loop = r == root ? null : (Loops.Loop) r;
            Block start = loop == null ? f.blocks().getFirst() : loop.head();
            Map<Block, List<Block>> edges = new IdentityHashMap<>();
            Map<Block, List<Double>> probs = new IdentityHashMap<>();
            Map<Block, Integer> indegree = new IdentityHashMap<>();
            for (Block b : f.blocks()) {
                Loops.Loop in = innermost.get(b);
                boolean node = in == loop || in != null && b == in.head() && parent.get(in) == loop;
                if (!node) {
                    continue;
                }
                indegree.putIfAbsent(b, 0);
                List<Block> targets = new ArrayList<>();
                if (in != loop) {
                    // an inner loop: its exits share its runs
                    for (Block x : in.blocks()) {
                        for (Block s : x.terminator() == null ? List.<Block>of() : x.terminator().successors()) {
                            if (!in.blocks().contains(s)) {
                                targets.add(s);
                            }
                        }
                    }
                } else if (b.terminator() != null) {
                    targets.addAll(b.terminator().successors());
                }
                List<Block> to = new ArrayList<>();
                List<Double> p = new ArrayList<>();
                for (Block s : targets) {
                    Block rep = representative(s, loop, innermost, parent);
                    if (rep != null && rep != start) {
                        to.add(rep);
                        p.add(1.0 / targets.size());
                        indegree.merge(rep, 1, Integer::sum);
                    }
                }
                edges.put(b, to);
                probs.put(b, p);
            }
            Map<Block, Double> got = new IdentityHashMap<>();
            got.put(start, 1.0);
            java.util.ArrayDeque<Block> ready = new java.util.ArrayDeque<>();
            for (Block b : f.blocks()) {
                if (indegree.containsKey(b) && (b == start || indegree.get(b) == 0)) {
                    ready.add(b);   // (a block nothing in the region reaches gets no share)
                }
            }
            indegree.put(start, 0);
            while (!ready.isEmpty()) {
                Block b = ready.removeFirst();
                List<Block> to = edges.getOrDefault(b, List.of());
                for (int k = 0; k < to.size(); k++) {
                    Block s = to.get(k);
                    got.merge(s, got.getOrDefault(b, 0.0) * probs.get(b).get(k), Double::sum);
                    if (indegree.merge(s, -1, Integer::sum) == 0) {
                        ready.add(s);
                    }
                }
            }
            if (indegree.values().stream().anyMatch(d -> d > 0)) {
                got = null;    // a cycle the loops do not explain
            }
            share.put(r, got);
        }
        Map<Block, Double> out = new IdentityHashMap<>();
        for (Block b : f.blocks()) {
            Loops.Loop l = innermost.get(b);
            Map<Block, Double> own = share.get(l == null ? root : l);
            double w = own == null ? 1 : own.getOrDefault(b, 0.0);
            while (l != null) {
                Map<Block, Double> up = share.get(parent.get(l) == null ? root : parent.get(l));
                w *= TRIPS * (up == null ? 1 : up.getOrDefault(l.head(), 0.0));
                l = parent.get(l);
            }
            out.put(b, w);
        }
        return out;
    }

    /**
     * The node of region {@code loop} (null: the function) that a way into s reaches: s itself
     * where its innermost loop is the region, the head of the inner loop it lies in, or null where
     * s lies outside the region.
     */
    private static Block representative(Block s, Loops.Loop loop, Map<Block, Loops.Loop> innermost,
                                        Map<Loops.Loop, Loops.Loop> parent) {
        Loops.Loop l = innermost.get(s);
        if (l == loop) {
            return s;
        }
        while (l != null && parent.get(l) != loop) {
            l = parent.get(l);
        }
        return l == null ? null : l.head();
    }

    /** The LIR blocks of each loop of f: those under the labels of the loop's MIR blocks. */
    static java.util.List<java.util.Set<LBlock>> loops(MFunction f, LFunction lf) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Block b : f.blocks()) {
            names.add(b.name());
        }
        Map<String, java.util.List<LBlock>> under = new HashMap<>();
        String current = null;
        for (LBlock b : lf.blocks()) {
            for (LItem it : b.items()) {
                if (it instanceof LLabel l && names.contains(l.name())) {
                    current = l.name();
                }
            }
            if (current != null) {
                under.computeIfAbsent(current, k -> new java.util.ArrayList<>()).add(b);
            }
        }
        java.util.List<java.util.Set<LBlock>> out = new java.util.ArrayList<>();
        for (Loops.Loop l : Loops.find(f)) {
            java.util.Set<LBlock> blocks = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (Block b : l.blocks()) {
                blocks.addAll(under.getOrDefault(b.name(), java.util.List.of()));
            }
            out.add(blocks);
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
