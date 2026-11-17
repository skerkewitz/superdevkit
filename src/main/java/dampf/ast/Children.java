package dampf.ast;

import java.util.ArrayList;
import java.util.List;

/** The direct children of AST nodes, for passes that only need to walk the tree. */
public final class Children {
    private Children() {}

    public static List<Expr> of(Expr e) {
        return switch (e) {
            case Expr.Const k -> List.of();
            case Expr.Var v -> List.of();
            case Expr.PostIncDec p -> List.of();
            case Expr.Binary b -> List.of(b.left(), b.right());
            case Expr.Unary u -> List.of(u.operand());
            case Expr.LogicalNot n -> List.of(n.operand());
            case Expr.Logical l -> List.of(l.left(), l.right());
            case Expr.Conditional c -> List.of(c.cond(), c.then(), c.otherwise());
            case Expr.Convert c -> List.of(c.operand());
            case Expr.Assign a -> List.of(a.value());
            case Expr.Call c -> c.args();
        };
    }

    /** The expressions directly in a statement (not those of nested statements). */
    public static List<Expr> exprsOf(Stmt s) {
        return switch (s) {
            case Stmt.ExprStmt e -> List.of(e.expr());
            case Stmt.Return r -> r.value() == null ? List.of() : List.of(r.value());
            case Stmt.If i -> List.of(i.cond());
            case Stmt.While w -> List.of(w.cond());
            case Stmt.DoWhile d -> List.of(d.cond());
            case Stmt.For f -> {
                List<Expr> l = new ArrayList<>();
                if (f.cond() != null) {
                    l.add(f.cond());
                }
                l.addAll(f.step());
                yield l;
            }
            case Stmt.Switch sw -> List.of(sw.value());
            case Stmt.Block b -> List.of();
            case Stmt.Break b -> List.of();
            case Stmt.Continue c -> List.of();
            case Stmt.Case c -> List.of();
        };
    }

    /** The statements directly in a statement. */
    public static List<Stmt> stmtsOf(Stmt s) {
        return switch (s) {
            case Stmt.Block b -> b.statements();
            case Stmt.If i -> i.otherwise() == null ? List.of(i.then()) : List.of(i.then(), i.otherwise());
            case Stmt.While w -> List.of(w.body());
            case Stmt.DoWhile d -> List.of(d.body());
            case Stmt.For f -> f.init() == null ? List.of(f.body()) : List.of(f.init(), f.body());
            case Stmt.Switch sw -> sw.body();
            case Stmt.ExprStmt e -> List.of();
            case Stmt.Return r -> List.of();
            case Stmt.Break b -> List.of();
            case Stmt.Continue c -> List.of();
            case Stmt.Case c -> List.of();
        };
    }
}
