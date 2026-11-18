package dampf.lir;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Peephole rules: a few instructions in a row replaced by cheaper ones that do the same. Each rule
 * looks only at neighbouring instructions in one block (comments, C line headers and debug
 * directives between them do not matter), and
 * only at code with a 16-bit accumulator unless it says otherwise.
 *
 * <ul>
 * <li>{@code STA p / LDX p} becomes {@code STA p / TAX} (and {@code LDY} becomes {@code TAY}): X
 *     gets the value A just stored, 2 cycles instead of 4 (3 in the direct page), with the same N
 *     and Z. Not with an 8-bit A: {@code TAX} would copy the hidden high byte, where {@code LDX}
 *     reads the byte after p. If nothing reads p afterwards, {@link DeadStores} removes the store.</li>
 * <li>k × {@code LSR A} followed by m × {@code ASL A}, m &gt; k: {@code (x >> k) << m} is x with
 *     its low k bits cleared, shifted by m - k: {@code AND #mask} and m - k × {@code ASL A}. N, Z
 *     and the carry (from the last shift) are the same.</li>
 * <li>{@code REP #$20 / SEP #$20} where A was 8 bit before: both go (they change only M).</li>
 * <li>{@code LDA x / XBA / AND #$00FF}, the high byte of the word at x, is the byte at x + 1:
 *     {@code LDA x+1 / AND #$00FF}. For a plain or X- or Y-indexed operand; a place in the
 *     direct page stays there only if x + 1 is still a whole word of it.</li>
 * </ul>
 */
public final class Peephole {
    private static final Pattern OPERAND = Pattern.compile("^(z:|a:|f:)?([A-Za-z_][A-Za-z0-9_]*)(?:\\+(\\d+))?(,x|,y)?$");

    private final String frame;
    private final int frameStart;

    /** {@code frame}: the symbol of the frame places, which starts at {@code frameStart} (for the direct page). */
    public Peephole(String frame, int frameStart) {
        this.frame = frame;
        this.frameStart = frameStart;
    }

    /** Applies the rules; returns how many places changed. */
    public int run(LFunction f) {
        int changed = 0;
        for (LBlock b : f.blocks()) {
            List<LItem> items = b.items();
            for (int k = 0; k < items.size(); k++) {
                if (items.get(k) instanceof LInstr) {
                    changed += apply(items, k);
                }
            }
        }
        if (changed > 0) {
            f.rebuild(f.items());
        }
        return changed;
    }

    /** The rules at the instruction at k; returns 1 if one applied. */
    private int apply(List<LItem> items, int k) {
        LInstr in = (LInstr) items.get(k);
        List<Integer> next = nextInstrs(items, k, 3);
        LInstr n1 = next.isEmpty() ? null : (LInstr) items.get(next.get(0));
        // STA p / LDX p
        if (n1 != null && in.mnemonic().equals("sta") && in.m16() && n1.x16() && n1.m16()
                && (n1.mnemonic().equals("ldx") || n1.mnemonic().equals("ldy"))
                && n1.operand() != null && n1.operand().equals(in.operand()) && plain(in.operand())) {
            items.set(next.get(0), new LInstr(n1.mnemonic().equals("ldx") ? "tax" : "tay", null, n1.comment(), true, true, false));
            return 1;
        }
        // REP #$20 / SEP #$20 with 8-bit A before
        if (n1 != null && isWidth(in, "rep") && !in.m16() && isWidth(n1, "sep")) {
            items.remove((int) next.get(0));
            items.remove(k);
            return 1;
        }
        // LDA x / XBA / AND #$00FF
        if (next.size() >= 2 && in.mnemonic().equals("lda") && in.m16() && n1.mnemonic().equals("xba")
                && items.get(next.get(1)) instanceof LInstr n2 && n2.mnemonic().equals("and")
                && "#$00FF".equals(n2.operand()) && n2.m16()) {
            String high = plusOne(in.operand());
            if (high != null) {
                items.remove((int) next.get(0));
                items.set(k, in.withOperand(high));
                return 1;
            }
        }
        // LSR A × k, ASL A × m, m > k
        if (isShift(in, "lsr")) {
            List<Integer> run = new ArrayList<>();
            int j = k;
            while (j >= 0 && items.get(j) instanceof LInstr s && isShift(s, "lsr")) {
                run.add(j);
                j = nextInstr(items, j);
            }
            int lsr = run.size();
            int asl = 0;
            while (j >= 0 && items.get(j) instanceof LInstr s && isShift(s, "asl")) {
                run.add(j);
                asl++;
                j = nextInstr(items, j);
            }
            if (asl > lsr) {
                long mask = 0xFFFF & ~((1L << lsr) - 1);
                LInstr and = new LInstr("and", String.format("#$%04X", mask), in.comment(), true, in.x16(), false);
                // the first instruction becomes AND, the next lsr are dropped, asl - lsr shifts stay
                List<Integer> drop = run.subList(1, 2 * lsr);
                items.set(k, and);
                for (int d = drop.size() - 1; d >= 0; d--) {
                    items.remove((int) drop.get(d));
                }
                return 1;
            }
        }
        return 0;
    }

    private static boolean isShift(LInstr i, String m) {
        return i.mnemonic().equals(m) && "a".equals(i.operand()) && i.m16();
    }

    private static boolean isWidth(LInstr i, String m) {
        return i.mnemonic().equals(m) && "#$20".equals(i.operand());
    }

    /** The operand of the byte after x, or null if it cannot be written. */
    private String plusOne(String x) {
        if (x == null) {
            return null;
        }
        Matcher m = OPERAND.matcher(x);
        if (!m.matches()) {
            return null;
        }
        String prefix = m.group(1) == null ? "" : m.group(1);
        String base = m.group(2);
        long offset = (m.group(3) == null ? 0 : Long.parseLong(m.group(3))) + 1;
        String index = m.group(4) == null ? "" : m.group(4);
        if (prefix.equals("z:")) {
            if (!base.equals(frame) || !index.isEmpty()) {
                return null;   // only the frame's direct page places are known to have room
            }
            prefix = frameStart + offset + 1 < 0x100 ? "z:" : "a:";
        }
        return prefix + base + "+" + offset + index;
    }

    /** A plain memory operand (not indexed, not indirect, not an immediate). */
    private static boolean plain(String operand) {
        return operand != null && !operand.startsWith("#") && !operand.startsWith("[") && !operand.startsWith("(")
                && !operand.contains(",");
    }

    /** The index of the next instruction after k in the list (comments skipped), -1 at a label or the end. */
    private static int nextInstr(List<LItem> items, int k) {
        for (int j = k + 1; j < items.size(); j++) {
            if (items.get(j) instanceof LInstr) {
                return j;
            }
            if (items.get(j) instanceof LLabel) {
                return -1;
            }
        }
        return -1;
    }

    private static List<Integer> nextInstrs(List<LItem> items, int k, int n) {
        List<Integer> out = new ArrayList<>();
        int j = k;
        while (out.size() < n && (j = nextInstr(items, j)) >= 0) {
            out.add(j);
        }
        return out;
    }
}
