package dampf.mir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes loops that do nothing (catalogue H): no store, copy, call or volatile load inside, no
 * value of theirs used after them, and a proof that they end. What is left of a loop whose body
 * went away, most often the calls of the debug view's empty functions on the SNES
 * (sdevkit_dbg_box in a loop over the actors, ADR record 32), or a loop whose results nobody reads.
 *
 * <p>gcc keeps a C loop that might not end, so dampf removes only those it can prove finite: one way
 * out, a branch in a block every round passes, on a counter compared with a constant, the counter a
 * φ of the head that every way back steps by the same constant c (or the stepped value itself):
 * <ul>
 *   <li>stays while the counter is not N: c odd, so the counter meets every value of its width;</li>
 *   <li>stays while the counter is below N (unsigned or signed), c &gt; 0: the counter reaches N
 *       before it could wrap, N + c - 1 fits the type (N + c for "at most N");</li>
 *   <li>stays while the counter is above N, c &lt; 0: the same downwards.</li>
 * </ul>
 * Every way into the loop then goes to the block the loop leaves to, with the values its φs took
 * from the loop's exit, which come from outside the loop.
 */
public final class DeadLoops {
    private DeadLoops() {}

    /** Returns whether a loop went. */
    public static boolean run(MFunction f) {
        boolean changed = false;
        boolean again = true;
        while (again) {
            again = false;
            Map<Block, Block> idom = Cfg.idoms(f);
            List<Loops.Loop> loops = Loops.find(f);
            for (Loops.Loop loop : loops) {
                // a loop around another: the other one is not proved finite (it would have gone
                // first, innermost loops come first), so neither is this one
                boolean nested = false;
                for (Loops.Loop other : loops) {
                    nested |= other != loop && loop.blocks().contains(other.head());
                }
                if (!nested && remove(f, loop, idom)) {
                    changed = true;
                    again = true;
                    break;     // the loops and dominators changed: find them again
                }
            }
        }
        return changed;
    }

    private static boolean remove(MFunction f, Loops.Loop loop, Map<Block, Block> idom) {
        Set<Block> in = loop.blocks();
        // nothing with an effect, and only jumps and branches
        for (Block b : in) {
            for (Instr i : b.instrs()) {
                if (i.isTerminator() ? !(i instanceof Instr.Jump || i instanceof Instr.Branch) : DeadCode.hasEffect(i)) {
                    return false;
                }
            }
        }
        // no value of the loop used outside it
        for (Block b : f.blocks()) {
            if (in.contains(b)) {
                continue;
            }
            for (Instr i : b.instrs()) {
                for (Value v : i.operands()) {
                    if (v instanceof Instr d && d.block() != null && in.contains(d.block())) {
                        return false;
                    }
                }
            }
        }
        // one way out
        Block exit = null;
        Block to = null;
        for (Block b : in) {
            for (Block s : b.terminator().successors()) {
                if (!in.contains(s)) {
                    if (exit != null) {
                        return false;
                    }
                    exit = b;
                    to = s;
                }
            }
        }
        if (exit == null || !(exit.terminator() instanceof Instr.Branch br) || br.ifTrue == br.ifFalse) {
            return false;
        }
        for (Block latch : loop.latches()) {
            if (!Loops.dominates(exit, latch, idom)) {
                return false;
            }
        }
        if (!(br.op(0) instanceof Instr.Cmp cmp) || !ends(cmp, br.ifTrue != to, loop)) {
            return false;
        }
        // the ways in go to where the loop goes out
        List<Block> outside = new ArrayList<>();
        for (Block p : loop.head().preds()) {
            if (!in.contains(p) && !outside.contains(p)) {
                outside.add(p);
            }
        }
        if (outside.isEmpty()) {
            return false;
        }
        List<Instr.Phi> phis = new ArrayList<>();
        for (Instr i : to.instrs()) {
            if (i instanceof Instr.Phi p) {
                phis.add(p);
            }
        }
        List<Value> fromExit = new ArrayList<>();
        for (Instr.Phi p : phis) {
            fromExit.add(p.operands().get(p.from.indexOf(exit)));
        }
        for (Block p : outside) {
            int edges = Cfg.retarget(p, loop.head(), to);
            if (edges == 0) {
                return false;   // cannot happen for jumps and branches; nothing has changed yet then
            }
            for (int e = 0; e < edges; e++) {
                for (int k = 0; k < phis.size(); k++) {
                    phis.get(k).add(p, fromExit.get(k));
                }
            }
        }
        Cfg.removeUnreachable(f);
        return true;
    }

