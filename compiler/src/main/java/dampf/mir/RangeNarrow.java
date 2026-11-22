package dampf.mir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Width narrowing from value ranges (the second half of optimization B), with {@link Ranges}:
 *
 * <ul>
 * <li>A comparison whose outcome the ranges decide becomes a constant.</li>
 * <li>An i32 operation whose operands (and, where it matters, result) fit 16 bits is done in 16
 *     bits and extended: sign-extended if everything fits signed 16 bits, zero-extended if it fits
 *     unsigned. Example: {@code (sy - 16) >> 3} after {@code if (sy < 16) return}: sy - 16 is
 *     0..32751, so it fits.</li>
 * <li>An i32 comparison whose operands fit 16 bits compares the 16-bit values.</li>
 * </ul>
 * Only operations whose operands are constants or extensions are narrowed (one step per round;
 * the next round goes on with the extension this one made), so no truncation of a genuinely
 * 32-bit value is added.
 */
public final class RangeNarrow {
    private static final Ranges.Range S16 = new Ranges.Range(-0x8000, 0x7FFF);
    private static final Ranges.Range U16 = new Ranges.Range(0, 0xFFFF);

    private final Ranges ranges;
    private final Map<Instr, Value> replaced = new HashMap<>();

    private RangeNarrow(MFunction f) {
        ranges = new Ranges(f);
    }

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        RangeNarrow n = new RangeNarrow(f);
        for (Block b : f.blocks()) {
            for (Instr i : List.copyOf(b.instrs())) {
                Value r = switch (i) {
                    case Instr.Cmp c -> n.compare(c, b);
                    case Instr.Bin x -> {
                        Value w = x.type() == MType.I32 ? n.bin(x, b) : null;
                        yield w != null ? w : n.unsignedOp(x, b);
                    }
                    default -> null;
                };
                if (r != null) {
                    n.replaced.put(i, r);
                }
            }
        }
        DeadCode.replaceUses(f, n.replaced);
        return !n.replaced.isEmpty();
    }

    private static boolean inside(Ranges.Range r, Ranges.Range of) {
        return r != null && r.within(of.lo(), of.hi());
    }

    /** A constant, or an extension (from at most 16 bits): narrowing it costs nothing. */
    private static boolean cheap(Value v) {
        return v instanceof Value.Const
                || v instanceof Instr.Cast c && (c.kind == Instr.CastKind.ZEXT || c.kind == Instr.CastKind.SEXT)
                && c.op(0).type().bits() <= 16;
    }

    private Value bin(Instr.Bin x, Block b) {
        Value p = x.op(0);
        Value q = x.op(1);
        if (p instanceof Value.Const && q instanceof Value.Const || !cheap(p) || !cheap(q)) {
            return null;
        }
        Ranges.Range ra = ranges.at(p, b);
        Ranges.Range rb = ranges.at(q, b);
        Ranges.Range rr = ranges.of(x);
        if (ra == null || rb == null || rr == null) {
            return null;
        }
        Instr.BinOp op = x.op;
        if (inside(ra, S16) && inside(rb, S16)) {
            boolean ok = switch (op) {
                case ADD, SUB, MUL, SDIV -> inside(rr, S16);
                case AND, OR, XOR, SREM -> true;
                case SHL -> rb.lo() >= 0 && rb.hi() < 16 && inside(rr, S16);
                case ASHR -> rb.lo() >= 0;
                case LSHR, UDIV, UREM -> ra.lo() >= 0 && rb.lo() >= 0;
            };
            if (ok) {
                // neither is negative: the unsigned operation at once, since StrengthReduce, later
                // in this round, would give a signed division its correction for negative values
                Instr.BinOp narrow = ra.lo() < 0 || rb.lo() < 0 ? op : switch (op) {
                    case SDIV -> Instr.BinOp.UDIV;
                    case SREM -> Instr.BinOp.UREM;
                    case ASHR -> Instr.BinOp.LSHR;
                    default -> op;
                };
                return make(x, narrow, Instr.CastKind.SEXT);
            }
        }
        if (inside(ra, U16) && inside(rb, U16)) {
            Instr.BinOp narrow = switch (op) {
                case ADD, SUB, MUL -> inside(rr, U16) ? op : null;
                case AND, OR, XOR, UDIV, UREM, LSHR -> op;
                case SDIV -> Instr.BinOp.UDIV;     // neither is negative
                case SREM -> Instr.BinOp.UREM;
                case ASHR -> Instr.BinOp.LSHR;
                case SHL -> rb.hi() < 16 && inside(rr, U16) ? op : null;
            };
            if (narrow != null) {
                return make(x, narrow, Instr.CastKind.ZEXT);
            }
        }
        return null;
    }

    private Value make(Instr.Bin x, Instr.BinOp op, Instr.CastKind ext) {
        Value a = to16(x.op(0), x);
        Value c = to16(x.op(1), x);
        Instr r = insert(x, new Instr.Bin(op, a, c, x.pos()));
        return insert(x, new Instr.Cast(ext, r, MType.I32, x.pos()));
    }

    private Value to16(Value v, Instr at) {
        if (v instanceof Value.Const k) {
            return new Value.Const(MType.I16, k.bits());
        }
        return insert(at, new Instr.Cast(Instr.CastKind.TRUNC, v, MType.I16, at.pos()));
    }

    private Value compare(Instr.Cmp c, Block b) {
        MType t = c.op(0).type();
        if (!Ranges.isInt(t) || t == MType.I1) {
            return null;
        }
        Ranges.Range ra = ranges.at(c.op(0), b);
        Ranges.Range rb = ranges.at(c.op(1), b);
        if (ra == null || rb == null) {
            return null;
        }
        Boolean known = decide(c.pred, ra, rb, ranges.atUnsigned(c.op(0), b), ranges.atUnsigned(c.op(1), b));
        if (known != null) {
            return new Value.Const(MType.I1, known ? 1 : 0);
        }
        if (signedPred(c.pred) && ra.lo() >= 0 && rb.lo() >= 0) {
            // neither is negative: the unsigned comparison is the same, and cheaper on the 65816
            return insert(c, new Instr.Cmp(unsignedPred(c.pred), c.op(0), c.op(1), c.pos()));
        }
        if (t != MType.I32 || c.op(0) instanceof Value.Const && c.op(1) instanceof Value.Const
                || !cheap(c.op(0)) || !cheap(c.op(1))) {
            return null;
        }
        if (inside(ra, S16) && inside(rb, S16)) {
            // sign extension keeps both the signed and the unsigned order
            return insert(c, new Instr.Cmp(c.pred, to16(c.op(0), c), to16(c.op(1), c), c.pos()));
        }
        if (inside(ra, U16) && inside(rb, U16)) {
            Instr.Pred p = switch (c.pred) {
                case SLT -> Instr.Pred.ULT;    // neither is negative
                case SLE -> Instr.Pred.ULE;
                case SGT -> Instr.Pred.UGT;
                case SGE -> Instr.Pred.UGE;
                default -> c.pred;
            };
            return insert(c, new Instr.Cmp(p, to16(c.op(0), c), to16(c.op(1), c), c.pos()));
        }
        return null;
    }

    private static boolean signedPred(Instr.Pred p) {
        return switch (p) {
            case SLT, SLE, SGT, SGE -> true;
            default -> false;
        };
    }

    private static Instr.Pred unsignedPred(Instr.Pred p) {
        return switch (p) {
            case SLT -> Instr.Pred.ULT;
            case SLE -> Instr.Pred.ULE;
            case SGT -> Instr.Pred.UGT;
            case SGE -> Instr.Pred.UGE;
            default -> p;
        };
    }

    /** ashr, sdiv and srem of values that are not negative are lshr, udiv and urem (cheaper). */
    private Value unsignedOp(Instr.Bin x, Block b) {
        Instr.BinOp op = switch (x.op) {
            case ASHR -> Instr.BinOp.LSHR;
            case SDIV -> Instr.BinOp.UDIV;
            case SREM -> Instr.BinOp.UREM;
            default -> null;
        };
        if (op == null) {
            return null;
        }
        Ranges.Range ra = ranges.at(x.op(0), b);
        Ranges.Range rb = ranges.at(x.op(1), b);
        if (ra == null || rb == null || ra.lo() < 0 || x.op != Instr.BinOp.ASHR && rb.lo() < 0) {
            return null;
        }
        return insert(x, new Instr.Bin(op, x.op(0), x.op(1), x.pos()));
    }

    /** The outcome of a comparison if the ranges decide it, else null. */
    static Boolean decide(Instr.Pred pred, Ranges.Range a, Ranges.Range b, Ranges.Range ua, Ranges.Range ub) {
        switch (pred) {
            case EQ, NE -> {
                Boolean eq = a.lo() == a.hi() && b.lo() == b.hi() && a.lo() == b.lo() ? Boolean.TRUE
                        : a.hi() < b.lo() || b.hi() < a.lo() ? Boolean.FALSE : null;
                return eq == null ? null : pred == Instr.Pred.EQ ? eq : !eq;
            }
            case SLT, SLE, SGT, SGE -> {
                return ordered(pred, a, b);
            }
            default -> {
                Instr.Pred signedPred = switch (pred) {
                    case ULT -> Instr.Pred.SLT;
                    case ULE -> Instr.Pred.SLE;
                    case UGT -> Instr.Pred.SGT;
                    default -> Instr.Pred.SGE;
                };
                return ordered(signedPred, ua, ub);
            }
        }
    }

    private static Boolean ordered(Instr.Pred p, Ranges.Range a, Ranges.Range b) {
        return switch (p) {
            case SLT -> a.hi() < b.lo() ? Boolean.TRUE : a.lo() >= b.hi() ? Boolean.FALSE : null;
            case SLE -> a.hi() <= b.lo() ? Boolean.TRUE : a.lo() > b.hi() ? Boolean.FALSE : null;
            case SGT -> a.lo() > b.hi() ? Boolean.TRUE : a.hi() <= b.lo() ? Boolean.FALSE : null;
            case SGE -> a.lo() >= b.hi() ? Boolean.TRUE : a.hi() < b.lo() ? Boolean.FALSE : null;
            default -> null;
        };
    }

    private static Instr insert(Instr at, Instr i) {
        at.block().insertBefore(at, i);
        return i;
    }
}
