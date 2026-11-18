package dampf.lir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives a function's local labels (ca65's cheap local labels, {@code @L...}) the numbers 0, 1, 2,
 * ... in the order they are defined, so their names depend only on the code, not on how often or
 * in which order it was written.
 */
public final class LocalLabels {
    private LocalLabels() {}

    /** Renames the labels that start with {@code prefix}; returns old name to new name. */
    public static Map<String, String> renumber(LFunction f, String prefix) {
        Map<String, String> to = new HashMap<>();
        for (LBlock b : f.blocks()) {
            for (LItem i : b.items()) {
                if (i instanceof LLabel l && l.name().startsWith(prefix)) {
                    to.put(l.name(), prefix + to.size());
                }
            }
        }
        for (LBlock b : f.blocks()) {
            List<LItem> items = b.items();
            for (int k = 0; k < items.size(); k++) {
                LItem i = items.get(k);
                if (i instanceof LLabel l && to.containsKey(l.name())) {
                    if (!l.text().equals(l.name() + ":")) {
                        throw new IllegalStateException("a local label with more on its line: " + l.text());
                    }
                    items.set(k, new LLabel(to.get(l.name())));
                } else if (i instanceof LInstr in && in.operand() != null && to.containsKey(in.operand())) {
                    items.set(k, in.withOperand(to.get(in.operand())));
                }
            }
        }
        f.link();
        return to;
    }
}
