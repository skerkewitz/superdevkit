package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The MIR interpreter stops a program that reads or writes outside the global or frame slot an
 * address is based on, as it stops one whose hint does not hold: such an access is undefined in
 * C, and the code generator may rely on it never happening.
 */
class ObjectBoundsTest {
    private static final String PROGRAM = """
            global @_t : 10
            global @_u : 2

            func @_get(i16 %i) -> i8 {
            b0_entry:
                %0 = ptradd @_t, i16 %i
                %1 = load i8 %0
                ret i8 %1
            }

            func @_put(i16 %i) -> void {
            b0_entry:
                %0 = ptradd @_t, i16 %i
                %1 = ptradd %0, i16 1
                store i16 %1, 7
                ret
            }
            """;

    @Test
    void anAccessInsideItsObjectRuns() {
        Interpreter in = new Interpreter(MirParser.parse(PROGRAM), 1000);
        assertEquals(0, in.call("_get", List.of(9L)));
        in.call("_put", List.of(7L));   // bytes 8 and 9
        assertEquals(7, in.call("_get", List.of(8L)));
    }

    @Test
    void anAccessOutsideItsObjectStops() {
        for (long i : new long[] {10, 0xFFFF, 400}) {
            Interpreter in = new Interpreter(MirParser.parse(PROGRAM), 1000);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> in.call("_get", List.of(i)));
            assertTrue(e.getMessage().contains("outside its object"), e.getMessage());
        }
        // a 16-bit store whose second byte is past the end
        Interpreter in = new Interpreter(MirParser.parse(PROGRAM), 1000);
        assertThrows(IllegalStateException.class, () -> in.call("_put", List.of(8L)));
    }
}
