package dampf.mir;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Value range analysis: two intervals for every integer value of a function, one of its signed
 * and one of its unsigned reading (an i8 holding 0..199 is the unsigned interval 0..199; its
 * signed reading, 0..127 and -128..-57, is no interval and so the whole type). Both hold for
 * every execution, and each tightens the other where it lies on one side of the sign change.
 * {@link #of} and {@link #at} give the signed interval, {@link #ofUnsigned} and
 * {@link #atUnsigned} the unsigned one.
 *
 * <p>Sources: constants, the operations (interval arithmetic in each reading; a result that may
 * leave the reading's range is the whole range, because it wraps), and the conditions of
 * branches: in a block whose only predecessor branches on {@code cmp x, c} with a constant c, x
 * is known to satisfy the comparison (or its negation), in that block and every block it
 * dominates; a signed comparison bounds the signed reading, an unsigned one the unsigned.
 * {@code x != c} is no interval, but cuts c off an end of x's. A cast computed before such a
 * condition on its operand is, where the condition holds, the cast of what is known there.
 *
 * <p>φs are joined over their incoming edges, with the conditions of the edge's source. Loops
 * are iterated until nothing changes; a value whose intervals keep growing is widened after
 * {@link #WIDEN_AFTER} changes, so the iteration ends: the end that moved goes to the reading's
 * limit, the other stays (a counter from 0 upwards keeps 0; narrowing then takes back what
 * widening added at the top). A loop counter gets the interval {@link CountedLoops} derives from
 * the loop's tests and the ranges of its start and bound, before and after widening: the counter
 * of {@code i != 100} is 0..100, which no iteration finds.
 *
 * <p>The ranges are claims about every execution, so a wrong one gives wrong code. The tests
 * check every value the interpreter computes against both its intervals (see MirRunner).
 */
public final class Ranges {
    /** Changes of one value before it is widened. */
    static final int WIDEN_AFTER = 24;

    /** An interval of values (signed or unsigned, as the context says), both ends included. */
    public record Range(long lo, long hi) {
        boolean within(long a, long b) {
            return lo >= a && hi <= b;
        }

        public Range join(Range o) {
            return o == null ? this : new Range(Math.min(lo, o.lo), Math.max(hi, o.hi));
        }

        public Range meet(Range o) {
            long l = Math.max(lo, o.lo);
            long h = Math.min(hi, o.hi);
            return l > h ? null : new Range(l, h);
        }

        public boolean contains(long v) {
            return v >= lo && v <= hi;
        }

        @Override
        public String toString() {
            return lo == hi ? Long.toString(lo) : lo + ".." + hi;
        }
    }

    /** The signed and the unsigned interval of one value (for an i1 both are 0..1). */
    private record Both(Range s, Range u) {
        Both join(Both o) {
            return o == null ? this : new Both(s.join(o.s), u.join(o.u));
        }

        /** What both say; null if that is nothing (no execution gets there). */
        Both meet(Both o) {
            Range a = s.meet(o.s);
            Range b = u.meet(o.u);
            return a == null || b == null ? null : new Both(a, b);
        }
    }

    private final Map<Value, Both> range = new HashMap<>();
    private final Map<Block, Map<Value, Both>> refine = new HashMap<>();
    /** The values a block knows its values are not ({@code x != c}), as c's bits. */
    private final Map<Block, Map<Value, Long>> notEqual = new HashMap<>();
    /** The loop counters, with their loops. */
    private final Map<Instr.Phi, CountedLoops.Loop> counters = new IdentityHashMap<>();
    /** What each counter's loop said in the last evaluation of the counter. */
    private final Map<Instr.Phi, Both> counted = new IdentityHashMap<>();

    /** Ranges of a first pass, for conditions between two values (null in the first pass). */
    private Map<Value, Both> prior;
    private boolean variableConditions; // NOPMD SingularField: set by conditions(), read by the constructor

    public Ranges(MFunction f) {
        Map<Block, Block> idom = Cfg.idoms(f);
        List<Block> rpo = Cfg.reversePostorder(f);
        for (CountedLoops.Loop l : CountedLoops.find(f, idom)) {
            for (CountedLoops.Counter k : l.counters()) {
                counters.put(k.phi(), l);
            }
        }
        solve(f, idom, rpo);
        if (variableConditions) {
            // a second pass: a condition between two values (r < n) bounds each by the other's
            // range from the first pass, which holds for every execution
            prior = new HashMap<>(range);
            range.clear();
            refine.clear();
            notEqual.clear();
            counted.clear();
            solve(f, idom, rpo);
        }
    }

    /** Rounds of the fixpoints so far (for tests: widening keeps them few). */
    private int rounds;

    int rounds() {
        return rounds;
    }

    private void solve(MFunction f, Map<Block, Block> idom, List<Block> rpo) {
        for (Block b : rpo) {
            Map<Value, Both> m = b == f.entry() ? new HashMap<>() : new HashMap<>(refine.get(idom.get(b)));
            Map<Value, Long> ne = b == f.entry() ? new HashMap<>() : new HashMap<>(notEqual.get(idom.get(b)));
            // the edge from the one predecessor that is not a back edge: its condition holds in b
            // (a value it tests is defined before b's loop, so the loop does not change it)
            Block entry = null;
            int entries = 0;
            for (Block p : b.preds()) {
                if (p == b || !Loops.dominates(b, p, idom)) {
                    entry = p;
                    entries++;
                }
            }
            if (entries == 1 && entry != b) {
                conditions(entry, b, m, ne);
            }
            refine.put(b, m);
            notEqual.put(b, ne);
        }
        Map<Instr, Integer> changes = new HashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            rounds++;
            for (Block b : rpo) {
                for (Instr i : b.instrs()) {
                    if (!isInt(i.type())) {
                        continue;
                    }
                    Both r = eval(i, b);
                    if (r == null) {
                        continue;
                    }
                    Both old = range.get(i);
                    Both next = r.join(old);
                    if (!next.equals(old)) {
                        int n = changes.merge(i, 1, Integer::sum);
                        if (n > WIDEN_AFTER && old != null) {
                            // widen the ends that moved, not the others: a counter from 0 upwards
                            // keeps 0. An end at its limit cannot move again, so each of the
                            // four is widened at most once.
                            next = new Both(widen(next.s, old.s, full(i.type())), widen(next.u, old.u, ufull(i.type())));
                            // a counter keeps what its loop says, unless that moves on as well (a
                            // bound whose own range is still growing would take the widening back
                            // every round, one step a round); the narrowing below gives it back
                            Both c = i instanceof Instr.Phi p && n <= 2 * WIDEN_AFTER ? counted.get(p) : null;
                            Both kept = c == null ? null : next.meet(c);
                            if (kept != null) {
                                next = reduce(kept.s, kept.u, i.type());
                            }
                        }
                        if (!next.equals(old)) {
                            range.put(i, next);
                            changed = true;
                        }
                    }
                }
            }
        }
        // Narrowing: widening made a loop counter's range whole (for (i = 0; i < 768; i++)
        // passes WIDEN_AFTER long before 768). The ranges now hold for every execution, and
        // evaluating an instruction from ranges that hold gives a range that holds, so one more
        // evaluation can only take back what widening added: the counter's φ gets 0 and what the
        // step gives under the loop's condition, 1..768.
        for (int round = 0; round < NARROW_ROUNDS; round++) {
            boolean narrower = false;
            for (Block b : rpo) {
                for (Instr i : b.instrs()) {
                    Both old = range.get(i);
                    if (old == null) {
                        continue;
                    }
                    Both r = eval(i, b);
                    Both both = r == null ? null : r.meet(old);
                    if (both != null) {
                        both = reduce(both.s, both.u, i.type());
                    }
                    if (both != null && !both.equals(old)) {
                        range.put(i, both);
                        narrower = true;
                    }
                }
            }
            if (!narrower) {
                break;
            }
        }
    }

    private static Range widen(Range next, Range old, Range whole) {
        return new Range(next.lo < old.lo ? whole.lo : next.lo, next.hi > old.hi ? whole.hi : next.hi);
    }

    /** Rounds of narrowing after the widened fixpoint. */
    static final int NARROW_ROUNDS = 8;

    static boolean isInt(MType t) {
        return t == MType.I1 || t == MType.I8 || t == MType.I16 || t == MType.I32;
    }

    /** The signed reading's whole range (0..1 for an i1). */
    public static Range full(MType t) {
        if (t == MType.I1) {
            return new Range(0, 1);
        }
        int w = t.bits();
        return new Range(-(1L << (w - 1)), (1L << (w - 1)) - 1);
    }

    /** The unsigned reading's whole range. */
    public static Range ufull(MType t) {
        return t == MType.I1 ? new Range(0, 1) : new Range(0, (1L << t.bits()) - 1);
    }

    private static Both whole(MType t) {
        return new Both(full(t), ufull(t));
    }

    /** The values of an unsigned interval as signed numbers of t, if one interval (else all of them). */
    static Range signedOf(Range u, MType t) {
        if (t == MType.I1) {
            return u;
        }
        long m = 1L << t.bits();
        long smax = (m >> 1) - 1;
        if (u.hi <= smax) {
            return u;
        }
        return u.lo > smax ? new Range(u.lo - m, u.hi - m) : full(t);
    }

    /** The values of a signed interval as unsigned numbers of t, if one interval (else all of them). */
    static Range unsignedOf(Range s, MType t) {
        if (t == MType.I1 || s.lo >= 0) {
            return s;
        }
        long m = 1L << t.bits();
        return s.hi < 0 ? new Range(s.lo + m, s.hi + m) : ufull(t);
    }

    /** Each reading tightened by the other where it lies on one side of the sign change. */
    private static Both reduce(Range s, Range u, MType t) {
        for (int round = 0; round < 2; round++) {
            Range s2 = s.meet(signedOf(u, t));
            Range u2 = u.meet(unsignedOf(s, t));
            if (s2 == null || u2 == null) {
                break;   // no value is both: no execution gets here, any answer will do
            }
            s = s2;
            u = u2;
        }
        return new Both(s, u);
    }

    private Both both(Value v) {
        return switch (v) {
            case Value.Const k -> isInt(k.type()) ? point(k) : null;
            case Instr i -> range.get(i);
            case Value.Param p when p.known != null && isInt(p.type()) -> reduce(p.known, ufull(p.type()), p.type());
            default -> isInt(v.type()) ? whole(v.type()) : null;
        };
    }

    /** The signed range of a value where it is defined (null: an instruction never reached). */
    public Range of(Value v) {
        Both b = both(v);
        return b == null ? null : b.s;
    }

    /** The unsigned range of a value where it is defined (null: an instruction never reached). */
    public Range ofUnsigned(Value v) {
        Both b = both(v);
        return b == null ? null : b.u;
    }

    private static Both point(Value.Const k) {
        MType t = k.type();
        long s = t == MType.I1 ? k.bits() : t.signed(k.bits());
        long u = t == MType.I1 ? k.bits() : k.bits() & ((1L << t.bits()) - 1);
        return new Both(new Range(s, s), new Range(u, u));
    }

    private Both bothAt(Value v, Block b) {
        Both r = both(v);
        if (r == null) {
            return null;
        }
        Map<Value, Both> m = refine.get(b);
        Both c = m == null ? null : m.get(v);
        if (c != null) {
            Both x = r.meet(c);
            r = x == null ? r : reduce(x.s, x.u, v.type());   // empty: b cannot be reached; any answer will do
        }
        Map<Value, Long> ne = notEqual.get(b);
        Long k = ne == null ? null : ne.get(v);
        if (k != null) {
            r = cut(r, k, v.type());
        }
        if (v instanceof Instr.Cast cv && isInt(cv.op(0).type()) && (cv.kind == Instr.CastKind.ZEXT || cv.kind == Instr.CastKind.SEXT
                || cv.kind == Instr.CastKind.TRUNC)) {
            // a cast defined before a condition on its operand: in b it is the cast of what b
            // knows of the operand (zext(n) made before the test n < 13, read after it)
            Both o = bothAt(cv.op(0), b);
            Both x = o == null ? null : r.meet(cast(cv, o));
            if (x != null) {
                r = reduce(x.s, x.u, v.type());
            }
        }
        return r;
    }

    /** r without the value whose bits are k, where k is an end of an interval. */
    private static Both cut(Both r, long k, MType t) {
        long sv = t.signed(k);
        long uv = k & ((1L << t.bits()) - 1);
        Range s = r.s.lo == sv && r.s.hi > sv ? new Range(sv + 1, r.s.hi) : r.s.hi == sv && r.s.lo < sv ? new Range(r.s.lo, sv - 1) : r.s;
        Range u = r.u.lo == uv && r.u.hi > uv ? new Range(uv + 1, r.u.hi) : r.u.hi == uv && r.u.lo < uv ? new Range(r.u.lo, uv - 1) : r.u;
        return s == r.s && u == r.u ? r : reduce(s, u, t);
    }

    /** The signed range of v as seen in block b: what the conditions on the way to b add. */
    public Range at(Value v, Block b) {
        Both r = bothAt(v, b);
        return r == null ? null : r.s;
    }

    /** The unsigned range of v as seen in block b. */
    public Range atUnsigned(Value v, Block b) {
        Both r = bothAt(v, b);
        return r == null ? null : r.u;
    }

    /**
     * Checks every value an interpreter computes against the ranges of the module's functions
     * (for tests): a value outside either of its intervals means the analysis is wrong.
     */
    public static Interpreter.Observer checker(MModule m) {
        Map<Instr, Both> all = new HashMap<>();
        for (MFunction f : m.functions()) {
            Ranges r = new Ranges(f);
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    Both g = r.range.get(i);
                    if (g != null) {
                        all.put(i, g);
                    }
                }
            }
        }
        return new Interpreter.Observer() {
            @Override
            public void value(Instr i, long bits) {
                Both g = all.get(i);
                if (g != null) {
                    MType t = i.type();
                    long v = t == MType.I1 ? bits : t.signed(bits);
                    long u = t == MType.I1 ? bits : bits & ((1L << t.bits()) - 1);
                    if (!g.s.contains(v) || !g.u.contains(u)) {
                        throw new IllegalStateException("range analysis is wrong: " + Printer.instr(i) + " in "
                                + i.block() + " gave " + v + " (unsigned " + u + "), outside " + g.s + " (unsigned " + g.u + ")"
                                + (i.pos() != null ? " (" + i.pos() + ")" : ""));
                    }
                }
            }

            @Override
            public void param(Value.Param p, long bits) {
                if (p.known != null && isInt(p.type())) {
                    long v = p.type() == MType.I1 ? bits : p.type().signed(bits);
                    if (!p.known.contains(v)) {
                        throw new IllegalStateException("range analysis is wrong: parameter " + p.name() + " is " + v
                                + ", outside " + p.known);
                    }
                }
            }
        };
    }

    // ------------------------------------------------------------------ conditions

    /** What b learns from the branch at the end of its only predecessor p. */
    private void conditions(Block p, Block b, Map<Value, Both> m, Map<Value, Long> ne) {
        if (!(p.terminator() instanceof Instr.Branch br) || br.ifTrue == br.ifFalse
                || !(br.op(0) instanceof Instr.Cmp c)) {
            return;
        }
        boolean holds = b == br.ifTrue;
        Instr.Pred pred = holds ? c.pred : negate(c.pred);
        Value x = c.op(0);
        Value y = c.op(1);
        if (pred == Instr.Pred.NE && isInt(x.type()) && x.type() != MType.I1) {
            if (y instanceof Value.Const k) {
                ne.put(x, k.bits());
            } else if (x instanceof Value.Const k) {
                ne.put(y, k.bits());
            }
        }
        if (y instanceof Value.Const k) {
            constrain(m, x, pred, k);
        } else if (x instanceof Value.Const k) {
            constrain(m, y, swap(pred), k);
        } else if (isInt(x.type()) && x.type() != MType.I1) {
            variableConditions = true;
            if (prior != null) {
                bound(m, x, pred, priorOf(y));
                bound(m, y, swap(pred), priorOf(x));
            }
        }
    }

    private Both priorOf(Value v) {
        return switch (v) {
            case Value.Const k -> point(k);
            case Instr i -> prior.get(i);
            case Value.Param p when p.known != null -> reduce(p.known, ufull(p.type()), p.type());
            default -> whole(v.type());
        };
    }

    /** x pred y, where y lies in r: x is bounded by r's end on the far side, in pred's reading. */
    private static void bound(Map<Value, Both> m, Value x, Instr.Pred pred, Both r) {
        if (r == null) {
            return;
        }
        MType t = x.type();
        Long end = switch (pred) {
            case SLT, SLE -> r.s.hi;
            case SGT, SGE -> r.s.lo;
            case ULT, ULE -> r.u.hi;
            case UGT, UGE -> r.u.lo;
            default -> null;
        };
        if (end != null) {
            constrain(m, x, pred, new Value.Const(t, t.wrap(end)));
        }
    }

    /** x pred k: a signed comparison bounds x's signed reading, an unsigned one its unsigned reading. */
    private static void constrain(Map<Value, Both> m, Value x, Instr.Pred pred, Value.Const k) {
        MType t = x.type();
        if (!isInt(t) || t == MType.I1) {
            return;
        }
        Range all = full(t);
        Range uall = ufull(t);
        long s = t.signed(k.bits());
        long u = k.bits() & uall.hi;
        Range sr = switch (pred) {
            case EQ -> new Range(s, s);
            case SLT -> new Range(all.lo, s - 1);
            case SLE -> new Range(all.lo, s);
            case SGT -> new Range(s + 1, all.hi);
            case SGE -> new Range(s, all.hi);
            default -> all;
        };
        Range ur = switch (pred) {
            case EQ -> new Range(u, u);
            case ULT -> new Range(0, u - 1);
            case ULE -> new Range(0, u);
            case UGT -> new Range(u + 1, uall.hi);
            case UGE -> new Range(u, uall.hi);
            default -> uall;   // NE gives no interval
        };
        if (sr.lo > sr.hi || ur.lo > ur.hi || pred == Instr.Pred.NE) {
            return;   // never true (or no interval): nothing to learn
        }
        Both old = m.get(x);
        Both c = new Both(sr, ur);
        Both both = old == null ? c : old.meet(c);
        if (both != null) {
            m.put(x, reduce(both.s, both.u, t));
        }
    }

    static Instr.Pred negate(Instr.Pred p) {
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

    /** x pred y as y pred' x. */
    static Instr.Pred swap(Instr.Pred p) {
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

    // ------------------------------------------------------------------ transfer functions

    private Both eval(Instr i, Block b) {
        MType t = i.type();
        if (i instanceof Instr.Phi p) {
            Both r = null;
            for (int k = 0; k < p.from.size(); k++) {
                Block pred = p.from.get(k);
                Both o = bothAt(p.op(k), pred);
                if (o != null) {
                    // what the edge pred -> b adds: the condition of pred's branch that leads here
                    // (abs: x on the edge where x < 0 is false is not negative)
                    Map<Value, Both> edge = new HashMap<>();
                    Map<Value, Long> edgeNe = new HashMap<>();
                    conditions(pred, b, edge, edgeNe);
                    Both e = edge.get(p.op(k));
                    Both both = e == null ? null : o.meet(e);
                    Both x = both != null ? both : o;
                    Long ne = edgeNe.get(p.op(k));
                    r = (ne == null ? x : cut(x, ne, t)).join(r);
                }
            }
            if (p.known != null) {
                Both known = reduce(p.known, ufull(t), t);
                Both both = r == null ? null : r.meet(known);
                r = both != null ? both : known;
            }
            CountedLoops.Loop loop = counters.get(p);
            if (loop != null && r != null) {
                // what the loop's tests say about its counter, from the ranges so far
                CountedLoops.Interval iv = loop.interval(loop.counter(p), (v, at, unsigned) -> {
                    Both x = bothAt(v, at);
                    return x == null ? null : unsigned ? x.u : x.s;
                });
                Both c = new Both(iv.signed() != null ? iv.signed() : full(t), iv.unsigned() != null ? iv.unsigned() : ufull(t));
                counted.put(p, c);
                Both both = r.meet(c);
                r = both != null ? both : r;
            }
            return r == null ? null : reduce(r.s, r.u, t);
        }
        return switch (i) {
            case Instr.Bin x -> {
                Both a = bothAt(x.op(0), b);
                Both c = bothAt(x.op(1), b);
                yield a == null || c == null ? null : reduce(bin(x.op, t, a.s, c.s), ubin(x.op, t, a.u, c.u), t);
            }
            case Instr.Cmp c -> new Both(new Range(0, 1), new Range(0, 1));
            case Instr.Cast c -> {
                Both a = bothAt(c.op(0), b);
                yield a == null && isInt(c.op(0).type()) ? null : cast(c, a);
            }
            default -> whole(t);
        };
    }

    /** [lo, hi] if it fits the type, else the type's whole range (the result wraps). */
    private static Range fit(long lo, long hi, MType t) {
        Range all = full(t);
        return lo >= all.lo && hi <= all.hi ? new Range(lo, hi) : all;
    }

    static Range bin(Instr.BinOp op, MType t, Range a, Range b) {
        int w = t.bits();
        Range all = full(t);
        return switch (op) {
            case ADD -> fit(a.lo + b.lo, a.hi + b.hi, t);
            case SUB -> fit(a.lo - b.hi, a.hi - b.lo, t);
            case MUL -> {
                long p1 = a.lo * b.lo;
                long p2 = a.lo * b.hi;
                long p3 = a.hi * b.lo;
                long p4 = a.hi * b.hi;
                yield fit(Math.min(Math.min(p1, p2), Math.min(p3, p4)), Math.max(Math.max(p1, p2), Math.max(p3, p4)), t);
            }
            case AND -> {
                if (a.lo >= 0 && b.lo >= 0) {
                    yield new Range(0, Math.min(a.hi, b.hi));
                }
                if (a.lo >= 0) {
                    yield new Range(0, a.hi);
                }
                yield b.lo >= 0 ? new Range(0, b.hi) : all;
            }
            case OR, XOR -> {
                if (a.lo >= 0 && b.lo >= 0) {
                    long m = Math.max(a.hi, b.hi);
                    yield new Range(0, m == 0 ? 0 : (Long.highestOneBit(m) << 1) - 1);
                }
                yield all;
            }
            case SHL -> {
                if (b.lo == b.hi && b.lo >= 0 && b.lo < w) {
                    yield fit(a.lo << b.lo, a.hi << b.lo, t);
                }
                yield all;
            }
            case ASHR -> {
                if (b.lo == b.hi && b.lo >= 0) {
                    long k = Math.min(b.lo, w - 1);
                    yield new Range(a.lo >> k, a.hi >> k);
                }
                yield new Range(Math.min(a.lo, 0), Math.max(a.hi, 0));
            }
            case LSHR -> {
                if (a.lo >= 0) {
                    if (b.lo == b.hi && b.lo >= 0) {
                        yield b.lo >= w ? new Range(0, 0) : new Range(a.lo >> b.lo, a.hi >> b.lo);
                    }
                    yield new Range(0, a.hi);
                }
                if (b.lo == b.hi && b.lo >= 1 && b.lo < w) {
                    yield new Range(0, ((1L << w) - 1) >> b.lo);
                }
                yield all;
            }
            case UDIV -> {
                if (a.lo >= 0 && b.lo >= 1) {
                    yield new Range(a.lo / b.hi, a.hi / b.lo);
                }
                yield a.lo >= 0 ? new Range(0, a.hi) : all;
            }
            case UREM -> {
                if (b.lo >= 1) {
                    long hi = b.hi - 1;
                    yield new Range(0, a.lo >= 0 ? Math.min(hi, a.hi) : hi);
                }
                yield a.lo >= 0 ? new Range(0, a.hi) : all;
            }
            case SDIV -> {
                if (b.lo >= 1) {
                    long lo = Math.min(a.lo / b.lo, a.lo / b.hi);
                    long hi = Math.max(a.hi / b.lo, a.hi / b.hi);
                    yield fit(lo, hi, t);
                }
                yield all;
            }
            case SREM -> {
                if (b.lo >= 1) {
                    long m = b.hi - 1;
                    yield new Range(a.lo < 0 ? Math.max(-m, a.lo) : 0, a.hi > 0 ? Math.min(m, a.hi) : 0);
                }
                yield all;
            }
        };
    }

    /** [lo, hi] as unsigned numbers of t, wrapped once if every value wraps, else the whole range. */
    private static Range ufit(long lo, long hi, MType t) {
        long m = 1L << t.bits();
        if (lo >= 0 && hi < m) {
            return new Range(lo, hi);
        }
        if (lo >= m && hi < 2 * m) {
            return new Range(lo - m, hi - m);
        }
        if (hi < 0 && lo >= -m) {
            return new Range(lo + m, hi + m);
        }
        return ufull(t);
    }

    /** The unsigned reading of a result from the unsigned readings of the operands. */
    static Range ubin(Instr.BinOp op, MType t, Range a, Range b) {
        if (t == MType.I1) {
            return bin(op, t, a, b);   // one bit reads the same both ways
        }
        int w = t.bits();
        Range all = ufull(t);
        return switch (op) {
            case ADD -> ufit(a.lo + b.lo, a.hi + b.hi, t);
            case SUB -> ufit(a.lo - b.hi, a.hi - b.lo, t);
            case MUL -> a.hi != 0 && b.hi > Long.MAX_VALUE / a.hi ? all : ufit(a.lo * b.lo, a.hi * b.hi, t);
            case AND -> new Range(0, Math.min(a.hi, b.hi));
            case OR -> new Range(Math.max(a.lo, b.lo), ones(Math.max(a.hi, b.hi)));
            case XOR -> new Range(0, ones(Math.max(a.hi, b.hi)));
            case SHL -> b.lo == b.hi && b.lo < w && (a.hi >>> (62 - b.lo)) == 0 ? ufit(a.lo << b.lo, a.hi << b.lo, t) : all;
            case LSHR -> b.lo == b.hi ? (b.lo >= w ? new Range(0, 0) : new Range(a.lo >> b.lo, a.hi >> b.lo)) : new Range(0, a.hi);
            case UDIV -> b.lo >= 1 ? new Range(a.lo / b.hi, a.hi / b.lo) : new Range(0, a.hi);
            case UREM -> b.lo >= 1 ? new Range(0, Math.min(b.hi - 1, a.hi)) : new Range(0, a.hi);
            case ASHR, SDIV, SREM -> all;   // reduce gives what the signed reading knows
        };
    }

    /** The least number of the form 2^n - 1 that is at least v. */
    private static long ones(long v) {
        return v == 0 ? 0 : (Long.highestOneBit(v) << 1) - 1;
    }

    private static Both cast(Instr.Cast c, Both a) {
        MType from = c.op(0).type();
        MType to = c.type();
        return switch (c.kind) {
            // the unsigned reading, now a non-negative number of the wider type
            case ZEXT -> from == MType.I1 ? new Both(a.s, a.s) : new Both(a.u, a.u);
            case SEXT -> {
                Range r = from == MType.I1 ? new Range(-a.s.hi, -a.s.lo) : a.s;   // 1 becomes -1
                yield reduce(r, unsignedOf(r, to), to);
            }
            case TRUNC -> {
                if (to == MType.I1) {
                    Range r = a.s.lo >= 0 && a.s.hi <= 1 ? a.s : new Range(0, 1);
                    yield new Both(r, r);
                }
                Range all = full(to);
                Range uall = ufull(to);
                yield reduce(a.s.within(all.lo, all.hi) ? a.s : all, a.u.within(0, uall.hi) ? a.u : uall, to);
            }
            default -> whole(to);
        };
    }
}
