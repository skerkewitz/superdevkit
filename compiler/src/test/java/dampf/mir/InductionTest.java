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
        // i < 30 is i <= 29, now offset <= 87 (offset = i * 3), so offset < 88, and the 8-bit
        // counter is gone
        assertTrue(up.contains("cmp ult i16") && up.contains(", 88"), up);
        assertFalse(up.contains("phi i8"), up);
    }
    @Test
    void twoCountersWithOneStartAndStepAreOne() {
        // a counter and the offset of a pointer walked with it (x_ptr_walk); a third counter
        // with another start stays
        String text = """
                global @_t : 100

                func @_walk(i16 %s) -> i16 {
                b0_entry:
                    jump b1
                b1:
                    %0 = phi i16 [b0_entry, 0], [b2, %5]
                    %1 = phi i16 [b0_entry, 0], [b2, %7]
                    %2 = phi i16 [b0_entry, 0], [b2, %6]
                    %10 = phi i16 [b0_entry, 1], [b2, %11]
                    %3 = cmp ult i16 %0, 100
                    br %3, b2, b3
                b2:
                    %4 = ptradd @_t, i16 %1
                    %8 = load i8 %4
                    %9 = zext i8 %8 to i16
                    %12 = add i16 %9, %10
                    %6 = add i16 %2, %12
                    %5 = add i16 %0, 1
                    %7 = add i16 %1, 1
                    %11 = add i16 %10, 1
                    jump b1
                b3:
                    ret i16 %2
                }
                """;
        MModule m = MirParser.parse(text);
        MFunction f = m.function("_walk");
        assertTrue(Induction.run(f));
        DeadCode.run(f);
        Verifier.verify(m);
        String printed = Printer.print(f);
        assertEquals(3, Loops.find(f).getFirst().head().instrs().stream().filter(x -> x instanceof Instr.Phi).count(), printed);
        assertEquals(new Interpreter(MirParser.parse(text), 100_000).call("_walk", List.of(0L)),
                new Interpreter(m, 100_000).call("_walk", List.of(0L)));
    }

    // the forms of L4 (docs/current-compiler-flaws.md): i - 1 from 1 to n, (i - 1) * 2 counting
    // down, r * 16 + c in a table, a stride of 5 (strength reduction makes it x * 4 + x), a
    // byte counter to a byte bound that may be 0, and a counter something else reads
    private static final String FORMS = """
            global @_t : 256

            func @_from1(i16 %n) -> i16 {
            b0_entry:
                %9 = and i16 %n, 127
                jump b1
            b1:
                %0 = phi i16 [b0_entry, 1], [b2, %5]
                %1 = phi i16 [b0_entry, 0], [b2, %6]
                %2 = cmp ult i16 %0, %9
                br %2, b2, b3
            b2:
                %3 = add i16 %0, -1
                %7 = ptradd @_t, i16 %3
                %8 = load i8 %7
                %10 = zext i8 %8 to i16
                %6 = add i16 %1, %10
                %5 = add i16 %0, 1
                jump b1
            b3:
                ret i16 %1
            }

            func @_down2(i16 %n) -> i16 {
            b0_entry:
                %9 = and i16 %n, 63
                jump b1
            b1:
                %0 = phi i16 [b0_entry, %9], [b2, %5]
                %1 = phi i16 [b0_entry, 0], [b2, %6]
                %2 = cmp ne i16 %0, 0
                br %2, b2, b3
            b2:
                %5 = add i16 %0, -1
                %3 = mul i16 %5, 2
                %7 = ptradd @_t, i16 %3
                %8 = load i16 %7
                %6 = add i16 %1, %8
                jump b1
            b3:
                ret i16 %1
            }

            func @_rows(i16 %r) -> i16 {
            b0_entry:
                %9 = and i16 %r, 7
                %4 = mul i16 %9, 16
                jump b1
            b1:
                %0 = phi i16 [b0_entry, 0], [b2, %5]
                %1 = phi i16 [b0_entry, 0], [b2, %6]
                %2 = cmp ult i16 %0, 16
                br %2, b2, b3
            b2:
                %3 = add i16 %4, %0
                %7 = ptradd @_t, i16 %3
                %8 = load i8 %7
                %10 = zext i8 %8 to i16
                %6 = add i16 %1, %10
                %5 = add i16 %0, 1
                jump b1
            b3:
                ret i16 %1
            }

            func @_stride5(i16 %n) -> i16 {
            b0_entry:
                %9 = and i16 %n, 31
                jump b1
            b1:
                %0 = phi i16 [b0_entry, %9], [b2, %5]
                %1 = phi i16 [b0_entry, 0], [b2, %6]
                %2 = cmp ne i16 %0, 0
                br %2, b2, b3
            b2:
                %5 = add i16 %0, -1
                %3 = mul i16 %5, 5
                %7 = ptradd @_t, i16 %3
                %8 = load i8 %7
                %10 = zext i8 %8 to i16
                %6 = add i16 %1, %10
                jump b1
            b3:
                ret i16 %1
            }

            func @_bytes(i8 %n) -> i16 {
            b0_entry:
                jump b1
            b1:
                %0 = phi i8 [b0_entry, 0], [b2, %5]
                %1 = phi i16 [b0_entry, 0], [b2, %6]
                %2 = cmp ult i8 %0, %n
                br %2, b2, b3
            b2:
                %3 = zext i8 %0 to i16
                %7 = ptradd @_t, i16 %3
                %8 = load i8 %7
                %10 = zext i8 %8 to i16
                %6 = add i16 %1, %10
                %5 = add i8 %0, 1
                jump b1
            b3:
                ret i16 %1
            }

            func @_keeps(i16 %n) -> i16 {
            b0_entry:
                %9 = and i16 %n, 63
                jump b1
            b1:
                %0 = phi i16 [b0_entry, 0], [b2, %5]
                %1 = phi i16 [b0_entry, 0], [b2, %6]
                %2 = cmp ult i16 %0, %9
                br %2, b2, b3
            b2:
                %3 = mul i16 %0, 3
                %7 = ptradd @_t, i16 %3
                %8 = load i8 %7
                %10 = zext i8 %8 to i16
                %11 = add i16 %1, %10
                %6 = add i16 %11, %0
                %5 = add i16 %0, 1
                jump b1
            b3:
                ret i16 %1
            }

            func @_fill() -> void {
            b0_entry:
                jump b1
            b1:
                %0 = phi i16 [b0_entry, 0], [b2, %2]
                %1 = cmp ult i16 %0, 256
                br %1, b2, b3
            b2:
                %3 = ptradd @_t, i16 %0
                %4 = mul i16 %0, 7
                %5 = trunc i16 %4 to i8
                store i8 %3, %5
                %2 = add i16 %0, 1
                jump b1
            b3:
                ret
            }
            """;

    /** The phis of the head of f's (one) loop. */
    private static long headPhis(MModule m, String f) {
        Loops.Loop l = Loops.find(m.function(f)).getFirst();
        return l.head().instrs().stream().filter(x -> x instanceof Instr.Phi).count();
    }

    @Test
    void affineValuesBecomeVariablesAndTheCounterGoes() {
        MModule plain = MirParser.parse(FORMS);
        MModule optimized = MirParser.parse(FORMS);
        Optimizer.run(optimized, true, false);
        // one variable for the index and the sum, the counter gone (rows: the index r * 16 + c)
        for (String f : List.of("_from1", "_down2", "_rows", "_stride5", "_bytes")) {
            assertEquals(2, headPhis(optimized, f), Printer.print(optimized.function(f)));
        }
        assertFalse(Printer.print(optimized.function("_bytes")).contains("phi i8"));
        // read by the sum too: the counter stays beside its product
        assertEquals(3, headPhis(optimized, "_keeps"), Printer.print(optimized.function("_keeps")));
        for (String f : List.of("_from1", "_down2", "_rows", "_stride5", "_bytes", "_keeps")) {
            for (long a : new long[] {0, 1, 2, 5, 13, 31, 63, 64, 127, 128, 255}) {
                Interpreter want = new Interpreter(plain, 10_000_000);
                Interpreter got = new Interpreter(optimized, 10_000_000);
                got.observe(Ranges.checker(optimized));
                want.call("_fill", List.of());
                got.call("_fill", List.of());
                assertEquals(want.call(f, List.of(a)), got.call(f, List.of(a)), f + "(" + a + ")\n" + Printer.print(optimized.function(f)));
            }
        }
    }
    @Test
    void aCounterReadOnlyByItsTestGivesItToASiblingCounter() {
        // copy_while_n: n counts down from 100 to 0, the pointers' offset up by 1 (o = 100 - n);
        // fill_ptr_walk: i < 64 beside an offset stepped by 2 (o = 2 * i)
        String text = """
                global @_t : 128

                func @_down() -> void {
                b0_entry:
                    jump b1
                b1:
                    %0 = phi i16 [b0_entry, 100], [b2, %5]
                    %1 = phi i16 [b0_entry, 0], [b2, %6]
                    %3 = cmp ne i16 %0, 0
                    br %3, b2, b3
                b2:
                    %4 = ptradd @_t, i16 %1
                    store i8 %4, 7
                    %5 = add i16 %0, -1
                    %6 = add i16 %1, 1
                    jump b1
                b3:
                    ret
                }

                func @_words() -> void {
                b0_entry:
                    jump b1
                b1:
                    %0 = phi i16 [b0_entry, 0], [b2, %5]
                    %1 = phi i16 [b0_entry, 0], [b2, %6]
                    %3 = cmp ult i16 %0, 64
                    br %3, b2, b3
                b2:
                    %4 = ptradd @_t, i16 %1
                    store i16 %4, 7
                    %5 = add i16 %0, 1
                    %6 = add i16 %1, 2
                    jump b1
                b3:
                    ret
                }
                """;
        MModule m = MirParser.parse(text);
        Optimizer.run(m, true, false);
        for (String f : List.of("_down", "_words")) {
            assertEquals(1, headPhis(m, f), Printer.print(m.function(f)));
            Interpreter want = new Interpreter(MirParser.parse(text), 100_000);
            Interpreter got = new Interpreter(m, 100_000);
            got.observe(Ranges.checker(m));
            want.call(f, List.of());
            got.call(f, List.of());
            for (int a = 0; a < 128; a++) {
                assertEquals(want.load(want.addressOf("_t") + a, 1), got.load(got.addressOf("_t") + a, 1), f + " t[" + a + "]");
            }
        }
    }

    @Test
    void besideACounterThatStaysAWordIndexIsShiftedWhereItIsRead() {
        // the counter is also added to the sum, so it stays: i * 2 gets no variable of its own
        // (one shift where it is read costs less than a second variable, K2); i * 3 does
        String text = """
                global @_w : 240

                func @_words() -> i16 {
                b0_entry:
                    jump b1
                b1:
                    %0 = phi i16 [b0_entry, 0], [b2, %5]
                    %1 = phi i16 [b0_entry, 0], [b2, %6]
                    %2 = cmp ult i16 %0, 40
                    br %2, b2, b3
                b2:
                    %3 = mul i16 %0, FACTOR
                    %7 = ptradd @_w, i16 %3
                    %8 = load i16 %7
                    %9 = add i16 %8, %0
                    %6 = add i16 %1, %9
                    %5 = add i16 %0, 1
                    jump b1
                b3:
                    ret i16 %1
                }
                """;
        for (int factor : new int[] { 2, 3 }) {
            String program = text.replace("FACTOR", Integer.toString(factor));
            MModule plain = MirParser.parse(program);
            MModule optimized = MirParser.parse(program);
            Optimizer.run(optimized, true, false);
            String words = Printer.print(optimized.function("_words"));
            long phis = words.lines().filter(l -> l.contains("= phi")).count();
            assertEquals(factor == 2 ? 2 : 3, phis, words);
            assertEquals(new Interpreter(plain, 1_000_000).call("_words", List.of()),
                    new Interpreter(optimized, 1_000_000).call("_words", List.of()));
        }
    }
}
