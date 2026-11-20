package dampf.lir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes stores to a function's own value places that no path reads afterwards (byte by byte,
 * backwards over the blocks). The places are given as frame offsets by the code generator: the
 * values and parameters of the function and their φ places, which nothing but the function
 * itself reads (a callee's frame never overlaps its caller's, and a value has no address), and
 * which nothing reads after it returns. Stores to anything else (globals, slots, the parameters
 * of a callee, the runtime's registers) stay.
 *
 * <p>A direct access to a place reads or writes its two bytes (one with an 8-bit register); a
 * pointer kept in a place ({@code [place],y}) reads its three bytes; an indexed access with a
 * value place as base reads all of them (it does not happen).
 */
public final class DeadStores {
    private final BitSet own;

    /** {@code own}: the frame offsets of the bytes of the function's value places. */
    public DeadStores(BitSet own) {
        this.own = own;
    }

    /** Removes the dead stores; returns how many. */
    public int run(LFunction f) {
        Map<LBlock, BitSet> liveOut = liveOut(f);
        int removed = 0;
        for (LBlock b : f.blocks()) {
            BitSet live = (BitSet) liveOut.getOrDefault(b, new BitSet()).clone();
            List<LItem> items = b.items();
            for (int k = items.size() - 1; k >= 0; k--) {
                if (!(items.get(k) instanceof LInstr in)) {
                    continue;
                }
                BitSet def = def(in);
                if (def != null && !def.intersects(live)) {
                    items.remove(k);
                    removed++;
                    continue;
                }
                transfer(in, live);
            }
        }
        if (removed > 0) {
            f.rebuild(f.items());
        }
        return removed;
    }

    /** The bytes live at the end of each block. */
    private Map<LBlock, BitSet> liveOut(LFunction f) {
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            for (LBlock s : b.successors()) {
                preds.computeIfAbsent(s, k -> new ArrayList<>()).add(b);
            }
        }
        Map<LBlock, BitSet> in = new IdentityHashMap<>();
        Map<LBlock, BitSet> out = new IdentityHashMap<>();
        ArrayDeque<LBlock> work = new ArrayDeque<>(f.blocks().reversed());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            BitSet live = new BitSet();
            for (LBlock s : b.successors()) {
                BitSet si = in.get(s);
                if (si != null) {
                    live.or(si);
                }
            }
            out.put(b, (BitSet) live.clone());
            List<LInstr> code = b.instrs();
            for (int k = code.size() - 1; k >= 0; k--) {
                transfer(code.get(k), live);
            }
            if (!live.equals(in.get(b))) {
                in.put(b, live);
                for (LBlock p : preds.getOrDefault(b, List.of())) {
                    if (!work.contains(p)) {
                        work.add(p);
                    }
                }
            }
        }
        return out;
    }

    /** Live before i, from live after it. */
    private void transfer(LInstr i, BitSet live) {
        BitSet def = def(i);
        if (def != null) {
            live.andNot(def);
        }
        if (!(i.op() instanceof LOperand.Mem op) || !op.frame()) {
            return;   // no frame place (an immediate that names one is no access)
        }
        if (op.indirect()) {
            live.set(op.offset(), op.offset() + 3);   // through a pointer kept in a place: it reads the pointer
            return;
        }
        if (!reads(i.mnemonic())) {
            return;
        }
        int at = op.frameOffset();
        if (at < 0) {
            live.or(own);   // indexed: anything may be read
            return;
        }
        live.set(at, at + width(i));
    }

    /** The own bytes a store writes completely, or null if it is not such a store. */
    private BitSet def(LInstr i) {
        String m = i.mnemonic();
        if (!(m.equals("sta") || m.equals("stx") || m.equals("sty") || m.equals("stz")) || i.operand() == null) {
            return null;
        }
        int at = RegContents.frameOffset(i.op());
        if (at < 0) {
            return null;
        }
        int w = width(i);
        for (int k = at; k < at + w; k++) {
            if (!own.get(k)) {
                return null;
            }
        }
        BitSet d = new BitSet();
        d.set(at, at + w);
        return d;
    }

    /** Bytes a memory access of i touches: X and Y are 16 bit in dampf's code, A as the instruction runs. */
    private static int width(LInstr i) {
        return switch (i.mnemonic()) {
            case "ldx", "ldy", "stx", "sty", "cpx", "cpy" -> i.x16() ? 2 : 1;
            default -> i.m16() ? 2 : 1;
        };
    }

    /** Whether the instruction reads its memory operand. */
    private static boolean reads(String mnemonic) {
        return switch (mnemonic) {
            case "sta", "stx", "sty", "stz", "jmp", "jml", "jsl", "jsr" -> false;
            default -> true;
        };
    }
}
