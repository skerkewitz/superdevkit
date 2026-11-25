package dampf.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The target's facts as the runtime (dampf/snes/runtime.s) has them too: the runtime is SNES code
 * written by hand, so what it sets up and names must be what the code generators ask SnesTarget.
 */
class SnesTargetTest {
    private static String runtime() throws IOException {
        try (InputStream in = SnesTargetTest.class.getResourceAsStream("/dampf/snes/runtime.s")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theRuntimeSetsTheDataBankAndTheStack() throws IOException {
        String s = runtime();
        assertTrue(s.contains(String.format("pea     $%02X%02X", SnesTarget.DATA_BANK, SnesTarget.DATA_BANK)), "DBR at start-up");
        assertTrue(s.contains(String.format("mvn     #^__DATA_LOAD__, #$%02X", SnesTarget.VARIABLE_BANK)),
                "the variables' initial values are copied into their bank");
        assertTrue(s.contains(String.format("ldx     #$%04X", SnesTarget.LOW_RAM_END - 1)), "the stack's top");
    }

    @Test
    void theRuntimeNamesTheSameMultiplierRegisters() throws IOException {
        String s = runtime();
        assertEquals(SnesTarget.WRMPYA, equate(s, "WRMPYA"));
        assertEquals(SnesTarget.WRMPYB, equate(s, "WRMPYB"));
        assertEquals(SnesTarget.RDMPY, equate(s, "RDMPY"));
    }

    private static int equate(String s, String name) {
        Matcher m = Pattern.compile("^" + name + "\\s*=\\s*\\$([0-9A-F]+)", Pattern.MULTILINE).matcher(s);
        assertTrue(m.find(), name);
        return Integer.parseInt(m.group(1), 16);
    }

    @Test
    void theDirectPageEndsAfter224BytesOfFrames() {
        assertTrue(SnesTarget.inDirectPage(0xDE, 2));
        assertFalse(SnesTarget.inDirectPage(0xDF, 2));
        assertTrue(SnesTarget.inDirectPage(0xDC, 4));
        assertFalse(SnesTarget.inDirectPage(0xDD, 4));
    }

    @Test
    void lowRamIsInWramsFirstBankAndItsMirrors() {
        assertTrue(SnesTarget.inLowRam(0x7E1FFF));
        assertTrue(SnesTarget.inLowRam(0x001E00));
        assertTrue(SnesTarget.inLowRam(0x800020));
        assertFalse(SnesTarget.inLowRam(0x7E2000));
        assertFalse(SnesTarget.inLowRam(0x7F0020));
        assertFalse(SnesTarget.inLowRam(0x400020));
    }
}
