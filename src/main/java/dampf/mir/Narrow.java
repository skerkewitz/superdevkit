package dampf.mir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Width narrowing (optimization B), from the types alone. C computes in 32-bit {@code int} after
 * the integer promotions; most of the game's values are 8 or 16 bits wide, and on the 65816 an
 * i32 operation costs about twice an i16 one. The rules, each exact for every input:
 *
 * <ul>
 * <li><b>Demanded bits</b> (top-down): {@code trunc (a op b) to N} for op in add, sub, mul, and,
 *     or, xor and shl by a constant below N: the low N bits of the result depend only on the low
 *     N bits of the operands, so the whole tree is computed at width N.</li>
 * <li><b>Extended operands</b> (bottom-up): if both operands are zero-extended from width W (or
 *     constants that fit), then and/or/xor, lshr, udiv/urem, and also ashr and sdiv/srem (the
 *     values are not negative) give the zero extension of the same operation at width W.
 *     Sign-extended operands: and/or/xor, ashr by a constant, and srem.</li>
 * <li><b>Comparisons</b> of two zero-extended values compare the narrow values unsigned; of two
 *     sign-extended values, with the same predicate (sign extension keeps the signed and the
 *     unsigned order).</li>
 * <li><b>Casts of casts</b> collapse: {@code trunc (zext x)}, {@code zext (zext x)}, ...; casts
 *     of constants fold.</li>
 * <li>The demanded-bits rule goes through <b>φs</b> used only by the truncation: each operand is
 *     narrowed at the end of the block it comes from.</li>
 * <li><b>Pointer offsets</b> become i16 (see {@link Instr.PtrAdd}).</li>
 * </ul>
 *
 * Narrowing by value ranges (where the types alone do not show that a value fits) is a later step.
 */
public final class Narrow {
    private final MFunction f;
    private final Map<Instr, Value> replaced = new HashMap<>();
    /** Set when an instruction was changed in place. */
    private boolean changed;
    private Map<Instr, Integer> uses;

