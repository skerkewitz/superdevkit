package dampf.frontend.pp;

/** An error of the preprocessor at a place in a source file (line and column from 1). */
public final class PpException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String file;
    private final int line;
    private final int col;

    PpException(String message, String file, int line, int col) {
        super(message);
        this.file = file;
        this.line = line;
        this.col = col;
    }

    PpException(String message, Token at) {
        this(message, at.file(), at.line(), at.col());
    }

    public String file() {
        return file;
    }

    public int line() {
        return line;
    }

    public int col() {
        return col;
    }
}
