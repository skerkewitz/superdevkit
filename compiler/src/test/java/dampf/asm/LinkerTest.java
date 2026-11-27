package dampf.asm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The linker's RAM: the variables of bank $7E and of bank $7F (ADR-6FVM9QWS). */
class LinkerTest {
    private static Linker.Image link(String source) {
        return Linker.link(List.of(Assembler.assemble("m.s", source, Map.of())), List.of(List.of()));
    }

    private static final String HEAD = """
                    .p816
                    .smart  off
                    .a16
                    .i16
            """;

    @Test
    void variablesInBank7fHaveTheirBankAndTheirInitialValuesALoadAddress() {
        Linker.Image image = link(HEAD + """
                        .segment "BSS"
                _near:  .res    4
                        .segment "BSS7F"
                _far:   .res    100
                        .segment "DATA7F"
                _set:   .byte   1, 2, 3
                        .segment "STARTUP"
                        lda     f:_far+2
                        sta     _near
                        rtl
                """);
        Map<String, Long> m = image.locals().values().iterator().next();
        assertEquals(0x2000L, m.get("_near"));   // bank $7E: without the bank
        assertEquals(0x7F0000L, m.get("_far"));
        assertEquals(0x7F0064L, m.get("_set"));
        assertEquals(100L, image.globals().get("__BSS7F_SIZE__"));
        assertEquals(3L, image.globals().get("__DATA7F_SIZE__"));
        assertEquals(0x7F0064L, image.globals().get("__DATA7F_RUN__"));
    }

    @Test
    void aSixteenBitAddressOfAVariableInBank7fIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> link(HEAD + """
                        .segment "BSS7F"
                _far:   .res    2
                        .segment "STARTUP"
                        lda     a:_far
                        rtl
                """));
        assertTrue(e.getMessage().contains("$7f0000 is in bank $7F, which a 16-bit address does not reach"), e.getMessage());
    }

    @Test
    void moreVariablesThanABankHoldsAreRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> link(HEAD + """
                        .segment "BSS"
                _a:     .res    30000
                _b:     .res    30000
                """));
        assertEquals("the variables in bank $7E take 60,000 bytes, more than its 57,343", e.getMessage());
    }
}
