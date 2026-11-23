package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
