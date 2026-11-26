package dampf.lir;

import java.util.ArrayList;
import java.util.List;

/**
 * A basic block: straight code, entered only at its start and left only at its end. It may start
 * with explaining lines (comments, a C line header), then its label (none for a block that a
 * conditional branch falls through to, or the first one of a function), then instructions with
 * comments and directives between them. The last instruction is the only one that branches,
 * jumps or returns. The successors are set by {@link LFunction#link()}: the targets of the last
 * instruction, and the next block in the layout if it falls through.
 */
public final class LBlock {
    private final List<LItem> items = new ArrayList<>();
    private final List<LBlock> successors = new ArrayList<>();
    private LBlock fallThrough;
    private LBlock target;

    public List<LItem> items() {
        return items;
    }

    void add(LItem item) {
        items.add(item);
    }

    /** The block's label, or null. */
    public LLabel label() {
        for (LItem i : items) {
            if (i instanceof LLabel l) {
                return l;
            }
            if (i instanceof LInstr) {
                return null;
            }
        }
        return null;
    }

    public List<LInstr> instrs() {
        List<LInstr> out = new ArrayList<>();
        for (LItem i : items) {
            if (i instanceof LInstr in) {
                out.add(in);
            }
        }
        return out;
    }

    public boolean hasCode() {
        for (LItem i : items) {
            if (i instanceof LInstr) {
                return true;
            }
        }
        return false;
    }

    /** A block of data without code (a jump table of a {@code switch}): control never enters it. */
    public boolean isData() {
        if (hasCode()) {
            return false;
        }
        for (LItem i : items) {
            if (i instanceof LDirective d && d.data()) {
                return true;
            }
        }
        return false;
    }

    /** The bytes the block takes in the ROM: its instructions and its data. */
    public int bytes() {
        int n = 0;
        for (LItem i : items) {
            if (i instanceof LInstr in) {
                n += in.length();
            } else if (i instanceof LDirective d) {
                n += d.dataBytes();
            }
        }
        return n;
    }

    /** The last instruction, or null. */
    public LInstr last() {
        for (int k = items.size() - 1; k >= 0; k--) {
            if (items.get(k) instanceof LInstr in) {
                return in;
            }
        }
        return null;
    }

    /** Where control goes next: branch or jump targets in this function, and the block it falls through to. */
    public List<LBlock> successors() {
        return successors;
    }

    /** The next block in the layout if control can go on there, else null. */
    public LBlock fallThrough() {
        return fallThrough;
    }

    /** The block the last instruction branches or jumps to in this function, or null. */
    public LBlock target() {
        return target;
    }

    void setSuccessors(List<LBlock> s, LBlock to, LBlock fall) {
        successors.clear();
        successors.addAll(s);
        target = to;
        fallThrough = fall;
    }

    @Override
    public String toString() {
        LLabel l = label();
        return l == null ? "(block)" : l.name() + ":";
    }
}
