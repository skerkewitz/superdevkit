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
            case Expr.Assign a -> print(a.target()) + " = " + print(a.value());
            case Expr.PostIncDec p -> operand(p.target()) + (p.increment() ? "++" : "--");
            case Expr.Index i -> operand(i.base()) + "[" + print(i.index()) + "]";
            case Expr.Member m -> m.base() instanceof Expr.Deref d ? operand(d.pointer()) + "->" + m.field().name()
                    : operand(m.base()) + "." + m.field().name();
            case Expr.Deref d -> "*" + operand(d.pointer());
            case Expr.AddressOf a -> "&" + operand(a.lvalue());
            case Expr.Decay d -> print(d.array());
            case Expr.PointerAdd p -> operand(p.pointer()) + (p.subtract() ? " - " : " + ") + operand(p.offset());
            case Expr.PointerDiff p -> operand(p.left()) + " - " + operand(p.right());
            case Expr.PointerCast c -> print(c.operand());
            case Expr.LogicalNot n -> "!" + operand(n.operand());
            case Expr.Logical l -> operand(l.left()) + (l.and() ? " && " : " || ") + operand(l.right());
            case Expr.Conditional c -> operand(c.cond()) + " ? " + operand(c.then()) + " : " + operand(c.otherwise());
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
            case Expr.Index i -> print(i);
            case Expr.Member m -> print(m);
            case Expr.Decay d -> print(d);
            default -> "(" + print(inner) + ")";
        };
    }
}
