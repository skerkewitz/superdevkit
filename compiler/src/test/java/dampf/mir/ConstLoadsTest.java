package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Loads from constant objects at constant places become their values ({@link ConstLoads}); the
 * function must return the same before and after, and the loads that may not be folded stay.
 */
class ConstLoadsTest {
    private static final String GLOBALS = """
            global @_t : 6 const = [0: i8 1, 1: i8 -56, 2: i16 -2, 4: i8 7]
            global @_l : 8 const = [0: i32 305419896, 6: i8 255]
            global @_p : 4 const = [0: ptr @_t+2]
            global @_v : 2 = [0: i16 1000]
            """;

    /** A function {@code _f() -> type} that returns the load at {@code address}. */
    private static String load(String type, String address, boolean isVolatile) {
        return GLOBALS + "\nfunc @_f() -> " + type + " {\nb0_entry:\n    %0 = ptradd " + address + "\n"
                + "    %1 = load " + (isVolatile ? "volatile " : "") + type + " %0\n    ret " + type + " %1\n}\n";
    }

    private static void folds(String text, boolean folded) {
        MModule plain = MirParser.parse(text);
        MModule changed = MirParser.parse(text);
        MFunction f = changed.functions().getFirst();
        assertEquals(folded, ConstLoads.run(changed, f), () -> Printer.print(changed));
        DeadCode.run(f);
        Verifier.verify(changed);
        boolean loads = f.blocks().stream().flatMap(b -> b.instrs().stream()).anyMatch(i -> i instanceof Instr.Load);
        assertEquals(!folded, loads, () -> Printer.print(changed));
        assertEquals(new Interpreter(plain, Long.MAX_VALUE).call("_f", List.of()),
                new Interpreter(changed, Long.MAX_VALUE).call("_f", List.of()), () -> Printer.print(changed));
    }

    /** The pass leaves the load alone (out of bounds the interpreter would refuse to run it). */
    private static void stays(String text) {
        MModule m = MirParser.parse(text);
        MFunction f = m.functions().getFirst();
        assertFalse(ConstLoads.run(m, f), () -> Printer.print(m));
        assertTrue(f.blocks().getFirst().instrs().stream().anyMatch(i -> i instanceof Instr.Load));
    }

    @Test
    void everyWidthAndSign() {
        folds(load("i8", "@_t, i16 1", false), true);
        folds(load("i16", "@_t, i16 2", false), true);
        folds(load("i16", "@_t, i16 1", false), true);     // the high byte of one datum, the low of the next
        folds(load("i32", "@_l, i32 0", false), true);
        folds(load("i32", "@_l, i16 4", false), true);     // bytes no datum gives are 0
        folds(load("i16", "@_t, i16 4", false), true);
    }

    @Test
    void whatStays() {
        stays(load("i16", "@_t, i16 5", false));            // past the end
        stays(load("i8", "@_t, i16 -1", false));            // before the start
        folds(load("i8", "@_t, i16 0", true), false);       // volatile
        folds(load("i16", "@_v, i16 0", false), false);     // not constant
        folds(load("i16", "@_p, i16 0", false), false);     // an address
    }

    @Test
    void chainsOfConstantOffsets() {
        String text = GLOBALS + """

                func @_f() -> i8 {
                b0_entry:
                    %0 = ptradd @_t, i16 2
                    %1 = ptradd %0, i32 2
                    %2 = load i8 %1
                    ret i8 %2
                }
                """;
        folds(text, true);
    }

    @Test
    void aVariableIndexStays() {
        String text = GLOBALS + """

                func @_f(i16 %i) -> i8 {
                b0_entry:
                    %0 = ptradd @_t, i16 %i
                    %1 = load i8 %0
                    ret i8 %1
                }
                """;
        stays(text);
    }
}
