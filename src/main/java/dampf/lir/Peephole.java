package dampf.lir;

import java.util.List;

/**
 * Peephole rules: a few instructions in a row replaced by cheaper ones that do the same. Each rule
 * looks only at neighbouring instructions in one block (comments between them do not matter).
 *
 * <ul>
 * <li>{@code STA p / LDX p} becomes {@code STA p / TAX} (and {@code LDY} becomes {@code TAY}): X
 *     gets the value A just stored, 2 cycles instead of 4 (3 in the direct page), with the same N
 *     and Z. Only with a 16-bit accumulator and index registers: with an 8-bit A, {@code TAX} would
 *     copy the hidden high byte, where {@code LDX} reads the byte after p. If nothing reads p
 *     afterwards, {@link DeadStores} then removes the store.</li>
 * </ul>
 */
public final class Peephole {
    private Peephole() {}

    /** Applies the rules; returns how many instructions changed. */
    public static int run(LFunction f) {
        int changed = 0;
        for (LBlock b : f.blocks()) {
            List<LItem> items = b.items();
            LInstr prev = null;
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof LInstr in)) {
                    continue;
                }
                if (prev != null && prev.mnemonic().equals("sta") && prev.m16() && in.x16() && in.m16()
                        && (in.mnemonic().equals("ldx") || in.mnemonic().equals("ldy"))
                        && in.operand() != null && in.operand().equals(prev.operand()) && direct(in.operand())) {
                    LInstr t = new LInstr(in.mnemonic().equals("ldx") ? "tax" : "tay", null, in.comment(), true, true, false);
                    items.set(k, t);
                    in = t;
                    changed++;
                }
                prev = in;
            }
        }
        if (changed > 0) {
            f.rebuild(f.items());
        }
        return changed;
    }

    /** A plain memory operand (not indexed, not indirect, not an immediate). */
    private static boolean direct(String operand) {
        return !operand.startsWith("#") && !operand.startsWith("[") && !operand.startsWith("(") && !operand.contains(",");
    }
}
