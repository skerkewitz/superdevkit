package dampf.lir;

import dampf.target.MasterCycles;

/**
 * The header of a C line: a comment with the file, line number and source text. Its cost in
 * master cycles (when known for one path) is set by whoever knows it and printed at the end
 * ({@code [ 72 mc ]}, {@code [ 84-88 mc ]} when an access through a pointer may go to faster or
 * slower memory, {@code + calls} when the line calls functions whose cycles are not included).
 */
public final class LSourceLine implements LItem {
    private final String text;
    private MasterCycles.Range cycles;
    private boolean calls;

    public LSourceLine(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    /** The line's cost in master cycles, or null. */
    public MasterCycles.Range cycles() {
        return cycles;
    }

    public boolean calls() {
        return calls;
    }

    /** The line's cost, null for none. */
    public void setCycles(MasterCycles.Range cycles, boolean calls) {
        this.cycles = cycles;
        this.calls = calls;
    }
}
