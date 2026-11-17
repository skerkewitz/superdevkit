package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pointer parameters become offsets when every call passes a pointer into the same global; the
 * results stay the same and every value stays in its range (the interpreter checks).
 */
class PointerParamsTest {
    private static final String PROGRAM = """
            global @_a : 20
            global @_b : 20

            func @_get(ptr %p) -> i8 {
            b0_entry:
                %0 = ptradd %p, i16 1
                %1 = load i8 %0
                ret i8 %1
            }

            func @_either(ptr %p) -> i8 {
            b0_entry:
                %0 = load i8 %p
                ret i8 %0
            }

            func @_main() -> i16 {
            b0_entry:
                store i8 @_a, 7
                %0 = ptradd @_a, i16 1
                store i8 %0, 9
                %1 = ptradd @_a, i16 5
                %2 = ptradd @_a, i16 6
                store i8 %2, 11
                store i8 @_b, 13
                %3 = call i8 @_get(ptr @_a)
                %4 = call i8 @_get(ptr %1)
                %5 = call i8 @_either(ptr @_a)
                %6 = call i8 @_either(ptr @_b)
                %7 = zext i8 %3 to i16
                %8 = zext i8 %4 to i16
                %9 = zext i8 %5 to i16
                %10 = zext i8 %6 to i16
                %11 = shl i16 %7, 12
                %12 = shl i16 %8, 8
                %13 = shl i16 %9, 4
                %14 = or i16 %11, %12
                %15 = or i16 %13, %10
                %16 = or i16 %14, %15
                ret i16 %16
            }
            """;

    @Test
    void aParameterAlwaysIntoOneArrayBecomesAnOffset() {
        MModule plain = MirParser.parse(PROGRAM);
        MModule optimized = MirParser.parse(PROGRAM);
        Optimizer.run(optimized, true, false);
        assertEquals(MType.I16, optimized.function("_get").params().getFirst().type(), Printer.print(optimized));
        assertEquals(MType.PTR, optimized.function("_either").params().getFirst().type());
        Interpreter a = new Interpreter(plain, 10_000);
        Interpreter b = new Interpreter(optimized, 10_000);
        b.observe(Ranges.checker(optimized));
        assertEquals(a.call("_main", List.of()), b.call("_main", List.of()));
    }
}
