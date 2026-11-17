package dampf.frontend;

/** A message about the source code, at an original source position. */
public record Diagnostic(Severity severity, SourcePos pos, String message) {
    public enum Severity { ERROR, WARNING, NOTE }

    @Override
    public String toString() {
        return pos + ": " + severity.name().toLowerCase() + ": " + message;
    }
}
