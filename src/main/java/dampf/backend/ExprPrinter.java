package dampf.backend;

import dampf.ast.Expr;
import java.util.stream.Collectors;

/** Prints expressions as C-like text for the comments in the generated assembly. */
final class ExprPrinter {
    private ExprPrinter() {}

    static String print(Expr e) {
        return switch (e) {
            case Expr.Const k -> Long.toString(k.value());
            case Expr.Var v -> v.variable().name();
            case Expr.Convert c -> c.explicit() ? "(" + c.type() + ")" + operand(c.operand()) : print(c.operand());
            case Expr.Unary u -> u.op().symbol + operand(u.operand());
            case Expr.Binary b -> operand(b.left()) + " " + b.op().symbol + " " + operand(b.right());
            case Expr.Assign a -> a.target().name() + " = " + print(a.value());
            case Expr.Call c -> c.function().name() + "(" + c.args().stream().map(ExprPrinter::print).collect(Collectors.joining(", ")) + ")";
        };
    }

    /** An operand: in brackets unless it is a single name or number. */
    private static String operand(Expr e) {
        Expr inner = e;
        while (inner instanceof Expr.Convert c && !c.explicit()) {
            inner = c.operand();
        }
        return switch (inner) {
            case Expr.Const k -> print(k);
            case Expr.Var v -> print(v);
            case Expr.Call c -> print(c);
            default -> "(" + print(inner) + ")";
        };
    }
}
