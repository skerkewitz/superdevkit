package dampf.lir;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes {@code CLC} where the carry is clear on every way there, and {@code SEC} where it is
 * set: a data flow analysis of the carry over the blocks. A branch on the carry tells it on both
 * ways ({@code BCC} taken: clear, not taken: set); {@code CLC} and {@code SEC} set it; arithmetic,
 * compares, shifts, calls and anything not known here make it unknown; everything else keeps it.
 * The typical case: {@code CMP #n / BCC body}, and in the body {@code CLC / ADC}.
 */
public final class KnownCarry {
    private static final int UNKNOWN = -1;

    private KnownCarry() {}

    /** Removes the needless CLC and SEC; returns how many. */
    public static int run(LFunction f) {
        if (f.blocks().isEmpty()) {
            return 0;
        }
        Map<LBlock, Integer> in = new IdentityHashMap<>();
        ArrayDeque<LBlock> work = new ArrayDeque<>();
        in.put(f.blocks().getFirst(), UNKNOWN);
        work.add(f.blocks().getFirst());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            int c = in.get(b);
            for (LInstr i : b.instrs()) {
                c = after(i, c);
            }
            for (LBlock s : b.successors()) {
                int edge = onEdge(b, s, c);
                Integer was = in.get(s);
                int now = was == null ? edge : was == edge ? was : UNKNOWN;
                if (was == null || now != was) {
                    in.put(s, now);
                    if (!work.contains(s)) {
                        work.add(s);
                    }
                }
            }
        }
        int removed = 0;
        for (LBlock b : f.blocks()) {
            Integer start = in.get(b);
            if (start == null) {
                continue;
            }
            int c = start;
            List<LItem> items = b.items();
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof LInstr i)) {
                    continue;
                }
                if (i.mnemonic().equals("clc") && c == 0 || i.mnemonic().equals("sec") && c == 1) {
                    items.remove(k--);
                    removed++;
                    continue;
                }
                c = after(i, c);
            }
        }
        if (removed > 0) {
            f.rebuild(f.items());
        }
        return removed;
    }

    /** The carry on the edge from b to s, from the carry at b's end. */
    private static int onEdge(LBlock b, LBlock s, int c) {
        LInstr last = b.last();
        if (last == null || b.target() == null || b.target() == b.fallThrough()) {
            return c;
        }
        boolean taken = s == b.target();
        return switch (last.mnemonic()) {
            case "bcc" -> taken ? 0 : 1;
            case "bcs" -> taken ? 1 : 0;
            default -> c;
        };
    }

    /** The carry after i. */
    static int after(LInstr i, int c) {
        return switch (i.mnemonic()) {
            case "clc" -> 0;
            case "sec" -> 1;
            // keep the carry
            case "lda", "ldx", "ldy", "sta", "stx", "sty", "stz", "and", "ora", "eor", "bit", "inc", "dec", "inx",
                 "iny", "dex", "dey", "tax", "tay", "txa", "tya", "txy", "tyx", "tsx", "txs", "tcd", "tdc", "xba",
                 "pha", "phx", "phy", "pla", "plx", "ply", "trb", "tsb", "nop", "bcc", "bcs", "beq", "bne", "bmi",
                 "bpl", "bvc", "bvs", "bra", "brl", "jmp", "jml", "clv" -> c;
            // REP/SEP change the carry only with bit 0
            case "rep", "sep" -> i.op() instanceof LOperand.Imm k && k.value() != null && (k.value() & 1) == 0 ? c : UNKNOWN;
            default -> UNKNOWN;   // adc, sbc, cmp, cpx, cpy, shifts, calls, anything else
        };
    }
}
