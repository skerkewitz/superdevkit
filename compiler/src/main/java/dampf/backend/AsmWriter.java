package dampf.backend;

import dampf.lir.LDirective;
import dampf.lir.LFunction;
import dampf.lir.LInstr;
import dampf.lir.LLabel;
import dampf.lir.LParser;
import dampf.lir.LPrinter;
import dampf.lir.LSourceLine;
import dampf.lir.LUnit;
import java.util.Map;

/**
 * Collects what a code generator writes as LIR ({@link LUnit}); {@link LPrinter} turns it into
 * ca65 source with aligned operands and comments, and the CPU cycles of every instruction in a
 * column on the right.
 *
 * <p>It follows the accumulator width through the code ({@code SEP/REP #$20}; index registers are
 * always 16 bit in dampf's code), so every instruction knows the width it runs in. The cost of a
 * C line comes from {@link Timing#lineCosts} when the text is written.
 */
final class AsmWriter {
    private final LUnit unit = new LUnit();
    private boolean m16 = true;

    /** Instructions whose cycles could not be determined (should never happen; tests check it). */
    private int unknown;

    /** Bytes of code written so far (for choosing short branches). */
    private int position;

    /** The bound of the small loops the code generator writes (label to the most rounds). */
    private final Map<String, Integer> loopBounds = new java.util.HashMap<>();

    int position() {
        return position;
    }

    Map<String, Integer> loopBounds() {
        return loopBounds;
    }

    /** The loop that starts at this label runs at most {@code rounds} times. */
    void loopBound(String label, int rounds) {
        loopBounds.put(label, rounds);
    }

    /** An instruction; {@code operand} and {@code comment} may be null. */
    void insOp(String mnemonic, dampf.lir.LOperand operand, String comment) {
        ins(mnemonic, operand == null ? null : operand.text(), comment);
    }

    void insOp(String mnemonic, dampf.lir.LOperand operand, String comment, boolean relax) {
        ins(mnemonic, operand == null ? null : operand.text(), comment, relax);
    }

    void ins(String mnemonic, String operand, String comment) {
        ins(mnemonic, operand, comment, false);
    }

    /** {@code relax}: a jump or branch in its long form that {@link dampf.lir.BranchRelax} may shorten. */
    void ins(String mnemonic, String operand, String comment, boolean relax) {
        LInstr in = new LInstr(mnemonic, operand, comment, m16, true, relax);
        if (!in.known()) {
            unknown++;
        }
        position += in.length();
        unit.add(in);
        if ((mnemonic.equals("sep") || mnemonic.equals("rep")) && "#$20".equals(operand)) {
            m16 = mnemonic.equals("rep");
        }
    }

    /** Starts a C line: its header comment, which gets the line's cycles when the text is written. */
    void sourceHeader(String text) {
        unit.add(new LSourceLine(text));
    }

    void label(String name) {
        unit.add(new LLabel(name));
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
        unit.addAll(other.unit);
    }

    /** What was written, as LIR. */
    LUnit unit() {
        return unit;
    }

    /** The function written (a writer that holds one). */
    LFunction function() {
        return unit.functions().getFirst();
    }

    int unknownInstructions() {
        return unknown;
    }

    @Override
    public String toString() {
        for (LFunction f : unit.functions()) {
            Timing.lineCosts(f);
        }
        return LPrinter.print(unit);
    }
}
