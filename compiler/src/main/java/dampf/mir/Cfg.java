package dampf.mir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Control flow graph helpers for passes: edges, unreachable blocks, the dominator tree. */
final class Cfg {
    private Cfg() {}

    /** Removes one edge from → to: the predecessor entry and the φ operands that came along it. */
    static void removeEdge(Block from, Block to) {
        int k = to.preds().indexOf(from);
        if (k < 0) {
            throw new IllegalStateException(from + " is no predecessor of " + to);
        }
        to.preds().remove(k);
        for (Instr i : to.instrs()) {
            if (i instanceof Instr.Phi p) {
                int j = p.from.indexOf(from);
                p.from.remove(j);
                p.operands().remove(j);
            }
        }
    }

    /** Replaces a block's terminator by a jump to one of its successors; the other edges go. */
    static void jumpTo(Block b, Block target) {
        Instr old = b.terminator();
        List<Block> succ = new ArrayList<>(old.successors());
        if (!succ.remove(target)) {
            throw new IllegalStateException(target + " is no successor of " + b);
        }
        for (Block s : succ) {
            removeEdge(b, s);
        }
        b.instrs().set(b.instrs().size() - 1, new Instr.Jump(target, old.pos()));
        b.instrs().getLast().block = b;
        old.block = null;
    }

    /**
     * Makes every edge p → from go to {@code to} instead (a new terminator for p). Returns the
     * number of edges moved (0 if p's terminator cannot be changed).
     */
    static int retarget(Block p, Block from, Block to) {
        Instr old = p.terminator();
        Instr t = switch (old) {
            case Instr.Jump j -> new Instr.Jump(to, j.pos());
            case Instr.Branch b -> new Instr.Branch(b.op(0), b.ifTrue == from ? to : b.ifTrue,
                    b.ifFalse == from ? to : b.ifFalse, b.pos());
            case Instr.Switch s -> {
                Instr.Switch n = new Instr.Switch(s.op(0), s.otherwise == from ? to : s.otherwise, s.pos());
                n.cases.addAll(s.cases);
                for (Block b : s.targets) {
                    n.targets.add(b == from ? to : b);
                }
                yield n;
            }
            default -> null;
        };
        if (t == null) {
            return 0;
        }
        int edges = 0;
        for (Block s : old.successors()) {
            if (s == from) {
                edges++;
            }
        }
        for (int k = 0; k < edges; k++) {
            from.preds().remove(p);
            to.preds().add(p);
        }
        p.instrs().set(p.instrs().size() - 1, t);
        t.block = p;
        old.block = null;
        return edges;
    }

    static Set<Block> reachable(MFunction f) {
        Set<Block> seen = new HashSet<>();
        Deque<Block> work = new ArrayDeque<>(List.of(f.entry()));
        while (!work.isEmpty()) {
            Block b = work.pop();
            if (seen.add(b)) {
                Instr t = b.terminator();
                if (t != null) {
                    work.addAll(t.successors());
                }
            }
        }
        return seen;
    }

    /** Removes the blocks control can never reach; returns whether there were any. */
    static boolean removeUnreachable(MFunction f) {
        Set<Block> live = reachable(f);
        List<Block> dead = f.blocks().stream().filter(b -> !live.contains(b)).toList();
        for (Block d : dead) {
            Instr t = d.terminator();
            if (t != null) {
                for (Block s : t.successors()) {
                    if (live.contains(s)) {
                        removeEdge(d, s);
                    }
                }
            }
        }
        f.blocks().removeAll(dead);
        return !dead.isEmpty();
    }

    /** The blocks in reverse postorder (every block after its dominators). */
    static List<Block> reversePostorder(MFunction f) {
        List<Block> post = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[] {f.entry(), 0});
        seen.add(f.entry());
        while (!stack.isEmpty()) {
            Object[] top = stack.peek();
            Block b = (Block) top[0];
            int k = (Integer) top[1];
            List<Block> succ = b.terminator() == null ? List.of() : b.terminator().successors();
            if (k < succ.size()) {
                top[1] = k + 1;
                Block s = succ.get(k);
                if (seen.add(s)) {
                    stack.push(new Object[] {s, 0});
                }
            } else {
                post.add(b);
                stack.pop();
            }
        }
        java.util.Collections.reverse(post);
        return post;
    }

    /** Immediate dominators (Cooper, Harvey and Kennedy, "A Simple, Fast Dominance Algorithm"). */
    static Map<Block, Block> idoms(MFunction f) {
        List<Block> rpo = reversePostorder(f);
        Map<Block, Integer> order = new HashMap<>();
        for (int i = 0; i < rpo.size(); i++) {
            order.put(rpo.get(i), i);
        }
        Map<Block, Block> idom = new HashMap<>();
        idom.put(f.entry(), f.entry());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Block b : rpo) {
                if (b == f.entry()) {
                    continue;
                }
                Block d = null;
                for (Block p : b.preds()) {
                    if (!idom.containsKey(p)) {
                        continue;
                    }
                    d = d == null ? p : intersect(p, d, idom, order);
                }
                if (d != null && idom.get(b) != d) {
                    idom.put(b, d);
                    changed = true;
                }
            }
        }
        return idom;
    }

    private static Block intersect(Block a, Block b, Map<Block, Block> idom, Map<Block, Integer> order) {
        while (a != b) {
            while (order.get(a) > order.get(b)) {
                a = idom.get(a);
            }
            while (order.get(b) > order.get(a)) {
                b = idom.get(b);
            }
        }
        return a;
    }

    /** The dominator tree as lists of children, in reverse postorder. */
    static Map<Block, List<Block>> domChildren(MFunction f, Map<Block, Block> idom) {
        Map<Block, List<Block>> children = new HashMap<>();
        for (Block b : reversePostorder(f)) {
            children.computeIfAbsent(b, k -> new ArrayList<>());
            if (b != f.entry()) {
                children.computeIfAbsent(idom.get(b), k -> new ArrayList<>()).add(b);
            }
        }
        return children;
    }
}
