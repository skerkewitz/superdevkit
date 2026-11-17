package dampf.mir;

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
        return changed || !s.replaced.isEmpty();
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
        return null;
    }

    private static Value.Const truth(boolean b) {
        return new Value.Const(MType.I1, b ? 1 : 0);
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
