package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 32-bit values only combined bit by bit become two 16-bit values (SplitWords). */
class SplitWordsTest {
    private static long words32(MFunction f) {
        return f.blocks().stream().flatMap(b -> b.instrs().stream()).filter(i -> i.type() == MType.I32).count();
    }

    @Test
    void anXorOfTwoLoadsIsTwoOperationsOnHalves() {
        MModule m = MirParser.parse("""
                global @_a : 4
                global @_b : 4
                func @_f() -> void {
                b0_entry:
                    %0 = load i32 @_a
                    %1 = load i32 @_b
                    %2 = xor i32 %0, %1
                    store i32 @_a, %2
                    ret
                }
                """);
        MFunction f = m.function("_f");
        assertTrue(SplitWords.run(f));
        Verifier.verify(m);
        assertEquals(0, words32(f), Printer.instr(f.blocks().getFirst().instrs().getFirst()));
    }

    @Test
    void aValueAnAdditionReadsStaysWholeWithAllThatFeedsIt() {
        MModule m = MirParser.parse("""
                global @_a : 4
                global @_b : 4
                func @_f() -> i32 {
                b0_entry:
                    %0 = load i32 @_a
                    %1 = load i32 @_b
                    %2 = xor i32 %0, %1
                    %3 = add i32 %2, 1
                    ret i32 %3
                }
                """);
        assertFalse(SplitWords.run(m.function("_f")));
    }

    private static final long[] EDGES = {0, 1, 0xFFFF, 0x10000, 0x10001, 0xFFFF0000L, 0xFFFFFFFFL, 0x12345678L, 0x12340000L, 0x5678L};

    private static long run(String program, long a, long b) {
        MModule m = MirParser.parse(program);
        Interpreter in = new Interpreter(m, 100_000);
        return in.call("_f", List.of(a, b));
    }

    @Test
    void aTestForEqualityOnHalvesGivesWhatTheWholeValueGives() {
        // the values reach the web through memory, as a loaded uint32_t does
        for (String pred : new String[] {"eq", "ne"}) {
            for (String right : new String[] {"0", "305419896", "%2"}) {
                String program = """
                        global @_a : 4
                        global @_b : 4
                        func @_f(i32 %%x, i32 %%y) -> i16 {
                        b0_entry:
                            store i32 @_a, %%x
                            store i32 @_b, %%y
                            %%1 = load i32 @_a
                            %%2 = load i32 @_b
                            %%3 = and i32 %%1, %%2
                            %%4 = xor i32 %%3, %%1
                            %%5 = cmp %s i32 %%4, %s
                            br %%5, b1_yes, b2_no
                        b1_yes:
                            ret i16 1
                        b2_no:
                            ret i16 0
                        }
                        """.formatted(pred, right);
                MModule split = MirParser.parse(program);
                MFunction f = split.function("_f");
                assertTrue(SplitWords.run(f), pred + " " + right);
                Verifier.verify(split);
                assertEquals(0, f.blocks().stream().flatMap(b -> b.instrs().stream())
                        .filter(i -> i instanceof Instr.Cmp c && c.op(0).type() == MType.I32).count(), "no 32-bit test is left");
                Interpreter got = new Interpreter(split, 100_000);
                for (long x : EDGES) {
                    for (long y : EDGES) {
                        assertEquals(run(program, x, y), got.call("_f", List.of(x, y)), x + " " + y + " " + pred + " " + right);
                    }
                }
            }
        }
    }

    @Test
    void anOrderedComparisonKeepsTheValueWhole() {
        MModule m = MirParser.parse("""
                global @_a : 4
                func @_f() -> i16 {
                b0_entry:
                    %0 = load i32 @_a
                    %1 = xor i32 %0, 1
                    %2 = cmp ult i32 %1, 5
                    br %2, b1_yes, b2_no
                b1_yes:
                    ret i16 1
                b2_no:
                    ret i16 0
                }
                """);
        assertFalse(SplitWords.run(m.function("_f")));
    }

    @Test
    void aVolatileValueStaysWhole() {
        MModule m = MirParser.parse("""
                global @_a : 4
                func @_f() -> void {
                b0_entry:
                    %0 = load volatile i32 @_a
                    %1 = xor i32 %0, 1
                    store i32 @_a, %1
                    ret
                }
                """);
        assertFalse(SplitWords.run(m.function("_f")));
    }
}
