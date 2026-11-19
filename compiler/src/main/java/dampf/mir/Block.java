package dampf.mir;

import java.util.ArrayList;
import java.util.List;

/** A basic block: φs first, then instructions, then one terminator. */
public final class Block {
    private final String name;
    private final List<Instr> instrs = new ArrayList<>();
    private final List<Block> preds = new ArrayList<>();

    Block(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public List<Instr> instrs() {
        return instrs;
    }

    public List<Block> preds() {
        return preds;
    }

    /** Removes an instruction (a φ that turned out to be unnecessary). */
    void remove(Instr instr) {
        instrs.remove(instr);
        instr.block = null;
    }

    public Instr terminator() {
        return instrs.isEmpty() || !instrs.getLast().isTerminator() ? null : instrs.getLast();
    }

    public boolean terminated() {
        return terminator() != null;
    }

    /** Inserts an instruction before another one of this block (for passes). */
    void insertBefore(Instr at, Instr instr) {
        int k = instrs.indexOf(at);
        if (k < 0 || instr.isTerminator() || instr instanceof Instr.Phi) {
            throw new IllegalStateException("cannot insert " + instr + " before " + at + " in " + name);
        }
        instr.block = this;
        instrs.add(k, instr);
    }

    /** Appends an instruction (a φ goes before the other instructions). */
    public <T extends Instr> T add(T instr) {
        if (terminated() && !(instr instanceof Instr.Phi)) {
            throw new IllegalStateException("block " + name + " already ends with a terminator");
        }
        instr.block = this;
        if (instr instanceof Instr.Phi) {
            int i = 0;
            while (i < instrs.size() && instrs.get(i) instanceof Instr.Phi) {
                i++;
            }
            instrs.add(i, instr);
        } else {
            instrs.add(instr);
        }
        if (instr.isTerminator()) {
            for (Block s : instr.successors()) {
                s.preds.add(this);
            }
        }
        return instr;
    }

    @Override
    public String toString() {
        return name;
    }
}
