package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The integer part of an 8.8 value as a byte load, for unsigned and signed shifts. */
class ByteLoadsTest {
    private static final String PROGRAM = """
            global @_v : 6

            func @_main() -> i16 {
            b0_entry:
                %0 = ptradd @_v, i16 2
                store i16 @_v, -12345
                store i16 %0, 30000
                %1 = load i16 @_v
                %2 = lshr i16 %1, 8
                %3 = load i16 %0
                %4 = ashr i16 %3, 11
                %5 = load volatile i16 @_v
                %6 = lshr i16 %5, 8
                %7 = xor i16 %2, %4
                %8 = add i16 %7, %6
                ret i16 %8
            }
            """;

    @Test
    void aShiftedLoadBecomesAByteLoad() {
        MModule plain = MirParser.parse(PROGRAM);
        MModule m = MirParser.parse(PROGRAM);
        assertTrue(ByteLoads.run(m.function("_main")));
        Verifier.verify(m);
        String text = Printer.print(m);
        assertTrue(text.contains("load i8"), text);
        assertTrue(text.contains("sext i8"), text);
        assertTrue(text.contains("load volatile i16"), text);   // left alone
        DeadCode.run(m.function("_main"));
        assertFalse(ByteLoads.run(m.function("_main")));   // nothing left to do
        assertEquals(new Interpreter(plain, 1000).call("_main", List.of()), new Interpreter(m, 1000).call("_main", List.of()));
    }
}
