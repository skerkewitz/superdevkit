package dampf.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dampf.Compiler;
import dampf.frontend.CompileError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which variables go to bank $7F (ADR-6FVM9QWS), and the error when both banks are full. */
class VariableBanksTest {
    @TempDir
    Path tmp;

    private static VariableBanks.Item item(String name, int bytes) {
        return new VariableBanks.Item(name, bytes, false);
    }

    @Test
    void everythingStaysInBank7eWhileItFits() throws Exception {
        // $7E holds $2000-$FFFE: 57,343 bytes
        assertEquals(Set.of(), VariableBanks.secondBank(List.of(item("a", 30000), item("b", 27343))));
    }

    @Test
    void theLargestGoFirstUntilTheRestFits() throws Exception {
        assertEquals(Set.of("big"), VariableBanks.secondBank(List.of(item("x", 2), item("mid", 20000), item("big", 30000),
                item("small", 10000))));
        // after the largest, the rest still does not fit: the next largest goes too
        assertEquals(List.of("a", "b"), List.copyOf(VariableBanks.secondBank(List.of(item("c", 28000), item("a", 32000),
                item("b", 30000)))));
        // equal sizes: the program's order
        assertEquals(Set.of("first"), VariableBanks.secondBank(List.of(item("first", 30000), item("second", 30000))));
    }

    @Test
    void pinnedVariablesStayAndOneTooLargeForWhatIsLeftIsPassedOver() throws Exception {
        assertEquals(Set.of("b"), VariableBanks.secondBank(List.of(new VariableBanks.Item("a", 32000, true), item("b", 30000))));
        // after a and b, $7F has 1,535 bytes left: c and e do not fit there, d does, and then the
        // rest fits into $7E
        assertEquals(Set.of("a", "b", "d"), VariableBanks.secondBank(List.of(item("a", 32000), item("b", 32000), item("c", 30000),
                item("d", 1500), item("e", 26000))));
    }

    @Test
    void variablesThatFitIntoNeitherBankAreAnError() throws Exception {
        VariableBanks.Overflow e = assertThrows(VariableBanks.Overflow.class, () -> VariableBanks.secondBank(List.of(
                item("a", 32000), item("b", 32000), item("c", 32000), item("d", 32000))));
        assertEquals("c", e.symbol());

        // the compiler's message, at the largest variable left over
        Path f = tmp.resolve("full.c");
        Files.writeString(f, """
                #include <stdint.h>
                uint8_t a[32000];
                uint8_t b[32000];
                uint8_t c[32000];
                uint8_t d[32000];
                void test_main(void) { a[1] = b[2]; c[3] = d[4]; }
                """);
        for (Compiler.Backend backend : Compiler.Backend.values()) {
            CompileError error = assertThrows(CompileError.class, () -> Compiler.compile(new Compiler.Options(List.of(f), List.of(),
                    List.of(), "test_main", tmp.resolve("out/full.sfc")).withBackend(backend)));
            String message = error.diagnostics().getFirst().toString();
            assertTrue(message.endsWith("full.c:4:9: error: the variables need 128,000 bytes, more than RAM holds: "
                    + "57,343 in bank $7E and 65,535 in bank $7F ('c', 32000 bytes, finds no room in either)"), backend + ": " + message);
        }
    }
}
