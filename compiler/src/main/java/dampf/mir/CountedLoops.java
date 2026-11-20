package dampf.mir;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counted loops: for every loop of {@link Loops#find}, its counters, the tests that end it, and
 * from these how often its head runs and which values each counter takes
 * (docs/current-compiler-flaws.md, flaw L). The one place that recognises a counting loop; it
 * works on the canonical form of {@link Simplify} (a step is {@code add φ, c}, never a
 * subtraction; a comparison with a constant has the constant on the right).
 *
 * <p>A <b>counter</b> is an integer φ of the head whose value on every way back is
 * {@code add φ, c} with one constant c, the step. An <b>exit</b> is the branch of a block that
 * every round passes (it dominates every way back) and that leaves the loop on one side, on a
 * comparison of a counter with a bound: a constant, or a value defined outside the loop. What is
 * compared is the φ or the stepped value φ + c (the test of a {@code do} loop), either one
 * possibly zero-extended to a wider type.
 *
 * <p>The numbers come from the ranges of the starts and of the bounds ({@link Oracle}), the final
 * ones of {@link Ranges} or its state while it iterates. Each exit gives, in the reading of its
 * comparison (both for {@code !=}):
 * <ul>
 *   <li>stays while the counter is below the bound (at most it), c &gt; 0: if the last value that
 *       stays plus c still fits the reading, the counter cannot wrap, the head runs at most
 *       (last - first compared) / c + 2 times, and the φ lies between its start and the last
 *       value that stays plus c (plus 0 when the stepped value is compared);</li>
 *   <li>stays while it is above the bound, c &lt; 0: the same downwards;</li>
 *   <li>stays while it is not the bound: with a step of 1 from below the bound (-1 from above),
 *       or start and bound constants the step reaches exactly, as "below" ("above"); else, with an
 *       odd step, the counter meets every value of its type, so the head runs at most 2^w times.</li>
 * </ul>
 * The head runs at most the fewest of these times ({@link #headRuns}, which also counts from the
 * counters' own ranges). Every counter, compared or not, then lies between its start and start
 * plus (runs - 1) steps, when that fits its type ({@link #interval}).
 */
public final class CountedLoops {
    /** No bound on the head runs. */
    public static final long UNKNOWN = Long.MAX_VALUE;

    /** Ranges of values: v as seen in block b, in the signed or unsigned reading; null if unknown. */
    @FunctionalInterface
    public interface Oracle {
        Ranges.Range at(Value v, Block b, boolean unsigned);
    }

    /** No ranges: constants only. */
    public static final Oracle CONSTANTS = (v, b, unsigned) -> {
        if (v instanceof Value.Const k && Ranges.isInt(k.type()) && k.type() != MType.I1) {
            long x = unsigned ? k.bits() & ((1L << k.type().bits()) - 1) : k.type().signed(k.bits());
            return new Ranges.Range(x, x);
        }
        return null;
    };

    /** The final ranges of a function. */
    public static Oracle of(Ranges r) {
        return (v, b, unsigned) -> unsigned ? r.atUnsigned(v, b) : r.at(v, b);
    }

    /** A φ of the head that every way back steps by c (the steps: the {@code add φ, c} it gets). */
    public record Counter(Instr.Phi phi, long c, List<Instr.Bin> steps) {
        /** The one step instruction of every way back, or null if the ways back have several. */
        public Instr.Bin step() {
            return steps.size() == 1 ? steps.getFirst() : null;
        }

        /** Whether v is the counter after its step: {@code add φ, c}. */
        public boolean isStepped(Value v) {
            return v instanceof Instr.Bin b && b.op == Instr.BinOp.ADD && b.op(0) == phi
                    && b.op(1) instanceof Value.Const k && phi.type().signed(k.bits()) == c;
        }
    }

    /**
     * A test that ends the loop: the branch of {@code block}, on {@code cmp}; the loop stays while
     * {@code counter stay bound} holds, with the counter as compared: after its step if
     * {@code stepped}, zero-extended to the comparison's type if {@code widened}. {@code out} is
     * the block it leaves to.
     */
    public record Exit(Block block, Instr.Cmp cmp, Counter counter, boolean stepped, boolean widened,
                       Instr.Pred stay, Value bound, Block out) {}

    /** A way out of the loop. */
    public record Edge(Block from, Block to) {}

    /** The signed and the unsigned interval of a counter in the head; null where it is not known. */
    public record Interval(Ranges.Range signed, Ranges.Range unsigned) {}

    /** A loop with its counters and exits. */
    public record Loop(Loops.Loop loop, List<Block> entries, List<Edge> exitEdges, List<Counter> counters,
                       List<Exit> exits) {
        public Block head() {
            return loop.head();
        }

        public Set<Block> blocks() {
            return loop.blocks();
        }

        public List<Block> latches() {
            return loop.latches();
        }

        /** The one block outside that enters the loop, or null if there are several. */
        public Block preheader() {
            return entries.size() == 1 ? entries.getFirst() : null;
        }

        /** The counter whose φ is p, or null. */
        public Counter counter(Instr.Phi p) {
            for (Counter c : counters) {
                if (c.phi == p) {
                    return c;
                }
            }
            return null;
        }

        /** How often the head runs at most per entry into the loop, from the exits only; UNKNOWN if not bounded. */
        public long runs(Oracle o) {
            long best = UNKNOWN;
            for (Exit e : exits) {
                for (Fact f : facts(e, o)) {
                    best = Math.min(best, f.runs);
                }
            }
            return best;
        }

        /**
         * How often the head runs at most: the exits, and each counter with one step instruction
         * whose signed range in the head is finite and which cannot wrap at its step:
         * (hi - lo) / |c| + 1. UNKNOWN if not bounded.
         */
        public long headRuns(Oracle o) {
            long best = runs(o);
            for (Counter k : counters) {
                Instr.Bin step = k.step();
                if (step == null) {
                    continue;
                }
                MType t = k.phi.type();
                Ranges.Range all = Ranges.full(t);
                Ranges.Range r = o.at(k.phi, head(), false);
                Ranges.Range at = o.at(k.phi, step.block(), false);
                if (r == null || at == null || r.equals(all) || at.hi() + k.c > all.hi() || at.lo() + k.c < all.lo()) {
                    continue;
                }
                best = Math.min(best, (r.hi() - r.lo()) / Math.abs(k.c) + 1);
            }
            return best;
        }

        /**
         * The exact number of head runs per entry, or -1: one way out, and it is an exit whose
         * counter starts at a constant and is compared with a constant it reaches without wrapping.
         */
        public long exactRuns() {
            if (exitEdges.size() != 1) {
                return -1;
            }
            for (Exit e : exits) {
                if (e.block == exitEdges.getFirst().from) {
                    for (Fact f : facts(e, CONSTANTS)) {
                        if (f.exact) {
                            return f.runs;
                        }
                    }
                }
            }
            return -1;
        }

        /**
         * The values counter k has in the head, in each reading: what its exits say, and start to
         * start + (runs - 1) * c if that fits the reading.
         */
        public Interval interval(Counter k, Oracle o) {
            MType t = k.phi.type();
            Ranges.Range s = null;
            Ranges.Range u = null;
            for (Exit e : exits) {
                if (e.counter == k) {
                    for (Fact f : facts(e, o)) {
                        if (f.phi != null) {
                            if (f.unsigned) {
                                u = u == null ? f.phi : u.meet(f.phi);
                            } else {
                                s = s == null ? f.phi : s.meet(f.phi);
                            }
                        }
                    }
                }
            }
            long runs = runs(o);
            if (runs != UNKNOWN) {
                for (boolean unsigned : new boolean[] {false, true}) {
                    Ranges.Range start = start(k, o, unsigned);
                    Ranges.Range all = unsigned ? Ranges.ufull(t) : Ranges.full(t);
                    if (runs - 1 > (all.hi() - all.lo()) / Math.abs(k.c)) {
                        continue;   // more steps than the type has room for
                    }
                    long lo = k.c > 0 ? start.lo() : start.lo() + (runs - 1) * k.c;
                    long hi = k.c > 0 ? start.hi() + (runs - 1) * k.c : start.hi();
                    if (lo >= all.lo() && hi <= all.hi()) {
                        Ranges.Range r = new Ranges.Range(lo, hi);
                        if (unsigned) {
                            u = u == null ? r : u.meet(r);
                        } else {
                            s = s == null ? r : s.meet(r);
                        }
                    }
                }
            }
            return new Interval(s, u);
        }

        /** The start values of counter k, joined over the ways in (the whole reading if unknown). */
        Ranges.Range start(Counter k, Oracle o, boolean unsigned) {
            MType t = k.phi.type();
            Ranges.Range all = unsigned ? Ranges.ufull(t) : Ranges.full(t);
            Ranges.Range r = null;
            for (int i = 0; i < k.phi.from.size(); i++) {
                Block from = k.phi.from.get(i);
                if (!blocks().contains(from)) {
                    Ranges.Range x = o.at(k.phi.op(i), from, unsigned);
                    r = (x == null ? all : x).join(r);
                }
            }
            return r == null ? all : r;
        }

        /** What one exit says, in each reading it holds in. */
        List<Fact> facts(Exit e, Oracle o) {
            List<Fact> out = new ArrayList<>(2);
            Counter k = e.counter;
            MType t = k.phi.type();
            int w = t.bits();
            // unsigned: the comparison's reading, in which the bound's range is taken
            for (boolean unsigned : new boolean[] {false, true}) {
                boolean skip = switch (e.stay) {
                    case SLT, SLE, SGT, SGE -> unsigned;
                    case ULT, ULE, UGT, UGE -> !unsigned;
                    // equality: either reading, but a zero-extended counter is a small unsigned number
                    default -> e.widened && !unsigned;
                };
                if (skip) {
                    continue;
                }
                // the counter's values: the comparison's reading, or its unsigned one when zero-extended
                boolean counterUnsigned = e.widened || unsigned;
                Ranges.Range all = counterUnsigned ? Ranges.ufull(t) : Ranges.full(t);
                Ranges.Range s = start(k, o, counterUnsigned);
                Ranges.Range y = o.at(e.bound, e.block, unsigned);
                if (y == null) {
                    MType bt = e.bound.type();
                    y = unsigned ? Ranges.ufull(bt) : Ranges.full(bt);
                }
                Fact f = fact(e.stay, k.c, e.stepped ? 1 : 0, s, y, all.lo(), all.hi(), w, counterUnsigned);
                if (f != null) {
                    out.add(f);
                }
            }
            return out;
        }
    }

    /**
     * What an exit says in one reading: the head runs at most {@code runs} times (exact if start
     * and bound are single values), and the φ lies in {@code phi} (null if it may wrap).
     */
    record Fact(long runs, Ranges.Range phi, boolean unsigned, boolean exact) {}

    /**
     * One exit in one reading: the loop stays while (counter stay y); the counter starts in s,
     * steps by c, and its values lie in [min, max]; the comparison sees it after d steps (0: the
     * φ, 1: the stepped value). Null if this says nothing.
     */
    static Fact fact(Instr.Pred stay, long c, int d, Ranges.Range s, Ranges.Range y, long min, long max, int w,
                     boolean unsigned) {
        if (c == 0) {
            return null;
        }
        boolean points = s.lo() == s.hi() && y.lo() == y.hi();
        boolean strict;
        boolean up;
        switch (stay) {
            case ULT, SLT -> { strict = true; up = true; }
            case ULE, SLE -> { strict = false; up = true; }
            case UGT, SGT -> { strict = true; up = false; }
            case UGE, SGE -> { strict = false; up = false; }
            case NE -> {
                // the first compared value, if it does not wrap
                long flo = s.lo() + d * c;
                long fhi = s.hi() + d * c;
                boolean reaches = flo >= min && fhi <= max
                        && (c == 1 && fhi <= y.lo() || c == -1 && flo >= y.hi()
                            || points && (y.lo() - flo) % c == 0 && (y.lo() - flo) / c >= 0);
                if (reaches) {
                    strict = true;
                    up = c > 0;
                } else if ((c & 1) != 0 && y.lo() >= min && y.hi() <= max) {
                    // an odd step meets every value of the type, the bound among them (a
                    // zero-extended counter never meets a bound above its type): within 2^w steps
                    return new Fact(1L << w, null, unsigned, false);
                } else {
                    return null;
                }
            }
            default -> {
                return null;   // EQ: not taken
            }
        }
        if (up != c > 0) {
            return null;   // stepping away from the end: it wraps or never ends
        }
        long a = Math.abs(c);
        if (up) {
            long last = strict ? y.hi() - 1 : y.hi();   // the largest compared value that stays
            if (last + a > max) {
                return null;   // the step could wrap around
            }
            long flo = s.lo() + d * a;
            long fhi = s.hi() + d * a;
            if (fhi > max) {
                // the first compared value may wrap: it is then at least min
                long n = last >= min ? (last - min) / a + 1 : 0;
                return new Fact(n + 1, null, unsigned, false);
            }
            long n = last >= flo ? (last - flo) / a + 1 : 0;   // rounds that go on, at most
            Ranges.Range phi;
            if (s.lo() == s.hi()) {
                phi = new Ranges.Range(s.lo(), s.lo() + n * a);
            } else {
                phi = new Ranges.Range(s.lo(), last >= flo ? Math.max(s.hi(), last + (1 - d) * a) : s.hi());
            }
            return new Fact(n + 1, phi, unsigned, points);
        }
        long last = strict ? y.lo() + 1 : y.lo();   // the smallest compared value that stays
        if (last - a < min) {
            return null;
        }
        long flo = s.lo() - d * a;
        long fhi = s.hi() - d * a;
        if (flo < min) {
            long n = last <= max ? (max - last) / a + 1 : 0;
            return new Fact(n + 1, null, unsigned, false);
        }
        long n = last <= fhi ? (fhi - last) / a + 1 : 0;
        Ranges.Range phi;
        if (s.lo() == s.hi()) {
            phi = new Ranges.Range(s.hi() - n * a, s.hi());
        } else {
            phi = new Ranges.Range(last <= fhi ? Math.min(s.lo(), last - (1 - d) * a) : s.lo(), s.hi());
        }
        return new Fact(n + 1, phi, unsigned, points);
    }

    private CountedLoops() {}

    /** The loops of f, innermost first, with their counters and exits. */
    public static List<Loop> find(MFunction f) {
        return find(f, Cfg.idoms(f));
    }

    /** As {@link #find(MFunction)}, with the dominators the caller has. */
    static List<Loop> find(MFunction f, Map<Block, Block> idom) {
        List<Loop> out = new ArrayList<>();
        for (Loops.Loop loop : Loops.find(f)) {
            out.add(analyse(loop, idom));
        }
        return out;
    }

    /** The loop whose head is h, or null. */
    public static Loop at(List<Loop> loops, Block h) {
        for (Loop l : loops) {
            if (l.head() == h) {
                return l;
            }
        }
        return null;
    }

    private static Loop analyse(Loops.Loop loop, Map<Block, Block> idom) {
        Set<Block> in = loop.blocks();
        List<Block> entries = new ArrayList<>();
        for (Block p : loop.head().preds()) {
            if (!in.contains(p) && !entries.contains(p)) {
                entries.add(p);
            }
        }
        List<Edge> exitEdges = new ArrayList<>();
        for (Block b : in) {
            for (Block s : b.terminator().successors()) {
                if (!in.contains(s)) {
                    exitEdges.add(new Edge(b, s));
                }
            }
        }
        List<Counter> counters = new ArrayList<>();
        for (Instr i : loop.head().instrs()) {
            if (!(i instanceof Instr.Phi p)) {
                break;
            }
            Counter k = counter(p, loop);
            if (k != null) {
                counters.add(k);
            }
        }
        List<Exit> exits = new ArrayList<>();
        if (!counters.isEmpty()) {
            for (Block b : in) {
                if (b.terminator() instanceof Instr.Branch br && br.ifTrue != br.ifFalse
                        && in.contains(br.ifTrue) != in.contains(br.ifFalse) && everyRound(loop, b)
                        && br.op(0) instanceof Instr.Cmp cmp && cmp.block() != null && in.contains(cmp.block())) {
                    Exit e = exit(b, br, cmp, counters, in);
                    if (e != null) {
                        exits.add(e);
                    }
                }
            }
        }
        return new Loop(loop, entries, exitEdges, counters, exits);
    }

    /** p as a counter: every way back brings {@code add p, c} with the same c, and there is a way in. */
    private static Counter counter(Instr.Phi p, Loops.Loop loop) {
        MType t = p.type();
        if (t != MType.I8 && t != MType.I16 && t != MType.I32) {
            return null;
        }
        Long c = null;
        List<Instr.Bin> steps = new ArrayList<>();
        boolean entry = false;
        for (int k = 0; k < p.from.size(); k++) {
            if (!loop.blocks().contains(p.from.get(k))) {
                entry = true;
                continue;
            }
            if (!(p.op(k) instanceof Instr.Bin b) || b.op != Instr.BinOp.ADD || b.op(0) != p
                    || !(b.op(1) instanceof Value.Const n)) {
                return null;
            }
            long step = t.signed(n.bits());
            if (c != null && c != step) {
                return null;
            }
            c = step;
            if (!steps.contains(b)) {
                steps.add(b);
            }
        }
        return entry && c != null && c != 0 ? new Counter(p, c, List.copyOf(steps)) : null;
    }

    /** The exit of block b's branch, if it compares a counter with a bound from outside the loop. */
    private static Exit exit(Block b, Instr.Branch br, Instr.Cmp cmp, List<Counter> counters, Set<Block> in) {
        boolean staysIfTrue = in.contains(br.ifTrue);
        Block out = staysIfTrue ? br.ifFalse : br.ifTrue;
        Instr.Pred stay = staysIfTrue ? cmp.pred : Ranges.negate(cmp.pred);
        for (int side = 0; side < 2; side++) {
            Value q = cmp.op(side);
            Value bound = cmp.op(1 - side);
            if (bound instanceof Instr d && d.block() != null && in.contains(d.block())) {
                continue;   // the bound changes in the loop
            }
            boolean widened = false;
            if (q instanceof Instr.Cast z && z.kind == Instr.CastKind.ZEXT && z.type().bits() > z.op(0).type().bits()) {
                q = z.op(0);
                widened = true;
            }
            for (Counter k : counters) {
                boolean stepped = k.isStepped(q);
                if (q == k.phi || stepped) {
                    return new Exit(b, cmp, k, stepped, widened, side == 0 ? stay : Ranges.swap(stay), bound, out);
                }
            }
        }
        return null;
    }

    /** Whether every way from the head back to it passes b. */
    private static boolean everyRound(Loops.Loop loop, Block b) {
        if (b == loop.head()) {
            return true;
        }
        Set<Block> seen = new HashSet<>();
        Deque<Block> work = new ArrayDeque<>();
        work.add(loop.head());
        seen.add(loop.head());
        while (!work.isEmpty()) {
            Block x = work.pop();
            if (loop.latches().contains(x)) {
                return false;   // a way back without passing b
            }
            for (Block s : x.terminator().successors()) {
                if (s != b && s != loop.head() && loop.blocks().contains(s) && seen.add(s)) {
                    work.add(s);
                }
            }
        }
        return true;
    }
}
