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

    private boolean asm;
    private String checkOf;

    /**
     * Written by hand in assembly (DAMPF_ASM): its blocks are a stand-in (a return of 0) that no
     * pass may look into. Calls of it stay calls, its parameters stay as they are, and it may
     * write any memory; the code generator reserves its frame and generates no code for it.
     */
    public boolean isAsm() {
        return asm;
    }

    public void setAsm(boolean asm) {
        this.asm = asm;
    }

    /**
     * For the C body of an assembly function, lowered so that dampf can run it against the
     * assembly: the assembly function's label; null otherwise. Nothing calls it.
     */
    public String checkOf() {
        return checkOf;
    }

    public void setCheckOf(String label) {
        this.checkOf = label;
    }

    /** A block with a given name (for the parser). */
    Block addBlock(String name) {
        Block b = new Block(name);
        blocks.add(b);
        blockCounter++;
        return b;
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
