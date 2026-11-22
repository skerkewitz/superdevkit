package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A 32-bit comparison with a sum of 16-bit values, as a test that it fits and a 16-bit comparison. */
class CompareSumTest {
    private static final long[] VALUES = {-32768, -32767, -32736, -32735, -33, -32, -31, -1, 0, 1, 31, 32, 33, 32734, 32735, 32736, 32766, 32767};
    private static final long[] CONSTANTS = {1, 2, 32, 255, 16384, 32767, -1, -2, -32, -255, -16384, -32767};
    private static final String[] PREDS = {"eq", "ne", "slt", "sle", "sgt", "sge"};

    private static String program(String pred, long k, boolean sumRight) {
        String cmp = sumRight ? "%x, %s" : "%s, %x";
        return """
                func @_f(i16 %%a, i16 %%b) -> i16 {
                b0_entry:
                    %%x = sext i16 %%a to i32
                    %%y = sext i16 %%b to i32
                    %%s = add i32 %%y, %d
                    %%c = cmp %s i32 %s
                    br %%c, b1_yes, b2_no
                b1_yes:
                    jump b3_join
                b2_no:
                    jump b3_join
                b3_join:
                    %%r = phi i16 [b1_yes, 11], [b2_no, 22]
                    ret i16 %%r
                }
                """.formatted(k, pred, cmp);
    }

    @Test
    void everyComparisonGivesWhatTheThirtyTwoBitOneGives() {
        for (String pred : PREDS) {
            for (long k : CONSTANTS) {
                for (boolean right : new boolean[] {true, false}) {
                    MModule plain = MirParser.parse(program(pred, k, right));
                    MModule split = MirParser.parse(program(pred, k, right));
                    assertTrue(CompareSum.run(split.function("_f")), pred + " " + k);
                    Verifier.verify(split);
                    Interpreter want = new Interpreter(plain, 1_000_000);
                    Interpreter got = new Interpreter(split, 1_000_000);
                    for (long a : VALUES) {
                        for (long b : VALUES) {
                            List<Long> args = List.of(MType.I16.wrap(a), MType.I16.wrap(b));
                            assertEquals(want.call("_f", args), got.call("_f", args), a + " " + pred + " " + b + " + " + k + " (sum right: " + right + ")");
                        }
                    }
                }
            }
        }
    }

    @Test
    void aSumOfAThirtyTwoBitValueStays() {
        MModule m = MirParser.parse("""
                func @_f(i32 %a, i32 %b) -> i16 {
                b0_entry:
                    %s = add i32 %b, 32
                    %c = cmp sgt i32 %a, %s
                    br %c, b1_yes, b2_no
                b1_yes:
                    ret i16 1
                b2_no:
                    ret i16 0
                }
                """);
        assertFalse(CompareSum.run(m.function("_f")));
    }

    @Test
    void aTestThatLeavesALoopStays() {
        MModule m = MirParser.parse("""
                func @_f(i16 %n) -> i16 {
                b0_entry:
                    jump b1_head
                b1_head:
                    %i = phi i16 [b0_entry, 0], [b2_body, %j]
                    %x = sext i16 %i to i32
                    %y = sext i16 %n to i32
                    %s = add i32 %y, 2
                    %c = cmp slt i32 %x, %s
                    br %c, b2_body, b3_end
                b2_body:
                    %j = add i16 %i, 1
                    jump b1_head
                b3_end:
                    ret i16 %i
                }
                """);
        assertFalse(CompareSum.run(m.function("_f")));
    }
}
