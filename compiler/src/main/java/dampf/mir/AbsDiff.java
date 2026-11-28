package dampf.mir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The absolute difference of two signed 16-bit values as one instruction, {@code abds a, b}: a φ
 * that chooses a - b where a signed comparison says a >= b and b - a where it says a <= b
 * ({@code a > b ? a - b : b - a}, the same with >=, < and <= and with the operands the other way
 * round), and the absolute value {@code v < 0 ? -v : v} as {@code abds v, 0}. Where a and b are
 * equal both sides are 0, so the comparison's own edge case does not matter, and in 16 bits the
 * two differences wrap as C's conversion of the int result to int16_t does. The code generator
 * writes it as one subtraction and a conditional negation; the branch, now without a reason, goes
 * away in {@link Simplify}.
 */
public final class AbsDiff {
    private AbsDiff() {}

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        Map<Block, Block> idom = Cfg.idoms(f);
        Map<Instr, Value> replaced = new HashMap<>();
        for (Block j : f.blocks()) {
            for (Instr i : List.copyOf(j.instrs())) {
                if (!(i instanceof Instr.Phi p)) {
                    break;
                }
                Value v = absDiff(p, j, idom.get(j));
                if (v != null) {
                    replaced.put(p, v);
                }
            }
        }
        DeadCode.replaceUses(f, replaced);
        for (Instr p : replaced.keySet()) {
            p.block().remove(p);
        }
        return !replaced.isEmpty();
    }

    private static Value absDiff(Instr.Phi p, Block j, Block d) {
        if (d == null || p.from.size() != 2 || p.type() != MType.I16
                || !(d.terminator() instanceof Instr.Branch br) || br.ifTrue == br.ifFalse
                || !(br.op(0) instanceof Instr.Cmp c) || c.op(0).type() != MType.I16) {
            return null;
        }
        // a and b with "true means a >= b, false means a <= b"
        Value a;
        Value b;
        switch (c.pred) {
            case SGT, SGE -> {
                a = c.op(0);
                b = c.op(1);
            }
            case SLT, SLE -> {
                a = c.op(1);
                b = c.op(0);
            }
            default -> {
                return null;
            }
        }
        Value whenTrue = null;
        Value whenFalse = null;
        for (int k = 0; k < 2; k++) {
            Block from = p.from.get(k);
            boolean onTrue;
            if (from == d) {
                onTrue = br.ifTrue == j;
            } else if (from.preds().size() == 1 && from.preds().getFirst() == d && from.terminator() instanceof Instr.Jump
                    && onlyComputes(from, p.op(k))) {
                onTrue = br.ifTrue == from;
            } else {
                return null;   // not straight from the branch
            }
            if (onTrue) {
                whenTrue = p.op(k);
            } else {
                whenFalse = p.op(k);
            }
        }
        if (whenTrue == null || whenFalse == null || !difference(whenTrue, a, b) || !difference(whenFalse, b, a)) {
            return null;
        }
        Instr at = firstNonPhi(j);
        // |a - b| = |b - a|: a constant second, as the code generator wants it
        Instr v = a instanceof Value.Const ? new Instr.Bin(Instr.BinOp.ABDS, b, a, p.pos()) : new Instr.Bin(Instr.BinOp.ABDS, a, b, p.pos());
        j.insertBefore(at, v);
        return v;
    }

    /** The block has only its jump, or computes v (for the φ) and jumps. */
    private static boolean onlyComputes(Block b, Value v) {
        return b.instrs().size() == 1 || b.instrs().size() == 2 && b.instrs().getFirst() == v;
    }

    /** v is x - y (x itself where y is 0). */
    private static boolean difference(Value v, Value x, Value y) {
        if (y instanceof Value.Const z && z.bits() == 0 && v == x) {
            return true;
        }
        return v instanceof Instr.Bin s && s.op == Instr.BinOp.SUB && same(s.op(0), x) && same(s.op(1), y);
    }

    private static boolean same(Value a, Value b) {
        return a == b || a instanceof Value.Const k && b instanceof Value.Const l && k.type() == l.type() && k.bits() == l.bits();
    }

    private static Instr firstNonPhi(Block b) {
        for (Instr i : b.instrs()) {
            if (!(i instanceof Instr.Phi)) {
                return i;
            }
        }
        throw new IllegalStateException(b + " has no terminator");
    }
}
