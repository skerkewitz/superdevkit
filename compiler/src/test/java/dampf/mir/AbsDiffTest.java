package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The absolute difference of two signed values becomes one instruction ({@link AbsDiff}): the
 * result must be the same as before for every pair of edge values, and the φ must be gone where
 * the pattern holds (and stay where it does not).
 */
class AbsDiffTest {
    private static final long[] EDGES = {0x8000, 0x8001, 0x8AD0, 0xFC18, 0xFFFE, 0xFFFF, 0, 1, 2, 1000, 30000, 0x7FFE, 0x7FFF};

    /** {@code cmp PRED %x, %y}, then THEN on the true side and ELSE on the false side (each a sub or an operand). */
    private static String diamond(String pred, String a, String b, String thenValue, String elseValue) {
        return "func @_f(i16 %x, i16 %y) -> i16 {\nb0_entry:\n"
                + "    %0 = cmp " + pred + " i16 " + a + ", " + b + "\n    br %0, b1_then, b2_else\n"
                + "b1_then:\n    %1 = " + thenValue + "\n    jump b3_cond\n"
                + "b2_else:\n    %2 = " + elseValue + "\n    jump b3_cond\n"
                + "b3_cond:\n    %3 = phi i16 [b1_then, %1], [b2_else, %2]\n    ret i16 %3\n}\n";
    }

    /** |v| as {@code v < 0 ? -v : v}: one arm, the other way straight from the branch. */
    private static String abs(String pred, String negatedWhen) {
        boolean thenNegates = negatedWhen.equals("true");
        return "func @_f(i16 %x, i16 %y) -> i16 {\nb0_entry:\n"
                + "    %0 = sub i16 %x, %y\n"
                + "    %1 = cmp " + pred + " i16 %0, 0\n    br %1, " + (thenNegates ? "b1_then, b3_cond" : "b3_cond, b1_then") + "\n"
                + "b1_then:\n    %2 = sub i16 0, %0\n    jump b3_cond\n"
                + "b3_cond:\n    %3 = phi i16 [b1_then, %2], [b0_entry, %0]\n    ret i16 %3\n}\n";
    }

    private static void same(String text, boolean replaced) {
        MModule plain = MirParser.parse(text);
        MModule changed = MirParser.parse(text);
        MFunction f = changed.functions().getFirst();
        assertEquals(replaced, AbsDiff.run(f), () -> Printer.print(changed));
        for (int round = 0; round < 10 && (Simplify.run(f) | DeadCode.run(f)); round++) {
            // as the optimizer repeats them
        }
        Verifier.verify(changed);
        if (replaced) {
            assertEquals(1, f.blocks().size(), () -> "one block:\n" + Printer.print(changed));
        }
        Interpreter a = new Interpreter(plain, Long.MAX_VALUE);
        Interpreter b = new Interpreter(changed, Long.MAX_VALUE);
        for (long x : EDGES) {
            for (long y : EDGES) {
                assertEquals(a.call("_f", List.of(x, y)), b.call("_f", List.of(x, y)),
                        () -> "x = " + x + ", y = " + y + ":\n" + Printer.print(changed));
            }
        }
    }

    @Test
    void everyRelationAndOrder() {
        same(diamond("sgt", "%x", "%y", "sub i16 %x, %y", "sub i16 %y, %x"), true);
        same(diamond("sge", "%x", "%y", "sub i16 %x, %y", "sub i16 %y, %x"), true);
        same(diamond("slt", "%x", "%y", "sub i16 %y, %x", "sub i16 %x, %y"), true);
        same(diamond("sle", "%x", "%y", "sub i16 %y, %x", "sub i16 %x, %y"), true);
        same(diamond("sgt", "%y", "%x", "sub i16 %y, %x", "sub i16 %x, %y"), true);
        same(diamond("slt", "%y", "%x", "sub i16 %x, %y", "sub i16 %y, %x"), true);
    }

    @Test
    void theAbsoluteValue() {
        same(abs("slt", "true"), true);
        same(abs("sle", "true"), true);
        same(abs("sge", "false"), true);
        same(abs("sgt", "false"), true);
    }

    @Test
    void notTheDifference() {
        // the arms the wrong way round: -|x - y|
        same(diamond("sgt", "%x", "%y", "sub i16 %y, %x", "sub i16 %x, %y"), false);
        // unsigned
        same(diamond("ugt", "%x", "%y", "sub i16 %x, %y", "sub i16 %y, %x"), false);
        // an arm that is not a difference of the two
        same(diamond("sgt", "%x", "%y", "sub i16 %x, %y", "add i16 %y, %x"), false);
        same(diamond("sgt", "%x", "%y", "sub i16 %x, 1", "sub i16 %y, %x"), false);
        // the negation where v is not negative
        same(abs("slt", "false"), false);
    }
}
