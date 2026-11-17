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
    record Conditional(Expr cond, Expr then, Expr otherwise, Type.Int type, SourcePos pos) implements Expr {}

    /** A conversion between integer types; {@code explicit} for a cast in the source. */
    record Convert(Expr operand, Type.Int type, boolean explicit, SourcePos pos) implements Expr {}

    /** Assignment to a variable; {@code value} already has the variable's type. */
    record Assign(Variable target, Expr value, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return target.type();
        }
    }

    /** {@code x++} or {@code x--}: the value is the old one. (Prefix forms are plain assignments.) */
    record PostIncDec(Variable target, boolean increment, SourcePos pos) implements Expr {
        @Override
        public Type type() {
            return target.type();
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
