package dampf.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dampf.util.Processes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rachael (lib/rachael) reports what she cannot show as the SNES would: register writes she does
 * not draw (sdevkit__undrawn). A small program on Rachael, built by gcc, writes registers and
 * prints her count after each group.
 */
class RachaelTest {
    @TempDir
    Path tmp;

    @Test
    void registerWritesRachaelDoesNotDrawAreCounted() throws Exception {
        String program = """
                #include <stdio.h>
                #include "rachael.h"
                static void show(const char *what) { printf("%s %u\\n", what, sdevkit__rachael.undrawn); }
                int main(void)
                {
                    sdevkit__reset();
                    sdevkit_set_mode(SDEVKIT_BG_MODE1, 0);           /* what she draws */
                    sdevkit_bg_set_scroll(0, 10, 20);
                    sdevkit_set_brightness(15);
                    sdevkit_reg_write(SDEVKIT_PPU_TM, 0x13);
                    sdevkit_reg_write(0x06, 0x00);                   /* mosaic off */
                    sdevkit_reg_write(0x1B, 0x40);                   /* a mode 7 matrix value, no effect in mode 1 */
                    show("drawn");
                    sdevkit_reg_write(0x06, 0x31);                   /* mosaic of size 3 on BG1 */
                    show("mosaic");
                    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, 0x03);     /* mode 3 */
                    show("mode");
                    sdevkit_reg_write(0x33, 0x01);                   /* SETINI: interlace */
                    sdevkit_reg_write(0x18, 0x55);                   /* VMDATAL: VRAM around her memories */
                    show("ports");
                    printf("first %02X %02X\\n", sdevkit__rachael.undrawn_reg, sdevkit__rachael.undrawn_value);
                    return 0;
                }
                """;
        Path c = tmp.resolve("undrawn.c"), exe = tmp.resolve("undrawn");
        Files.writeString(c, program);
        Processes.check(Path.of("."), Duration.ofMinutes(1), List.of("cc", "-std=c99", "-Wall", "-Wextra",
                "-Wno-unused-parameter", "-I", "lib/include", "-I", "lib/rachael", c.toString(), "lib/rachael/ppu.c",
                "lib/rachael/render.c", "-o", exe.toString()));
        String out = Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString()));
        assertEquals("drawn 0\nmosaic 1\nmode 2\nports 4\nfirst 06 31", out.strip());
    }
}
