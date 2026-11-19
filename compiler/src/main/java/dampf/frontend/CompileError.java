package dampf.frontend;

import java.util.List;

/** Compilation stopped; the diagnostics say why. */
public final class CompileError extends Exception {
    private static final long serialVersionUID = 1L;

    private final transient List<Diagnostic> diagnostics;

    public CompileError(List<Diagnostic> diagnostics) {
        super(String.join("\n", diagnostics.stream().map(Diagnostic::toString).toList()));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public CompileError(SourcePos pos, String message) {
        this(List.of(new Diagnostic(Diagnostic.Severity.ERROR, pos, message)));
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
