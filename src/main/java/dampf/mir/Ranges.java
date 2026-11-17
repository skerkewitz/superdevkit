package dampf.mir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Value range analysis: an interval for every integer value of a function, as signed numbers of
 * the value's type (an i8 holding 200 is -56 here; {@link #unsigned} gives the other view).
 *
 * <p>Sources: constants, the operations (interval arithmetic; a result that may leave the type's
 * range is the whole range, because it wraps), and the conditions of branches: in a block whose
 * only predecessor branches on {@code cmp x, c} with a constant c, x is known to satisfy the
 * comparison (or its negation), in that block and every block it dominates.
 *
 * <p>φs are joined over their incoming edges, with the conditions of the edge's source. Loops
 * are iterated until nothing changes; a value whose interval keeps growing is widened to its
 * type's whole range after {@link #WIDEN_AFTER} changes, so the iteration ends.
 *
 * <p>The ranges are claims about every execution, so a wrong one gives wrong code. The tests
 * check every value the interpreter computes against its range (see MirRunner).
 */
public final class Ranges {
    /** Changes of one value before it is widened to its type's range. */
    static final int WIDEN_AFTER = 24;

    /** An interval of signed values, both ends included. */
    public record Range(long lo, long hi) {
        boolean within(long a, long b) {
            return lo >= a && hi <= b;
        }

        Range join(Range o) {
            return o == null ? this : new Range(Math.min(lo, o.lo), Math.max(hi, o.hi));
        }

        Range meet(Range o) {
            long l = Math.max(lo, o.lo);
            long h = Math.min(hi, o.hi);
            return l > h ? null : new Range(l, h);
        }

        boolean contains(long v) {
            return v >= lo && v <= hi;
        }

        @Override
        public String toString() {
            return lo == hi ? Long.toString(lo) : lo + ".." + hi;
        }
    }

    private final Map<Value, Range> range = new HashMap<>();
    private final Map<Block, Map<Value, Range>> refine = new HashMap<>();

