package dampf.ast;

import dampf.frontend.SourcePos;
import java.util.List;

/** Typed expressions. Every implicit conversion of C appears as an explicit {@link Convert}. */
public sealed interface Expr {
    Type type();

    SourcePos pos();

    record Const(long value, Type.Int type, SourcePos pos) implements Expr {}

    record Var(Variable variable, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return variable.type();
        }
    }

    enum BinOp {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"), SHL("<<"), SHR(">>"), AND("&"), OR("|"), XOR("^"),
        LT("<"), LE("<="), GT(">"), GE(">="), EQ("=="), NE("!=");

        public final String symbol;

        BinOp(String symbol) {
            this.symbol = symbol;
        }

        public boolean isComparison() {
            return ordinal() >= LT.ordinal();
        }

        public boolean isShift() {
            return this == SHL || this == SHR;
        }

        public static BinOp of(String symbol) {
            for (BinOp op : values()) {
                if (op.symbol.equals(symbol)) {
                    return op;
                }
            }
            return null;
        }
    }

    /**
     * A binary operator. Arithmetic and bitwise operators: both operands have {@code type}.
     * Shifts: the left operand has {@code type}, the right one its own promoted type.
     * Comparisons: both operands have the common type, {@code type} is int (the result is 0 or 1).
     */
    record Binary(BinOp op, Expr left, Expr right, Type.Int type, SourcePos pos) implements Expr {}

    enum UnOp {
        NEG("-"), NOT("~");

        public final String symbol;

        UnOp(String symbol) {
            this.symbol = symbol;
        }
    }

    record Unary(UnOp op, Expr operand, Type.Int type, SourcePos pos) implements Expr {}

    /** {@code !operand}: int 1 if the operand is 0, else 0. */
    record LogicalNot(Expr operand, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return Type.I32;
        }
    }

    /** {@code &&} ({@code and} true) or {@code ||}: int 0 or 1, the right operand only evaluated when needed. */
    record Logical(boolean and, Expr left, Expr right, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return Type.I32;
        }
    }

    /** {@code cond ? then : otherwise}; both branches have {@code type}. */
    record Conditional(Expr cond, Expr then, Expr otherwise, Type type, SourcePos pos) implements Expr {}

    /** A conversion between integer types; {@code explicit} for a cast in the source. */
    record Convert(Expr operand, Type.Int type, boolean explicit, SourcePos pos) implements Expr {}

    /**
     * Assignment to an lvalue ({@link #isLvalue}); {@code value} already has the target's type.
     * For a struct type this copies the whole struct.
     */
    record Assign(Expr target, Expr value, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return target.type();
        }
    }

    /**
     * {@code x++} or {@code x--} on an lvalue: the value is the old one. {@code step} is 1 for
     * integers and the element size for pointers. (Prefix forms are plain assignments.)
     */
    record PostIncDec(Expr target, boolean increment, int step, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return target.type();
        }
    }

    /** {@code base[index]}: base is an array lvalue or a pointer value; index has type int or unsigned int. */
    record Index(Expr base, Expr index, Type type, SourcePos pos) implements Expr {}

    /** {@code base.field}; {@code p->field} is {@code (*p).field}. */
    record Member(Expr base, Type.Field field, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return field.type();
        }
    }

    /** {@code *pointer}. */
    record Deref(Expr pointer, Type type, SourcePos pos) implements Expr {}

    /** {@code &lvalue}. */
    record AddressOf(Expr lvalue, Type.Pointer type, SourcePos pos) implements Expr {}

    /** An array used as a value: a pointer to its first element. */
    record Decay(Expr array, Type.Pointer type, SourcePos pos) implements Expr {}

    /** {@code pointer + offset * scale} (or minus); offset has type int or unsigned int. */
    record PointerAdd(Expr pointer, Expr offset, int scale, boolean subtract, Type.Pointer type, SourcePos pos) implements Expr {}

    /** {@code (a - b) / scale}: the distance between two pointers into the same array, as an int. */
    record PointerDiff(Expr left, Expr right, int scale, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return Type.I32;
        }
    }

    /** A conversion to a pointer type, from a pointer or from an integer; the bits stay the same. */
    record PointerCast(Expr operand, Type.Pointer type, SourcePos pos) implements Expr {}

    /**
     * A pointer as a 32-bit integer: its SNES address, {@code bank << 16 | address} (the pointer's
     * bits; docs/memory-map.md). A cast to another integer type converts this value further.
     */
    record PointerToInt(Expr operand, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return Type.U32;
        }
    }

    /** True for expressions that designate an object (can be assigned to, or have their address taken). */
    static boolean isLvalue(Expr e) {
        return e instanceof Var || e instanceof Index || e instanceof Member || e instanceof Deref;
    }

    /**
     * A call through a function pointer in a constant table ({@code handlers[state](x)}).
     * {@code callee} is the lvalue of the table entry, an {@link Index} and {@link Member} chain
     * on a const {@link Var} in ROM; the functions it can reach are the table's entries there
     * ({@code dampf.check.FunctionTables}). Each argument already has its parameter's type.
     */
    record TableCall(Expr callee, Type.Func signature, List<Expr> args, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return signature.result();
        }
    }

    /** A call; each argument already has its parameter's type. */
    record Call(Function function, List<Expr> args, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return function.returnType();
        }
    }
}
