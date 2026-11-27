package dampf.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dampf.mir.MFunction;
import dampf.mir.MirParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How often blocks run without a profile: loops 8 times, a branch's ways share equally. */
class WeightsTest {
    private static final String NESTED = """
            func @_run(i16 %n) -> void {
            b0_entry:
                jump b1_for
            b1_for:
                %0 = phi i16 [b0_entry, 0], [b4_step, %9]
                %1 = cmp ult i16 %0, 48
                br %1, b2_body, b7_end
            b2_body:
                %4 = cmp eq i16 %0, %n
                br %4, b3_rare, b5_inner
            b3_rare:
                jump b4_step
            b5_inner:
                %5 = phi i16 [b2_body, 0], [b6_in, %6]
                %7 = cmp ult i16 %5, 4
                br %7, b6_in, b4_step
            b6_in:
                %6 = add i16 %5, 1
                jump b5_inner
            b4_step:
                %9 = add i16 %0, 6
                jump b1_for
            b7_end:
                ret
            }
            """;

    @Test
    void branchesShareAndLoopsRunEightTimes() {
        MFunction f = MirParser.parse(NESTED).function("_run");
        Map<String, Double> w = Weights.of(f, null);
        assertEquals(1.0, w.get("b0_entry"));
        assertEquals(8.0, w.get("b1_for"));
        assertEquals(4.0, w.get("b2_body"));       // the loop's test: half stays
        assertEquals(2.0, w.get("b3_rare"));
        assertEquals(16.0, w.get("b5_inner"));     // entered 2 times per entry, 8 runs each
        assertEquals(8.0, w.get("b6_in"));
        assertEquals(4.0, w.get("b4_step"));       // both ways of the body meet again
        assertEquals(1.0, w.get("b7_end"));        // left once per entry
    }

    @Test
    void aProfileWins() {
        MFunction f = MirParser.parse(NESTED).function("_run");
        Map<String, Double> w = Weights.of(f, name -> name.equals("b3_rare") ? 7L : null);
        assertEquals(7.0, w.get("b3_rare"));
    }
}