    public Ranges(MFunction f) {
        Map<Block, Block> idom = Cfg.idoms(f);
        List<Block> rpo = Cfg.reversePostorder(f);
        for (Block b : rpo) {
            Map<Value, Range> m = b == f.entry() ? new HashMap<>() : new HashMap<>(refine.get(idom.get(b)));
            if (b.preds().size() == 1 && b.preds().getFirst() != b) {
                conditions(b.preds().getFirst(), b, m);
            }
            refine.put(b, m);
        }
        Map<Instr, Integer> changes = new HashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Block b : rpo) {
                for (Instr i : b.instrs()) {
                    if (!isInt(i.type())) {
                        continue;
                    }
                    Range r = eval(i, b);
                    if (r == null) {
                        continue;
                    }
                    Range old = range.get(i);
                    Range next = r.join(old);
                    if (!next.equals(old)) {
                        if (changes.merge(i, 1, Integer::sum) > WIDEN_AFTER) {
                            next = full(i.type());
                        }
                        if (!next.equals(old)) {
                            range.put(i, next);
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    static boolean isInt(MType t) {
        return t == MType.I1 || t == MType.I8 || t == MType.I16 || t == MType.I32;
    }

    public static Range full(MType t) {
        if (t == MType.I1) {
            return new Range(0, 1);
        }
        int w = t.bits();
        return new Range(-(1L << (w - 1)), (1L << (w - 1)) - 1);
    }

    /** The range of a value where it is defined (null: an instruction never reached). */
    public Range of(Value v) {
        return switch (v) {
            case Value.Const k -> isInt(k.type()) ? point(k) : null;
            case Instr i -> range.get(i);
            case Value.Param p when p.known != null -> p.known;
            default -> isInt(v.type()) ? full(v.type()) : null;
        };
    }

    private static Range point(Value.Const k) {
        long s = k.type() == MType.I1 ? k.bits() : k.type().signed(k.bits());
        return new Range(s, s);
    }

    /** The range of v as seen in block b: what the conditions on the way to b add. */
    public Range at(Value v, Block b) {
        Range r = of(v);
        if (r == null) {
            return null;
        }
        Map<Value, Range> m = refine.get(b);
        Range c = m == null ? null : m.get(v);
        if (c != null) {
            Range both = r.meet(c);
            return both == null ? r : both;   // empty: b cannot be reached; any answer will do
        }
        return r;
    }

    /**
     * Checks every value an interpreter computes against the ranges of the module's functions
     * (for tests): a value outside its range means the analysis is wrong.
     */
    public static Interpreter.Observer checker(MModule m) {
        Map<Instr, Range> all = new HashMap<>();
        for (MFunction f : m.functions()) {
            Ranges r = new Ranges(f);
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    Range g = r.of(i);
                    if (g != null) {
                        all.put(i, g);
                    }
                }
            }
        }
        return (i, bits) -> {
            Range g = all.get(i);
            if (g != null) {
                long v = i.type() == MType.I1 ? bits : i.type().signed(bits);
                if (!g.contains(v)) {
                    throw new IllegalStateException("range analysis is wrong: " + Printer.instr(i) + " in "
                            + i.block() + " gave " + v + ", outside " + g + (i.pos() != null ? " (" + i.pos() + ")" : ""));
                }
            }
        };
    }

    /** The values as unsigned numbers of type t, if they form one interval there (else null). */
    public static Range unsigned(Range r, MType t) {
        if (r == null) {
            return null;
        }
        if (t == MType.I1 || r.lo >= 0) {
            return r;
        }
        long m = 1L << t.bits();
        if (r.hi < 0) {
            return new Range(r.lo + m, r.hi + m);
        }
        return new Range(0, m - 1);
    }

    // ------------------------------------------------------------------ conditions

    /** What b learns from the branch at the end of its only predecessor p. */
    private void conditions(Block p, Block b, Map<Value, Range> m) {
        if (!(p.terminator() instanceof Instr.Branch br) || br.ifTrue == br.ifFalse
                || !(br.op(0) instanceof Instr.Cmp c)) {
            return;
        }
        boolean holds = b == br.ifTrue;
        Instr.Pred pred = holds ? c.pred : negate(c.pred);
        Value x = c.op(0);
        Value y = c.op(1);
        if (y instanceof Value.Const k) {
            constrain(m, x, pred, k);
        } else if (x instanceof Value.Const k) {
            constrain(m, y, swap(pred), k);
        }
    }

    private static void constrain(Map<Value, Range> m, Value x, Instr.Pred pred, Value.Const k) {
        MType t = x.type();
        if (!isInt(t) || t == MType.I1) {
            return;
        }
        Range all = full(t);
        long s = t.signed(k.bits());
        long u = k.bits();
        long max = (1L << t.bits()) - 1;
        Range r = switch (pred) {
            case EQ -> new Range(s, s);
            case SLT -> new Range(all.lo, s - 1);
            case SLE -> new Range(all.lo, s);
            case SGT -> new Range(s + 1, all.hi);
            case SGE -> new Range(s, all.hi);
            // unsigned: only where the unsigned interval is also one signed interval
            case ULT -> u - 1 <= all.hi ? new Range(0, u - 1) : null;
            case ULE -> u <= all.hi ? new Range(0, u) : null;
            case UGT -> u + 1 > all.hi && u < max ? new Range(t.signed(u + 1), -1) : null;
            case UGE -> u > all.hi ? new Range(t.signed(u), -1) : null;
            default -> null;   // NE gives no interval
        };
        if (r == null || r.lo > r.hi) {
            return;
        }
        Range old = m.get(x);
        Range both = old == null ? r : old.meet(r);
        if (both != null) {
            m.put(x, both);
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

    private Range eval(Instr i, Block b) {
        MType t = i.type();
        if (i instanceof Instr.Phi p) {
            Range r = null;
            for (int k = 0; k < p.from.size(); k++) {
                Range o = at(p.op(k), p.from.get(k));
                if (o != null) {
                    r = o.join(r);
                }
            }
            if (p.known != null) {
                Range both = r == null ? null : r.meet(p.known);
                r = both != null ? both : p.known;
            }
            return r;
        }
        return switch (i) {
            case Instr.Bin x -> {
                Range a = at(x.op(0), b);
                Range c = at(x.op(1), b);
                yield a == null || c == null ? null : bin(x.op, t, a, c);
            }
            case Instr.Cmp c -> new Range(0, 1);
            case Instr.Cast c -> {
                Range a = at(c.op(0), b);
                yield a == null && isInt(c.op(0).type()) ? null : cast(c, a);
            }
            default -> full(t);
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

    private static Range cast(Instr.Cast c, Range a) {
        MType from = c.op(0).type();
        MType to = c.type();
        return switch (c.kind) {
            case ZEXT -> from == MType.I1 ? a : unsigned(a, from);
            case SEXT -> {
                if (from == MType.I1) {
                    yield new Range(-a.hi, -a.lo);   // 1 becomes -1
                }
                yield a;
            }
            case TRUNC -> {
                if (to == MType.I1) {
                    yield a.lo >= 0 && a.hi <= 1 ? a : new Range(0, 1);
                }
                Range all = full(to);
                yield a.within(all.lo, all.hi) ? a : all;
            }
            default -> full(to);
        };
    }
}
