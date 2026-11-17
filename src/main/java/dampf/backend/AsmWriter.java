package dampf.backend;

/** Writes ca65 source with aligned operands and comments. */
final class AsmWriter {
    private static final int OPERAND_COLUMN = 16;
    private static final int COMMENT_COLUMN = 36;

    private final StringBuilder out = new StringBuilder();

    /** An instruction; {@code operand} and {@code comment} may be null. */
    void ins(String mnemonic, String operand, String comment) {
        StringBuilder line = new StringBuilder("        ").append(mnemonic);
        if (operand != null) {
            pad(line, OPERAND_COLUMN);
            line.append(operand);
        }
        if (comment != null) {
            pad(line, COMMENT_COLUMN);
            line.append("; ").append(comment);
        }
        out.append(line).append('\n');
    }

    void label(String name) {
        out.append(name).append(":\n");
    }

    /** A line written as is. */
    void raw(String text) {
        out.append(text).append('\n');
    }

    /** A comment indented like an instruction. */
    void note(String text) {
        StringBuilder line = new StringBuilder();
        pad(line, COMMENT_COLUMN);
        out.append(line).append("; ").append(text).append('\n');
    }

    void blank() {
        out.append('\n');
    }

    private static void pad(StringBuilder line, int column) {
        line.append(" ".repeat(Math.max(1, column - line.length())));
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
