package dampf.lir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Branch relaxation: the code generator writes every jump and conditional branch between blocks
 * in its long form, marked {@link LInstr#relaxable}; this pass gives them the short form where it
 * reaches. It starts from the long forms and shortens, in rounds, every marked branch that
 * reaches its target in the code of the round before, until nothing changes; shorter code only
 * brings targets closer, so what reached once still reaches.
 *
 * <p>The forms, with {@code @s} a local label only the branch refers to:
 * <ul>
 * <li>{@code JMP T} becomes {@code BRA T};
 * <li>{@code Bc @s / JMP T / @s:} becomes {@code B!c T};
 * <li>{@code Bc @s / ... / @s: JMP T}, where nothing falls into {@code @s}, becomes
 *     {@code Bc T / ...}, and the jump after {@code @s} goes.
 * </ul>
 */
public final class BranchRelax {
    private BranchRelax() {}

    public static void run(LFunction f) {
        while (round(f)) {
            // again, on the shorter code
        }
    }

    /** One round: every marked branch that reaches in the code as it is gets its short form. */
    private static boolean round(LFunction f) {
        List<LItem> items = f.items();
        int[] pos = new int[items.size() + 1];
        Map<String, Integer> labelPos = new HashMap<>();
        Map<String, Integer> labelIndex = new HashMap<>();
        Map<String, Integer> refs = new HashMap<>();
        for (int k = 0; k < items.size(); k++) {
            LItem i = items.get(k);
            pos[k + 1] = pos[k] + (i instanceof LInstr in ? in.length() : i instanceof LDirective d ? d.dataBytes() : 0);
            if (i instanceof LLabel l && !l.unnamed()) {
                labelPos.put(l.name(), pos[k]);
                labelIndex.put(l.name(), k);
            }
            if (i instanceof LInstr in && in.operand() != null) {
                refs.merge(in.operand(), 1, Integer::sum);
            }
            if (i instanceof LDirective d) {
                for (String entry : d.tableEntries()) {
                    refs.merge(entry, 1, Integer::sum);   // a label a jump table goes to
                }
            }
        }
        Map<Integer, LItem> replace = new HashMap<>();
        Set<Integer> remove = new HashSet<>();
        for (int k = 0; k < items.size(); k++) {
            if (!(items.get(k) instanceof LInstr in) || !in.relaxable() || remove.contains(k)) {
                continue;
            }
            if (in.isConditionalBranch()) {
                String skip = in.operand();
                if (!skip.startsWith("@") || refs.getOrDefault(skip, 0) != 1 || !labelIndex.containsKey(skip)) {
                    continue;
                }
                // (the branch to a jump first: where both apply, it keeps the jump on the way
                // that does not branch, as the code generator's own short forms did)
                // branch to a jump that nothing falls into
                int s = labelIndex.get(skip);
                int m = nextCode(items, s);
                int before = previousCode(items, s);
                if (m >= 0 && items.get(m) instanceof LInstr jump && isRelaxableJump(jump) && before >= 0
                        && items.get(before) instanceof LInstr prev && !prev.fallsThrough()) {
                    Integer to = labelPos.get(jump.operand());
                    if (to != null && LLayout.reaches(pos[k], to > pos[m] ? to - jump.length() : to)) {
                        replace.put(k, in.withOperand(jump.operand()));
                        remove.add(s);
                        remove.add(m);
                        continue;
                    }
                }
                // branch over a jump
                int j = nextCode(items, k);
                if (j >= 0 && items.get(j) instanceof LInstr jump && isRelaxableJump(jump)
                        && nextCode(items, j) == labelIndex.get(skip)) {
                    Integer to = labelPos.get(jump.operand());
                    // the jump goes: a target after it comes that much closer
                    if (to != null && LLayout.reaches(pos[k], to > pos[j] ? to - jump.length() : to)) {
                        replace.put(k, new LInstr(inverse(in.mnemonic()), jump.operand(), in.comment(), in.m16(), in.x16(), true));
                        remove.add(j);
                        remove.add(labelIndex.get(skip));
                    }
                }
            } else if (in.mnemonic().equals("jmp") && isRelaxableJump(in)) {
                Integer to = labelPos.get(in.operand());
                if (to != null && LLayout.reaches(pos[k], to)) {
                    replace.put(k, in.withMnemonic("bra"));
                }
            }
        }
        if (replace.isEmpty() && remove.isEmpty()) {
            return false;
        }
        List<LItem> out = new ArrayList<>();
        for (int k = 0; k < items.size(); k++) {
            if (!remove.contains(k)) {
                out.add(replace.getOrDefault(k, items.get(k)));
            }
        }
        f.rebuild(out);
        return true;
    }

    private static boolean isRelaxableJump(LInstr in) {
        return in.relaxable() && (in.mnemonic().equals("jmp") || in.mnemonic().equals("bra")) && in.operand() != null;
    }

    /** The index of the next instruction or label after k (comments and directives skipped), -1 at the end. */
    private static int nextCode(List<LItem> items, int k) {
        for (int j = k + 1; j < items.size(); j++) {
            if (items.get(j) instanceof LInstr || items.get(j) instanceof LLabel) {
                return j;
            }
        }
        return -1;
    }

    private static int previousCode(List<LItem> items, int k) {
        for (int j = k - 1; j >= 0; j--) {
            if (items.get(j) instanceof LInstr || items.get(j) instanceof LLabel) {
                return j;
            }
        }
        return -1;
    }

    static String inverse(String branch) {
        return switch (branch) {
            case "bcc" -> "bcs";
            case "bcs" -> "bcc";
            case "beq" -> "bne";
            case "bne" -> "beq";
            case "bmi" -> "bpl";
            case "bpl" -> "bmi";
            case "bvc" -> "bvs";
            case "bvs" -> "bvc";
            default -> throw new IllegalArgumentException(branch);
        };
    }
}
