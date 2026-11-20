package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Loops without effects and without results go when they are proved to end; a loop that might not
 * end, writes memory, or whose value is used after it stays (DeadLoops).
 */
class DeadLoopsTest {
    /** A counting loop: stays while %0 PRED N, steps %0 by STEP; %a is used after it. */
    private static String loop(String type, String pred, long n, long step, String body) {
        return """
                func @_main(%s %%a) -> %s {
                b0_entry:
                    jump b1_head
                b1_head:
                    %%0 = phi %s [b0_entry, %%a], [b2_body, %%2]
                    %%1 = cmp %s %s %%0, %d
                    br %%1, b2_body, b3_end
                b2_body:
                %s
                    %%2 = add %s %%0, %d
                    jump b1_head
                b3_end:
                    ret %s %%a
                }
                """.formatted(type, type, type, pred, type, n, body, type, step, type);
    }

    private static boolean removes(String program) {
        MModule m = MirParser.parse(program);
        MFunction f = m.function("_main");
        DeadCode.run(f);
        boolean removed = DeadLoops.run(f);
        Verifier.verify(m);
        if (removed) {
            assertTrue(Loops.find(f).isEmpty(), "no loop left");
            MModule plain = MirParser.parse(program);
            for (long a : new long[] {0, 1, 5, 9, 200}) {
                assertEquals(new Interpreter(plain, 1_000_000).call("_main", List.of(a)),
                        new Interpreter(m, 1_000_000).call("_main", List.of(a)));
            }
        }
        return removed;
    }

    @Test
    void finiteLoopsWithoutEffectsGo() {
        assertTrue(removes(loop("i16", "ult", 10, 1, "")));
        assertTrue(removes(loop("i16", "ult", 10, 1, "    %9 = mul i16 %0, 3")));   // a result nobody reads
        assertTrue(removes(loop("i8", "ult", 255, 1, "")));                       // 255 + 1 - 1 fits a byte
        assertTrue(removes(loop("i16", "ne", 7, 3, "")));                         // an odd step meets 7
        assertTrue(removes(loop("i16", "sge", -99, -2, "")));                     // counting down, signed (canonical: no sgt)
        assertTrue(removes(loop("i16", "uge", 4, -1, "")));
    }

    @Test
    void loopsThatMightNotEndStay() {
        assertFalse(removes(loop("i16", "ne", 7, 2, "")));       // 2, 4, 6, 8, ...: never 7 from an even start
        assertFalse(removes(loop("i8", "ule", 255, 1, "")));      // every byte is at most 255
        assertFalse(removes(loop("i8", "ult", 255, 2, "")));      // 254 + 2 wraps to 0
        assertFalse(removes(loop("i16", "uge", 0, -1, "")));      // every value is at least 0
        assertFalse(removes(loop("i16", "eq", 7, 1, "")));
        assertFalse(removes("""
                func @_main(i16 %a) -> i16 {
                b0_entry:
                    jump b1_forever
                b1_forever:
                    jump b1_forever
                }
                """));
    }

    @Test
    void loopsWithEffectsOrResultsStay() {
        assertFalse(removes(loop("i16", "ult", 10, 1, "    store i16 @_g, %0").replace("func @_main",
                "global @_g : 2\n\nfunc @_main")));
        assertFalse(removes("""
                func @_main(i16 %a) -> i16 {
                b0_entry:
                    jump b1_head
                b1_head:
                    %0 = phi i16 [b0_entry, %a], [b2_body, %2]
                    %1 = cmp ult i16 %0, 10
                    br %1, b2_body, b3_end
                b2_body:
                    %2 = add i16 %0, 1
                    jump b1_head
                b3_end:
                    ret i16 %0
                }
                """));
    }

    @Test
    void aLoopAroundOneThatMightNotEndStays() {
        assertFalse(removes("""
                func @_main(i16 %a) -> i16 {
                b0_entry:
                    jump b1_outer
                b1_outer:
                    %0 = phi i16 [b0_entry, 0], [b3_next, %4]
                    %1 = cmp ult i16 %0, 10
                    br %1, b2_inner, b4_end
                b2_inner:
                    %2 = phi i16 [b1_outer, %a], [b2_inner, %3]
                    %3 = add i16 %2, 2
                    %5 = cmp ne i16 %3, 7
                    br %5, b2_inner, b3_next
                b3_next:
                    %4 = add i16 %0, 1
                    jump b1_outer
                b4_end:
                    ret i16 %a
                }
                """));
    }
}
