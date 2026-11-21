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

    private static final String ABS = """
            func @_abs(i8 %a, i8 %b) -> i16 {
            b0_entry:
                %0 = zext i8 %a to i16
                %1 = zext i8 %b to i16
                %2 = sub i16 %0, %1
                %3 = cmp slt i16 %2, 0
                br %3, b1_neg, b2_join
            b1_neg:
                %4 = sub i16 0, %2
                jump b2_join
            b2_join:
                %5 = phi i16 [b1_neg, %4], [b0_entry, %2]
                ret i16 %5
            }
            """;

    @Test
    void aPhiOperandHasTheRangeOfItsEdge() {
        // abs of a difference of two bytes: on the edge where x < 0 is false, x is not negative
        MModule m = MirParser.parse(ABS);
        MFunction f = m.function("_abs");
        Ranges r = new Ranges(f);
        Instr.Phi abs = (Instr.Phi) f.blocks().stream().filter(b -> b.name().startsWith("b2")).findFirst().orElseThrow()
                .instrs().getFirst();
        assertEquals(new Ranges.Range(0, 255), r.of(abs));
        Interpreter in = new Interpreter(m, 10_000);
        in.observe(Ranges.checker(m));
        for (long a : new long[] {0, 1, 200, 255}) {
            for (long b : new long[] {0, 1, 200, 255}) {
                in.call("_abs", List.of(a, b));
            }
        }
    }

    /** A loop that stays while counter PRED bound, stepping it from start by step; the counter's range. */
    private static Ranges.Range counter(String type, long start, String pred, long bound, long step) {
        return counter(type, start, pred, bound, step, false);
    }

    /** The same, the signed or the unsigned range. */
    private static Ranges.Range counter(String type, long start, String pred, long bound, long step, boolean unsigned) {
        String text = """
                func @_count() -> i16 {
                b0_entry:
                    jump b1_for
                b1_for:
                    %%0 = phi %s [b0_entry, %d], [b2_body, %%2]
                    %%1 = cmp %s %s %%0, %d
                    br %%1, b2_body, b3_end
                b2_body:
                    %%2 = add %s %%0, %d
                    jump b1_for
                b3_end:
                    %%3 = sext %s %%0 to i16
                    ret i16 %%3
                }
                """.formatted(type, start, pred, type, bound, type, step, type);
        MModule m = MirParser.parse(text);
        MFunction f = m.function("_count");
        Interpreter in = new Interpreter(m, 1_000_000);
        in.observe(Ranges.checker(m));
        in.call("_count", List.of());   // every value lies in its range
        Instr.Phi phi = (Instr.Phi) Loops.find(f).getFirst().head().instrs().getFirst();
        return unsigned ? new Ranges(f).ofUnsigned(phi) : new Ranges(f).of(phi);
    }

    @Test
    void wideningKeepsTheEndThatDidNotMove() {
        // more rounds than WIDEN_AFTER: the end that moves is widened, the other stays
        assertEquals(new Ranges.Range(0, 100), counter("i16", 0, "slt", 100, 1));        // signed, up
        assertEquals(new Ranges.Range(0, 30), counter("i16", 0, "slt", 30, 1));          // past 24 rounds
        assertEquals(new Ranges.Range(-1, 99), counter("i16", 99, "sge", 0, -1));        // signed, down
        assertEquals(new Ranges.Range(0, 300), counter("i16", 0, "ult", 300, 1));        // unsigned
        // a byte counter that really wraps (1, 2, ..., 127, -128, ..., -1, 0): its whole type
        assertEquals(new Ranges.Range(-128, 127), counter("i8", 1, "ne", 0, 1));
    }

    @Test
    void bytesAbove127HaveAnUnsignedRange() {
        // 0..128 and 0..199 are no signed intervals of a byte, but unsigned ones
        assertEquals(new Ranges.Range(0, 129), counter("i8", 0, "ult", 129, 1, true));
        assertEquals(new Ranges.Range(0, 200), counter("i8", 0, "ult", 200, 1, true));
        assertEquals(new Ranges.Range(0, 255), counter("i8", 0, "ult", 255, 1, true));
        assertEquals(new Ranges.Range(-128, 127), counter("i8", 0, "ult", 200, 1));   // the signed reading: all
        // down: 200, 199, ..., 1, and 0 when the loop ends; with != 0, the canonical form of > 0,
        // iterating finds no bound, the counted loop does (CountedLoops)
        assertEquals(new Ranges.Range(0, 200), counter("i8", 200, "uge", 1, -1, true));
        assertEquals(new Ranges.Range(0, 200), counter("i8", 200, "ne", 0, -1, true));
        assertEquals(new Ranges.Range(0, 255), counter("i8", 255, "uge", 1, -1, true));
        // an unsigned condition bounds a byte above 127, and its zero extension gets that range
        MModule m = MirParser.parse("""
                func @_f(i8 %a) -> i16 {
                b0_entry:
                    %0 = cmp ult i8 %a, 200
                    br %0, b1_low, b2_high
                b1_low:
                    %1 = zext i8 %a to i16
                    ret i16 %1
                b2_high:
                    %2 = add i8 %a, -200
                    %3 = zext i8 %2 to i16
                    ret i16 %3
                }
                """);
        MFunction f = m.function("_f");
        Ranges r = new Ranges(f);
        Value low = f.blocks().get(1).instrs().getFirst();
        Value high = f.blocks().get(2).instrs().get(1);
        assertEquals(new Ranges.Range(0, 199), r.of(low));
        assertEquals(new Ranges.Range(0, 55), r.of(high));   // 200..255 - 200
        Interpreter in = new Interpreter(m, 100_000);
        in.observe(Ranges.checker(m));
        for (long a = 0; a < 256; a++) {
            in.call("_f", List.of(a));
        }
    }
    @Test
    void aCounterTestedWithNotEqualHasTheRangeOfItsLoop() {
        // d_u8_ne100 and n_u16_ne100: iterating finds no bound, the counted loop 0..100
        assertEquals(new Ranges.Range(0, 100), counter("i8", 0, "ne", 100, 1, true));
        assertEquals(new Ranges.Range(0, 100), counter("i16", 0, "ne", 100, 1));
        // g_u16_down: in the body i != 0 cuts 0 off, so i - 1 is an index 0..99
        MModule m = MirParser.parse("""
                global @_data : 100

                func @_f() -> i16 {
                b0_entry:
                    jump b1_for
                b1_for:
                    %0 = phi i16 [b0_entry, 100], [b2_body, %3]
                    %1 = phi i16 [b0_entry, 0], [b2_body, %7]
                    %2 = cmp ne i16 %0, 0
                    br %2, b2_body, b4_end
                b2_body:
                    %3 = add i16 %0, -1
                    %4 = ptradd @_data, i16 %3
                    %5 = load i8 %4
                    %6 = zext i8 %5 to i16
                    %7 = add i16 %1, %6
                    jump b1_for
                b4_end:
                    ret i16 %1
                }
                """);
        MFunction f = m.function("_f");
        Ranges r = new Ranges(f);
        Value phi = f.blocks().get(1).instrs().getFirst();
        Value index = f.blocks().get(2).instrs().getFirst();
        assertEquals(new Ranges.Range(0, 100), r.of(phi));
        assertEquals(new Ranges.Range(1, 100), r.at(phi, f.blocks().get(2)));
        assertEquals(new Ranges.Range(0, 99), r.of(index));
        Interpreter in = new Interpreter(m, 100_000);
        in.observe(Ranges.checker(m));
        in.call("_f", List.of());
    }
    @Test
    void aCastMadeBeforeAConditionFollowsItsOperandAfterIt() {
        // zext(n) is computed before the test n < 13; where the test holds, it is 0..12 too
        MModule m = MirParser.parse("""
                func @_f(i8 %n) -> i16 {
                b0_entry:
                    %0 = zext i8 %n to i16
                    %1 = cmp ult i8 %n, 13
                    br %1, b1_small, b2_big
                b1_small:
                    ret i16 %0
                b2_big:
                    ret i16 0
                }
                """);
        MFunction f = m.function("_f");
        Ranges r = new Ranges(f);
        Value z = f.blocks().getFirst().instrs().getFirst();
        assertEquals(new Ranges.Range(0, 255), r.of(z));
        assertEquals(new Ranges.Range(0, 12), r.at(z, f.blocks().get(1)));
    }

    private static final String UNTIL_NOT_EQUAL = """
            global @_n : 2
            global @_a : 128

            func @_f() -> void {
            b0_entry:
                jump b1_while
            b1_while:
                %0 = phi i16 [b0_entry, 1], [b5_next, %3]
                %1 = load i16 @_n
                %2 = cmp ne i16 %0, %1
                br %2, b2_do, b3_end
            b2_do:
                %4 = and i16 %0, 63
                %5 = mul i16 %4, 2
                %6 = ptradd @_a, i16 %5
                %7 = load i16 %6
                %8 = cmp ne i16 %7, 0
                br %8, b3_end, b4_step
            b4_step:
                %9 = cmp ugt i16 %0, %0
                br %9, b3_end, b5_next
            b5_next:
                %3 = add i16 %0, 1
                jump b1_while
            b3_end:
                ret
            }
            """;

    @Test
    void wideningIsNotTakenBackByTheCountersOwnBound() {
        // while (c != n) c++: what the loop's test says about c comes from c's own range so far;
        // cutting the widened range back to it every round grew it by one a round (a random loop
        // program, seed 29469, took 77 s to compile)
        MFunction f = MirParser.parse(UNTIL_NOT_EQUAL).function("_f");
        Ranges r = new Ranges(f);
        assertTrue(r.rounds() < 8 * Ranges.WIDEN_AFTER, "rounds: " + r.rounds());   // two passes; without the fix 131,074
    }
}
