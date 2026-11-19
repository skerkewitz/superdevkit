package dampf.frontend;

/** A position in an original source file (before preprocessing). Line and column are 1-based. */
public record SourcePos(String file, int line, int column) {
    @Override
    public String toString() {
        return file + ":" + line + ":" + column;
    }
}
