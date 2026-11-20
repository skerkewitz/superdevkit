package dampf.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dampf.mir.CountedLoops;
import dampf.mir.MFunction;
import dampf.mir.MirParser;
import dampf.mir.Ranges;
import org.junit.jupiter.api.Test;

class TimingTest {
    @Test
    void aLoopLongerThanTheWideningLimitIsBounded() {
        String loop = """
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
        MFunction f = MirParser.parse(loop).function("_clear");
        assertEquals(769, Timing.headRuns(CountedLoops.find(f).getFirst(), new Ranges(f)));
    }
}
