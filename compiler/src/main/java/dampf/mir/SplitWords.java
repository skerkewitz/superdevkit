package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 32-bit values that are only loaded, combined bit by bit and stored become two 16-bit values. An
 * AND, OR or XOR never carries from one half to the other, so {@code hash ^= key} on two
 * {@code uint32_t} is two independent 16-bit operations; as one 32-bit value every word goes through
 * a frame place on the way. The 65816 works in 16 bits, so the halves cost what two {@code uint16_t}
 * cost.
 *
 * <p>A value is split if it is a load (not volatile), an AND, OR or XOR, or a φ, if its operands are
 * such values, constants or zero extensions, and if everything that reads it can take the halves: a
 * store, another such value, a truncation (the low half), {@code lshr 16} (the high half), or a test
 * for equality: {@code x != 0} is {@code (lo | hi) != 0}, and {@code x == y} compares
 * {@code (xl ^ yl) | (xh ^ yh)} with 0. Anything else (an addition, an ordered comparison, a call)
 * keeps the whole web as it is, so a value is never put together again.
 */
public final class SplitWords {
    private final MFunction f;
    private final Map<Instr, List<Instr>> users = new HashMap<>();
    private final Map<Instr, Value[]> halves = new HashMap<>();

    private SplitWords(MFunction f) {
        this.f = f;
    }

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        return new SplitWords(f).split();
    }

    private boolean split() {
        boolean removed = Cfg.removeUnreachable(f);   // every value left has its halves made in order
        Set<Instr> web = new HashSet<>();
        for (Block b : f.blocks()) {
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    if (v instanceof Instr d) {
                        users.computeIfAbsent(d, k -> new ArrayList<>()).add(i);
                    }
                }
                if (candidate(i)) {
                    web.add(i);
                }
            }
        }
        boolean shrunk = true;
        while (shrunk) {
            shrunk = web.removeIf(i -> !operandsFit(i, web) || !usersFit(i, web));
        }
        if (web.isEmpty()) {
            return removed;
        }
        List<Block> order = Cfg.reversePostorder(f);
        // the φs first, since a φ may read a value of a later block
        List<Instr.Phi> phis = new ArrayList<>();
        for (Block b : order) {
            for (Instr i : List.copyOf(b.instrs())) {
                if (i instanceof Instr.Phi p && web.contains(p)) {
                    Instr.Phi lo = new Instr.Phi(MType.I16, p.pos());
                    Instr.Phi hi = new Instr.Phi(MType.I16, p.pos());
                    b.add(lo);
                    b.add(hi);
                    halves.put(p, new Value[] {lo, hi});
                    phis.add(p);
                }
            }
        }
        for (Block b : order) {
            for (Instr i : List.copyOf(b.instrs())) {
                if (!web.contains(i) || i instanceof Instr.Phi) {
                    continue;
                }
                switch (i) {
                    case Instr.Load l -> {
                        Instr lo = before(l, new Instr.Load(MType.I16, l.op(0), false, l.pos()));
                        Instr high = before(l, new Instr.PtrAdd(l.op(0), new Value.Const(MType.I16, 2), l.pos()));
                        Instr hi = before(l, new Instr.Load(MType.I16, high, false, l.pos()));
                        halves.put(l, new Value[] {lo, hi});
                    }
                    case Instr.Bin x -> {
                        Instr lo = before(x, new Instr.Bin(x.op, half(x.op(0), 0), half(x.op(1), 0), x.pos()));
                        Instr hi = before(x, new Instr.Bin(x.op, half(x.op(0), 1), half(x.op(1), 1), x.pos()));
                        halves.put(x, new Value[] {lo, hi});
                    }
                    default -> throw new IllegalStateException("cannot split " + Printer.instr(i));
                }
            }
        }
        for (Instr.Phi p : phis) {
            Value[] h = halves.get(p);
            for (int k = 0; k < p.from.size(); k++) {
                ((Instr.Phi) h[0]).add(p.from.get(k), half(p.op(k), 0));
                ((Instr.Phi) h[1]).add(p.from.get(k), half(p.op(k), 1));
            }
        }
        Map<Instr, Value> replaced = new HashMap<>();
        for (Instr i : web) {
            for (Instr u : users.getOrDefault(i, List.of())) {
                if (web.contains(u)) {
                    continue;
                }
                Value[] h = halves.get(i);
                switch (u) {
                    case Instr.Store s -> {
                        before(s, new Instr.Store(s.op(0), h[0], false, s.pos()));
                        Instr high = before(s, new Instr.PtrAdd(s.op(0), new Value.Const(MType.I16, 2), s.pos()));
                        before(s, new Instr.Store(high, h[1], false, s.pos()));
                        s.block().remove(s);
                    }
                    case Instr.Cmp c -> {
                        if (!replaced.containsKey(c)) {   // (both of its operands may be split)
                            replaced.put(c, equality(c));
                        }
                    }
                    case Instr.Cast c -> replaced.put(c, c.type() == MType.I16 ? h[0]
                            : before(c, new Instr.Cast(Instr.CastKind.TRUNC, h[0], c.type(), c.pos())));
                    case Instr.Bin x -> replaced.put(x, before(x, new Instr.Cast(Instr.CastKind.ZEXT, h[1], MType.I32, x.pos())));
                    default -> throw new IllegalStateException("cannot read halves in " + Printer.instr(u));
                }
            }
        }
        DeadCode.replaceUses(f, replaced);
        for (Instr r : replaced.keySet()) {
            r.block().remove(r);
        }
        for (Instr i : web) {
            i.block().remove(i);
        }
        return true;
    }

    /** c, an EQ or NE of two 32-bit values, on their halves: zero exactly if every half is equal. */
    private Instr equality(Instr.Cmp c) {
        Value lo = difference(c, 0);
        Value hi = difference(c, 1);
        Value any;
        if (lo instanceof Value.Const k && k.bits() == 0) {
            any = hi;
        } else if (hi instanceof Value.Const k && k.bits() == 0) {
            any = lo;
        } else {
            any = before(c, new Instr.Bin(Instr.BinOp.OR, lo, hi, c.pos()));
        }
        return before(c, new Instr.Cmp(c.pred, any, new Value.Const(MType.I16, 0), c.pos()));
    }

    /** Half w of c's operands, XORed; the half itself where the other one is the constant 0. */
    private Value difference(Instr.Cmp c, int w) {
        Value a = half(c.op(0), w);
        Value b = half(c.op(1), w);
        if (b instanceof Value.Const k && k.bits() == 0) {
            return a;
        }
        if (a instanceof Value.Const k && k.bits() == 0) {
            return b;
        }
        return before(c, new Instr.Bin(Instr.BinOp.XOR, a, b, c.pos()));
    }

    private static <T extends Instr> T before(Instr at, T instr) {
        at.block().insertBefore(at, instr);
        return instr;
    }

    private static boolean candidate(Instr i) {
        if (i.type() != MType.I32) {
            return false;
        }
        return switch (i) {
            case Instr.Load l -> !l.isVolatile;
            case Instr.Bin x -> x.op == Instr.BinOp.AND || x.op == Instr.BinOp.OR || x.op == Instr.BinOp.XOR;
            case Instr.Phi p -> p.known == null;
            default -> false;
        };
    }

    private static boolean operandsFit(Instr i, Set<Instr> web) {
        if (i instanceof Instr.Load) {
            return true;
        }
        for (Value v : i.operands()) {
            if (!hasHalves(v, web)) {
                return false;
            }
        }
        return true;
    }

    /** A split value, a constant, or a zero extension of a 16-bit value. */
    private static boolean hasHalves(Value v, Set<Instr> web) {
        return v instanceof Value.Const || v instanceof Instr d && web.contains(d)
                || v instanceof Instr.Cast c && c.kind == Instr.CastKind.ZEXT && c.op(0).type() == MType.I16;
    }

    private boolean usersFit(Instr i, Set<Instr> web) {
        for (Instr u : users.getOrDefault(i, List.of())) {
            boolean fits = switch (u) {
                case Instr.Store s -> !s.isVolatile && s.op(1) == i && s.op(0) != i;
                case Instr.Cast c -> c.kind == Instr.CastKind.TRUNC && (c.type() == MType.I16 || c.type() == MType.I8);
                case Instr.Bin x when web.contains(x) -> true;
                case Instr.Bin x -> x.op == Instr.BinOp.LSHR && x.op(0) == i && x.op(1) instanceof Value.Const k && k.bits() == 16;
                case Instr.Phi p -> web.contains(p);
                case Instr.Cmp c -> (c.pred == Instr.Pred.EQ || c.pred == Instr.Pred.NE) && hasHalves(c.op(0), web)
                        && hasHalves(c.op(1), web);
                default -> false;
            };
            if (!fits) {
                return false;
            }
        }
        return true;
    }

    /** Half w (0 low, 1 high) of an operand: a split value, a constant, or a zero extension. */
    private Value half(Value v, int w) {
        return switch (v) {
            case Value.Const k -> new Value.Const(MType.I16, k.bits() >>> (16 * w));
            case Instr.Cast c when !halves.containsKey(c) -> w == 0 ? c.op(0) : new Value.Const(MType.I16, 0);
            case Instr i -> halves.get(i)[w];
            default -> throw new IllegalStateException("no halves of " + v);
        };
    }
}
