package dampf.mir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Constant folding and algebraic identities (part of optimization H): operations on constants
 * are computed (with the interpreter's arithmetic, so they mean the same), {@code x + 0},
 * {@code x & -1}, {@code x - x}, ... are simplified, branches on constants become jumps, blocks
 * that can no longer be reached go, φs whose operands are all the same value become that
 * value, and a block that only one block jumps to is merged into it.
 */
public final class Simplify {
    private final Map<Instr, Value> replaced = new HashMap<>();

    private Simplify() {}

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        Simplify s = new Simplify();
        boolean changed = false;
        for (Block b : f.blocks()) {
            for (Instr i : List.copyOf(b.instrs())) {
                s.resolve(i);
                Value r = s.simplify(i);
                if (r != null) {
                    s.replaced.put(i, r);
                }
            }
            changed |= foldTerminator(b);
            changed |= narrowSwitch(b);
        }
        DeadCode.replaceUses(f, s.replaced);
        changed |= Cfg.removeUnreachable(f);
        // φs that lost operands may now be trivial; replacing one can make another trivial
        boolean more = true;
        while (more) {
            more = false;
            Map<Instr, Value> trivial = new HashMap<>();
            for (Block b : f.blocks()) {
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Phi p) {
                        Value v = sameOperand(p);
                        if (v != null) {
                            trivial.put(p, v);
                        }
                    }
                }
            }
            if (!trivial.isEmpty()) {
                DeadCode.replaceUses(f, trivial);
                for (Instr p : trivial.keySet()) {
                    p.block().remove(p);
                }
                more = true;
                changed = true;
            }
        }
        changed |= mergeBlocks(f);
        changed |= threadJumps(f);
        changed |= threadConstants(f);
        return changed || !s.replaced.isEmpty();
    }

    /**
     * A block that only decides a branch from a φ ({@code phi [P: 1, Q: %x]; cmp ne %phi, 0; br})
     * is skipped by a predecessor that gives the φ a constant: that predecessor already knows
     * where the branch goes. Typical after inlining a function that returns a truth value. The
     * block may hold nothing but φs, the comparison and the branch, and nothing outside it may use
     * its values (the predecessor no longer passes through).
     */
    private static boolean threadConstants(MFunction f) {
        boolean changed = false;
        for (Block b : List.copyOf(f.blocks())) {
            if (!f.blocks().contains(b) || b == f.entry() || !(b.terminator() instanceof Instr.Branch br)) {
                continue;
            }
            Instr.Phi phi;
            Instr.Cmp cmp = null;
            if (br.op(0) instanceof Instr.Phi p && p.block() == b) {
                phi = p;
            } else if (br.op(0) instanceof Instr.Cmp c && c.block() == b && c.op(0) instanceof Instr.Phi p
                    && p.block() == b && c.op(1) instanceof Value.Const) {
                phi = p;
                cmp = c;
            } else {
                continue;
            }
            if (!onlyDecides(f, b, cmp)) {
                continue;
            }
            for (int k = 0; k < phi.from.size(); k++) {
                Block pred = phi.from.get(k);
                if (!(phi.op(k) instanceof Value.Const v) || pred == b || Collections.frequency(b.preds(), pred) != 1) {
                    continue;
                }
                boolean taken = cmp == null ? v.bits() != 0
                        : Interpreter.cmp(cmp.pred, phi.type(), v.bits(), ((Value.Const) cmp.op(1)).bits());
                Block target = taken ? br.ifTrue : br.ifFalse;
                if (target == b || target.preds().contains(pred) && target.instrs().getFirst() instanceof Instr.Phi) {
                    continue;
                }
                // what target's φs get along the new edge: what they would get from b
                List<Value> incoming = new ArrayList<>();
                for (Instr i : target.instrs()) {
                    if (!(i instanceof Instr.Phi tp)) {
                        break;
                    }
                    Value fromB = tp.op(tp.from.indexOf(b));
                    incoming.add(fromB instanceof Instr.Phi bp && bp.block() == b ? bp.op(bp.from.indexOf(pred)) : fromB);
                }
                if (Cfg.retarget(pred, b, target) != 1) {
                    continue;
                }
                int n = 0;
                for (Instr i : target.instrs()) {
                    if (!(i instanceof Instr.Phi tp)) {
                        break;
                    }
                    tp.add(pred, incoming.get(n++));
                }
                for (Instr i : b.instrs()) {
                    if (i instanceof Instr.Phi bp) {
                        int j = bp.from.indexOf(pred);
                        bp.from.remove(j);
                        bp.operands().remove(j);
                    }
                }
                changed = true;
                break;   // the φ's operands changed: look at the block again next round
            }
        }
        if (changed) {
            Cfg.removeUnreachable(f);
        }
        return changed;
    }

    /** b holds only φs, the comparison (if any) and its branch, and its values are used only in b. */
    private static boolean onlyDecides(MFunction f, Block b, Instr.Cmp cmp) {
        for (Instr i : b.instrs()) {
            if (!(i instanceof Instr.Phi) && i != cmp && !i.isTerminator()) {
                return false;
            }
        }
        for (Block o : f.blocks()) {
            for (Instr i : o.instrs()) {
                for (int k = 0; k < i.operands().size(); k++) {
                    Value v = i.op(k);
                    if (v instanceof Instr d && d.block() == b && o != b) {
                        // a φ of the target may read b's φ along b's edge: that is handled
                        if (!(i instanceof Instr.Phi tp && tp.from.get(k) == b && d instanceof Instr.Phi)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * A branch to a block that only jumps on goes straight to where that block jumps. Not for a
     * predecessor that already goes to the target when the target has φs (one edge per value),
     * nor for the entry block.
     */
    private static boolean threadJumps(MFunction f) {
        boolean changed = false;
        for (Block e : List.copyOf(f.blocks())) {
            if (e == f.entry() || e.instrs().size() != 1 || !(e.terminator() instanceof Instr.Jump j) || j.target == e) {
                continue;
            }
            Block t = j.target;
            boolean phis = t.instrs().getFirst() instanceof Instr.Phi;
            for (Block p : List.copyOf(e.preds())) {
                if (p == e || phis && t.preds().contains(p)) {
                    continue;
                }
                int edges = Cfg.retarget(p, e, t);
                if (edges == 0) {
                    continue;
                }
                for (Instr i : t.instrs()) {
                    if (i instanceof Instr.Phi phi) {
                        Value v = phi.op(phi.from.indexOf(e));
                        for (int k = 0; k < edges; k++) {
                            phi.add(p, v);
                        }
                    }
                }
                changed = true;
            }
        }
        if (changed) {
            Cfg.removeUnreachable(f);
        }
        return changed;
    }

    /** A block that only its one predecessor jumps to joins that predecessor. */
    private static boolean mergeBlocks(MFunction f) {
        boolean changed = false;
        for (Block b : List.copyOf(f.blocks())) {
            if (!f.blocks().contains(b)) {
                continue;
            }
            while (b.terminator() instanceof Instr.Jump j && j.target != b && j.target != f.entry()
                    && j.target.preds().size() == 1) {
                Block s = j.target;
                Map<Instr, Value> phis = new HashMap<>();
                for (Instr i : List.copyOf(s.instrs())) {
                    if (i instanceof Instr.Phi p) {
                        phis.put(p, p.op(0));
                        s.remove(p);
                    }
                }
                DeadCode.replaceUses(f, phis);
                b.instrs().removeLast().block = null;
                for (Instr i : s.instrs()) {
                    i.block = b;
                    b.instrs().add(i);
                }
                for (Block succ : s.terminator().successors()) {
                    java.util.Collections.replaceAll(succ.preds(), s, b);
                    for (Instr i : succ.instrs()) {
                        if (i instanceof Instr.Phi p) {
                            java.util.Collections.replaceAll(p.from, s, b);
                        }
                    }
                }
                f.blocks().remove(s);
                changed = true;
            }
        }
        return changed;
    }

    private void resolve(Instr i) {
        for (int k = 0; k < i.operands().size(); k++) {
            Value v = i.op(k);
            while (v instanceof Instr d && replaced.containsKey(d)) {
                v = replaced.get(d);
            }
            if (v != i.op(k)) {
                i.setOp(k, v);
            }
        }
    }

    /** The one value all operands of a φ are (itself left aside), or null. */
    private static Value sameOperand(Instr.Phi p) {
        Value same = null;
        for (Value v : p.operands()) {
            if (v == p || v.equals(same)) {
                continue;
            }
            if (same != null) {
                return null;
            }
            same = v;
        }
        return same;
    }

    private Value simplify(Instr i) {
        return switch (i) {
            case Instr.Bin b -> bin(b);
            case Instr.Cmp c -> cmp(c);
            case Instr.PtrAdd p -> ptrAdd(p);
            default -> null;
        };
    }

    private static Value bin(Instr.Bin b) {
        MType t = b.type();
        Value x = b.op(0);
        Value y = b.op(1);
        if (x instanceof Value.Const kx && y instanceof Value.Const ky) {
            try {
                return new Value.Const(t, Interpreter.bin(b.op, t, kx.bits(), ky.bits()));
            } catch (ArithmeticException e) {
                return null;   // division by zero: undefined in C, left for the program to do
            }
        }
        long ones = t.wrap(-1);
        if (y instanceof Value.Const k) {
            long c = k.bits();
            switch (b.op) {
                case ADD, SUB, OR, XOR, SHL, LSHR, ASHR -> {
                    if (c == 0) {
                        return x;
                    }
                }
                case AND -> {
                    if (c == 0) {
                        return k;
                    }
                    if (c == ones) {
                        return x;
                    }
                }
                case MUL, UDIV, SDIV -> {
                    if (c == 1) {
                        return x;
                    }
                    if (c == 0 && b.op == Instr.BinOp.MUL) {
                        return k;
                    }
                }
                default -> { }
            }
        }
        if (x instanceof Value.Const k && k.bits() == 0) {
            switch (b.op) {
                case ADD, OR, XOR -> {
                    return y;
                }
                case AND, MUL, SHL, LSHR, ASHR -> {
                    return k;
                }
                default -> { }
            }
        }
        if (x.equals(y) && !(x instanceof Value.Undef)) {
            switch (b.op) {
                case SUB, XOR -> {
                    return new Value.Const(t, 0);
                }
                case AND, OR -> {
                    return x;
                }
                default -> { }
            }
        }
        return null;
    }

    /** ptradd p, 0 is p; ptradd (ptradd p, a), b with constants is ptradd p, a + b. */
    private static Value ptrAdd(Instr.PtrAdd p) {
        if (p.op(1) instanceof Value.Const k && k.bits() == 0) {
            return p.op(0);
        }
        if (p.op(1) instanceof Value.Const k && p.op(0) instanceof Instr.PtrAdd inner
                && inner.op(1) instanceof Value.Const j && k.type() == j.type()) {
            long sum = k.type().signed(k.bits()) + j.type().signed(j.bits());
            if (k.type() == MType.I32 || sum >= -0x8000 && sum < 0x8000) {
                Instr r = new Instr.PtrAdd(inner.op(0), new Value.Const(k.type(), sum), p.pos());
                p.block().insertBefore(p, r);
                return r;
            }
        }
        return null;
    }

    private static Value cmp(Instr.Cmp c) {
        Value x = c.op(0);
        Value y = c.op(1);
        if (x instanceof Value.Const kx && y instanceof Value.Const ky) {
            return truth(Interpreter.cmp(c.pred, x.type(), kx.bits(), ky.bits()));
        }
        if (x.equals(y) && !(x instanceof Value.Undef)) {
            return truth(switch (c.pred) {
                case EQ, ULE, UGE, SLE, SGE -> true;
                default -> false;
            });
        }
        if (y instanceof Value.Const k && k.bits() == 0) {
            if (c.pred == Instr.Pred.ULT) {
                return truth(false);
            }
            if (c.pred == Instr.Pred.UGE) {
                return truth(true);
            }
        }
        return canonical(c);
    }

    /**
     * A comparison with a constant, written as {@code x < c} or {@code x >= c} with the constant
     * on the right (the code generator compares a value with a constant immediate most cheaply):
     * {@code c < x} is {@code x > c}, {@code x > c} is {@code x >= c + 1}, {@code x <= c} is
     * {@code x < c + 1}, as long as c + 1 does not overflow.
     */
    private static Value canonical(Instr.Cmp c) {
        Value x = c.op(0);
        Value y = c.op(1);
        Instr.Pred p = c.pred;
        if (x instanceof Value.Const && !(y instanceof Value.Const)) {
            Value s = x;
            x = y;
            y = s;
            p = Ranges.swap(p);
        }
        if (!(y instanceof Value.Const k) || x.type() == MType.PTR) {
            return x == c.op(0) ? null : insert(c, new Instr.Cmp(p, x, y, c.pos()));
        }
        MType t = x.type();
        long s = t.signed(k.bits());
        long u = k.bits();
        long smax = (1L << (t.bits() - 1)) - 1;
        long umax = (1L << t.bits()) - 1;
        Instr.Pred q = null;
        long next = 0;
        switch (p) {
            case SGT, SLE -> {
                if (s < smax) {
                    q = p == Instr.Pred.SGT ? Instr.Pred.SGE : Instr.Pred.SLT;
                    next = s + 1;
                }
            }
            case UGT, ULE -> {
                if (u < umax) {
                    q = p == Instr.Pred.UGT ? Instr.Pred.UGE : Instr.Pred.ULT;
                    next = u + 1;
                }
            }
            default -> { }
        }
        if (q != null) {
            return insert(c, new Instr.Cmp(q, x, new Value.Const(t, next), c.pos()));
        }
        return x == c.op(0) ? null : insert(c, new Instr.Cmp(p, x, y, c.pos()));
    }

    private static Instr insert(Instr at, Instr i) {
        at.block().insertBefore(at, i);
        return i;
    }

    private static Value.Const truth(boolean b) {
        return new Value.Const(MType.I1, b ? 1 : 0);
    }

    /**
     * A switch on a 32-bit widening of an 8 or 16-bit value (C's promotion of a {@code uint8_t})
     * whose cases all lie in the narrow value's range compares 16 bits instead: the widened 8-bit
     * value as an I16, or the 16-bit value itself. Every case then needs one comparison instead of
     * two, and the high word is not computed.
     */
    private static boolean narrowSwitch(Block b) {
        if (!(b.terminator() instanceof Instr.Switch s) || s.op(0).type() != MType.I32
                || !(s.op(0) instanceof Instr.Cast c) || c.kind != Instr.CastKind.ZEXT && c.kind != Instr.CastKind.SEXT) {
            return false;
        }
        MType from = c.op(0).type();
        if (from != MType.I8 && from != MType.I16) {
            return false;
        }
        long lo = c.kind == Instr.CastKind.ZEXT ? 0 : -(1L << (from.bits() - 1));
        long hi = c.kind == Instr.CastKind.ZEXT ? (1L << from.bits()) - 1 : (1L << (from.bits() - 1)) - 1;
        for (long k : s.cases) {
            long v = MType.I32.signed(MType.I32.wrap(k));
            if (v < lo || v > hi) {
                return false;
            }
        }
        s.setOp(0, from == MType.I16 ? c.op(0) : insert(s, new Instr.Cast(c.kind, c.op(0), MType.I16, c.pos())));
        return true;
    }

    /** A branch or switch whose way is known becomes a jump. */
    private static boolean foldTerminator(Block b) {
        Instr t = b.terminator();
        if (t instanceof Instr.Branch br) {
            if (br.ifTrue == br.ifFalse) {
                Cfg.jumpTo(b, br.ifTrue);
                return true;
            }
            if (br.op(0) instanceof Value.Const k) {
                Cfg.jumpTo(b, k.bits() != 0 ? br.ifTrue : br.ifFalse);
                return true;
            }
        }
        if (t instanceof Instr.Switch s && s.op(0) instanceof Value.Const k) {
            Block target = s.otherwise;
            for (int c = 0; c < s.cases.size(); c++) {
                if (k.type().wrap(s.cases.get(c)) == k.bits()) {
                    target = s.targets.get(c);
                    break;
                }
            }
            Cfg.jumpTo(b, target);
            return true;
        }
        return false;
    }
}
