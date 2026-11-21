package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Two loops over the rows of a table as one loop (Collapse): the nest it collapses, with the
 * results the same in the interpreter, and one nest for each reason it must not.
 */
class CollapseTest {
    /**
     * The sum of a table row by row, as the optimizer leaves table-2d's rows_u16: the outer loop
     * steps the offset %1 from START by STEP while %1 < LIMIT, the inner loop steps %5 by INNER
     * from %1 while %5 < %1 + STEP. BODY is extra code in the inner loop's body.
     */
    private static String rows(long start, long step, long limit, long inner, String body) {
        return """
                global @_t : 256

                func @_rows() -> i16 {
                b0_entry:
                    jump b1_for
                b1_for:
                    %%0 = phi i16 [b0_entry, 0], [b5_for, %%4]
                    %%1 = phi i16 [b0_entry, %d], [b5_for, %%2]
                    %%2 = add i16 %%1, %d
                    %%3 = cmp ult i16 %%1, %d
                    br %%3, b5_for, b4_endfor
                b4_endfor:
                    ret i16 %%0
                b5_for:
                    %%4 = phi i16 [b6_body, %%10], [b1_for, %%0]
                    %%5 = phi i16 [b6_body, %%11], [b1_for, %%1]
                    %%6 = cmp ult i16 %%5, %%2
                    br %%6, b6_body, b1_for
                b6_body:
                    %%7 = ptradd @_t, i16 %%5
                    %%8 = load i8 %%7
                    %%9 = zext i8 %%8 to i16
                %s
                    %%10 = add i16 %%4, %%9
                    %%11 = add i16 %%5, %d
                    jump b5_for
                }

                func @_fill() -> void {
                b0_entry:
                    jump b1
                b1:
                    %%0 = phi i16 [b0_entry, 0], [b2, %%2]
                    %%1 = cmp ult i16 %%0, 256
                    br %%1, b2, b3
                b2:
                    %%3 = ptradd @_t, i16 %%0
                    %%4 = mul i16 %%0, 7
                    %%5 = trunc i16 %%4 to i8
                    store i8 %%3, %%5
                    %%2 = add i16 %%0, 1
                    jump b1
                b3:
                    ret
                }
                """.formatted(start, step, limit, body, inner);
    }

    /** Collapse on @_rows: whether it changed something, and that the result is the same. */
    private static boolean collapses(String program) {
        MModule before = MirParser.parse(program);
        MModule after = MirParser.parse(program);
        Verifier.verify(after);
        boolean changed = Collapse.run(after.function("_rows"));
        Verifier.verify(after);
        assertEquals(run(before), run(after), Printer.print(after.function("_rows")));
        return changed;
    }

    private static long run(MModule m) {
        Interpreter in = new Interpreter(m, 1_000_000);
        in.call("_fill", List.of());
        return in.call("_rows", List.of());
    }

    @Test
    void theRowsOfATableAreOneLoop() {
        MModule m = MirParser.parse(rows(0, 16, 113, 1, ""));
        assertTrue(Collapse.run(m.function("_rows")));
        String text = Printer.print(m.function("_rows"));
        // the inner loop walks to the end of the last row and leaves to where the outer loop left
        assertTrue(text.lines().anyMatch(l -> l.matches(".*= cmp ult i16 %\\d+, 128\\b.*")), text);
        assertTrue(text.lines().anyMatch(l -> l.matches(" *br %\\d+, b6_body, b4_endfor\\b.*")), text);
        assertTrue(collapses(rows(0, 16, 113, 1, "")));
    }

    @Test
    void rowsThatDoNotFillTheBoundAndNoRowsAtAll() {
        assertTrue(collapses(rows(32, 16, 100, 1, "")), "rows 2 to 6: the end is 112");
        assertTrue(collapses(rows(144, 16, 113, 1, "")), "the start is past the bound: no row, as before");
    }

    @Test
    void notWhereTheBodyReadsTheRowsOffset() {
        assertFalse(collapses(rows(0, 16, 113, 1, "    %12 = add i16 %9, %1")));
    }

    @Test
    void aStepThatDividesTheRowAndOneThatDoesNot() {
        assertTrue(collapses(rows(0, 16, 113, 2, "")), "words: x ends at o + 16 as well");
        assertFalse(collapses(rows(0, 16, 113, 3, "")), "16 is no multiple of 3: x passes o + 16");
    }

    @Test
    void notWhereTheEndDoesNotFitTheType() {
        // rows of 16 up to 0xFFF8: the last row ends at 0x10000
        assertFalse(Collapse.run(MirParser.parse(rows(0xFF00, 16, 0xFFF8, 1, "")).function("_rows")));
    }

    @Test
    void notWithWorkPerRow() {
        // a store in the outer loop, after the inner one: one per row
        String program = rows(0, 16, 113, 1, "").replace("""
                    %2 = add i16 %1, 16
                    %3 = cmp ult i16 %1, 113
                    br %3, b5_for, b4_endfor
                """, """
                    %2 = add i16 %1, 16
                    %3 = cmp ult i16 %1, 113
                    br %3, b7_row, b4_endfor
                b7_row:
                    %12 = ptradd @_t, i16 255
                    store i8 %12, 1
                    jump b5_for
                """).replace("[b1_for, %0]", "[b7_row, %0]").replace("[b1_for, %1]", "[b7_row, %1]");
        assertFalse(collapses(program));
    }

    @Test
    void notWhereTheExitHasAnotherWayIn() {
        String program = rows(0, 16, 113, 1, "").replace("""
                b0_entry:
                    jump b1_for
                b1_for:
                    %0 = phi i16 [b0_entry, 0], [b5_for, %4]""", """
                b0_entry:
                    %12 = load i8 @_t
                    %13 = cmp eq i8 %12, 99
                    br %13, b4_endfor, b1_for
                b1_for:
                    %0 = phi i16 [b0_entry, 0], [b5_for, %4]""").replace("""
                b4_endfor:
                    ret i16 %0""", """
                b4_endfor:
                    %14 = phi i16 [b0_entry, 5], [b1_for, %0]
                    ret i16 %14""");
        assertFalse(collapses(program));
    }
}
