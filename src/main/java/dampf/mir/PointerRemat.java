package dampf.mir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pointer sums computed again where their value is needed, so that the code generator need not
 * compute them where they are defined. {@code Item *it = &g_items[i]} in a loop is a sum of a
 * global and an index; its loads and stores ({@code it->x}) are addressed with the index in X or
 * Y, and the sum itself is never computed ("folded", {@code FrameLayout}). But one call that takes
 * {@code &it->x}, on a rare way through the loop, made the code generator compute the whole
 * pointer (and store its bank word) in every round. Here such a use gets its own copy of the sum,
 * just before it; the sum where it is defined then only addresses loads and stores.
 *
 * <p>A sum qualifies when it is a chain of {@code ptradd}s on a global or a frame slot, no use of
 * its value is a φ or lies in a loop that the definition does not (the copy never runs more often
 * than the original would), and a base of the chain lies in another block than a use (else
 * nothing is won). Each use gets a copy of the whole chain; the original and the bases that
 * nothing else uses go. Run just before code generation (GVN would merge the copies again).
 */
public final class PointerRemat {
    private PointerRemat() {}

    /** Returns whether anything changed. */
    public static boolean run(MFunction f) {
        Map<Block, Set<Loops.Loop>> loopsOf = new HashMap<>();
        for (Loops.Loop l : Loops.find(f)) {
            for (Block b : l.blocks()) {
                loopsOf.computeIfAbsent(b, k -> new HashSet<>()).add(l);
            }
        }
        // the uses of every instruction
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
        boolean changed = false;
        for (Block b : f.blocks()) {
            for (Instr i : List.copyOf(b.instrs())) {
                if (!(i instanceof Instr.PtrAdd p) || p.block() == null || !chain(p)) {
                    continue;   // (removed as an unused base of an earlier chain)
                }
                List<Instr> valueUses = new ArrayList<>();
                boolean ok = true;
                for (Instr u : users.getOrDefault(p, List.of())) {
                    if (addressUse(u, p)) {
                        continue;
                    }
                    Set<Loops.Loop> at = loopsOf.getOrDefault(u.block(), Set.of());
                    if (u instanceof Instr.Phi || !loopsOf.getOrDefault(b, Set.of()).containsAll(at)) {
                        ok = false;
                        break;
                    }
                    valueUses.add(u);
                }
                // worth it only if a base of the chain lies in another block than the uses: its
                // computation leaves that (more frequent) block
                if (!ok || valueUses.isEmpty() || !baseElsewhere(p, valueUses)) {
                    continue;
                }
                for (Instr u : valueUses) {
                    Instr.PtrAdd copy = copy(p, u);
                    for (int k = 0; k < u.operands().size(); k++) {
                        if (u.op(k) == p) {
                            u.setOp(k, copy);
                        }
                    }
                }
                // the original is unused now: it goes, and so do the bases nothing else uses
                Instr.PtrAdd gone = p;
                while (gone != null && unused(f, gone)) {
                    gone.block().remove(gone);
                    gone = gone.op(0) instanceof Instr.PtrAdd q ? q : null;
                }
                changed = true;
            }
        }
        return changed;
    }

    private static boolean baseElsewhere(Instr.PtrAdd p, List<Instr> uses) {
        for (Instr u : uses) {
            Value base = p;
            while (base instanceof Instr.PtrAdd q) {
                if (q.block() != u.block()) {
                    return true;
                }
                base = q.op(0);
            }
        }
        return false;
    }

    private static boolean unused(MFunction f, Instr i) {
        for (Block b : f.blocks()) {
            for (Instr u : b.instrs()) {
                if (u.operands().contains(i)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A chain of sums on a global or a frame slot. */
    private static boolean chain(Instr.PtrAdd p) {
        Value base = p.op(0);
        while (base instanceof Instr.PtrAdd q) {
            base = q.op(0);
        }
        return base instanceof Value.GlobalAddr || base instanceof Value.SlotAddr;
    }

    /** u uses p only as an address: of a load or store, or as the base of another sum. */
    private static boolean addressUse(Instr u, Instr p) {
        for (int k = 0; k < u.operands().size(); k++) {
            if (u.op(k) != p) {
                continue;
            }
            boolean address = k == 0 && (u instanceof Instr.Load || u instanceof Instr.Store || u instanceof Instr.PtrAdd);
            if (!address) {
                return false;
            }
        }
        return true;
    }

    /** A copy of the chain p, inserted just before u. */
    private static Instr.PtrAdd copy(Instr.PtrAdd p, Instr u) {
        Value base = p.op(0) instanceof Instr.PtrAdd q ? copy(q, u) : p.op(0);
        Instr.PtrAdd c = new Instr.PtrAdd(base, p.op(1), p.pos());
        u.block().insertBefore(u, c);
        return c;
    }
}
