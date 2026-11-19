package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A byte parameter used only as an index into an array of 23-byte structs, and in unsigned
 * comparisons with a constant or another such index, becomes the 16-bit product; one used any
 * other way stays a byte. The results stay the same (the interpreter, with every range checked).
 */
class IndexParamsTest {
    private static final String PROGRAM = """
            global @_d : 368

            func @_pair(i8 %i, i8 %j, i8 %k) -> i16 {
            b0_entry:
                %0 = zext i8 %i to i16
                %1 = mul i16 %0, 23
                %2 = ptradd @_d, i16 %1
                %3 = load i8 %2
                %4 = zext i8 %j to i16
                %5 = mul i16 %4, 23
                %6 = ptradd @_d, i16 %5
                %7 = load i8 %6
                %8 = cmp ugt i8 %i, %j
                br %8, b1_more, b2_less
            b1_more:
                %9 = zext i8 %3 to i16
                %10 = zext i8 %k to i16
                %11 = add i16 %9, %10
                ret i16 %11
            b2_less:
                %12 = cmp eq i8 %j, 3
                br %12, b3_three, b4_other
            b3_three:
                ret i16 1000
            b4_other:
                %13 = zext i8 %7 to i16
                ret i16 %13
            }

            func @_main(i8 %a, i8 %b) -> i16 {
            b0_entry:
                store i8 @_d, 5
                %0 = ptradd @_d, i16 69
                store i8 %0, 9
                %1 = call i16 @_pair(i8 %a, i8 %b, i8 7)
                %2 = call i16 @_pair(i8 %b, i8 3, i8 %a)
                %3 = add i16 %1, %2
                ret i16 %3
            }
            """;

    @Test
    void anIndexParameterBecomesTheProduct() {
        MModule plain = MirParser.parse(PROGRAM);
        MModule m = MirParser.parse(PROGRAM);
        assertTrue(IndexParams.run(m));
        Verifier.verify(m);
        MFunction pair = m.function("_pair");
        assertEquals(List.of(MType.I16, MType.I16, MType.I8), pair.params().stream().map(Value.Param::type).toList(),
                "i and j scaled, k (added, not an index) a byte still");
        String text = Printer.print(pair);
        assertTrue(!text.contains("mul"), text);
        assertTrue(text.contains("cmp ugt i16") && text.contains("cmp eq i16 %j, 69"), text);
        for (long a = 0; a < 16; a++) {
            for (long b = 0; b < 16; b++) {
                assertEquals(new Interpreter(plain, 10_000).call("_main", List.of(a, b)),
                        new Interpreter(m, 10_000).call("_main", List.of(a, b)), "a " + a + ", b " + b);
            }
        }
    }
}
