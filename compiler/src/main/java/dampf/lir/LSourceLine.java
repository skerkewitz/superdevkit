package dampf.lir;

/**
 * The header of a C line: a comment with the file, line number and source text. Its cost in
 * cycles (when known for one path) is set by whoever knows it and printed at the end
 * ({@code [ 12 cyc ]}, {@code + calls} when the line calls functions whose cycles are not
 * included).
 */
public final class LSourceLine implements LItem {
    private final String text;
    private Integer cycles;
    private boolean calls;

    public LSourceLine(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    public Integer cycles() {
        return cycles;
    }

    public boolean calls() {
        return calls;
    }

    /** The line's cost, null for none. */
    public void setCycles(Integer cycles, boolean calls) {
        this.cycles = cycles;
        this.calls = calls;
    }
}
