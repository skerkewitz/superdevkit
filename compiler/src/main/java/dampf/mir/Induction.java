package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Induction variables: in a loop whose counter i grows by a constant c per round, a product
 * {@code i * C} (or {@code zext(i) * C}, typically the offset of {@code array[i]} for an array of
 * structs) becomes a variable of its own that starts at {@code init * C} and grows by
 * {@code c * C}: an addition per round instead of a multiplication.
 *
 * <p>Exact: for a 16-bit counter everything is modulo 2^16, and {@code (i + c) * C = i * C + c * C}
 * there. For an 8-bit counter that is zero-extended first, the counter must not wrap around on the
 * way back into the loop; the value ranges have to show that.
 *
 * <p>The new φ carries the range of the product in the loop head (where the counter also has the
 * value after the last round) ({@link Instr.Phi#known}): the value
 * ranges cannot see on their own that it stays within, and indexed addressing needs to know it
 * is never negative.
 */
public final class Induction {
    private Induction() {}

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        Ranges ranges = new Ranges(f);
        List<CountedLoops.Loop> loops = CountedLoops.find(f);
        Map<Instr, List<Instr>> users = new HashMap<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    if (v instanceof Instr d) {
                        users.computeIfAbsent(d, k -> new ArrayList<>()).add(i);
                    }
                }
            }
        }
        Map<Instr, Value> replaced = new HashMap<>();
        for (Block h : List.copyOf(f.blocks())) {
            for (Instr x : List.copyOf(h.instrs())) {
                if (!(x instanceof Instr.Phi i)) {
                    break;
                }
                if (i.type() != MType.I8 && i.type() != MType.I16) {
                    continue;
                }
                CountedLoops.Counter k = counter(loops, h, i);
                Instr.Bin step = k == null ? null : k.step();
                if (step == null) {
                    continue;
                }
                long c = k.c();
                Map<Long, Instr.Phi> made = new HashMap<>();
                for (Instr e : products(i, users)) {
                    long factor = ((Value.Const) (e.op(0) instanceof Value.Const ? e.op(0) : e.op(1))).bits();
                    boolean widened = i.type() == MType.I8;
                    if (widened && !staysInByte(i, step, c, ranges)) {
                        continue;
                    }
                    // the range in the loop head: it holds the value after the last round too
                    Ranges.Range ri = widened ? ranges.atUnsigned(i, h) : ranges.at(i, h);
                    if (ri == null) {
                        continue;
                    }
                    long fs = MType.I16.signed(factor);
                    Ranges.Range r = Ranges.bin(Instr.BinOp.MUL, MType.I16, ri, new Ranges.Range(fs, fs));
                    Instr.Phi o = made.get(factor);
                    if (o == null) {
                        o = variable(i, h, step, c, factor, widened, e);
                        o.known = r;
                        made.put(factor, o);
                    } else {
                        o.known = o.known.join(r);
                    }
                    replaced.put(e, o);
                }
            }
        }
        if (replaced.isEmpty()) {
            // the loop tests move only once the variables are made and the products gone
            for (Block h : f.blocks()) {
                for (Instr x : List.copyOf(h.instrs())) {
                    if (!(x instanceof Instr.Phi i)) {
                        break;
                    }
                    CountedLoops.Counter k = i.type() == MType.I8 ? counter(loops, h, i) : null;
                    Instr.Bin step = k == null ? null : k.step();
                    if (step != null && staysInByte(i, step, k.c(), ranges)) {
                        moveTest(i, h, step, k.c(), users, replaced);
                    }
                }
            }
        }
        DeadCode.replaceUses(f, replaced);
        return !replaced.isEmpty();
    }

    /** i as a counter of the loop headed by h (CountedLoops), or null. */
    private static CountedLoops.Counter counter(List<CountedLoops.Loop> loops, Block h, Instr.Phi i) {
        CountedLoops.Loop l = CountedLoops.at(loops, h);
        return l == null ? null : l.counter(i);
    }

    /**
     * The loop's test moves from the 8-bit counter i to a variable o = zext(i) * F of the same
     * loop (the kind this pass makes: it starts at zext(start) * F and grows by c * F), if i
     * serves nothing else: {@code i < N} becomes {@code o < N * F} (unsigned, and equality).
     * Exact: o never wraps (i stays in its byte, 0 < F, F * 255 <= 0xFFFF), so the order is the
     * same. The counter and its step then feed only each other and go (DeadCode); in the game's
     * pair loop the offset in X is all that is left.
     */
    private static void moveTest(Instr.Phi i, Block h, Instr.Bin step, long c, Map<Instr, List<Instr>> users,
                                 Map<Instr, Value> replaced) {
        Instr.Cmp test = null;
        for (Instr u : users.getOrDefault(i, List.of())) {
            if (u == step) {
                continue;
            }
            if (u instanceof Instr.Cmp t && test == null && t.op(0) == i && t.op(1) instanceof Value.Const
                    && switch (t.pred) {
                        case EQ, NE, ULT, ULE, UGT, UGE -> true;
                        default -> false;
                    }) {
                test = t;
                continue;
            }
            return;   // i is used for something else
        }
        if (test == null || !users.getOrDefault(step, List.of()).stream().allMatch(u -> u == i)) {
            return;
        }
        for (Instr x : h.instrs()) {
            if (!(x instanceof Instr.Phi o)) {
                break;
            }
            long factor = o == i || o.type() != MType.I16 ? 0 : factorOf(i, o, c);
            if (factor > 0) {
                long n = ((Value.Const) test.op(1)).bits() & 0xFF;
                Instr.Cmp moved = new Instr.Cmp(test.pred, o, new Value.Const(MType.I16, n * factor), test.pos());
                test.block().insertBefore(test, moved);
                replaced.put(test, moved);
                return;
            }
        }
    }

    /** F if o = zext(i) * F with 0 < F, F * 255 <= 0xFFFF, read off o's start and step; else 0. */
    private static long factorOf(Instr.Phi i, Instr.Phi o, long c) {
        long factor = 0;
        for (int k = 0; k < i.from.size(); k++) {
            int ko = o.from.indexOf(i.from.get(k));
            if (ko < 0) {
                return 0;
            }
            Value vi = i.op(k);
            Value vo = o.op(ko);
            long f;
            Value.Const d = vo instanceof Instr.Bin s && s.op == Instr.BinOp.ADD
                    ? (s.op(0) == o && s.op(1) instanceof Value.Const k1 ? k1 : s.op(1) == o && s.op(0) instanceof Value.Const k2 ? k2 : null)
                    : null;
            if (d != null && vi instanceof Instr.Bin si && (si.op(0) == i || si.op(1) == i)) {
                // the back edge: i grows by c, o by c * F
                long ds = MType.I16.signed(d.bits());
                if (c == 0 || ds % c != 0) {
                    return 0;
                }
                f = ds / c;
            } else if (vi instanceof Value.Const k0 && vo instanceof Value.Const o0) {
                long u = k0.bits() & 0xFF;
                if (u == 0) {
                    if (o0.bits() != 0) {
                        return 0;
                    }
                    continue;   // says nothing about F
                }
                if (o0.bits() % u != 0) {
                    return 0;
                }
                f = o0.bits() / u;
            } else if (vo instanceof Instr.Bin m && m.op == Instr.BinOp.MUL && m.op(1) instanceof Value.Const fk
                    && m.op(0) instanceof Instr.Cast z && z.kind == Instr.CastKind.ZEXT && z.op(0) == vi) {
                f = fk.bits();
            } else {
                return 0;
            }
            if (f <= 0 || f * 0xFF > 0xFFFF || factor != 0 && f != factor) {
                return 0;
            }
            factor = f;
        }
        return factor;
    }

    /** The multiplications of i (or of its zero extension to i16) by a constant, as i16. */
    private static List<Instr> products(Instr.Phi i, Map<Instr, List<Instr>> users) {
        List<Instr> out = new ArrayList<>();
        List<Value> forms = new ArrayList<>(List.of(i));
        for (Instr u : users.getOrDefault(i, List.of())) {
            if (i.type() == MType.I8 && u instanceof Instr.Cast c && c.kind == Instr.CastKind.ZEXT && c.type() == MType.I16) {
                forms.add(c);
            }
        }
        for (Value form : forms) {
            if (form.type() != MType.I16) {
                continue;
            }
            for (Instr u : users.getOrDefault((Instr) form, List.of())) {
                if (u instanceof Instr.Bin b && b.op == Instr.BinOp.MUL && b.type() == MType.I16
                        && (b.op(0) == form && b.op(1) instanceof Value.Const || b.op(1) == form && b.op(0) instanceof Value.Const)) {
                    out.add(b);
                }
            }
        }
        return out;
    }

    /** An 8-bit counter does not wrap when it steps (so its zero extension steps by c as well). */
    private static boolean staysInByte(Instr.Phi i, Instr.Bin step, long c, Ranges ranges) {
        Ranges.Range r = ranges.atUnsigned(i, step.block());
        return r != null && r.lo() + c >= 0 && r.hi() + c <= 0xFF;
    }

    /** The new φ in h: init * factor on the way in, plus c * factor after each round. */
    private static Instr.Phi variable(Instr.Phi i, Block h, Instr.Bin step, long c, long factor, boolean widened, Instr e) {
        Instr.Phi o = h.add(new Instr.Phi(MType.I16, e.pos()));
        Block sb = step.block();
        Instr after = sb.instrs().get(sb.instrs().indexOf(step) + 1);
        Instr next = new Instr.Bin(Instr.BinOp.ADD, o, new Value.Const(MType.I16, c * factor), e.pos());
        sb.insertBefore(after, next);
        for (int k = 0; k < i.from.size(); k++) {
            Block pred = i.from.get(k);
            Value v = i.op(k);
            if (v == step) {
                o.add(pred, next);
                continue;
            }
            Value start;
            if (v instanceof Value.Const k0) {
                long n = widened ? k0.bits() : k0.type().signed(k0.bits());
                start = new Value.Const(MType.I16, n * factor);
            } else if (v instanceof Value.Undef) {
                start = new Value.Undef(MType.I16);
            } else {
                Instr term = pred.terminator();
                Value x = v;
                if (widened) {
                    Instr z = new Instr.Cast(Instr.CastKind.ZEXT, v, MType.I16, e.pos());
                    pred.insertBefore(term, z);
                    x = z;
                }
                Instr m = new Instr.Bin(Instr.BinOp.MUL, x, new Value.Const(MType.I16, factor), e.pos());
                pred.insertBefore(term, m);
                start = m;
            }
            o.add(pred, start);
        }
        return o;
    }
}
