package dampf.mir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The order of a block for the one accumulator (Schedule). */
class ScheduleTest {
    private static List<Object> order(MFunction f) {
        return f.blocks().getFirst().instrs().stream().map(i -> (Object) i.getClass()).toList();
    }

    private static final String COPY_BYTE = """
            func @_main(ptr %%d, ptr %%s, i16 %%i) -> void {
            b0_entry:
            %s
                ret
            }
            """;

    @Test
    void aStoresValueComesAfterItsAddress() {
        // the value goes from A into the store; the address needs A too, so it comes first
        MModule m = MirParser.parse(COPY_BYTE.formatted("""
                    %0 = ptradd %s, i16 %i
                    %1 = load i8 %0
                    %2 = ptradd %d, i16 %i
                    store i8 %2, %1
                """));
        MFunction f = m.function("_main");
        Schedule.run(f);
        Verifier.verify(m);
        assertEquals(List.<Object>of(Instr.PtrAdd.class, Instr.PtrAdd.class, Instr.Load.class, Instr.Store.class, Instr.Ret.class),
                order(f));
    }

    @Test
    void anOrderThatIsRightStaysAndTheResultDoesNotDependOnRounds() {
        MModule m = MirParser.parse(COPY_BYTE.formatted("""
                    %0 = ptradd %d, i16 %i
                    %1 = ptradd %s, i16 %i
                    %2 = load i8 %1
                    store i8 %0, %2
                """));
        MFunction f = m.function("_main");
        assertFalse(Schedule.run(f), "nothing to move");
        assertFalse(Schedule.run(f), "and again nothing");
    }

    @Test
    void aLoadMovesPastAStoreOnlyToOtherBytesOfAGlobal() {
        String program = """
                global @_a : 4
                global @_b : 4
                func @_main() -> void {
                b0_entry:
                    %%0 = load i16 %s
                    store i16 @_a, 1
                    %%1 = add i16 %%0, 1
                    store i16 @_b, %%1
                    ret
                }
                """;
        for (String[] c : new String[][] {{"@_b", "moves"}, {"@_a", "stays"}}) {
            MModule m = MirParser.parse(program.formatted(c[0]));
            MFunction f = m.function("_main");
            Schedule.run(f);
            Verifier.verify(m);
            boolean first = order(f).getFirst() == Instr.Load.class;
            assertEquals(c[1].equals("stays"), first, c[0]);
        }
    }

    @Test
    void aLoadDoesNotMovePastAStore() {
        MModule m = MirParser.parse(COPY_BYTE.formatted("""
                    %0 = load i8 %s
                    store i8 %d, 1
                    %1 = ptradd %d, i16 %i
                    store i8 %1, %0
                """));
        MFunction f = m.function("_main");
        Schedule.run(f);
        Verifier.verify(m);
        assertEquals(Instr.Load.class, order(f).getFirst(), "the load stays before the store that may change it");
    }
}
