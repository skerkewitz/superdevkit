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

    /**
     * Moves a store at the end of a block into the successors that read the place, where some
     * other successor does not (a store only a rare way reads): it runs only on those ways. The
     * store goes to the start of each such successor, which must have no other way in (where the
     * successor only jumps, the long form of a branch, to the block it jumps to, which must have
     * no other way in either). Between the store and the block's end nothing may
     * touch the place, change the stored register or the register widths. Returns how many moved.
     */
    public int sink(LFunction f) {
        int moved = 0;
        for (int round = 0; round < 8; round++) {
            int n = sinkOnce(f);
            if (n == 0) {
                break;
            }
            moved += n;
        }
        return moved;
    }

    private int sinkOnce(LFunction f) {
        Map<LBlock, BitSet> liveOut = liveOut(f);
        Map<LBlock, BitSet> liveIn = new IdentityHashMap<>();
        Map<LBlock, Integer> predCount = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            BitSet live = (BitSet) liveOut.getOrDefault(b, new BitSet()).clone();
            List<LInstr> code = b.instrs();
            for (int k = code.size() - 1; k >= 0; k--) {
                transfer(code.get(k), live);
            }
            liveIn.put(b, live);
            for (LBlock s : b.successors()) {
                predCount.merge(s, 1, Integer::sum);
            }
        }
        Map<LItem, List<LItem>> replace = new IdentityHashMap<>();
        int moved = 0;
        for (LBlock b : f.blocks()) {
            List<LBlock> succ = b.successors().stream().distinct().toList();
            if (succ.size() < 2) {
                continue;
            }
            List<LItem> items = b.items();
            for (int k = items.size() - 1; k >= 0; k--) {
                if (!(items.get(k) instanceof LInstr st)) {
                    continue;
                }
                BitSet def = def(st);
                if (def == null || !between(st, items, k)) {
                    continue;
                }
                List<LBlock> into = new ArrayList<>();
                boolean someDead = false;
                boolean ok = true;
                for (LBlock s : succ) {
                    if (!liveIn.get(s).intersects(def)) {
                        someDead = true;
                        continue;
                    }
                    // through the long form of a branch (a block that only jumps) to where it goes
                    LBlock t = s;
                    if (jumpOnly(t) && predCount.getOrDefault(t, 0) == 1 && t.successors().size() == 1) {
                        t = t.successors().getFirst();
                    }
                    LInstr first = t.instrs().isEmpty() ? null : t.instrs().getFirst();
                    if (predCount.getOrDefault(s, 0) != 1 || predCount.getOrDefault(t, 0) != 1 || first == null || jumpOnly(t)
                            || first.m16() != st.m16() || first.x16() != st.x16() || replace.containsKey(anchor(t))) {
                        ok = false;
                        break;
                    }
                    into.add(t);
                }
                if (!ok || !someDead) {
                    continue;
                }
                for (LBlock s : into) {
                    LItem a = anchor(s);
                    LInstr copy = new LInstr(st.mnemonic(), st.op(), st.comment(), st.m16(), st.x16(), false);
                    replace.put(a, a instanceof LLabel ? List.of(a, copy) : List.of(copy, a));
                }
                replace.put(st, List.of());
                moved++;
                break;   // one store per block and round: the liveness changed
            }
        }
        if (moved > 0) {
            List<LItem> out = new ArrayList<>();
            for (LItem it : f.items()) {
                out.addAll(replace.getOrDefault(it, List.of(it)));
            }
            f.rebuild(out);
        }
        return moved;
    }

    /** Where a store goes at the start of s: right after its label (comments before it belong above). */
    private static LItem anchor(LBlock s) {
        return s.label() != null ? s.label() : s.items().getFirst();
    }

    /** Whether s is only a jump (the long form of a branch, which BranchRelax folds back). */
    private static boolean jumpOnly(LBlock s) {
        List<LInstr> code = s.instrs();
        return code.size() == 1 && !code.getFirst().fallsThrough();
    }

    /**
     * Whether the instructions after the store at k, to the block's end, leave the place and the
     * stored register alone: compares, the carry, branches, and loads, stores and steps of the
     * other registers (not of the place, nor through an index or a pointer into the frame).
     */
    private static boolean between(LInstr st, List<LItem> items, int k) {
        String reg = switch (st.mnemonic()) {
            case "sta" -> "a";
            case "stx" -> "x";
            case "sty" -> "y";
            default -> "";
        };
        int at = RegContents.frameOffset(st.op());
        for (int j = k + 1; j < items.size(); j++) {
            if (!(items.get(j) instanceof LInstr i)) {
                if (items.get(j) instanceof LCopy || items.get(j) instanceof LMark) {
                    return false;
                }
                continue;
            }
            if (i.m16() != st.m16() || i.x16() != st.x16()) {
                return false;
            }
            if (i.op() instanceof LOperand.Mem m && m.frame()) {
                int o = m.frameOffset();
                if (o < 0 || Math.abs(o - at) <= 1) {
                    return false;
                }
            }
            String m = i.mnemonic();
            boolean allowed = switch (m) {
                case "cmp", "cpx", "cpy", "clc", "sec", "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bra", "brl",
                     "jmp" -> !(i.op() instanceof LOperand.Raw);
                case "ldx", "stx", "inx", "dex", "tax" -> !reg.equals("x");
                case "ldy", "sty", "iny", "dey", "tay" -> !reg.equals("y");
                case "stz" -> true;
                default -> false;
            };
            if (!allowed) {
                return false;
            }
        }
        return true;
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
