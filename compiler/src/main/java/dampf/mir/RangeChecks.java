package dampf.mir;

import java.util.List;

/**
 * Tests of one value against neighbouring constants in a chain of branches become one range
 * check, as in LLVM's InstCombine: {@code x == 2 || x == 3} is {@code (x - 2) <u 2}, or, where the
 * values are an aligned block of a power of two, {@code (x & ~1) == 2}. In MIR the
 * {@code ||} is control flow: block A branches on {@code x == 2} to T, else to B, which does
 * nothing but branch on {@code x == 3} to T, else to F. A then branches on the range check to T
 * or F, and B goes. On the 65816 that is one load, subtraction and compare instead of a second
 * block with its own load and compare, and often a jump less. A chain of more constants
 * ({@code x == 2 || x == 3 || x == 4}) merges step by step, since a range check is recognized as
 * the test of an interval too; {@code !=} with {@code &&} is the same with the branches swapped.
 */
public final class RangeChecks {
    private RangeChecks() {}

    /** The condition c is "x in [lo, hi]" (unsigned, in x's type) if inside, else "x not in [lo, hi]". */
    private record Test(Value x, long lo, long hi, boolean inside) {}

    public static boolean run(MFunction f) {
        boolean changed = false;
        boolean again = true;
        while (again) {
            again = false;
            for (Block a : List.copyOf(f.blocks())) {
                if (merge(f, a)) {
                    again = true;
                    changed = true;
                }
            }
            if (again) {
                Cfg.removeUnreachable(f);
            }
        }
        return changed;
    }

    private static boolean merge(MFunction f, Block a) {
        if (!(a.terminator() instanceof Instr.Branch br) || br.ifTrue == br.ifFalse) {
            return false;
        }
        Test ta = test(br.op(0));
        if (ta == null) {
            return false;
        }
        // the successor where x is in the interval, and the one where the chain goes on
        Block in = ta.inside ? br.ifTrue : br.ifFalse;
        Block b = ta.inside ? br.ifFalse : br.ifTrue;
        if (b == a || b.preds().size() != 1 || b.instrs().size() != 2 || !(b.instrs().get(0) instanceof Instr.Cmp c2)
                || !(b.instrs().get(1) instanceof Instr.Branch br2) || br2.op(0) != c2 || br2.ifTrue == br2.ifFalse
                || usedElsewhere(f, c2, br2)) {
            return false;
        }
        Test tb = test(c2);
        if (tb == null || tb.x != ta.x) {
            return false;
        }
        Block in2 = tb.inside ? br2.ifTrue : br2.ifFalse;
        Block out = tb.inside ? br2.ifFalse : br2.ifTrue;
        if (in2 != in || out == in || out == a || out == b) {
            return false;
        }
        // the union must be one interval that is not the whole type
        if (Math.max(ta.lo, tb.lo) > Math.min(ta.hi, tb.hi) + 1) {
            return false;
        }
        long lo = Math.min(ta.lo, tb.lo);
        long hi = Math.max(ta.hi, tb.hi);
        MType t = ta.x.type();
        long count = hi - lo + 1;
        if (count >= 1L << t.bits()) {
            return false;
        }
        // φs in `in` must get the same value from a and from b; then b's entry goes
        for (Instr i : in.instrs()) {
            if (!(i instanceof Instr.Phi p)) {
                break;
            }
            if (p.op(p.from.indexOf(a)) != p.op(p.from.indexOf(b))) {
                return false;
            }
        }
        Instr.Branch old = br;
        Instr.Cmp check;
        if (Long.bitCount(count) == 1 && lo % count == 0) {
            // an aligned block of a power of two values: (x & ~(count - 1)) == lo, without the
            // subtraction ({2, 3}: x & ~1 == 2)
            Instr.Bin and = new Instr.Bin(Instr.BinOp.AND, ta.x, new Value.Const(t, ~(count - 1)), old.pos());
            a.insertBefore(old, and);
            check = new Instr.Cmp(Instr.Pred.EQ, and, new Value.Const(t, lo), old.pos());
        } else {
            // (x + -lo) <u count (canonical: no x - lo)
            Value v = ta.x;
            if (lo != 0) {
                Instr.Bin sub = new Instr.Bin(Instr.BinOp.ADD, v, new Value.Const(t, t.wrap(-lo)), old.pos());
                a.insertBefore(old, sub);
                v = sub;
            }
            check = new Instr.Cmp(Instr.Pred.ULT, v, new Value.Const(t, count), old.pos());
        }
        a.insertBefore(old, check);
        Cfg.removeEdge(a, b);
        a.instrs().set(a.instrs().size() - 1, new Instr.Branch(check, in, out, old.pos()));
        a.instrs().getLast().block = a;
        old.block = null;
        // a goes to `out` itself, with what b brought there; b is unreachable now and goes with
        // its edges (Cfg.removeUnreachable)
        out.preds().add(a);
        for (Instr i : out.instrs()) {
            if (!(i instanceof Instr.Phi p)) {
                break;
            }
            p.add(a, p.op(p.from.indexOf(b)));
        }
        return true;
    }

    /** What a branch condition tests about one value, or null. */
    private static Test test(Value c) {
        if (!(c instanceof Instr.Cmp cmp)) {
            return null;
        }
        Value x = cmp.op(0);
        Value k = cmp.op(1);
        if (x instanceof Value.Const && !(k instanceof Value.Const)) {
            Value s = x;
            x = k;
            k = s;
        }
        if (!(k instanceof Value.Const kc) || x instanceof Value.Const
                || x.type() != MType.I8 && x.type() != MType.I16) {
            return null;
        }
        switch (cmp.pred) {
            case EQ, NE -> {
                boolean inside = cmp.pred == Instr.Pred.EQ;
                // (y & ~(n - 1)) == lo, n a power of two, lo a multiple of it: y in [lo, lo + n - 1]
                if (x instanceof Instr.Bin m && m.op == Instr.BinOp.AND && m.op(1) instanceof Value.Const mask
                        && !(m.op(0) instanceof Value.Const)) {
                    long width = 1L << x.type().bits();
                    long n = width - mask.bits();
                    if (Long.bitCount(n) == 1 && n > 1 && kc.bits() % n == 0) {
                        return new Test(m.op(0), kc.bits(), kc.bits() + n - 1, inside);
                    }
                }
                return new Test(x, kc.bits(), kc.bits(), inside);
            }
            case ULT, UGE -> {
                // (y + -lo) <u n: y in [lo, lo + n - 1], or y itself (lo = 0)
                if (cmp.op(1) != k || kc.bits() == 0) {
                    return null;
                }
                long n = kc.bits();
                boolean inside = cmp.pred == Instr.Pred.ULT;
                if (x instanceof Instr.Bin s && s.op == Instr.BinOp.ADD && s.op(1) instanceof Value.Const l
                        && !(s.op(0) instanceof Value.Const)) {
                    long lo = x.type().wrap(-l.bits());
                    long hi = lo + n - 1;
                    return hi < 1L << x.type().bits() ? new Test(s.op(0), lo, hi, inside) : null;
                }
                return new Test(x, 0, n - 1, inside);
            }
            default -> {
                return null;
            }
        }
    }

    /** Whether c has a use other than the branch br. */
    private static boolean usedElsewhere(MFunction f, Instr c, Instr br) {
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                if (i != br && i.operands().contains(c)) {
                    return true;
                }
            }
        }
        return false;
    }
}
