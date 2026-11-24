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
            case Expr.PostIncDec p -> List.of(p.target());
            case Expr.Index i -> List.of(i.base(), i.index());
            case Expr.Member m -> List.of(m.base());
            case Expr.Deref d -> List.of(d.pointer());
            case Expr.AddressOf a -> List.of(a.lvalue());
            case Expr.Decay d -> List.of(d.array());
            case Expr.PointerAdd p -> List.of(p.pointer(), p.offset());
            case Expr.PointerDiff p -> List.of(p.left(), p.right());
            case Expr.PointerCast p -> List.of(p.operand());
            case Expr.PointerToInt p -> List.of(p.operand());
            case Expr.Binary b -> List.of(b.left(), b.right());
            case Expr.Unary u -> List.of(u.operand());
            case Expr.LogicalNot n -> List.of(n.operand());
            case Expr.Logical l -> List.of(l.left(), l.right());
            case Expr.Conditional c -> List.of(c.cond(), c.then(), c.otherwise());
            case Expr.Convert c -> List.of(c.operand());
            case Expr.Assign a -> List.of(a.target(), a.value());
            case Expr.Call c -> c.args();
            case Expr.TableCall c -> {
                List<Expr> l = new ArrayList<>();
                l.add(c.callee());
                l.addAll(c.args());
                yield l;
            }
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
            case Stmt.Range r -> List.of();
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
            case Stmt.Range r -> List.of();
        };
    }
}
