package dampf.backend;

import dampf.lir.LBlock;
import dampf.lir.LFunction;
import dampf.lir.LInstr;
import dampf.lir.LItem;
import dampf.lir.LLabel;
import dampf.lir.LSourceLine;
import dampf.mir.Block;
import dampf.mir.CountedLoops;
import dampf.mir.MFunction;
import dampf.mir.Ranges;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Static timing: the best and the worst case of a function in CPU cycles, from the code the code
 * generator wrote (docs/timing.md, levels 1 and 2).
 *
 * <p>Within a block the code is straight, except for forward branches over a few instructions
 * and the small loops the code generator marks with their bound (shifts, copies). Every way out
 * of a block (to another block, or the return) gets a cost interval; a jump table of a switch is
 * a way out to each of its entries.
 *
 * <p>Across blocks: the loops of the function, innermost first, each collapsed into one node. A
 * loop needs a bound on how often its head runs, from a counter that every way back steps by the
 * same constant ({@link #headRuns}): its finite value range in the head without wrapping, a step
 * of 1 towards the constant a test compares it with for equality ({@link #untilEqual}), or a step
 * towards the end of an ordered test ({@link #untilBound}); the smallest bound wins. Without one
 * the worst case is unbounded. The rest of the function is then free of cycles, and the bounds
 * are the shortest and longest paths to the return. A function that never returns has neither.
 *
 * <p>A call costs its own cycles plus the callee's bounds (functions are analysed callees
 * first; there is no recursion); the runtime routines have fixed bounds.
 */
final class Timing {
    /** No bound. */
    static final long UNBOUNDED = Long.MAX_VALUE / 4;

    record Bound(long best, long worst) {
        Bound plus(Bound o) {
            return new Bound(add(best, o.best), add(worst, o.worst));
        }

        boolean bounded() {
            return worst < UNBOUNDED;
        }
    }

    static long add(long a, long b) {
        return a >= UNBOUNDED || b >= UNBOUNDED ? UNBOUNDED : a + b;
    }

    private static long times(long n, long a) {
        if (n == 0 || a == 0) {
            return 0;
        }
        return n >= UNBOUNDED || a >= UNBOUNDED || a > UNBOUNDED / n ? UNBOUNDED : n * a;
    }

    /**
     * The runtime's routines, from JSL's target to their RTL, counted by hand from runtime.s with
     * the cycle table (16-bit registers, D = 0): the multiplications without a loop, on the
     * CPU's multiplier; the divisions in fixed loops of 16 or 32 rounds, best and worst way
     * through each round.
     */
    static final Map<String, Bound> RUNTIME = Map.of(
            "dampf_mul16", new Bound(108, 108),
            "dampf_mul32", new Bound(398, 400),
            "dampf_udiv16", new Bound(564, 772),
            "dampf_udiv32", new Bound(1888, 2432),
            "dampf_sdiv32", new Bound(1940, 2594));

    /**
     * The multiplications of interrupt code, in software (ADR-6FM9TXV4), counted the same way: one
     * round of 23 or 32 cycles per bit of the second factor up to its highest, and 32 rounds of 36
     * or 61. Only a program with an interrupt handler has them.
     */
    static final Map<String, Bound> RUNTIME_SOFT = Map.of(
            "dampf_mul16_soft", new Bound(22, 532),
            "dampf_mul32_soft", new Bound(1168, 1968));

    /**
     * The runtime's entry stub of an interrupt handler (runtime.s), in CPU cycles: from the
     * interrupt being taken (the CPU's 8 cycles of pushing PBR, PC and P and reading the vector) to
     * the handler's first instruction, and from its RTL's target to the end of the RTI. The NMI
     * reads RDNMI in 8 bits, so it takes 5 more cycles than the IRQ. The instruction the CPU was
     * running when the interrupt came, which it finishes first, is not counted.
     */
    static final Map<dampf.ast.Interrupt, Integer> INTERRUPT_ENTRY = Map.of(dampf.ast.Interrupt.NMI, 71,
            dampf.ast.Interrupt.IRQ, 66);
    static final int INTERRUPT_EXIT = 31;

    private static final Object RET = new Object();

    private Timing() {}

    /**
     * The bounds of one function.
     *
     * @param order   the blocks in the order their code was written (falling through goes to the next)
     * @param callees the bounds of called functions (by label), including the runtime's
     */
    static Bound of(MFunction f, LFunction code, Map<String, Integer> loopBounds, List<Block> order,
                    Ranges ranges, Function<String, Bound> callees) {
        Map<String, Block> byName = new HashMap<>();
        for (Block b : f.blocks()) {
            byName.put(b.name(), b);
        }
        // the code in layout order: instructions and labels
        List<LItem> items = new ArrayList<>();
        for (LBlock b : code.blocks()) {
            for (LItem i : b.items()) {
                if (i instanceof LInstr || i instanceof LLabel) {
                    items.add(i);
                }
            }
        }
        Map<String, Integer> labelAt = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof LLabel l) {
                labelAt.put(l.name(), i);
            }
        }
        // every block's ways out
        Map<Block, Map<Object, Bound>> exits = new IdentityHashMap<>();
        for (int k = 0; k < order.size(); k++) {
            Block b = order.get(k);
            int from = labelAt.get(b.name());
            int to = k + 1 < order.size() ? labelAt.get(order.get(k + 1).name()) : items.size();
            Block next = k + 1 < order.size() ? order.get(k + 1) : null;
            exits.put(b, block(items, from + 1, to, labelAt, loopBounds, byName, next, callees, code::tableTargets));
        }
        // loops, innermost first, become single nodes
        Map<Block, Node> node = new IdentityHashMap<>();
        for (Block b : f.blocks()) {
            Node n = new Node(b);
            n.out.putAll(exits.getOrDefault(b, Map.of()));
            node.put(b, n);
        }
        for (CountedLoops.Loop loop : CountedLoops.find(f)) {
            Node head = node.get(loop.head());
            Set<Node> inside = new HashSet<>();
            for (Block b : loop.blocks()) {
                inside.add(node.get(b));
            }
            Map<Object, Bound> paths = paths(head, inside, node, true);
            long rounds = headRuns(loop, ranges);
            Bound round = paths.remove(loop.head());   // once around the loop
            Node collapsed = new Node(loop.head());
            for (Map.Entry<Object, Bound> e : paths.entrySet()) {
                Bound out = e.getValue();
                long worst = round == null ? out.worst()
                        : add(times(rounds >= UNBOUNDED ? UNBOUNDED : rounds - 1, round.worst()), out.worst());
                collapsed.out.put(e.getKey(), new Bound(out.best(), worst));
            }
            for (Block b : loop.blocks()) {
                node.put(b, collapsed);
            }
        }
        Map<Object, Bound> all = paths(node.get(f.entry()), null, node, false);
        Bound r = all.get(RET);
        return r == null ? new Bound(UNBOUNDED, UNBOUNDED) : r;   // never returns
    }

    /** A block, or a loop collapsed into one node (entered at its head). */
    private static final class Node {
        final Block entry;
        final Map<Object, Bound> out = new LinkedHashMap<>();   // to a Block, or RET

        Node(Block entry) {
            this.entry = entry;
        }
    }

    /**
     * Shortest and longest ways from {@code start} to everything outside {@code inside} (or
     * everything, if null) and, for a loop, back to its head; the graph without the head's back
     * edges is acyclic (inner loops are collapsed).
     */
    private static Map<Object, Bound> paths(Node start, Set<Node> inside, Map<Block, Node> node, boolean loop) {
        List<Node> topo = new ArrayList<>();
        Set<Node> seen = new HashSet<>();
        visit(start, start, inside, node, seen, topo);
        java.util.Collections.reverse(topo);
        Map<Node, Bound> arrive = new IdentityHashMap<>();
        arrive.put(start, new Bound(0, 0));
        Map<Object, Bound> result = new LinkedHashMap<>();
        for (Node u : topo) {
            Bound a = arrive.get(u);
            if (a == null) {
                continue;
            }
            for (Map.Entry<Object, Bound> e : u.out.entrySet()) {
                Bound c = a.plus(e.getValue());
                Object target = e.getKey();
                Node v = target instanceof Block b ? node.get(b) : null;
                if (v == start && loop) {
                    join(result, start.entry, c);   // a way around the loop
                } else if (v != null && v != u && (inside == null || inside.contains(v))) {
                    arrive.merge(v, c, Timing::union);
                } else if (v == u) {
                    // a collapsed inner loop's way back to itself was counted inside it
                    continue;
                } else {
                    join(result, target instanceof Block b ? node.get(b).entry : RET, c);
                }
            }
        }
        return result;
    }

    private static void visit(Node u, Node start, Set<Node> inside, Map<Block, Node> node, Set<Node> seen, List<Node> post) {
        if (!seen.add(u)) {
            return;
        }
        for (Object t : u.out.keySet()) {
            if (t instanceof Block b) {
                Node v = node.get(b);
                if (v != start && v != u && (inside == null || inside.contains(v))) {
                    visit(v, start, inside, node, seen, post);
                }
            }
        }
        post.add(u);
    }

    private static Bound union(Bound a, Bound b) {
        return new Bound(Math.min(a.best(), b.best()), Math.max(a.worst(), b.worst()));
    }

    private static void join(Map<Object, Bound> m, Object k, Bound b) {
        m.merge(k, b, Timing::union);
    }

    /**
     * How often the loop's head runs at most, from the tests that end it and from its counters'
     * ranges ({@link CountedLoops.Loop#headRuns}); UNBOUNDED without a bound.
     */
    static long headRuns(CountedLoops.Loop loop, Ranges ranges) {
        long r = loop.headRuns(CountedLoops.of(ranges));
        return r == CountedLoops.UNKNOWN ? UNBOUNDED : r;
    }

    /** The ways out of one block's code, items [from, to), with their cost intervals. */
    private static Map<Object, Bound> block(List<LItem> items, int from, int to, Map<String, Integer> labelAt,
                                            Map<String, Integer> loopBounds, Map<String, Block> byName, Block next,
                                            Function<String, Bound> callees, Function<String, List<String>> tables) {
        int n = to - from;
        long[] min = new long[n + 1];
        long[] max = new long[n + 1];
        java.util.Arrays.fill(min, -1);
        min[0] = 0;
        max[0] = 0;
        Map<Object, Bound> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (min[i] < 0) {
                continue;
            }
            Bound a = new Bound(min[i], max[i]);
            if (items.get(from + i) instanceof LLabel label) {
                Integer rounds = loopBounds.get(label.name());
                if (rounds != null) {
                    // a small loop: from here to the branch back; straight code in between
                    int back = backBranch(items, from + i, to, label.name());
                    long body = 0;
                    for (int j = from + i + 1; j < back; j++) {
                        body += ((LInstr) items.get(j)).cycles();
                    }
                    LInstr br = (LInstr) items.get(back);
                    Bound loop = new Bound(body + br.cycles(),
                            add(times(rounds - 1, body + br.cyclesTaken()), body + br.cycles()));
                    relax(min, max, back - from + 1, a.plus(loop));
                } else {
                    relax(min, max, i + 1, a);
                }
                continue;
            }
            LInstr it = (LInstr) items.get(from + i);
            String m = it.mnemonic();
            String op = it.operand();
            Block target = op == null ? null : byName.get(op);
            if (it.isConditionalBranch()) {
                Bound taken = a.plus(new Bound(it.cyclesTaken(), it.cyclesTaken()));
                if (target != null) {
                    join(out, target, taken);
                } else {
                    Integer t = labelAt.get(op);
                    if (t == null || t <= from + i) {
                        return Map.of(RET, new Bound(0, UNBOUNDED));   // a loop without a bound
                    }
                    relax(min, max, t - from, taken);
                }
                relax(min, max, i + 1, a.plus(new Bound(it.cycles(), it.cycles())));
            } else if (it.isTableJump()) {
                // a switch's jump table: to each of its entries, a block or a stub behind the jump
                Bound go = a.plus(new Bound(it.cycles(), it.cycles()));
                List<String> entries = tables.apply(it.tableLabel());
                if (entries.isEmpty()) {
                    throw new IllegalStateException("a jump through a table that is not in the function: " + it);
                }
                for (String entry : entries) {
                    Block tb = byName.get(entry);
                    if (tb != null) {
                        join(out, tb, go);
                        continue;
                    }
                    Integer t = labelAt.get(entry);
                    if (t == null || t <= from + i || t >= to) {
                        throw new IllegalStateException("a table entry neither a block nor behind its jump: " + entry);
                    }
                    relax(min, max, t - from, go);
                }
            } else if (m.equals("jmp") || m.equals("bra") || m.equals("brl") || m.equals("jml")) {
                Bound go = a.plus(new Bound(it.cycles(), it.cycles()));
                if (target != null) {
                    join(out, target, go);
                } else {
                    Integer t = labelAt.get(op);
                    if (t == null || t <= from + i) {
                        return Map.of(RET, new Bound(0, UNBOUNDED));
                    }
                    relax(min, max, t - from, go);
                }
            } else if (m.equals("rtl") || m.equals("rts") || m.equals("rti")) {
                join(out, RET, a.plus(new Bound(it.cycles(), it.cycles())));
            } else if (m.equals("jsl") || m.equals("jsr")) {
                Bound callee = callees.apply(op);
                relax(min, max, i + 1, a.plus(new Bound(it.cycles(), it.cycles())).plus(callee));
            } else {
                relax(min, max, i + 1, a.plus(new Bound(it.cycles(), it.cycles())));
            }
        }
        if (min[n] >= 0) {
            if (next == null) {
                throw new IllegalStateException("the code of the last block falls through");
            }
            join(out, next, new Bound(min[n], max[n]));
        }
        return out;
    }

    private static int backBranch(List<LItem> items, int label, int to, String name) {
        for (int j = label + 1; j < to; j++) {
            if (!(items.get(j) instanceof LInstr it)) {
                continue;
            }
            if (it.isConditionalBranch() && name.equals(it.operand())) {
                return j;
            }
            if (it.isConditionalBranch() || it.mnemonic().startsWith("j") || it.mnemonic().equals("bra")) {
                throw new IllegalStateException("a marked loop with a branch inside: " + name);
            }
        }
        throw new IllegalStateException("a marked loop without its branch back: " + name);
    }

    private static void relax(long[] min, long[] max, int k, Bound b) {
        if (min[k] < 0) {
            min[k] = b.best();
            max[k] = b.worst();
        } else {
            min[k] = Math.min(min[k], b.best());
            max[k] = Math.max(max[k], b.worst());
        }
    }

    /**
     * The cost of each C line of a function, set on its header: the sum of its instructions when
     * the line is straight code; none for a line with branches, jumps or labels, whose cost
     * depends on the path (the bounds of the whole function cover those). Calls count only the
     * call itself and are marked "+ calls".
     */
    static void lineCosts(LFunction f) {
        LSourceLine header = null;
        long sum = 0;
        boolean branches = false;
        boolean calls = false;
        for (LBlock b : f.blocks()) {
            for (LItem i : b.items()) {
                switch (i) {
                    case LSourceLine s -> {
                        setCost(header, sum, branches, calls);
                        header = s;
                        sum = 0;
                        branches = false;
                        calls = false;
                    }
                    case LLabel l -> branches = true;
                    case LInstr in -> {
                        if (in.isConditionalBranch() || in.isJump()) {
                            branches = true;
                        } else {
                            sum += in.cycles();
                        }
                        calls |= in.isCall();
                    }
                    default -> { }
                }
            }
        }
        setCost(header, sum, branches, calls);
    }

    private static void setCost(LSourceLine header, long sum, boolean branches, boolean calls) {
        if (header != null) {
            header.setCycles(!branches && sum > 0 ? Integer.valueOf((int) sum) : null, calls);
        }
    }
}
