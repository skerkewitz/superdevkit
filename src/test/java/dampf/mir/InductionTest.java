package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Induction variables: the multiplication by the element size leaves the loop, the results stay
 * the same, and every value stays in its inferred range (the interpreter checks).
 */
class InductionTest {
    // sum += table[i * 3] for i from start upwards (8-bit counter) and downwards (16-bit counter)
    private static final String PROGRAM = """
            global @_table : 120

            func @_up(i8 %start) -> i16 {
            b0_entry:
                jump b1
            b1:
                %0 = phi i8 [b0_entry, %start], [b2, %5]
                %1 = phi i16 [b0_entry, 0], [b2, %6]
                %2 = cmp ult i8 %0, 30
                br %2, b2, b3
            b2:
                %3 = zext i8 %0 to i16
                %4 = mul i16 %3, 3
                %7 = ptradd @_table, i16 %4
                %8 = load i8 %7
                %9 = zext i8 %8 to i16
                %6 = add i16 %1, %9
                %5 = add i8 %0, 1
                jump b1
            b3:
                ret i16 %1
            }

            func @_down(i16 %start) -> i16 {
            b0_entry:
                jump b1
            b1:
                %0 = phi i16 [b0_entry, %start], [b2, %5]
                %1 = phi i16 [b0_entry, 0], [b2, %6]
                %2 = cmp sgt i16 %0, 0
                br %2, b2, b3
            b2:
                %4 = mul i16 %0, 3
                %7 = ptradd @_table, i16 %4
                %8 = load i8 %7
                %9 = zext i8 %8 to i16
                %6 = add i16 %1, %9
                %5 = add i16 %0, -1
                jump b1
            b3:
                ret i16 %1
            }

            func @_fill() -> void {
            b0_entry:
                jump b1
            b1:
                %0 = phi i16 [b0_entry, 0], [b2, %2]
                %1 = cmp ult i16 %0, 120
                br %1, b2, b3
            b2:
                %3 = ptradd @_table, i16 %0
                %4 = trunc i16 %0 to i8
                store i8 %3, %4
                %2 = add i16 %0, 1
                jump b1
            b3:
                ret
            }
            """;

    private static long[] run(MModule m, boolean check) {
        Interpreter in = new Interpreter(m, 10_000_000);
        if (check) {
            in.observe(Ranges.checker(m));
        }
        in.call("_fill", List.of());
        return new long[] {in.call("_up", List.of(0L)), in.call("_up", List.of(17L)), in.call("_up", List.of(29L)),
                in.call("_down", List.of(39L)), in.call("_down", List.of(5L)), in.call("_down", List.of(0L))};
    }

    @Test
    void theMultiplicationLeavesTheLoopAndTheResultsStay() {
        MModule plain = MirParser.parse(PROGRAM);
        MModule optimized = MirParser.parse(PROGRAM);
        Optimizer.run(optimized, true, false);
        String up = Printer.print(optimized.function("_up"));
        String down = Printer.print(optimized.function("_down"));
        assertFalse(up.contains("mul i16 %3") || up.lines().anyMatch(l -> l.contains("mul") && l.contains("b2")), up);
        assertFalse(down.contains("mul i16 %0"), down);
        long[] want = run(plain, false);
        long[] got = run(optimized, true);
        assertEquals(java.util.Arrays.toString(want), java.util.Arrays.toString(got));
    }

    @Test
    void anOnlyCountingCounterGivesItsTestToTheOffsetAndGoes() {
        MModule optimized = MirParser.parse(PROGRAM);
        Optimizer.run(optimized, true, false);
        String up = Printer.print(optimized.function("_up"));
        // i < 30 is now offset < 90 (offset = i * 3), and the 8-bit counter is gone
        assertTrue(up.contains("cmp ult i16") && up.contains(", 90"), up);
        assertFalse(up.contains("phi i8"), up);
    }
}
