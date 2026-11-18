package dampf.lir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A function: the code between {@code .proc} and {@code .endproc}, as basic blocks in layout
 * order (the order they lie in the ROM).
 */
public final class LFunction implements LItem {
    private final LDirective proc;
    private LDirective endproc;
    private final List<LBlock> blocks = new ArrayList<>();

    public LFunction(LDirective proc) {
        this.proc = proc;
    }

    public String name() {
        return proc.args().getFirst();
    }

    public LDirective proc() {
        return proc;
    }

    public LDirective endproc() {
        return endproc;
    }

    void close(LDirective end) {
        this.endproc = end;
        link();
    }

    public List<LBlock> blocks() {
        return blocks;
    }

    LBlock newBlock() {
        LBlock b = new LBlock();
        blocks.add(b);
        return b;
    }

    /** The block a named label starts, or null. */
    public LBlock block(String label) {
        for (LBlock b : blocks) {
            LLabel l = b.label();
            if (l != null && l.name().equals(label)) {
                return b;
            }
        }
        return null;
    }

    /** All instructions in layout order. */
    public List<LInstr> instrs() {
        List<LInstr> out = new ArrayList<>();
        for (LBlock b : blocks) {
            out.addAll(b.instrs());
        }
        return out;
    }

    /** Sets every block's successors from its last instruction (after the blocks changed). */
    public void link() {
        Map<String, LBlock> named = new HashMap<>();
        for (LBlock b : blocks) {
            LLabel l = b.label();
            if (l != null && !l.unnamed()) {
                named.put(l.name(), b);
            }
        }
        for (int k = 0; k < blocks.size(); k++) {
            LBlock b = blocks.get(k);
            LBlock next = k + 1 < blocks.size() ? blocks.get(k + 1) : null;
            LInstr last = b.last();
            List<LBlock> s = new ArrayList<>();
            LBlock fall = last == null || last.fallsThrough() ? next : null;
            LBlock t = last != null && last.target() != null ? target(k, last.target(), named) : null;
            if (t != null) {
                s.add(t);
            }
            if (fall != null && !s.contains(fall)) {
                s.add(fall);
            }
            b.setSuccessors(s, t, fall);
        }
    }

    /** The block a branch in block k goes to: a named label, or an unnamed one ({@code :+}, {@code :--}). */
    private LBlock target(int k, String target, Map<String, LBlock> named) {
        if (target.length() > 1 && target.charAt(0) == ':' && target.chars().skip(1).allMatch(c -> c == '+' || c == '-')) {
            int n = target.length() - 1;
            int step = target.charAt(1) == '+' ? 1 : -1;
            for (int j = step > 0 ? k + 1 : k; j >= 0 && j < blocks.size(); j += step) {
                LLabel l = blocks.get(j).label();
                if (l != null && l.unnamed() && --n == 0) {
                    return blocks.get(j);
                }
            }
            return null;
        }
        return named.get(target);
    }
}
