package dampf.backend;

import dampf.mir.Block;
import dampf.mir.Instr;
import dampf.mir.Loops;
import dampf.mir.MFunction;
import dampf.mir.MType;
import dampf.mir.Ranges;
import dampf.mir.Value;
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
 * of a block (to another block, or the return) gets a cost interval.
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
     * the cycle table (16-bit registers, D = 0): fixed loops of 16 or 32 rounds, best and worst
     * way through each round.
     */
    static final Map<String, Bound> RUNTIME = Map.of(
            "dampf_mul16", new Bound(287, 367),
            "dampf_mul32", new Bound(1168, 1968),
            "dampf_udiv16", new Bound(564, 772),
            "dampf_udiv32", new Bound(1888, 2432),
            "dampf_sdiv32", new Bound(1940, 2594));

    private static final Object RET = new Object();

    private Timing() {}

    /**
     * The bounds of one function.
     *
     * @param order   the blocks in the order their code was written (falling through goes to the next)
     * @param callees the bounds of called functions (by label), including the runtime's
     */
    static Bound of(MFunction f, List<AsmWriter.Item> items, Map<String, Integer> loopBounds, List<Block> order,
                    Ranges ranges, Function<String, Bound> callees) {
        Map<String, Block> byName = new HashMap<>();
        for (Block b : f.blocks()) {
            byName.put(b.name(), b);
        }
        Map<String, Integer> labelAt = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isLabel()) {
                labelAt.put(items.get(i).label(), i);
            }
        }
        // every block's ways out
        Map<Block, Map<Object, Bound>> exits = new IdentityHashMap<>();
        for (int k = 0; k < order.size(); k++) {
            Block b = order.get(k);
            int from = labelAt.get(b.name());
            int to = k + 1 < order.size() ? labelAt.get(order.get(k + 1).name()) : items.size();
            Block next = k + 1 < order.size() ? order.get(k + 1) : null;
            exits.put(b, block(items, from + 1, to, labelAt, loopBounds, byName, next, callees));
        }
        // loops, innermost first, become single nodes
        Map<Block, Node> node = new IdentityHashMap<>();
        for (Block b : f.blocks()) {
            Node n = new Node(b);
            n.out.putAll(exits.getOrDefault(b, Map.of()));
            node.put(b, n);
        }
        for (Loops.Loop loop : Loops.find(f)) {
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
     * How often the loop's head runs at most, the smallest of three bounds for each counter φ that
     * every way back steps by the same constant c: a test every round for equality or against an
     * end ({@link #untilEqual}, {@link #untilBound}), and, without wrapping, its finite range
     * [lo, hi] in the head: (hi - lo) / |c| + 1.
     */
    static long headRuns(Loops.Loop loop, Ranges ranges) {
        long best = UNBOUNDED;
        for (Instr i : loop.head().instrs()) {
            if (!(i instanceof Instr.Phi p)) {
                break;
            }
            if (p.type() != MType.I8 && p.type() != MType.I16 && p.type() != MType.I32) {
                continue;
            }
            Instr.Bin step = null;
            boolean ok = true;
            for (int k = 0; k < p.from.size(); k++) {
                if (loop.blocks().contains(p.from.get(k))) {
                    if (p.op(k) instanceof Instr.Bin s && (s.op == Instr.BinOp.ADD || s.op == Instr.BinOp.SUB)
                            && s.op(0) == p && s.op(1) instanceof Value.Const && (step == null || step == s)) {
                        step = s;
                    } else {
                        ok = false;
                    }
                }
            }
            if (!ok || step == null) {
                continue;
            }
            long c = p.type().signed(((Value.Const) step.op(1)).bits());
            if (step.op == Instr.BinOp.SUB) {
                c = -c;
            }
            for (Block test : everyRoundTests(loop)) {
                best = Math.min(best, untilEqual(loop, test, p, c, ranges));
                best = Math.min(best, untilBound(loop, test, p, c, ranges));
            }
            Ranges.Range r = ranges.of(p);
            Ranges.Range at = ranges.at(p, step.block());
            Ranges.Range all = Ranges.full(p.type());
            if (c == 0 || r == null || at == null || r.equals(all)
                    || at.hi() + c > all.hi() || at.lo() + c < all.lo()) {
                continue;   // no bound from this one (or it may wrap around)
            }
            best = Math.min(best, (r.hi() - r.lo()) / Math.abs(c) + 1);
        }
        return best;
    }

    /**
     * A counter stepped by 1 or -1 towards the constant the head compares it with for equality
     * ({@code k != 0} stays in the loop): from a start on the right side, the head runs at most
     * |start - constant| + 1 times. UNBOUNDED if that does not apply.
     */
    private static long untilEqual(Loops.Loop loop, Block test, Instr.Phi p, long c, Ranges ranges) {
        if (Math.abs(c) != 1 || !(test.terminator() instanceof Instr.Branch br)
                || !(br.op(0) instanceof Instr.Cmp cmp) || cmp.block() != test || cmp.op(0) != p
                || !(cmp.op(1) instanceof Value.Const k)) {
            return UNBOUNDED;
        }
        boolean staysIfTrue = loop.blocks().contains(br.ifTrue) && !loop.blocks().contains(br.ifFalse);
        boolean staysIfFalse = loop.blocks().contains(br.ifFalse) && !loop.blocks().contains(br.ifTrue);
        boolean leavesWhenEqual = cmp.pred == Instr.Pred.NE && staysIfTrue || cmp.pred == Instr.Pred.EQ && staysIfFalse;
        if (!leavesWhenEqual) {
            return UNBOUNDED;
        }
        long target = p.type().signed(k.bits());
        long most = 0;
        for (int i = 0; i < p.from.size(); i++) {
            Block from = p.from.get(i);
            if (loop.blocks().contains(from)) {
                continue;
            }
            Ranges.Range start = ranges.at(p.op(i), from);
            if (start == null) {
                return UNBOUNDED;
            }
            // steps until equal, modulo 2^w: from the other side it goes round the type
            long m = 1L << p.type().bits();
            if (c < 0) {
                most = Math.max(most, start.lo() < target && target <= start.hi() ? m - 1 : Math.floorMod(start.hi() - target, m));
            } else {
                most = Math.max(most, start.lo() <= target && target < start.hi() ? m - 1 : Math.floorMod(target - start.lo(), m));
            }
        }
        return most + 1;
    }

    /**
     * A counter stepped by c towards the end of an ordered test in the head that keeps the loop
     * going ({@code i < n}, {@code i > k}, signed or unsigned): while it stays, it is on the near
     * side of the other operand's extreme, so with a step that cannot wrap there, the head runs
     * at most (extreme - start) / |c| + 2 times. Needs no range of the counter itself (an 8-bit
     * unsigned counter has none in the signed view): an unknown start is the type's end.
     */
    private static long untilBound(Loops.Loop loop, Block test, Instr.Phi p, long c, Ranges ranges) {
        Block head = loop.head();
        if (!(test.terminator() instanceof Instr.Branch br) || !(br.op(0) instanceof Instr.Cmp cmp)
                || cmp.block() != test) {
            return UNBOUNDED;
        }
        boolean staysIfTrue = loop.blocks().contains(br.ifTrue) && !loop.blocks().contains(br.ifFalse);
        boolean staysIfFalse = loop.blocks().contains(br.ifFalse) && !loop.blocks().contains(br.ifTrue);
        if (staysIfTrue == staysIfFalse) {
            return UNBOUNDED;
        }
        Instr.Pred stay = staysIfTrue ? cmp.pred : negate(cmp.pred);
        Value y;
        Value q;   // the counter as compared: p, or p zero-extended
        if (counted(cmp.op(0), p)) {
            q = cmp.op(0);
            y = cmp.op(1);
        } else if (counted(cmp.op(1), p)) {
            q = cmp.op(1);
            y = cmp.op(0);
            stay = swap(stay);
        } else {
            return UNBOUNDED;
        }
        if (stay == Instr.Pred.EQ || stay == Instr.Pred.NE) {
            return UNBOUNDED;   // untilEqual
        }
        boolean unsigned = stay == Instr.Pred.ULT || stay == Instr.Pred.ULE || stay == Instr.Pred.UGT || stay == Instr.Pred.UGE;
        boolean up = stay == Instr.Pred.ULT || stay == Instr.Pred.ULE || stay == Instr.Pred.SLT || stay == Instr.Pred.SLE;
        boolean inclusive = stay == Instr.Pred.ULE || stay == Instr.Pred.SLE || stay == Instr.Pred.UGE || stay == Instr.Pred.SGE;
        if (up != c > 0) {
            return UNBOUNDED;   // stepping away from the end
        }
        // the counter's values: in its own type, as its unsigned value when compared zero-extended
        MType t = p.type();
        int w = t.bits();
        boolean asUnsigned = unsigned || q != p;
        long min = asUnsigned ? 0 : -(1L << (w - 1));
        long max = asUnsigned ? (1L << w) - 1 : (1L << (w - 1)) - 1;
        // the other operand in the comparison's type
        MType ct = q.type();
        int cw = ct.bits();
        Ranges.Range yr = view(ranges.at(y, test), ct, unsigned,
                unsigned ? 0 : -(1L << (cw - 1)), unsigned ? (1L << cw) - 1 : (1L << (cw - 1)) - 1);
        Ranges.Range start = null;
        for (int i = 0; i < p.from.size(); i++) {
            Block from = p.from.get(i);
            if (!loop.blocks().contains(from)) {
                Ranges.Range r = view(ranges.at(p.op(i), from), t, asUnsigned, min, max);
                start = r.join(start);
            }
        }
        if (start == null) {
            return UNBOUNDED;
        }
        long a = Math.abs(c);
        if (up) {
            long last = inclusive ? yr.hi() : yr.hi() - 1;   // the largest value that stays
            if (last + a > max) {
                return UNBOUNDED;   // the step could wrap around
            }
            return last < start.lo() ? 1 : (last - start.lo()) / a + 2;
        }
        long last = inclusive ? yr.lo() : yr.lo() + 1;   // the smallest value that stays
        if (last - a < min) {
            return UNBOUNDED;
        }
        return last > start.hi() ? 1 : (start.hi() - last) / a + 2;
    }

    /** Whether v is the counter p, or p zero-extended to a wider type (its unsigned value). */
    private static boolean counted(Value v, Instr.Phi p) {
        return v == p || v instanceof Instr.Cast k && k.kind == Instr.CastKind.ZEXT && k.op(0) == p
                && k.type().bits() > p.type().bits();
    }

    /**
     * The blocks of a loop that end in a test leaving the loop and that every round passes (they
     * dominate every way back): the head, and tests further down such as {@code if (i == 0)
     * return;}. Every head run but the last passes each of them with the loop going on, so any of
     * them bounds the head runs as the head's own test would.
     */
    static List<Block> everyRoundTests(Loops.Loop loop) {
        List<Block> out = new ArrayList<>();
        for (Block b : loop.blocks()) {
            if (b.terminator() instanceof Instr.Branch br && br.ifTrue != br.ifFalse
                    && loop.blocks().contains(br.ifTrue) != loop.blocks().contains(br.ifFalse)
                    && dominatesLatches(loop, b)) {
                out.add(b);
            }
        }
        return out;
    }

    /** Whether every way from the head to a latch passes b (inside the loop). */
    private static boolean dominatesLatches(Loops.Loop loop, Block b) {
        if (b == loop.head()) {
            return true;
        }
        Set<Block> seen = new HashSet<>();
        java.util.ArrayDeque<Block> work = new java.util.ArrayDeque<>();
        work.add(loop.head());
        seen.add(loop.head());
        while (!work.isEmpty()) {
            Block x = work.pop();
            if (loop.latches().contains(x)) {
                return false;   // a latch reached without passing b
            }
            if (x.terminator() == null) {
                continue;
            }
            for (Block s : x.terminator().successors()) {
                if (s != b && s != loop.head() && loop.blocks().contains(s) && seen.add(s)) {
                    work.add(s);
                }
            }
        }
        return true;
    }

    /** r in the signed or unsigned view of type t; the whole type if unknown or not one interval. */
    private static Ranges.Range view(Ranges.Range r, MType t, boolean unsigned, long min, long max) {
        Ranges.Range v = r == null ? null : unsigned ? Ranges.unsigned(r, t) : r;
        return v == null ? new Ranges.Range(min, max) : v;
    }

    private static Instr.Pred negate(Instr.Pred p) {
        return switch (p) {
            case EQ -> Instr.Pred.NE;
            case NE -> Instr.Pred.EQ;
            case ULT -> Instr.Pred.UGE;
            case UGE -> Instr.Pred.ULT;
            case ULE -> Instr.Pred.UGT;
            case UGT -> Instr.Pred.ULE;
            case SLT -> Instr.Pred.SGE;
            case SGE -> Instr.Pred.SLT;
            case SLE -> Instr.Pred.SGT;
            case SGT -> Instr.Pred.SLE;
        };
    }

    private static Instr.Pred swap(Instr.Pred p) {
        return switch (p) {
            case ULT -> Instr.Pred.UGT;
            case UGT -> Instr.Pred.ULT;
            case ULE -> Instr.Pred.UGE;
            case UGE -> Instr.Pred.ULE;
            case SLT -> Instr.Pred.SGT;
            case SGT -> Instr.Pred.SLT;
            case SLE -> Instr.Pred.SGE;
            case SGE -> Instr.Pred.SLE;
            default -> p;
        };
    }

    /** The ways out of one block's code, items [from, to), with their cost intervals. */
    private static Map<Object, Bound> block(List<AsmWriter.Item> items, int from, int to, Map<String, Integer> labelAt,
                                            Map<String, Integer> loopBounds, Map<String, Block> byName, Block next,
                                            Function<String, Bound> callees) {
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
            AsmWriter.Item it = items.get(from + i);
            if (it.isLabel()) {
                Integer rounds = loopBounds.get(it.label());
                if (rounds != null) {
                    // a small loop: from here to the branch back; straight code in between
                    int back = backBranch(items, from + i, to, it.label());
                    long body = 0;
                    for (int j = from + i + 1; j < back; j++) {
                        body += items.get(j).cycles();
                    }
                    AsmWriter.Item br = items.get(back);
                    Bound loop = new Bound(body + br.cycles(),
                            add(times(rounds - 1, body + br.taken()), body + br.cycles()));
                    relax(min, max, back - from + 1, a.plus(loop));
                } else {
                    relax(min, max, i + 1, a);
                }
                continue;
            }
            String m = it.mnemonic();
            String op = it.operand();
            Block target = op == null ? null : byName.get(op);
            if (it.conditional()) {
                Bound taken = a.plus(new Bound(it.taken(), it.taken()));
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

    private static int backBranch(List<AsmWriter.Item> items, int label, int to, String name) {
        for (int j = label + 1; j < to; j++) {
            AsmWriter.Item it = items.get(j);
            if (!it.isLabel() && it.conditional() && name.equals(it.operand())) {
                return j;
            }
            if (!it.isLabel() && (it.conditional() || it.mnemonic().startsWith("j") || it.mnemonic().equals("bra"))) {
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
}
