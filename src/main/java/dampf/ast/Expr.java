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
        ADD("+"), SUB("-"), AND("&"), OR("|"), XOR("^");

        public final String symbol;

        BinOp(String symbol) {
            this.symbol = symbol;
        }
    }

    record Binary(BinOp op, Expr left, Expr right, Type.Int type, SourcePos pos) implements Expr {}

    enum UnOp {
        NEG("-"), NOT("~");

        public final String symbol;

        UnOp(String symbol) {
            this.symbol = symbol;
        }
    }

    record Unary(UnOp op, Expr operand, Type.Int type, SourcePos pos) implements Expr {}

    /** A conversion between integer types; {@code explicit} for a cast in the source. */
    record Convert(Expr operand, Type.Int type, boolean explicit, SourcePos pos) implements Expr {}

    /** Assignment to a variable; {@code value} already has the variable's type. */
    record Assign(Variable target, Expr value, SourcePos pos) implements Expr {
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
