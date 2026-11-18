package dampf.backend;

import dampf.lir.LDirective;
import dampf.lir.LInstr;
import dampf.lir.LLabel;
import dampf.lir.LParser;
import dampf.lir.LPrinter;
import dampf.lir.LSourceLine;
import dampf.lir.LUnit;
import dampf.target.Cycles65816;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects what a code generator writes as LIR ({@link LUnit}); {@link LPrinter} turns it into
 * ca65 source with aligned operands and comments, and the CPU cycles of every instruction in a
 * column on the right ({@link Cycles65816}; a conditional branch shows "not taken/taken").
 *
 * <p>It follows the accumulator width through the code ({@code SEP/REP #$20}; index registers are
 * always 16 bit in dampf's code). A C line started with {@link #sourceHeader} gets the sum of its
 * instructions when it is straight-line code; a line with branches or jumps gets none, because
 * its cost depends on the path (the static timing analysis gives best and worst case, see
 * docs/timing.md). Calls count only the call itself and are marked "+ calls".
 */
final class AsmWriter {
    private final LUnit unit = new LUnit();
    private boolean m16 = true;

    // the C line being written
    private LSourceLine header;
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

    /** Short branches (conditional and BRA): where each starts, and its target. */
    private final List<Object[]> shortBranches = new ArrayList<>();

    /** Whether every short branch reaches its label (-128..127 from the next instruction). */
    boolean branchesReach() {
        for (Object[] b : shortBranches) {
            Integer to = labelAt.get((String) b[1]);
            if (to == null) {
                continue;   // a label outside this code (none in practice)
            }
            int d = to - ((Integer) b[0] + 2);
            if (d < -128 || d > 127) {
                return false;
            }
        }
        return true;
    }

    /**
     * What was written, for the timing analysis: instructions with their CPU cycles (a
     * conditional branch: not taken, taken) and labels; and the bound of the small loops the code
     * generator writes (label to the most rounds).
     */
    record Item(String mnemonic, String operand, int cycles, int taken, boolean conditional, String label) {
        boolean isLabel() {
            return label != null;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final java.util.Map<String, Integer> loopBounds = new java.util.HashMap<>();

    List<Item> items() {
        return items;
    }

    java.util.Map<String, Integer> loopBounds() {
        return loopBounds;
    }

    /** The loop that starts at this label runs at most {@code rounds} times. */
    void loopBound(String label, int rounds) {
        loopBounds.put(label, rounds);
    }

    Integer labelAt(String name) {
        return labelAt.get(name);
    }

    /** An instruction; {@code operand} and {@code comment} may be null. */
    void ins(String mnemonic, String operand, String comment) {
        LInstr in = new LInstr(mnemonic, operand, comment, m16);
        int op = in.opcode();
        if (op >= 0 && in.isShortBranch()) {
            shortBranches.add(new Object[] {position, operand});
        }
        position += in.length();
        if (op < 0) {
            unknown++;
            items.add(new Item(mnemonic, operand, 0, 0, false, null));
        } else if (in.isConditionalBranch()) {
            lineBranches = true;
            items.add(new Item(mnemonic, operand, in.cycles(), in.cyclesTaken(), true, null));
        } else {
            int c = in.cycles();
            items.add(new Item(mnemonic, operand, c, c, false, null));
            lineCycles += c;
            if (mnemonic.equals("jmp") || mnemonic.equals("bra") || mnemonic.equals("jml")) {
                lineBranches = true;
            }
            if (mnemonic.equals("jsl") || mnemonic.equals("jsr")) {
                lineCalls = true;
            }
        }
        unit.add(in);
        if ((mnemonic.equals("sep") || mnemonic.equals("rep")) && operand != null && operand.equals("#$20")) {
            m16 = mnemonic.equals("rep");
        }
    }

    /** Starts a C line: its header comment, which gets the line's cycles when the line is done. */
    void sourceHeader(String text) {
        finishLine();
        header = new LSourceLine(text);
        unit.add(header);
    }

    void label(String name) {
        labelAt.put(name, position);
        items.add(new Item(null, null, 0, 0, false, name));
        unit.add(new LLabel(name));
        lineBranches = true; // a label inside a line: something jumps here
    }

    /** A directive, data or comment line, written as ca65 source (read into LIR by {@link LParser}). */
    void raw(String text) {
        unit.add(LParser.line(text, m16));
    }

    /** A comment indented like an instruction. */
    void note(String text) {
        unit.add(LDirective.note(text));
    }

    void blank() {
        unit.add(LDirective.BLANK_LINE);
    }

    /** Appends what another writer wrote. */
    void append(AsmWriter other) {
        other.finishLine();
        unit.addAll(other.unit);
    }

    /** What was written, as LIR (the cost of the last C line is set). */
    LUnit unit() {
        finishLine();
        return unit;
    }

    int unknownInstructions() {
        return unknown;
    }

    private void finishLine() {
        if (header != null && !lineBranches && lineCycles > 0) {
            header.setCycles(lineCycles, lineCalls);
        }
        header = null;
        lineCycles = 0;
        lineBranches = false;
        lineCalls = false;
    }

    @Override
    public String toString() {
        finishLine();
        return LPrinter.print(unit);
    }
}
