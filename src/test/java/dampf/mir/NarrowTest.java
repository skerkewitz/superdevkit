package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Width narrowing on hand-written MIR: the function body after the passes, as text. */
class NarrowTest {
    private static String optimize(String body) {
        MModule m = MirParser.parse(body);
        Optimizer.run(m, true);
        String text = Printer.print(m);
        // only the instructions, without the header and the "; from" comments
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.startsWith("    ")) {
                out.append(line.strip()).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void anAdditionTruncatedTo16BitsIsDoneIn16Bits() {
        assertEquals("""
                %0 = add i16 %a, %b
                ret i16 %0
                """, optimize("""
                func @_f(i16 %a, i16 %b) -> i16 {
                b0_entry:
                    %0 = zext i16 %a to i32
                    %1 = zext i16 %b to i32
                    %2 = add i32 %0, %1
                    %3 = trunc i32 %2 to i16
                    ret i16 %3
                }
                """));
    }

    @Test
    void aSignedCompareOfSignExtendedValuesIsNarrow() {
        assertEquals("""
                %0 = cmp slt i16 %a, 16
                br %0, b1, b2
                ret i8 0
                ret i8 1
                """, optimize("""
                func @_f(i16 %a) -> i8 {
                b0_entry:
                    %0 = sext i16 %a to i32
                    %1 = cmp slt i32 %0, 16
                    br %1, b1, b2
                b1:
                    ret i8 0
                b2:
                    ret i8 1
                }
                """));
    }

    @Test
    void aShiftOfANonNegativeValueIsALogicalShift() {
        // (int16_t)(x >> 3) for a uint16_t x: int is signed, so C shifts arithmetically; x >= 0
        assertEquals("""
                %0 = lshr i16 %x, 3
                ret i16 %0
                """, optimize("""
                func @_f(i16 %x) -> i16 {
                b0_entry:
                    %0 = zext i16 %x to i32
                    %1 = ashr i32 %0, 3
                    %2 = trunc i32 %1 to i16
                    ret i16 %2
                }
                """));
    }

    @Test
    void aSumThatMayOverflow16BitsStaysWide() {
        // (x - 16) >> 3: x - 16 can be below -32768, so 16 bits would give a different result
        assertEquals("""
                %0 = sext i16 %x to i32
                %1 = sub i32 %0, 16
                %2 = ashr i32 %1, 3
                %3 = trunc i32 %2 to i16
                ret i16 %3
                """, optimize("""
                func @_f(i16 %x) -> i16 {
                b0_entry:
                    %0 = sext i16 %x to i32
                    %1 = sub i32 %0, 16
                    %2 = ashr i32 %1, 3
                    %3 = trunc i32 %2 to i16
                    ret i16 %3
                }
                """));
    }

    @Test
    void anArrayIndexIsComputedIn16Bits() {
        assertEquals("""
                %0 = shl i16 %i, 2
                %1 = ptradd @_table, i16 %0
                %2 = load i16 %1
                ret i16 %2
                """, optimize("""
                global @_table : 400

                func @_f(i16 %i) -> i16 {
                b0_entry:
                    %0 = zext i16 %i to i32
                    %1 = shl i32 %0, 2
                    %2 = ptradd @_table, i32 %1
                    %3 = load i16 %2
                    ret i16 %3
                }
                """));
    }

    @Test
    void aTruthValueComparedWithZeroIsItself() {
        assertEquals("""
                %0 = cmp ult i8 %a, %b
                br %0, b1, b2
                ret i8 0
                ret i8 1
                """, optimize("""
                func @_f(i8 %a, i8 %b) -> i8 {
                b0_entry:
                    %0 = zext i8 %a to i32
                    %1 = zext i8 %b to i32
                    %2 = cmp slt i32 %0, %1
                    %3 = zext i1 %2 to i32
                    %4 = cmp ne i32 %3, 0
                    br %4, b1, b2
                b1:
                    ret i8 0
                b2:
                    ret i8 1
                }
                """));
    }

    @Test
    void aConditionalExpressionTruncatedTo16BitsIsNarrow() {
        // (uint16_t)(d > 0 ? x - 64 : x + 64)
        assertEquals("""
                %0 = cmp sgt i16 %d, 0
                br %0, b1, b2
                %1 = sub i16 %x, 64
                jump b3
                %2 = add i16 %x, 64
                jump b3
                %3 = phi i16 [b1, %1], [b2, %2]
                ret i16 %3
                """, optimize("""
                func @_f(i16 %d, i16 %x) -> i16 {
                b0_entry:
                    %0 = cmp sgt i16 %d, 0
                    br %0, b1, b2
                b1:
                    %1 = zext i16 %x to i32
                    %2 = sub i32 %1, 64
                    jump b3
                b2:
                    %3 = zext i16 %x to i32
                    %4 = add i32 %3, 64
                    jump b3
                b3:
                    %5 = phi i32 [b1, %2], [b2, %4]
                    %6 = trunc i32 %5 to i16
                    ret i16 %6
                }
                """));
    }
}
