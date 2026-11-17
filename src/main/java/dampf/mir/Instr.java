package dampf.mir;

import dampf.frontend.SourcePos;
import java.util.ArrayList;
import java.util.List;

/**
 * An MIR instruction. Instructions that produce a value are that value (as in LLVM), so other
 * instructions use them directly. Operands are mutable, because SSA construction replaces
 * provisional φs after the fact. Every instruction carries the C source position it came from.
 */
public abstract sealed class Instr implements Value
        permits Instr.Bin, Instr.Cmp, Instr.Cast, Instr.PtrAdd, Instr.Load, Instr.Store, Instr.Copy, Instr.Call,
        Instr.Phi, Instr.Jump, Instr.Branch, Instr.Ret, Instr.Switch {

    protected final List<Value> ops = new ArrayList<>();
    private final MType type;
    private final SourcePos pos;
    Block block;
    int id = -1;
    /** The interpreter's slot for this instruction's value (set once per function). */
    int index = -1;

    protected Instr(MType type, SourcePos pos, Value... operands) {
        this.type = type;
        this.pos = pos;
        ops.addAll(List.of(operands));
    }

    @Override
    public MType type() {
        return type;
    }

    public SourcePos pos() {
        return pos;
    }

    public Block block() {
        return block;
    }

    public List<Value> operands() {
        return ops;
    }

    public Value op(int i) {
        return ops.get(i);
    }

    public void setOp(int i, Value v) {
        ops.set(i, v);
    }

    public boolean isTerminator() {
        return this instanceof Jump || this instanceof Branch || this instanceof Ret || this instanceof Switch;
    }

    /** The name in the text format; "%id" for value-producing instructions. */
    @Override
    public String toString() {
        return "%" + id;
    }

    // ------------------------------------------------------------------ arithmetic

    public enum BinOp {
        ADD, SUB, MUL, UDIV, SDIV, UREM, SREM, AND, OR, XOR, SHL, LSHR, ASHR;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    /** {@code a op b}; both operands and the result have the same type. */
    public static final class Bin extends Instr {
        public final BinOp op;

        public Bin(BinOp op, Value a, Value b, SourcePos pos) {
            super(a.type(), pos, a, b);
            this.op = op;
        }
    }

    public enum Pred {
        EQ, NE, ULT, ULE, UGT, UGE, SLT, SLE, SGT, SGE;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    /** A comparison; the result is I1. */
    public static final class Cmp extends Instr {
        public final Pred pred;

        public Cmp(Pred pred, Value a, Value b, SourcePos pos) {
            super(MType.I1, pos, a, b);
            this.pred = pred;
        }
    }

    public enum CastKind {
        ZEXT, SEXT, TRUNC, PTRTOINT, INTTOPTR;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    /** A change of width (zext, sext, trunc) or between integer and pointer. */
    public static final class Cast extends Instr {
        public final CastKind kind;

        public Cast(CastKind kind, Value a, MType to, SourcePos pos) {
            super(to, pos, a);
            this.kind = kind;
        }
    }

    /**
     * {@code pointer + offset} bytes. The offset is an I32, or an I16 (signed): the result is only
     * used while it points into the same object (or just past its end), and objects are at most
     * 32 KB and never cross a bank, so the target only adds the low 16 bits, within the bank
     * (docs/memory-map.md).
     */
    public static final class PtrAdd extends Instr {
        public PtrAdd(Value pointer, Value offset, SourcePos pos) {
            super(MType.PTR, pos, pointer, offset);
        }
    }

    // ------------------------------------------------------------------ memory and calls

    /** A value from memory at the address in operand 0. */
    public static final class Load extends Instr {
        public final boolean isVolatile;

        public Load(MType type, Value address, boolean isVolatile, SourcePos pos) {
            super(type, pos, address);
            this.isVolatile = isVolatile;
        }
    }

    /** Stores operand 1 at the address in operand 0. */
    public static final class Store extends Instr {
        public final boolean isVolatile;

        public Store(Value address, Value value, boolean isVolatile, SourcePos pos) {
            super(MType.VOID, pos, address, value);
            this.isVolatile = isVolatile;
        }
    }

    /** Copies {@code size} bytes from operand 1 to operand 0 (struct assignment). */
    public static final class Copy extends Instr {
        public final int size;

        public Copy(Value to, Value from, int size, SourcePos pos) {
            super(MType.VOID, pos, to, from);
            this.size = size;
        }
    }

    /** A call; the operands are the arguments. */
    public static final class Call extends Instr {
        public final String callee;

        public Call(String callee, MType result, List<Value> args, SourcePos pos) {
            super(result, pos, args.toArray(Value[]::new));
            this.callee = callee;
        }
    }

    /** Chooses a value by the block control came from; {@code from.get(i)} goes with operand i. */
    public static final class Phi extends Instr {
        public final List<Block> from = new ArrayList<>();
        /**
         * A range the φ's values are known to lie in, from how a pass built it (an induction
         * variable that replaces {@code i * C} has the range of {@code i * C}); null if none.
         */
        public Ranges.Range known;

        public Phi(MType type, SourcePos pos) {
            super(type, pos);
        }

        public void add(Block pred, Value v) {
            from.add(pred);
            ops.add(v);
        }
    }

    // ------------------------------------------------------------------ terminators

    public static final class Jump extends Instr {
        public final Block target;

        public Jump(Block target, SourcePos pos) {
            super(MType.VOID, pos);
            this.target = target;
        }
    }

    /** Goes to {@code ifTrue} if operand 0 (I1) is true. */
    public static final class Branch extends Instr {
        public final Block ifTrue;
        public final Block ifFalse;

        public Branch(Value cond, Block ifTrue, Block ifFalse, SourcePos pos) {
            super(MType.VOID, pos, cond);
            this.ifTrue = ifTrue;
            this.ifFalse = ifFalse;
        }
    }

    /** Returns operand 0, or nothing. */
    public static final class Ret extends Instr {
        public Ret(Value value, SourcePos pos) {
            super(MType.VOID, pos);
            if (value != null) {
                ops.add(value);
            }
        }
    }

    /** Goes to the target of the case equal to operand 0, else to {@code otherwise}. */
    public static final class Switch extends Instr {
        public final List<Long> cases = new ArrayList<>();
        public final List<Block> targets = new ArrayList<>();
        public final Block otherwise;

        public Switch(Value value, Block otherwise, SourcePos pos) {
            super(MType.VOID, pos, value);
            this.otherwise = otherwise;
        }
    }

    /** The blocks a terminator can go to. */
    public List<Block> successors() {
        return switch (this) {
            case Jump j -> List.of(j.target);
            case Branch b -> List.of(b.ifTrue, b.ifFalse);
            case Switch s -> {
                List<Block> l = new ArrayList<>(s.targets);
                l.add(s.otherwise);
                yield l;
            }
            default -> List.of();
        };
    }
}
