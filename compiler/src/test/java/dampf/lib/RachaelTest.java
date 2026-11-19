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
 * Rachael (lib/rachael) as the SNES: small programs on her, built by gcc, print what she did. She
 * reports what she cannot show (register writes she does not draw, sdevkit__undrawn), keeps the
 * library's copies of registers as lib/snes does, starts an HDMA table on the first row, and
 * draws what the SNES draws (the C tests in compiler/src/test/c).
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
                "lib/rachael/render.c", "lib/rachael/dsp.c", "-o", exe.toString()));
        String out = Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString()));
        assertEquals("drawn 0\nmosaic 1\nmode 2\nports 4\nfirst 06 31", out.strip());
    }

    /**
     * The functions that change one field of TM, TS or the tile bases start from the library's copy,
     * as lib/snes/snes.c does, not from the register: a game's own write is undone by the next of
     * them, the number 4 is the sprites' bit, and the copies are zeroed with the console, on both
     * platforms alike.
     */
    @Test
    void layersAndTileBasesStartFromTheLibrarysCopyAsOnTheSnes() throws Exception {
        String program = """
                #include <stdio.h>
                #include "rachael.h"
                int main(void)
                {
                    sdevkit__reset();
                    sdevkit_set_mode(SDEVKIT_BG_MODE1, 0);           /* TM: BG1, BG2, BG3, sprites */
                    sdevkit_reg_write(SDEVKIT_PPU_TM, 0x01);         /* the game's own write: BG1 only */
                    sdevkit_bg_set_disable(1);                       /* from the copy: BG1, BG3, sprites */
                    printf("%02X", sdevkit__rachael.regs[SDEVKIT_PPU_TM]);
                    sdevkit_bg_set_disable(4);                       /* the sprites' bit */
                    printf(" %02X", sdevkit__rachael.regs[SDEVKIT_PPU_TM]);
                    sdevkit_bg_set_gfx_ptr(0, 0x3000);
                    sdevkit_reg_write(SDEVKIT_PPU_BG12NBA, 0xFF);    /* the game's own write */
                    sdevkit_bg_set_gfx_ptr(1, 0x2000);               /* from the copy: BG1 3, BG2 2 */
                    printf(" %02X", sdevkit__rachael.regs[SDEVKIT_PPU_BG12NBA]);
                    sdevkit__reset();                                /* the copies are zeroed too */
                    sdevkit_bg_set_enable(0);
                    sdevkit_bg_set_gfx_ptr(2, 0x1000);
                    printf(" %02X %02X\\n", sdevkit__rachael.regs[SDEVKIT_PPU_TM], sdevkit__rachael.regs[SDEVKIT_PPU_BG34NBA]);
                    return 0;
                }
                """;
        Path c = tmp.resolve("layers.c"), exe = tmp.resolve("layers");
        Files.writeString(c, program);
        Processes.check(Path.of("."), Duration.ofMinutes(1), List.of("cc", "-std=c99", "-Wall", "-Wextra",
                "-Wno-unused-parameter", "-I", "lib/include", "-I", "lib/rachael", c.toString(), "lib/rachael/ppu.c",
                "lib/rachael/render.c", "lib/rachael/dsp.c", "-o", exe.toString()));
        String out = Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString()));
        assertEquals("15 05 23 01 01", out.strip());
    }

    /**
     * Where an HDMA table's first entry lands: on row 0 of the picture (scanline 1), as in Mesen
     * (measured by Blubber Blasen with this program, 2026-11-19). The backdrop is black and adds
     * the fixed colour; HDMA writes COLDATA, black for three lines, then white. Rows 0 to 2 are
     * black and row 3 is the first white one.
     */
    @Test
    void anHdmaTablesFirstEntryLandsOnTheFirstRow() throws Exception {
        String program = """
                #include <stdio.h>
                #include "rachael.h"
                static const uint8_t table[] = { 3, 0xE0, 1, 0xFF, 0 };
                static uint16_t picture[SDEVKIT__W * SDEVKIT__H];
                int main(void)
                {
                    int y;
                    sdevkit__reset();
                    sdevkit_set_mode(SDEVKIT_BG_MODE1, 0);
                    sdevkit_set_palette_color(0, 0);
                    sdevkit_set_color_effect(0x00, 0x20);            /* math always, fixed colour, on the backdrop */
                    sdevkit_hdma_set(1, SDEVKIT_PPU_COLDATA, SDEVKIT_HDMA_ONE, table);
                    sdevkit_hdma_enable(SDEVKIT_HDMA_CHANNEL1);
                    sdevkit_set_screen_on();
                    sdevkit__vblank();
                    sdevkit__render(picture);
                    for (y = 0; y < 5; y++) {
                        printf("%04X ", picture[y * SDEVKIT__W]);
                    }
                    return 0;
                }
                """;
        Path c = tmp.resolve("hdma.c"), exe = tmp.resolve("hdma");
        Files.writeString(c, program);
        Processes.check(Path.of("."), Duration.ofMinutes(1), List.of("cc", "-std=c99", "-Wall", "-Wextra",
                "-Wno-unused-parameter", "-I", "lib/include", "-I", "lib/rachael", c.toString(), "lib/rachael/ppu.c",
                "lib/rachael/render.c", "lib/rachael/dsp.c", "-o", exe.toString()));
        String out = Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString()));
        assertEquals("0000 0000 0000 7FFF 7FFF", out.strip());
    }

    /**
     * The renderer's tests (compiler/src/test/c/rachael_test.c, from the game's tests of its pseudo
     * SNES): tiles, palettes and flips, scrolling and 32 x 64 maps, layer order, sprites and the
     * per-line limits, colour math in windows, HDMA tables, brightness, the vertical blank's rules,
     * the DMA count.
     */
    @Test
    void theRenderersTestsPass() throws Exception {
        Path exe = tmp.resolve("rachael_test");
        Processes.check(Path.of("."), Duration.ofMinutes(1), List.of("cc", "-std=c99", "-Wall", "-Wextra",
                "-Wno-unused-parameter", "-I", "lib/include", "-I", "lib/rachael", "compiler/src/test/c/rachael_test.c",
                "lib/rachael/ppu.c", "lib/rachael/render.c", "lib/rachael/dsp.c", "-o", exe.toString()));
        String out = Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString()));
        assertEquals("all tests passed", out.strip());
    }
}
