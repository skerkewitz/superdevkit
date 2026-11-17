package dampf.mir;

import dampf.frontend.SourcePos;
import java.util.ArrayList;
import java.util.List;

/** A function in MIR: parameters, frame slots and blocks; the first block is the entry. */
public final class MFunction {
    private final String name;
    private final MType result;
    private final List<Value.Param> params = new ArrayList<>();
    private final List<Value.Slot> slots = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();
    private final SourcePos pos;
    private int blockCounter;

    public MFunction(String name, MType result, SourcePos pos) {
        this.name = name;
        this.result = result;
        this.pos = pos;
    }

    public String name() {
        return name;
    }

    public MType result() {
        return result;
    }

    public SourcePos pos() {
        return pos;
    }

    public List<Value.Param> params() {
        return params;
    }

    public List<Value.Slot> slots() {
        return slots;
    }

    public List<Block> blocks() {
        return blocks;
    }

    public Block entry() {
        return blocks.getFirst();
    }

    public Block newBlock(String hint) {
        Block b = new Block("b" + (blockCounter++) + (hint == null ? "" : "_" + hint));
        blocks.add(b);
        return b;
    }

    /** Numbers the value-producing instructions %0, %1, ... in block order. */
    public void number() {
        int n = 0;
        for (Block b : blocks) {
            for (Instr i : b.instrs()) {
                i.id = i.type() == MType.VOID ? -1 : n++;
            }
        }
    }
}
