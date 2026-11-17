package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Inlining on hand-written MIR: the call goes, the results stay. */
class InlineTest {
    private static final String PROGRAM = """
            global @_bubbles : 32

            func @_floating(ptr %b) -> i8 {
            b0_entry:
                %0 = load i8 %b
                %1 = cmp eq i8 %0, 2
                br %1, b1, b2
            b1:
                jump b3
            b2:
                %2 = cmp eq i8 %0, 3
                %3 = zext i1 %2 to i8
                jump b3
            b3:
                %4 = phi i8 [b1, 1], [b2, %3]
                ret i8 %4
            }

            func @_count() -> i16 {
            b0_entry:
                jump b1
            b1:
                %0 = phi i16 [b0_entry, 0], [b4, %7]
                %1 = phi i16 [b0_entry, 0], [b4, %8]
                %2 = cmp ult i16 %0, 8
                br %2, b2, b3
            b2:
                %3 = shl i16 %0, 2
                %4 = ptradd @_bubbles, i16 %3
                %5 = call i8 @_floating(ptr %4)
                %6 = cmp ne i8 %5, 0
                br %6, b5, b4
            b5:
                %9 = add i16 %1, 1
                jump b4
            b4:
                %8 = phi i16 [b2, %1], [b5, %9]
                %7 = add i16 %0, 1
                jump b1
            b3:
                ret i16 %1
            }
            """;

    private static long run(MModule m) {
        Interpreter in = new Interpreter(m, 1_000_000);
        int base = in.addressOf("_bubbles");
        int[] states = {2, 0, 3, 1, 2, 3, 3, 0};
        for (int k = 0; k < states.length; k++) {
            in.call("_set", List.of((long) base + 4 * k, (long) states[k]));
        }
        return in.call("_count", List.of());
    }

    private static final String SETTER = """

            func @_set(ptr %p, i8 %v) -> void {
            b0_entry:
                store i8 %p, %v
                ret
            }
            """;

    @Test
    void aSmallFunctionIsInlinedAndTheResultStaysTheSame() {
        MModule plain = MirParser.parse(PROGRAM + SETTER);
        MModule inlined = MirParser.parse(PROGRAM + SETTER);
        Optimizer.run(inlined, true);
        String count = Printer.print(inlined.function("_count"));
        assertFalse(count.contains("call"), count);
        assertEquals(5, run(plain));
        assertEquals(5, run(inlined));
    }
}
