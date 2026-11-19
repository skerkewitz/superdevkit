package dampf.ast;

import dampf.frontend.SourcePos;
import java.util.List;

public sealed interface Stmt {
    SourcePos pos();

    /** {@code end} is the closing brace. */
    record Block(List<Stmt> statements, SourcePos pos, SourcePos end) implements Stmt {}

    record ExprStmt(Expr expr, SourcePos pos) implements Stmt {}

    /** {@code value} is null in a void function, otherwise it has the return type. */
    record Return(Expr value, SourcePos pos) implements Stmt {}

    /** {@code otherwise} may be null. */
    record If(Expr cond, Stmt then, Stmt otherwise, SourcePos pos) implements Stmt {}

    record While(Expr cond, Stmt body, SourcePos pos) implements Stmt {}

    record DoWhile(Stmt body, Expr cond, SourcePos pos) implements Stmt {}

    /** Every part but the body may be null; {@code init} is a statement (it may declare variables). */
    record For(Stmt init, Expr cond, List<Expr> step, Stmt body, SourcePos pos) implements Stmt {}

    record Break(SourcePos pos) implements Stmt {}

    record Continue(SourcePos pos) implements Stmt {}

    /**
     * A switch. The body is a flat list of statements with {@link Case} markers in it (dampf
     * allows case labels only at the top level of the switch body). {@code value} has its
     * promoted type, and every case value is converted to that type.
     */
    record Switch(Expr value, List<Stmt> body, SourcePos pos) implements Stmt {}

    /** A case label inside a switch body; {@code value} null for {@code default}. */
    record Case(Long value, SourcePos pos) implements Stmt {}

    /**
     * The hint {@code DAMPF_RANGE(v, lo, hi)}: the local variable or parameter {@code v} lies in
     * lo..hi here ({@code <dampf.h>}). It makes no code; the bounds are values of v's type.
     */
    record Range(Variable variable, long lo, long hi, SourcePos pos) implements Stmt {}
}
