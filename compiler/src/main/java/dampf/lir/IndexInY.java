package dampf.lir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uses Y for an index it holds already: {@code LDX p / LDA table,X} becomes {@code LDA table,Y}
 * where Y holds p on every way in ({@link RegContents}), every read of X until it is written again
 * is such an access, Y does not change in between, and nothing reads X afterwards. The code
 * generator chooses X or Y while it writes a block, without knowing what Y holds where the block
 * starts.
 */
public final class IndexInY {
    /** The instructions with an absolute,Y form (not direct page, not long). */
    private static final Set<String> ABS_Y = Set.of("lda", "sta", "adc", "sbc", "and", "ora", "eor", "cmp");

    private final RegContents contents;

    public IndexInY() {
        this.contents = new RegContents();
    }

    /** Returns how many loads of X went. */
    public int run(LFunction f) {
        Map<LBlock, RegContents.State> entries = contents.entries(f);
        Map<LBlock, Boolean> xLiveOut = xLiveOut(f);
        int changed = 0;
        for (LBlock b : f.blocks()) {
            RegContents.State s = entries.get(b);
            if (s == null) {
                continue;
            }
            List<LItem> items = b.items();
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof LInstr in)) {
                    continue;
                }
                if (in.mnemonic().equals("ldx") && in.op() != null && in.op().equals(s.y())) {
                    List<Integer> uses = convertible(items, k, xLiveOut.getOrDefault(b, true));
                    if (uses != null) {
                        for (int u : uses) {
                            LInstr use = (LInstr) items.get(u);
                            LOperand.Mem m = (LOperand.Mem) use.op();
                            items.set(u, use.withOperand(new LOperand.Mem(m.width(), m.base(), m.offset(), LOperand.Index.Y, false).text()));
                        }
                        items.remove(k--);
                        changed++;
                        continue;
                    }
                }
                s = contents.after(in, s);
            }
        }
        if (changed > 0) {
            f.rebuild(f.items());
        }
        return changed;
    }

    /**
     * The X-indexed accesses after the LDX at k that can take Y instead, or null: all reads of X
     * until X is written are such accesses, Y stays, and X is not read after that (at the end of
     * the block: not live out).
     */
    private static List<Integer> convertible(List<LItem> items, int k, boolean liveOut) {
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

    /** Whether X is read before it is written, after the end of each block (true where unsure). */
    private static Map<LBlock, Boolean> xLiveOut(LFunction f) {
        Map<LBlock, List<LBlock>> preds = new IdentityHashMap<>();
        for (LBlock b : f.blocks()) {
            for (LBlock s : b.successors()) {
                preds.computeIfAbsent(s, x -> new ArrayList<>()).add(b);
            }
        }
        Map<LBlock, Boolean> in = new IdentityHashMap<>();
        Map<LBlock, Boolean> out = new IdentityHashMap<>();
        ArrayDeque<LBlock> work = new ArrayDeque<>(f.blocks().reversed());
        while (!work.isEmpty()) {
            LBlock b = work.removeFirst();
            boolean live = false;
            for (LBlock s : b.successors()) {
                live |= in.getOrDefault(s, false);
            }
            LInstr last = b.last();
            if (last != null && last.isJump() && b.target() == null) {
                live = true;   // a jump out of the function
            }
            out.put(b, live);
            List<LInstr> code = b.instrs();
            for (int k = code.size() - 1; k >= 0; k--) {
                LInstr i = code.get(k);
                if (writesX(i) && !i.mnemonic().equals("inx") && !i.mnemonic().equals("dex")) {
                    live = false;
                }
                if (readsX(i)) {
                    live = true;
                }
            }
            Boolean was = in.get(b);
            if (was == null || was != live) {
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
}
