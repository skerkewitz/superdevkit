package dampf.mir;

import java.util.ArrayList;
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
 * out, and {@link CountedLoops} bounds how often its head runs from the test there: a counter
 * that reaches its bound before it could wrap (from constants, or from the ranges of start and
 * bound), or, with {@code !=}, meets a constant bound exactly or every value of its type with
 * an odd step. Every way into the loop then
 * goes to the block the loop leaves to, with the values its φs took from the loop's exit, which
 * come from outside the loop.
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
            List<CountedLoops.Loop> loops = CountedLoops.find(f);
            for (CountedLoops.Loop loop : loops) {
                // a loop around another: the other one is not proved finite (it would have gone
                // first, innermost loops come first), so neither is this one
                boolean nested = false;
                for (CountedLoops.Loop other : loops) {
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

    private static boolean remove(MFunction f, CountedLoops.Loop loop, Map<Block, Block> idom) {
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
        // the one way out is the loop's only exit test: proved finite from constants, or else
        // from the ranges of its start and bound
        if (loop.exits().isEmpty() || loop.runs(CountedLoops.CONSTANTS) == CountedLoops.UNKNOWN
                && loop.runs(CountedLoops.of(new Ranges(f))) == CountedLoops.UNKNOWN) {
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
}