    /**
     * Whether the loop ends: it stays while {@code cmp} is {@code staysIfTrue}, and cmp compares a
     * counter of the head (or the counter after its step) with a constant.
     */
    private static boolean ends(Instr.Cmp cmp, boolean staysIfTrue, Loops.Loop loop) {
        Instr.Pred pred = staysIfTrue ? cmp.pred : negate(cmp.pred);
        Value a = cmp.op(0);
        Value b = cmp.op(1);
        if (a instanceof Value.Const) {
            Value t = a;
            a = b;
            b = t;
            pred = swap(pred);
        }
        if (!(b instanceof Value.Const n) || !(a instanceof Instr counter)) {
            return false;
        }
        Long step = step(counter, loop);
        if (step == null || step == 0) {
            return false;
        }
        MType type = counter.type();
        int w = type.bits();
        if (w <= 1 || w > 32) {
            return false;
        }
        long umax = (1L << w) - 1;
        long smax = (1L << (w - 1)) - 1;
        long smin = -(1L << (w - 1));
        long c = step;
        long un = n.bits() & umax;
        long sn = type.signed(n.bits());
        return switch (pred) {
            case NE -> (c & 1) != 0;
            case ULT -> c > 0 && un + c - 1 <= umax;
            case ULE -> c > 0 && un + c <= umax;
            case UGT -> c < 0 && un - (-c) + 1 >= 0;
            case UGE -> c < 0 && un - (-c) >= 0;
            case SLT -> c > 0 && sn + c - 1 <= smax;
            case SLE -> c > 0 && sn + c <= smax;
            case SGT -> c < 0 && sn + c + 1 >= smin;
            case SGE -> c < 0 && sn + c >= smin;
            default -> false;   // EQ: stays while the counter equals N, which a step leaves at once or never
        };
    }

    /**
     * The constant every way back steps the counter by: the counter is a φ of the head whose value
     * from each latch is it plus (or minus) one constant, or such a stepped value of that φ.
     */
    private static Long step(Instr counter, Loops.Loop loop) {
        Instr.Phi phi;
        if (counter instanceof Instr.Phi p && p.block() == loop.head()) {
            phi = p;
        } else if (counter instanceof Instr.Bin s && s.op(0) instanceof Instr.Phi p && p.block() == loop.head()) {
            phi = p;
        } else {
            return null;
        }
        Long step = null;
        Set<Block> latches = new HashSet<>(loop.latches());
        for (int k = 0; k < phi.from.size(); k++) {
            if (!latches.contains(phi.from.get(k))) {
                continue;
            }
            Long c = constantStep(phi, phi.operands().get(k));
            if (c == null || (step != null && !step.equals(c))) {
                return null;
            }
            step = c;
        }
        if (step == null) {
            return null;
        }
        // the stepped value compared instead of the φ: the same step
        if (counter != phi && !step.equals(constantStep(phi, counter))) {
            return null;
        }
        return step;
    }

    /** c if v is phi + c or phi - c (c a constant), else null. */
    private static Long constantStep(Instr.Phi phi, Value v) {
        if (!(v instanceof Instr.Bin b) || b.op(0) != phi || !(b.op(1) instanceof Value.Const k)) {
            return null;
        }
        long c = phi.type().signed(k.bits());
        return switch (b.op) {
            case ADD -> c;
            case SUB -> -c;
            default -> null;
        };
    }

    private static Instr.Pred negate(Instr.Pred p) {
        return switch (p) {
            case EQ -> Instr.Pred.NE;
            case NE -> Instr.Pred.EQ;
            case ULT -> Instr.Pred.UGE;
            case ULE -> Instr.Pred.UGT;
            case UGT -> Instr.Pred.ULE;
            case UGE -> Instr.Pred.ULT;
            case SLT -> Instr.Pred.SGE;
            case SLE -> Instr.Pred.SGT;
            case SGT -> Instr.Pred.SLE;
            case SGE -> Instr.Pred.SLT;
        };
    }

    /** The predicate with its operands swapped: a &lt; b is b &gt; a. */
    private static Instr.Pred swap(Instr.Pred p) {
        return switch (p) {
            case EQ, NE -> p;
            case ULT -> Instr.Pred.UGT;
            case ULE -> Instr.Pred.UGE;
            case UGT -> Instr.Pred.ULT;
            case UGE -> Instr.Pred.ULE;
            case SLT -> Instr.Pred.SGT;
            case SLE -> Instr.Pred.SGE;
            case SGT -> Instr.Pred.SLT;
            case SGE -> Instr.Pred.SLE;
        };
    }
}
