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
 * reports what she cannot show (register writes she does not draw, sdevkit__undrawn), lays the
 * shadow state out as dampf does, starts an HDMA table on the first row, and draws what the SNES
 * draws (the C tests in compiler/src/test/c, which also test the shadow state's rules).
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
                static void commit(void) { sdevkit_marks = SDEVKIT_MARK_ALL; sdevkit__vblank(); }
                int main(void)
                {
                    static const uint8_t data[2] = { 1, 2 };
                    sdevkit__reset();
                    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;           /* what she draws */
                    sdevkit_ppu.hofs[0] = 10;
                    sdevkit_ppu.vofs[0] = 20;
                    sdevkit_ppu.inidisp = 15;
                    sdevkit_ppu.tm = 0x13;
                    sdevkit_ppu.mosaic = 0x00;                       /* mosaic off */
                    sdevkit_ppu.m7a = 0x40;                          /* a mode 7 matrix value, no effect in mode 1 */
                    commit();
                    show("drawn");
                    sdevkit_ppu.mosaic = 0x31;                       /* mosaic of size 3 on BG1 */
                    commit();
                    show("mosaic");
                    sdevkit_ppu.mosaic = 0x00;
                    sdevkit_ppu.bgmode = 0x03;                       /* mode 3 */
                    commit();
                    show("mode");
                    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;
                    sdevkit_ppu.setini = 0x01;                       /* SETINI: interlace */
                    commit();
                    sdevkit_ppu.setini = 0x00;
                    sdevkit_screen_off_now();
                    sdevkit_dma_now(0x18, 0x01, 0x84, 0x1000, data, 2);   /* VMAIN's address remapping */
                    show("copies");
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
        assertEquals("drawn 0\nmosaic 1\nmode 2\ncopies 4\nfirst 06 31", out.strip());
    }

    /**
     * The shadow state's layout is the same on the PC as dampf's on the SNES, which the checks hash
     * byte for byte: no padding, the 16-bit fields first, and the groups the marks name each in one
     * piece, in the order lib/rachael/ppu.c compares them.
     */
    @Test
    void theShadowStateHasNoPadding() throws Exception {
        String program = """
                #include <stdio.h>
                #include <stddef.h>
                #include "rachael.h"
                #include "sdevkit/loop.h"
                int main(void)
                {
                    printf("%u %u %u %u %u %u\\n", (unsigned)sizeof(sdevkit_ppu_regs), (unsigned)offsetof(sdevkit_ppu_regs, m7a),
                           (unsigned)offsetof(sdevkit_ppu_regs, bgmode), (unsigned)offsetof(sdevkit_ppu_regs, w12sel),
                           (unsigned)offsetof(sdevkit_ppu_regs, inidisp), (unsigned)sizeof(sdevkit_input_state));
                    return 0;
                }
                """;
        Path c = tmp.resolve("layout.c"), exe = tmp.resolve("layout");
        Files.writeString(c, program);
        Processes.check(Path.of("."), Duration.ofMinutes(1), List.of("cc", "-std=c99", "-Wall", "-Wextra",
                "-I", "lib/include", "-I", "lib/rachael", c.toString(), "-o", exe.toString()));
        String out = Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString()));
        assertEquals("58 18 33 45 57 8", out.strip());
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
                    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;
                    sdevkit_color_now(0, 0);
                    sdevkit_ppu.cgadsub = 0x20;                      /* math always, fixed colour, on the backdrop */
                    sdevkit_hdma[1].table = table;
                    sdevkit_hdma[1].dmap = SDEVKIT_HDMA_ONE;
                    sdevkit_hdma[1].bbad = SDEVKIT_PPU_COLDATA;
                    sdevkit_ppu.hdmaen = SDEVKIT_HDMA_CHANNEL1;
                    sdevkit_ppu.inidisp = 0x0F;
                    sdevkit_marks = SDEVKIT_MARK_ALL;
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
     * per-line limits, colour math in windows, HDMA tables, brightness; and the shadow state's rules:
     * the sprite list, the writes now, the queue, the marks, sdevkit_commit_now.
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
