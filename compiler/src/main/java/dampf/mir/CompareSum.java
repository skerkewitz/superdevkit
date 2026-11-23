package dampf.mir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A branch on a 32-bit comparison of 16-bit values, one of them plus a constant: C adds an
 * {@code int16_t} and a constant in {@code int}, which has 32 bits, so {@code a > b + 32} is a
 * 32-bit comparison, since the sum may not fit 16 bits. Where it does not fit, the answer is known:
 * a sum above 32767 is greater than every {@code int16_t}, one below -32768 is less. So
 *
 * <pre>
 *     br (cmp P (sext a), (add (sext b), K)), T, F
 * </pre>
 * becomes a test whether the sum fits, which goes straight to the known answer if not, and a
 * 16-bit comparison if it does. Where the ranges show that it always fits, the next round of
 * passes removes the test. Exact for all inputs. A branch that leaves a loop stays as it is: the
 * loop passes read its test.
 */
public final class CompareSum {
    private CompareSum() {}

    /** a P (b + k), all as integers; a is a 16-bit value. */
    private record Form(Instr.Pred pred, Value a, Value b, long k) {}

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        Map<Instr, Integer> uses = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    if (v instanceof Instr d) {
                        uses.merge(d, 1, Integer::sum);
                    }
                }
            }
        }
        List<Loops.Loop> loops = Loops.find(f);
        boolean changed = false;
        for (Block b : List.copyOf(f.blocks())) {
            if (!(b.terminator() instanceof Instr.Branch br) || br.ifTrue == br.ifFalse
                    || !(br.op(0) instanceof Instr.Cmp c) || c.block() != b || uses.getOrDefault(c, 0) != 1
                    || leavesLoop(b, br, loops)) {
                continue;
            }
            Form form = form(c);
            if (form != null) {
                split(f, b, br, c, form);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean leavesLoop(Block b, Instr.Branch br, List<Loops.Loop> loops) {
        for (Loops.Loop l : loops) {
            if (l.blocks().contains(b) && (!l.blocks().contains(br.ifTrue) || !l.blocks().contains(br.ifFalse))) {
                return true;
            }
        }
        return false;
    }

    private static Form form(Instr.Cmp c) {
        if (c.op(0).type() != MType.I32) {
            return null;
        }
        Instr.Pred asWritten = c.pred;
        if (asWritten != Instr.Pred.EQ && asWritten != Instr.Pred.NE && asWritten != Instr.Pred.SLT
                && asWritten != Instr.Pred.SLE && asWritten != Instr.Pred.SGT && asWritten != Instr.Pred.SGE) {
            return null;
        }
        Form f = form(asWritten, c.op(0), c.op(1));
        return f != null ? f : form(mirror(asWritten), c.op(1), c.op(0));
    }

    private static Form form(Instr.Pred p, Value left, Value right) {
        Value a = narrow(left);
        if (a == null || !(right instanceof Instr.Bin s) || s.op != Instr.BinOp.ADD
                || !(s.op(1) instanceof Value.Const k) || !(s.op(0) instanceof Instr.Cast x)
                || x.kind != Instr.CastKind.SEXT || x.op(0).type() != MType.I16) {
            return null;
        }
        long K = MType.I32.signed(k.bits());
        return K == 0 || K < -0x7FFF || K > 0x7FFF ? null : new Form(p, a, x.op(0), K);
    }

    /** The 16-bit value of a sign extension or a constant that fits, else null. */
    private static Value narrow(Value v) {
        if (v instanceof Instr.Cast x && x.kind == Instr.CastKind.SEXT && x.op(0).type() == MType.I16) {
            return x.op(0);
        }
        if (v instanceof Value.Const k) {
            long c = MType.I32.signed(k.bits());
            return c >= -0x8000 && c <= 0x7FFF ? new Value.Const(MType.I16, MType.I16.wrap(c)) : null;
        }
        return null;
    }

    private static Instr.Pred mirror(Instr.Pred p) {
        return switch (p) {
            case SLT -> Instr.Pred.SGT;
            case SLE -> Instr.Pred.SGE;
            case SGT -> Instr.Pred.SLT;
            case SGE -> Instr.Pred.SLE;
            default -> p;
        };
    }

    /** What a P s is when s lies above every 16-bit a ({@code above}), or below every one. */
    private static boolean outcome(Instr.Pred p, boolean above) {
        return switch (p) {
            case NE -> true;
            case SLT, SLE -> above;
            case SGT, SGE -> !above;
            default -> false;
        };
    }

    private static void split(MFunction f, Block b, Instr.Branch br, Instr.Cmp c, Form form) {
        Block t = br.ifTrue;
        Block e = br.ifFalse;
        Block fits = f.newBlock("fits");
        for (Block s : List.of(t, e)) {
            s.preds().remove(b);
            for (Instr i : s.instrs()) {
                if (i instanceof Instr.Phi p) {
                    p.from.set(p.from.indexOf(b), fits);
                }
            }
        }
        Instr sum = fits.add(new Instr.Bin(Instr.BinOp.ADD, form.b(), new Value.Const(MType.I16, MType.I16.wrap(form.k())), c.pos()));
        Instr test = fits.add(new Instr.Cmp(form.pred(), form.a(), sum, c.pos()));
        fits.add(new Instr.Branch(test, t, e, br.pos()));
        b.remove(br);
        b.remove(c);
        boolean above = form.k() > 0;
        long limit = above ? 0x7FFF - form.k() : -0x8000 - form.k();
        Instr inRange = b.add(new Instr.Cmp(above ? Instr.Pred.SLE : Instr.Pred.SGE, form.b(),
                new Value.Const(MType.I16, MType.I16.wrap(limit)), c.pos()));
        Block known = outcome(form.pred(), above) ? t : e;
        b.add(new Instr.Branch(inRange, fits, known, br.pos()));
        for (Instr i : known.instrs()) {
            if (i instanceof Instr.Phi p) {
                p.add(b, p.op(p.from.indexOf(fits)));
            }
        }
    }
}
