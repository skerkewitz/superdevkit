package dampf.mir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A choice between one bit and 0 on a single bit: {@code (x & M) ? K : 0} with M and K powers of
 * two is {@code x & M}, shifted from M's place to K's ({@code (x & M) ? 0 : K} the same with the
 * bit inverted) ({@code (attr & FLIP_Y) ? 0x80 : 0} in the
 * game's sprite code). In MIR it is a branch on {@code cmp ne (and x, M), 0} and a φ of K and 0
 * where the two ways meet; the φ becomes the masked bit, and the branch, now without a reason,
 * goes away in {@link Simplify}.
 */
public final class BitSelect {
    private BitSelect() {}

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        Map<Block, Block> idom = Cfg.idoms(f);
        Map<Instr, Value> replaced = new HashMap<>();
        for (Block j : f.blocks()) {
            for (Instr i : List.copyOf(j.instrs())) {
                if (!(i instanceof Instr.Phi p)) {
                    break;
                }
                Value v = select(p, j, idom.get(j));
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

    private static Value select(Instr.Phi p, Block j, Block d) {
        if (d == null || p.from.size() != 2 || !Ranges.isInt(p.type()) || p.type() == MType.I1
                || !(d.terminator() instanceof Instr.Branch br) || br.ifTrue == br.ifFalse
                || !(br.op(0) instanceof Instr.Cmp c) || c.pred != Instr.Pred.EQ && c.pred != Instr.Pred.NE
                || !(c.op(1) instanceof Value.Const z) || z.bits() != 0
                || !(c.op(0) instanceof Instr.Bin m) || m.op != Instr.BinOp.AND
                || !(m.op(1) instanceof Value.Const mask) || Long.bitCount(mask.bits()) != 1) {
            return null;
        }
        // which incoming edge is the way "the bit is set"
        Block set = c.pred == Instr.Pred.NE ? br.ifTrue : br.ifFalse;
        Block clear = c.pred == Instr.Pred.NE ? br.ifFalse : br.ifTrue;
        Value whenSet = null;
        Value whenClear = null;
        for (int k = 0; k < 2; k++) {
            Block from = p.from.get(k);
            Block arm = from == d ? (br.ifTrue == j ? br.ifTrue : br.ifFalse) : from;
            if (from != d && !(from.preds().size() == 1 && from.preds().getFirst() == d && from.instrs().size() == 1
                    && from.terminator() instanceof Instr.Jump)) {
                return null;   // not straight from the branch
            }
            boolean isSet = from == d ? (set == j) : (arm == set);
            boolean isClear = from == d ? (clear == j) : (arm == clear);
            if (isSet == isClear) {
                return null;
            }
            if (isSet) {
                whenSet = p.op(k);
            } else {
                whenClear = p.op(k);
            }
        }
        // K when the bit is set and 0 when not, or the other way round (then the bit inverted)
        boolean inverted;
        Value.Const k;
        if (whenSet instanceof Value.Const ks && Long.bitCount(ks.bits()) == 1
                && whenClear instanceof Value.Const zero && zero.bits() == 0) {
            k = ks;
            inverted = false;
        } else if (whenClear instanceof Value.Const kc && Long.bitCount(kc.bits()) == 1
                && whenSet instanceof Value.Const zero && zero.bits() == 0) {
            k = kc;
            inverted = true;
        } else {
            return null;
        }
        int from = Long.numberOfTrailingZeros(mask.bits());
        int to = Long.numberOfTrailingZeros(k.bits());
        MType tm = m.type();
        MType tp = p.type();
        MType wide = tm.bits() >= tp.bits() ? tm : tp;
        Instr at = firstNonPhi(j);
        Value v = m;
        if (inverted) {
            v = insert(j, at, new Instr.Bin(Instr.BinOp.XOR, v, mask, p.pos()));
        }
        if (wide != tm) {
            v = insert(j, at, new Instr.Cast(Instr.CastKind.ZEXT, v, wide, p.pos()));
        }
        if (to > from) {
            v = insert(j, at, new Instr.Bin(Instr.BinOp.SHL, v, new Value.Const(wide, to - from), p.pos()));
        } else if (to < from) {
            v = insert(j, at, new Instr.Bin(Instr.BinOp.LSHR, v, new Value.Const(wide, from - to), p.pos()));
        }
        if (wide != tp) {
            v = insert(j, at, new Instr.Cast(Instr.CastKind.TRUNC, v, tp, p.pos()));
        }
        return v;
    }

    private static Instr firstNonPhi(Block b) {
        for (Instr i : b.instrs()) {
            if (!(i instanceof Instr.Phi)) {
                return i;
            }
        }
        throw new IllegalStateException(b + " has no terminator");
    }

    private static Instr insert(Block b, Instr at, Instr i) {
        b.insertBefore(at, i);
        return i;
    }
}
