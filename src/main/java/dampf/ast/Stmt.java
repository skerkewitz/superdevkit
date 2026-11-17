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
}
