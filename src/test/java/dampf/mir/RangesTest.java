package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The range analysis on loops longer than its widening limit, and parameter ranges from calls. */
class RangesTest {
    static final String LOOP = """
            global @_a : 1536

            func @_clear() -> void {
            b0_entry:
                jump b1_for
            b1_for:
                %0 = phi i16 [b0_entry, 0], [b2_body, %4]
                %1 = cmp ult i16 %0, 768
                br %1, b2_body, b3_end
            b2_body:
                %2 = shl i16 %0, 1
                %3 = ptradd @_a, i16 %2
                store i16 %3, 0
                %4 = add i16 %0, 1
                jump b1_for
            b3_end:
                ret
            }
            """;

    @Test
    void aLongLoopsCounterIsNarrowedAfterWidening() {
        MModule m = MirParser.parse(LOOP);
        MFunction f = m.function("_clear");
        Ranges r = new Ranges(f);
        Loops.Loop loop = Loops.find(f).getFirst();
        Instr.Phi counter = (Instr.Phi) loop.head().instrs().getFirst();
        assertEquals(new Ranges.Range(0, 768), r.of(counter));
    }

    private static final String CALLS = """
            global @_t : 64

            func @_get(i16 %i, i16 %j) -> i8 {
            b0_entry:
                %0 = ptradd @_t, i16 %i
                %1 = load i8 %0
                ret i8 %1
            }

            func @_main(i16 %n) -> i8 {
            b0_entry:
                %0 = call i8 @_get(i16 3, i16 %n)
                %1 = call i8 @_get(i16 40, i16 %n)
                %2 = add i8 %0, %1
                ret i8 %2
            }
            """;

    @Test
    void parametersGetTheRangeOfTheirArguments() {
        MModule m = MirParser.parse(CALLS);
        assertTrue(ParamRanges.run(m));
        assertEquals(new Ranges.Range(3, 40), m.function("_get").params().get(0).known);
        assertNull(m.function("_get").params().get(1).known);   // %n is anything: no range
        assertNull(m.function("_main").params().getFirst().known);   // no caller
        Interpreter in = new Interpreter(m, 10_000);
        in.observe(Ranges.checker(m));
        in.call("_main", List.of(5L));
    }
}
