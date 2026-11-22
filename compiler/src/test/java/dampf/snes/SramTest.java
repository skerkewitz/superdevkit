package dampf.snes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dampf.Compiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Save RAM (dampf --sram, ADR-6F3CK03Z): the header announces the cartridge's battery RAM in the
 * smallest of 2, 4, 8, 16 and 32 KB that holds it, the checksum stays right, and the program sees
 * the exact size as SDEVKIT_SRAM_SIZE.
 */
class SramTest {
    @TempDir
    Path tmp;

    private byte[] build(int sram) throws Exception {
        Path src = tmp.resolve("sram" + sram + ".c");
        Files.writeString(src, """
                #if SDEVKIT_SRAM_SIZE != %d
                #error "SDEVKIT_SRAM_SIZE is not the size of --sram"
                #endif
                void test_main(void)
                {
                }
                """.formatted(sram));
        Compiler.Options o = new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                tmp.resolve("sram" + sram + ".sfc")).withBackend(Compiler.Backend.MIR).withSram(sram);
        return Files.readAllBytes(Compiler.compile(o).rom().rom());
    }

    private static int checksum(byte[] rom) {
        int sum = 0;
        for (byte b : rom) {
            sum = (sum + (b & 0xFF)) & 0xFFFF;
        }
        return sum;
    }

    @Test
    void theHeaderAnnouncesTheSaveRamRoundedUp() throws Exception {
        int[][] cases = { { 8, 1 }, { 2048, 1 }, { 2049, 2 }, { 5000, 3 }, { 16384, 4 }, { 32768, 5 } };
        for (int[] c : cases) {
            byte[] rom = build(c[0]);
            assertEquals(0x02, rom[0x7FD6] & 0xFF, "the cartridge type for " + c[0] + " bytes: ROM, RAM and battery");
            assertEquals(c[1], rom[0x7FD8] & 0xFF, "the RAM size of " + c[0] + " bytes, as 2^n KB");
            int stored = (rom[0x7FDE] & 0xFF) | (rom[0x7FDF] & 0xFF) << 8;
            int complement = (rom[0x7FDC] & 0xFF) | (rom[0x7FDD] & 0xFF) << 8;
            assertEquals(checksum(rom), stored, "the checksum after the save RAM went into the header");
            assertEquals(0xFFFF, stored ^ complement);
        }
    }

    @Test
    void withoutSaveRamTheHeaderSaysRomOnly() throws Exception {
        Path src = tmp.resolve("none.c");
        Files.writeString(src, """
                #if SDEVKIT_SRAM_SIZE
                #error "no --sram, no SDEVKIT_SRAM_SIZE"
                #endif
                void test_main(void)
                {
                }
                """);
        byte[] rom = Files.readAllBytes(Compiler.compile(new Compiler.Options(List.of(src), List.of(), List.of(), "test_main",
                tmp.resolve("none.sfc")).withBackend(Compiler.Backend.MIR)).rom().rom());
        assertEquals(0x00, rom[0x7FD6] & 0xFF);
        assertEquals(0x00, rom[0x7FD8] & 0xFF);
    }

    @Test
    void moreThan32KbIsRefused() {
        Compiler.Options o = new Compiler.Options(List.of(), List.of(), List.of(), "main", tmp.resolve("x.sfc"));
        assertThrows(IllegalArgumentException.class, () -> o.withSram(32769));
        assertThrows(IllegalArgumentException.class, () -> o.withSram(-1));
    }
}
