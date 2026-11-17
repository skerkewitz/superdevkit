package dampf.backend;

import dampf.target.Cycles65816;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes ca65 source with aligned operands and comments, and the CPU cycles of every instruction
 * in a column on the right ({@link Cycles65816}; a conditional branch shows "not taken/taken").
 *
 * <p>It follows the accumulator width through the code ({@code SEP/REP #$20}; index registers are
 * always 16 bit in dampf's code). A C line started with {@link #sourceHeader} gets the sum of its
 * instructions when it is straight-line code; a line with branches or jumps gets none, because
 * its cost depends on the path (the static timing analysis gives best and worst case, see
 * docs/timing.md). Calls count only the call itself and are marked "+ calls".
 */
final class AsmWriter {
    private static final int OPERAND_COLUMN = 16;
    private static final int COMMENT_COLUMN = 36;
    private static final int CYCLE_COLUMN = 92;

    private final List<String> lines = new ArrayList<>();
    private boolean m16 = true;

    // the C line being written
    private int header = -1;
    private int lineCycles;
    private boolean lineBranches;
    private boolean lineCalls;

    /** Instructions whose cycles could not be determined (should never happen; tests check it). */
    private int unknown;

    /** Bytes of code written so far, and where each label is (for choosing short branches). */
    private int position;
    private final java.util.Map<String, Integer> labelAt = new java.util.HashMap<>();

    int position() {
        return position;
    }

    Integer labelAt(String name) {
        return labelAt.get(name);
    }

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
        int op = Cycles65816.opcode(mnemonic, Cycles65816.mode(mnemonic, operand));
        if (op >= 0) {
            position += Cycles65816.length(op, m16, true);
        }
        String cycles;
        if (op < 0) {
            unknown++;
            cycles = "?";
        } else if (Cycles65816.isConditionalBranch(op)) {
            cycles = Cycles65816.cycles(op, m16, true, false) + "/" + Cycles65816.cycles(op, m16, true, true);
            lineBranches = true;
        } else {
            int c = Cycles65816.cycles(op, m16, true, false);
            cycles = Integer.toString(c);
            lineCycles += c;
            if (mnemonic.equals("jmp") || mnemonic.equals("bra") || mnemonic.equals("jml")) {
                lineBranches = true;
            }
            if (mnemonic.equals("jsl") || mnemonic.equals("jsr")) {
                lineCalls = true;
            }
        }
        if (comment == null) {
            pad(line, COMMENT_COLUMN);
            line.append(';');
        }
        pad(line, CYCLE_COLUMN);
        line.append(" ".repeat(Math.max(0, 5 - cycles.length()))).append(cycles);
        lines.add(line.toString());
        if ((mnemonic.equals("sep") || mnemonic.equals("rep")) && operand != null && operand.equals("#$20")) {
            m16 = mnemonic.equals("rep");
        }
    }

    /** Starts a C line: its header comment, which gets the line's cycles when the line is done. */
    void sourceHeader(String text) {
        finishLine();
        header = lines.size();
        lines.add(text);
    }

    void label(String name) {
        labelAt.put(name, position);
        lines.add(name + ":");
        lineBranches = true; // a label inside a line: something jumps here
    }

    /** A line written as is. */
    void raw(String text) {
        lines.add(text);
    }

    /** A comment indented like an instruction. */
    void note(String text) {
        StringBuilder line = new StringBuilder();
        pad(line, COMMENT_COLUMN);
        lines.add(line + "; " + text);
    }

    void blank() {
        lines.add("");
    }

    int unknownInstructions() {
        return unknown;
    }

    private void finishLine() {
        if (header >= 0 && !lineBranches && lineCycles > 0) {
            StringBuilder h = new StringBuilder(lines.get(header));
            pad(h, CYCLE_COLUMN - 12);
            h.append("[ ").append(lineCycles).append(" cyc").append(lineCalls ? " + calls" : "").append(" ]");
            lines.set(header, h.toString());
        }
        header = -1;
        lineCycles = 0;
        lineBranches = false;
        lineCalls = false;
    }

    private static void pad(StringBuilder line, int column) {
        line.append(" ".repeat(Math.max(1, column - line.length())));
    }

    @Override
    public String toString() {
        finishLine();
        StringBuilder out = new StringBuilder();
        for (String l : lines) {
            out.append(l).append('\n');
        }
        return out.toString();
    }
}