    private Narrow(MFunction f) {
        this.f = f;
    }

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        Narrow n = new Narrow(f);
        n.uses = DeadCode.useCounts(f);
        for (Block b : f.blocks()) {
            for (Instr i : List.copyOf(b.instrs())) {
                n.resolveOperands(i);
                Value r = n.rewrite(i);
                if (r != null && r != i) {
                    n.replaced.put(i, r);
                }
            }
        }
        DeadCode.replaceUses(f, n.replaced);
        return n.changed || !n.replaced.isEmpty();
    }

    private void resolveOperands(Instr i) {
        for (int k = 0; k < i.operands().size(); k++) {
            Value v = i.op(k);
            Value r = v;
            while (r instanceof Instr d && replaced.containsKey(d)) {
                r = replaced.get(d);
            }
            if (r != v) {
                i.setOp(k, r);
            }
        }
    }

    private Value rewrite(Instr i) {
        return switch (i) {
            case Instr.Cast c -> {
                Value r = truncTree(c);
                yield r != null ? r : cast(c);
            }
            case Instr.Bin b -> extended(b);
            case Instr.Cmp c -> compare(c);
            case Instr.PtrAdd p -> {
                offset(p);
                yield null;
            }
            default -> null;
        };
    }

    // ------------------------------------------------------------------ casts

    private Value cast(Instr.Cast c) {
        MType to = c.type();
        Value x = c.op(0);
        if (x instanceof Value.Const k) {
            return switch (c.kind) {
                case ZEXT, TRUNC -> new Value.Const(to, k.bits());
                case SEXT -> new Value.Const(to, k.type().signed(k.bits()));
                default -> null;
            };
        }
        if (!(x instanceof Instr.Cast inner)) {
            return null;
        }
        Value src = inner.op(0);
        boolean innerExt = inner.kind == Instr.CastKind.ZEXT || inner.kind == Instr.CastKind.SEXT;
        switch (c.kind) {
            case TRUNC -> {
                if (innerExt) {
                    // the low bits of an extension are the source's
                    if (src.type() == to) {
                        return src;
                    }
                    return src.type().bits() < to.bits() ? insert(c, new Instr.Cast(inner.kind, src, to, c.pos()))
                            : insert(c, new Instr.Cast(Instr.CastKind.TRUNC, src, to, c.pos()));
                }
                if (inner.kind == Instr.CastKind.TRUNC) {
                    return insert(c, new Instr.Cast(Instr.CastKind.TRUNC, src, to, c.pos()));
                }
            }
            case ZEXT -> {
                if (inner.kind == Instr.CastKind.ZEXT) {
                    return insert(c, new Instr.Cast(Instr.CastKind.ZEXT, src, to, c.pos()));
                }
            }
            case SEXT -> {
                if (innerExt) {
                    // sext (sext x) = sext x; sext (zext x) = zext x: its top bit is 0
                    return insert(c, new Instr.Cast(inner.kind, src, to, c.pos()));
                }
            }
            default -> { }
        }
        return null;
    }

    // ------------------------------------------------------------------ demanded bits

    /** {@code trunc (tree) to N} with a tree that can be computed at width N. */
    private Value truncTree(Instr.Cast c) {
        MType n = c.type();
        if (c.kind != Instr.CastKind.TRUNC || !(n == MType.I8 || n == MType.I16)) {
            return null;
        }
        Value v = c.op(0);
        if (!(v instanceof Instr.Bin || v instanceof Instr.Phi) || !canEval(v, n, 0)) {
            return null;
        }
        return eval(v, n, c);
    }

    private boolean canEval(Value v, MType n, int depth) {
        return switch (v) {
            case Value.Const k -> true;
            case Value.Undef u -> true;
            case Instr.Cast c -> c.kind == Instr.CastKind.ZEXT || c.kind == Instr.CastKind.SEXT || c.kind == Instr.CastKind.TRUNC;
            case Instr.Bin b -> depth < 12 && uses.getOrDefault(b, 0) == 1 && switch (b.op) {
                case ADD, SUB, MUL, AND, OR, XOR -> canEval(b.op(0), n, depth + 1) && canEval(b.op(1), n, depth + 1);
                case SHL -> b.op(1) instanceof Value.Const k && k.bits() < n.bits() && canEval(b.op(0), n, depth + 1);
                default -> false;
            };
            // a φ (a ?: or a truth value as an int): its operands are narrowed where they come from
            case Instr.Phi p -> depth < 12 && uses.getOrDefault(p, 0) == 1
                    && p.operands().stream().allMatch(o -> o != p && canEval(o, n, depth + 1));
            default -> false;
        };
    }

    /** The low n bits of v, computed at width n (new instructions go before {@code at}). */
    private Value eval(Value v, MType n, Instr at) {
        return switch (v) {
            case Value.Const k -> new Value.Const(n, k.bits());
            case Value.Undef u -> new Value.Undef(n);
            case Instr.Cast c -> {
                Value src = c.op(0);
                if (src.type() == n) {
                    yield src;
                }
                if (src.type().bits() > n.bits()) {
                    yield insert(at, new Instr.Cast(Instr.CastKind.TRUNC, src, n, c.pos()));
                }
                // a narrower source: zext and sext extend it; a trunc's source is wider than its result,
                // so a trunc never gets here
                yield insert(at, new Instr.Cast(c.kind, src, n, c.pos()));
            }
            case Instr.Bin b -> {
                Value x = eval(b.op(0), n, at);
                Value y = b.op == Instr.BinOp.SHL ? new Value.Const(n, ((Value.Const) b.op(1)).bits()) : eval(b.op(1), n, at);
                yield insert(at, new Instr.Bin(b.op, x, y, b.pos()));
            }
            case Instr.Phi p -> {
                Instr.Phi narrow = p.block().add(new Instr.Phi(n, p.pos()));
                for (int k = 0; k < p.from.size(); k++) {
                    Block pred = p.from.get(k);
                    narrow.add(pred, eval(p.op(k), n, pred.terminator()));
                }
                yield narrow;
            }
            default -> throw new IllegalStateException("cannot narrow " + v);
        };
    }

    // ------------------------------------------------------------------ extended operands

    /** The narrowest width (8 or 16) a value is the zero extension of, or 0 if none below its type. */
    private static int zeroWidth(Value v) {
        int w = switch (v) {
            case Value.Const k -> k.bits() < 0x100 ? 8 : k.bits() < 0x10000 ? 16 : 0;
            case Instr.Cast c when c.kind == Instr.CastKind.ZEXT -> Math.max(8, c.op(0).type().bits());
            default -> 0;
        };
        return w < v.type().bits() ? w : 0;
    }

    /** The narrowest width (8 or 16) a value is the sign extension of, or 0. */
    private static int signWidth(Value v) {
        int w = switch (v) {
            case Value.Const k -> {
                long s = k.type().signed(k.bits());
                yield s >= -0x80 && s < 0x80 ? 8 : s >= -0x8000 && s < 0x8000 ? 16 : 0;
            }
            case Instr.Cast c when c.kind == Instr.CastKind.SEXT -> Math.max(8, c.op(0).type().bits());
            default -> 0;
        };
        return w < v.type().bits() ? w : 0;
    }

    private static MType ofWidth(int w) {
        return w == 8 ? MType.I8 : MType.I16;
    }

    /** v, known to be an extension from at most width t (or a constant that fits), as a value of type t. */
    private Value narrowOperand(Value v, MType t, Instr at, boolean signed) {
        if (v instanceof Value.Const k) {
            return new Value.Const(t, signed ? k.type().signed(k.bits()) : k.bits());
        }
        Value src = ((Instr.Cast) v).op(0);
        if (src.type() == t) {
            return src;
        }
        return insert(at, new Instr.Cast(signed ? Instr.CastKind.SEXT : Instr.CastKind.ZEXT, src, t, at.pos()));
    }

    private static boolean bothConst(Instr i) {
        return i.op(0) instanceof Value.Const && i.op(1) instanceof Value.Const;
    }

    private Value extended(Instr.Bin b) {
        if (bothConst(b)) {
            return null;
        }
        MType t = b.type();
        Instr.BinOp op = b.op;
        int zx = zeroWidth(b.op(0));
        int zy = zeroWidth(b.op(1));
        if (zx > 0 && zy > 0) {
            Instr.BinOp narrow = switch (op) {
                case AND, OR, XOR, LSHR, UDIV, UREM -> op;
                case ASHR -> Instr.BinOp.LSHR;     // not negative: the same
                case SDIV -> Instr.BinOp.UDIV;
                case SREM -> Instr.BinOp.UREM;
                default -> null;
            };
            if (narrow != null) {
                MType w = ofWidth(Math.max(zx, zy));
                Value x = narrowOperand(b.op(0), w, b, false);
                Value y = narrowOperand(b.op(1), w, b, false);
                Instr r = insert(b, new Instr.Bin(narrow, x, y, b.pos()));
                return insert(b, new Instr.Cast(Instr.CastKind.ZEXT, r, t, b.pos()));
            }
        }
        int sx = signWidth(b.op(0));
        int sy = signWidth(b.op(1));
        if (sx > 0 && sy > 0) {
            MType w = ofWidth(Math.max(sx, sy));
            boolean ok = switch (op) {
                case AND, OR, XOR, SREM -> true;
                case ASHR -> b.op(1) instanceof Value.Const;
                default -> false;
            };
            if (ok) {
                Value x = narrowOperand(b.op(0), w, b, true);
                Value y;
                if (op == Instr.BinOp.ASHR) {
                    long amount = ((Value.Const) b.op(1)).bits();
                    y = new Value.Const(w, Math.min(amount, w.bits() - 1)); // further only repeats the sign
                } else {
                    y = narrowOperand(b.op(1), w, b, true);
                }
                Instr r = insert(b, new Instr.Bin(op, x, y, b.pos()));
                return insert(b, new Instr.Cast(Instr.CastKind.SEXT, r, t, b.pos()));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ pointer offsets

    /**
     * An offset within an object fits in 16 bits (signed), so only the low 16 bits of a ptradd's
     * offset matter: {@code ptradd p, x} becomes {@code ptradd p, trunc x to i16}, and the
     * demanded-bits rule narrows the computation of x.
     */
    private void offset(Instr.PtrAdd p) {
        Value x = p.op(1);
        if (x.type() != MType.I32) {
            return;
        }
        p.setOp(1, x instanceof Value.Const k ? new Value.Const(MType.I16, k.bits())
                : insert(p, new Instr.Cast(Instr.CastKind.TRUNC, x, MType.I16, p.pos())));
        changed = true;
    }

    // ------------------------------------------------------------------ comparisons

    private Value compare(Instr.Cmp c) {
        MType t = c.op(0).type();
        if (t == MType.PTR || bothConst(c)) {
            return null;
        }
        // cmp ne (ext i1 x), 0 is x
        if (c.pred == Instr.Pred.NE && c.op(1) instanceof Value.Const k && k.bits() == 0
                && c.op(0) instanceof Instr.Cast x && (x.kind == Instr.CastKind.ZEXT) && x.op(0).type() == MType.I1) {
            return x.op(0);
        }
        int zx = zeroWidth(c.op(0));
        int zy = zeroWidth(c.op(1));
        if (zx > 0 && zy > 0) {
            MType w = ofWidth(Math.max(zx, zy));
            Instr.Pred p = switch (c.pred) {
                case SLT -> Instr.Pred.ULT;     // neither is negative
                case SLE -> Instr.Pred.ULE;
                case SGT -> Instr.Pred.UGT;
                case SGE -> Instr.Pred.UGE;
                default -> c.pred;
            };
            return insert(c, new Instr.Cmp(p, narrowOperand(c.op(0), w, c, false), narrowOperand(c.op(1), w, c, false), c.pos()));
        }
        int sx = signWidth(c.op(0));
        int sy = signWidth(c.op(1));
        if (sx > 0 && sy > 0) {
            MType w = ofWidth(Math.max(sx, sy));
            return insert(c, new Instr.Cmp(c.pred, narrowOperand(c.op(0), w, c, true), narrowOperand(c.op(1), w, c, true), c.pos()));
        }
        return null;
    }

    private Instr insert(Instr at, Instr i) {
        at.block().insertBefore(at, i);
        return i;
    }
}
