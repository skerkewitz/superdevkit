package dampf.lir;

/**
 * LIR as ca65 source: instructions with aligned operands and comments, and their CPU cycles in a
 * column on the right (a conditional branch shows "not taken/taken", an instruction the cycle
 * table does not know "?"); a C line's cost at the end of its header; every other line as
 * written.
 */
public final class LPrinter {
    static final int OPERAND_COLUMN = 16;
    public static final int COMMENT_COLUMN = 36;
    static final int CYCLE_COLUMN = 92;

    private LPrinter() {}

    public static String print(LUnit unit) {
        StringBuilder out = new StringBuilder();
        for (LItem i : unit.items()) {
            print(i, out);
        }
        return out.toString();
    }

    private static void print(LItem item, StringBuilder out) {
        switch (item) {
            case LFunction f -> {
                line(f.proc().text(), out);
                for (LBlock b : f.blocks()) {
                    for (LItem i : b.items()) {
                        print(i, out);
                    }
                }
                if (f.endproc() != null) {
                    line(f.endproc().text(), out);
                }
            }
            default -> line(line(item), out);
        }
    }

    private static void line(String text, StringBuilder out) {
        out.append(text).append('\n');
    }

    /** One item as a line of text (not a function). */
    public static String line(LItem item) {
        return switch (item) {
            case LInstr in -> instr(in);
            case LLabel l -> l.text();
            case LDirective d -> d.text();
            case LSourceLine s -> sourceLine(s);
            case LFunction f -> throw new IllegalArgumentException("a function is not one line");
            case LMark m -> throw new IllegalArgumentException("a mark is taken out before the text is written");
            case LCopy c -> throw new IllegalArgumentException("a copy is expanded before the text is written");
        };
    }

    static String instr(LInstr in) {
        StringBuilder line = new StringBuilder("        ").append(in.mnemonic());
        if (in.operand() != null) {
            pad(line, OPERAND_COLUMN);
            line.append(in.operand());
        }
        if (in.comment() != null) {
            pad(line, COMMENT_COLUMN);
            line.append("; ").append(in.comment());
        } else {
            pad(line, COMMENT_COLUMN);
            line.append(';');
        }
        String cycles = !in.known() ? "?"
                : in.isConditionalBranch() ? in.cycles() + "/" + in.cyclesTaken()
                : Integer.toString(in.cycles());
        pad(line, CYCLE_COLUMN);
        line.append(" ".repeat(Math.max(0, 5 - cycles.length()))).append(cycles);
        return line.toString();
    }

    static String sourceLine(LSourceLine s) {
        if (s.cycles() == null) {
            return s.text();
        }
        StringBuilder h = new StringBuilder(s.text());
        pad(h, CYCLE_COLUMN - 12);
        h.append("[ ").append(s.cycles()).append(" cyc").append(s.calls() ? " + calls" : "").append(" ]");
        return h.toString();
    }

    static void pad(StringBuilder line, int column) {
        line.append(" ".repeat(Math.max(1, column - line.length())));
    }
}
