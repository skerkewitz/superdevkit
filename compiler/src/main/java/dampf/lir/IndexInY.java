package dampf.lir;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which X-indexed accesses after an {@code LDX} could use Y instead, for the register choice: an
 * index that lies in Y is read with {@code LDA table,Y}.
 */
public final class IndexInY {
    /** The instructions with an absolute,Y form (not direct page, not long). */
    private static final Set<String> ABS_Y = Set.of("lda", "sta", "adc", "sbc", "and", "ora", "eor", "cmp");

    private IndexInY() {}

    /**
     * The X-indexed accesses after the LDX at k that can take Y instead, or null: all reads of X
     * until X is written are such accesses, Y stays, and X is not read after that (at the end of
     * the block: not live out).
     */
    public static List<Integer> convertible(List<LItem> items, int k, boolean liveOut) {
        List<Integer> uses = new ArrayList<>();
        boolean flagsSet = false;   // N and Z of the LDX are overwritten before anything reads them
        for (int j = k + 1; j < items.size(); j++) {
            if (!(items.get(j) instanceof LInstr in)) {
                continue;
            }
            int nz = RedundantLoads.N | RedundantLoads.Z;
            if (!flagsSet && (RedundantLoads.reads(in) & nz) != 0) {
                return null;
            }
            flagsSet |= (RedundantLoads.writes(in) & nz) == nz;
            if (readsX(in)) {
                // an absolute address indexed by X (no direct page or long form has a Y twin)
                if (!(in.op() instanceof LOperand.Mem m) || m.index() != LOperand.Index.X || m.indirect()
                        || m.width() == LOperand.Width.DP || m.width() == LOperand.Width.LONG || !ABS_Y.contains(in.mnemonic())) {
                    return null;
                }
                uses.add(j);
            }
            if (writesY(in)) {
                return null;
            }
            if (writesX(in)) {
                return uses.isEmpty() || !flagsSet ? null : uses;
            }
        }
        return liveOut || uses.isEmpty() || !flagsSet ? null : uses;
    }

    static boolean readsX(LInstr i) {
        if (i.op() instanceof LOperand.Mem m && m.index() == LOperand.Index.X
                || i.op() instanceof LOperand.Raw r && (r.text().endsWith(",x") || r.text().contains(",x)"))) {
            return true;
        }
        return switch (i.mnemonic()) {
            case "txa", "txy", "txs", "stx", "cpx", "inx", "dex", "phx", "rtl", "rts" -> true;
            default -> false;
        };
    }

    static boolean writesX(LInstr i) {
        return switch (i.mnemonic()) {
            case "ldx", "tax", "tyx", "tsx", "plx", "inx", "dex", "jsl", "jsr" -> true;
            default -> false;
        };
    }

    private static boolean writesY(LInstr i) {
        return switch (i.mnemonic()) {
            case "ldy", "tay", "txy", "ply", "iny", "dey", "jsl", "jsr" -> true;
            default -> false;
        };
    }
}
