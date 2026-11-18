package dampf.lir;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Where things lie in a function's code: byte offsets from its start, and the reach of short branches. */
public final class LLayout {
    private LLayout() {}

    /** The byte offset of every named label of the function. */
    public static Map<String, Integer> labelOffsets(LFunction f) {
        Map<String, Integer> at = new HashMap<>();
        int position = 0;
        for (LBlock b : f.blocks()) {
            for (LItem i : b.items()) {
                if (i instanceof LLabel l && !l.unnamed()) {
                    at.put(l.name(), position);
                } else if (i instanceof LInstr in) {
                    position += in.length();
                }
            }
        }
        return at;
    }

    /** The byte offset where each block starts. */
    public static Map<LBlock, Integer> blockOffsets(LFunction f) {
        Map<LBlock, Integer> at = new IdentityHashMap<>();
        int position = 0;
        for (LBlock b : f.blocks()) {
            at.put(b, position);
            for (LInstr in : b.instrs()) {
                position += in.length();
            }
        }
        return at;
    }

    /** Whether a short branch from {@code at} (its first byte) reaches {@code to} (-128..127 from the next instruction). */
    public static boolean reaches(int at, int to) {
        int d = to - (at + 2);
        return d >= -128 && d <= 127;
    }

    /** Whether every short branch (conditional, BRA) reaches its target in this function. */
    public static boolean shortBranchesReach(LFunction f) {
        Map<LBlock, Integer> start = blockOffsets(f);
        for (LBlock b : f.blocks()) {
            LInstr last = b.last();
            if (last == null || !last.isShortBranch() || last.target() == null) {
                continue;
            }
            LBlock target = b.target();
            if (target == null) {
                continue;   // a label outside this function (none in practice)
            }
            int at = start.get(b);
            for (LInstr in : b.instrs()) {
                if (in == last) {
                    break;
                }
                at += in.length();
            }
            if (!reaches(at, start.get(target))) {
                return false;
            }
        }
        return true;
    }
}
