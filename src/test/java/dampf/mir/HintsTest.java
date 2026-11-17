package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The checks of a DAMPF_RANGE hint: what the range analysis learns, the interpreter, the strip. */
class HintsTest {
    private static final String PROGRAM = """
            func @_f(i8 %n) -> i8 {
            b0_entry:
                %0 = cmp ult i8 %n, 11
                br %0, b2_range, b1_hint
            b1_hint:
                unreachable
            b2_range:
                %1 = add i8 %n, 1
                ret i8 %1
            }
            """;

    @Test
    void theRangeHoldsAfterTheCheckAndTheCheckGoes() {
        MModule m = MirParser.parse(PROGRAM);
        MFunction f = m.function("_f");
        Instr add = f.blocks().get(2).instrs().getFirst();
        assertEquals(new Ranges.Range(1, 11), new Ranges(f).of(add));
        Ranges before = Hints.strip(m).get(f);
        assertEquals(new Ranges.Range(1, 11), before.of(add));
        assertEquals(2, f.blocks().size(), Printer.print(m));
        assertFalse(Printer.print(m).contains("unreachable"));
        assertFalse(Printer.print(m).contains("cmp"));
    }

    @Test
    void theInterpreterFailsWhenAHintDoesNotHold() {
        MModule m = MirParser.parse(PROGRAM);
        assertEquals(6L, new Interpreter(m, 1000).call("_f", List.of(5L)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new Interpreter(m, 1000).call("_f", List.of(11L)));
        assertTrue(e.getMessage().contains("a hint does not hold"), e.getMessage());
    }
}
