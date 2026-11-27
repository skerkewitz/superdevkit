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
 * reports what she cannot show (register writes she does not draw, sdevkit__undrawn) and what the
 * SNES would show otherwise (writes while the picture is drawn), lays the register shadow out as
 * dampf does, starts an HDMA table on the first row, and draws what the SNES draws (the C tests in
 * compiler/src/test/c, which also test the core's ports and the optional layers' rules).
 */
class RachaelTest {
    @TempDir
    Path tmp;

    private static final List<String> RACHAEL = List.of("lib/rachael/ppu.c", "lib/rachael/render.c", "lib/rachael/dsp.c", "lib/rachael/irq.c",
            "lib/rachael/layers.c");

    private String run(String name, String program) throws Exception {
        Path c = tmp.resolve(name + ".c"), exe = tmp.resolve(name);
        Files.writeString(c, program);
        List<String> cmd = new java.util.ArrayList<>(List.of("cc", "-std=c99", "-Wall", "-Wextra", "-Wno-unused-parameter",
                "-I", "lib/include", "-I", "lib/rachael", c.toString()));
        cmd.addAll(RACHAEL);
        cmd.addAll(List.of("-o", exe.toString()));
        Processes.check(Path.of("."), Duration.ofMinutes(1), cmd);
        return Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString())).strip();
    }

    @Test
    void registerWritesRachaelDoesNotDrawAreCounted() throws Exception {
        String out = run("undrawn", """
                #include <stdio.h>
                #include "rachael.h"
                static void show(const char *what) { printf("%s %u\\n", what, sdevkit__rachael.undrawn); }
                int main(void)
                {
                    static const uint8_t data[2] = { 1, 2 };
                    static const uint8_t table[1] = { 0 };
                    uint8_t into[2];
                    sdevkit__reset();                                /* the screen off: every write arrives */
                    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1);   /* what she draws */
                    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, 10);
                    sdevkit_reg_write(SDEVKIT_PPU_TM, 0x13);
                    sdevkit_reg_write(SDEVKIT_PPU_MOSAIC, 0x31);     /* mosaic of size 3 on BG1 */
                    sdevkit_reg_write_twice(SDEVKIT_PPU_M7A, 0x40);  /* a mode 7 matrix value, no effect in mode 1 */
                    sdevkit_vram_copy(0x1000, data, 2);
                    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE0);
                    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE2);
                    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE3);
                    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE4);
                    sdevkit_reg_write(SDEVKIT_PPU_CGWSEL, SDEVKIT_CM_DIRECT_COLOR);
                    sdevkit_reg_write(SDEVKIT_PPU_SETINI, SDEVKIT_SETINI_EXTBG);
                    sdevkit_reg_write(SDEVKIT_PPU_VMAIN, 0x80 | SDEVKIT_VMAIN_REMAP_4BPP);
                    sdevkit_dma(SDEVKIT_DMA_FROM_PPU | 0x01, SDEVKIT_PPU_VMDATALREAD, into, 2);
                    show("drawn");
                    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, 0x05);     /* mode 5: high resolution */
                    show("mode");
                    sdevkit_reg_write(SDEVKIT_PPU_SETINI, 0x01);     /* SETINI: interlace */
                    sdevkit_reg_write(SDEVKIT_PPU_SETINI, 0x00);
                    sdevkit_hdma_set(1, SDEVKIT_PPU_BG1HOFS, 0x40 | SDEVKIT_HDMA_TWICE, table);   /* indirect HDMA */
                    show("more");
                    printf("first %02X %02X\\n", sdevkit__rachael.undrawn_reg, sdevkit__rachael.undrawn_value);
                    return 0;
                }
                """);
        assertEquals("drawn 0\nmode 1\nmore 3\nfirst 05 05", out);
    }

    /**
     * The ports as the SNES has them: VMAIN's address remapping (a run of addresses fills a row of
     * pixels across 4-bit tiles), and VRAM, CGRAM and OAM read back by DMA, VRAM with the word it
     * fetched when the address was set given twice (dev's examples/modes compares the picture with
     * Mesen's).
     */
    @Test
    void thePortsRemapAndReadBack() throws Exception {
        String out = run("ports", """
                #include <stdio.h>
                #include "rachael.h"
                int main(void)
                {
                    static const uint16_t words[4] = { 0x1111, 0x2222, 0x3333, 0x4444 };
                    static const uint16_t colours[2] = { 0x7FFF, 0x1234 };
                    static const uint8_t sprite[4] = { 10, 20, 30, 40 };
                    uint16_t back[5];
                    uint8_t cg[4], ob[4];
                    sdevkit__reset();                                /* the screen off */
                    /* remapped: logical 0, 1, 2, 3 are words 0, 8, 16, 24 (planes 0-1 and 2-3 of tile 0, then tile 1) */
                    sdevkit_reg_write(SDEVKIT_PPU_VMAIN, 0x80 | SDEVKIT_VMAIN_REMAP_4BPP);
                    sdevkit_reg_write(SDEVKIT_PPU_VMADDL, 0);
                    sdevkit_reg_write(SDEVKIT_PPU_VMADDH, 0);
                    sdevkit_dma(0x01, SDEVKIT_PPU_VMDATAL, words, sizeof words);
                    printf("remap %04X %04X %04X %04X %04X\\n", sdevkit__rachael.vram[0], sdevkit__rachael.vram[8],
                           sdevkit__rachael.vram[16], sdevkit__rachael.vram[24], sdevkit__rachael.vram[1]);
                    sdevkit_vram_copy(0x100, words, sizeof words);
                    sdevkit_reg_write(SDEVKIT_PPU_VMAIN, 0x80);
                    sdevkit_reg_write(SDEVKIT_PPU_VMADDL, 0x00);
                    sdevkit_reg_write(SDEVKIT_PPU_VMADDH, 0x01);
                    sdevkit_dma(SDEVKIT_DMA_FROM_PPU | 0x01, SDEVKIT_PPU_VMDATALREAD, back, sizeof back);
                    printf("vram %04X %04X %04X %04X %04X\\n", back[0], back[1], back[2], back[3], back[4]);
                    sdevkit_cgram_copy(5, colours, sizeof colours);
                    sdevkit_reg_write(SDEVKIT_PPU_CGADD, 5);
                    sdevkit_dma(SDEVKIT_DMA_FROM_PPU, SDEVKIT_PPU_CGDATAREAD, cg, sizeof cg);
                    printf("cgram %02X %02X %02X %02X\\n", cg[0], cg[1], cg[2], cg[3]);
                    sdevkit_oam_copy(8, sprite, sizeof sprite);
                    sdevkit_reg_write(SDEVKIT_PPU_OAMADDL, 4);       /* word 4: byte 8 */
                    sdevkit_reg_write(SDEVKIT_PPU_OAMADDH, 0);
                    sdevkit_dma(SDEVKIT_DMA_FROM_PPU, SDEVKIT_PPU_OAMDATAREAD, ob, sizeof ob);
                    printf("oam %u %u %u %u\\n", ob[0], ob[1], ob[2], ob[3]);
                    sdevkit__vblank();
                    sdevkit__display();
                    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);
                    sdevkit_dma(SDEVKIT_DMA_FROM_PPU | 0x01, SDEVKIT_PPU_VMDATALREAD, back, 2);   /* while drawn: lost */
                    printf("lost %u\\n", sdevkit__rachael.dma_lost);
                    return 0;
                }
                """);
        assertEquals("remap 1111 2222 3333 4444 0000\nvram 1111 1111 2222 3333 4444\ncgram FF 7F 34 12\noam 10 20 30 40\nlost 2", out);
    }

    /**
     * What the SNES does with writes while the picture is drawn: VRAM, CGRAM and OAM drop their
     * bytes, registers change the picture from the line being drawn on. Rachael loses the bytes and
     * counts the registers and the HDMA channels; switching the screen off is no such write, and
     * the vertical blank and a screen that is off take everything.
     */
    @Test
    void writesWhileThePictureIsDrawnAreCounted() throws Exception {
        String out = run("drawing", """
                #include <stdio.h>
                #include "rachael.h"
                int main(void)
                {
                    static const uint8_t data[4] = { 1, 2, 3, 4 };
                    static const uint8_t table[2] = { 0 };
                    sdevkit__reset();
                    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);      /* with the screen off: allowed */
                    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, 5); /* now the picture is drawn: two */
                    sdevkit_hdma_set(1, SDEVKIT_PPU_COLDATA, SDEVKIT_HDMA_ONE, table);   /* three */
                    sdevkit_hdma_enable(0x02);                       /* four */
                    sdevkit_vram_copy(0x1000, data, 4);              /* lost */
                    sdevkit_cgram_set(3, 0x1234);                    /* lost */
                    printf("drawing %u lost %u first %02X %02X\\n", sdevkit__rachael.drawing, sdevkit__stats().dma_lost,
                           sdevkit__rachael.drawing_reg, sdevkit__rachael.drawing_value);
                    sdevkit__vblank();                               /* the vertical blank takes everything */
                    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, 6);
                    sdevkit_vram_copy(0x1000, data, 4);
                    sdevkit__display();
                    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, SDEVKIT_FORCED_BLANK);   /* off at once: allowed */
                    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, 7); /* and with the screen off */
                    printf("drawing %u lost %u vram %04X\\n", sdevkit__rachael.drawing, sdevkit__stats().dma_lost,
                           sdevkit_dbg_vram()[0x1000]);
                    return 0;
                }
                """);
        assertEquals("drawing 4 lost 6 first 0D 05\ndrawing 4 lost 0 vram 0201", out);
    }

    /**
     * The game's IRQ (sdevkit/irq.h): Rachael calls it before the row it is set for, a row set
     * below comes in the same picture and one at or above in the next. A register written before
     * sdevkit_irq_wait_hblank counts as written while the picture is drawn, with its register and
     * value, so a handler that forgets the wait fails the desktop check; after the wait it is free.
     * VRAM stays closed in the IRQ: its bytes are lost.
     */
    @Test
    void theGamesIrqRunsAtItsRowAndCountsWritesBeforeTheWait() throws Exception {
        String out = run("irq", """
                #include <stdio.h>
                #include "rachael.h"
                #include "sdevkit/irq.h"
                static uint16_t frame[SDEVKIT__W * SDEVKIT__H];
                static int waits, calls;
                static uint8_t rows[8];
                static void irq(void)
                {
                    static const uint8_t data[2] = { 1, 2 };
                    rows[calls++ & 7] = sdevkit__rachael.irq_row;
                    if (waits) {
                        sdevkit_irq_wait_hblank();
                    }
                    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, 9);
                    if (sdevkit__rachael.irq_row == 40) {
                        sdevkit_irq_line(100);           /* below: in this picture */
                    } else {
                        sdevkit_irq_line(40);            /* above: in the next one */
                        sdevkit_vram_copy(0x1000, data, 2);
                    }
                }
                int main(void)
                {
                    sdevkit__reset();
                    sdevkit__irq_handler = irq;
                    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);
                    sdevkit_irq_line(40);
                    sdevkit__vblank();
                    sdevkit__render(frame);              /* without the wait: four writes counted */
                    printf("calls %d rows %u %u drawing %u first %02X %02X lost %u\\n", calls, rows[0], rows[1],
                           sdevkit__rachael.drawing, sdevkit__rachael.drawing_reg, sdevkit__rachael.drawing_value,
                           sdevkit__stats().dma_lost);
                    waits = 1;
                    sdevkit__vblank();
                    sdevkit__render(frame);              /* with it: none more */
                    printf("calls %d drawing %u lost %u\\n", calls, sdevkit__rachael.drawing, sdevkit__stats().dma_lost);
                    sdevkit_irq_off();
                    sdevkit__vblank();
                    sdevkit__render(frame);
                    sdevkit_irq_line(SDEVKIT_IRQ_ROWS);  /* off too */
                    sdevkit__vblank();
                    sdevkit__render(frame);
                    printf("calls %d\\n", calls);
                    return 0;
                }
                """);
        assertEquals("calls 2 rows 40 100 drawing 4 first 0D 09 lost 2\ncalls 4 drawing 4 lost 2\ncalls 4", out);
    }

    /**
     * SDEVKIT_IRQ_HDMA (sdevkit/irq.h, ADR-6FVWK4JE): on the IRQ's line Rachael compares the HDMA
     * that runs (channels, bytes written) with what the handler declared, and counts the line when
     * more runs; dampf takes the declared HDMA from the time after the wait, so a handler that
     * forgets or understates it fails the desktop check.
     */
    @Test
    void hdmaOnTheIrqsLineMustBeDeclared() throws Exception {
        String out = run("irqhdma", """
                #include <stdio.h>
                #include "rachael.h"
                #include "sdevkit/irq.h"
                static uint16_t frame[SDEVKIT__W * SDEVKIT__H];
                static int declare;
                static const uint8_t table[] = { 200, 15, 0 };
                static void irq(void)
                {
                    if (declare == 1) {
                        SDEVKIT_IRQ_HDMA(SDEVKIT_HDMA_CHANNEL1, 1);
                    } else if (declare == 2) {
                        SDEVKIT_IRQ_HDMA(SDEVKIT_HDMA_CHANNEL2, 1);   /* another channel */
                    }
                    sdevkit_irq_wait_hblank();
                }
                static void show(void)
                {
                    printf("lines %u row %u declared %02X %u ran %02X %u\\n", sdevkit__rachael.irq_hdma,
                           sdevkit__rachael.irq_hdma_row, sdevkit__rachael.irq_hdma_declared[0],
                           sdevkit__rachael.irq_hdma_declared[1], sdevkit__rachael.irq_hdma_actual[0],
                           sdevkit__rachael.irq_hdma_actual[1]);
                }
                int main(void)
                {
                    sdevkit__reset();
                    sdevkit__irq_handler = irq;
                    sdevkit_hdma_set(1, SDEVKIT_PPU_INIDISP, SDEVKIT_HDMA_ONE, table);
                    sdevkit_hdma_enable(SDEVKIT_HDMA_CHANNEL1);
                    sdevkit_irq_line(0);                 /* the entry's first line: its count and byte */
                    sdevkit__vblank();
                    sdevkit__render(frame);              /* not declared */
                    show();
                    declare = 1;
                    sdevkit__vblank();
                    sdevkit__render(frame);              /* declared: nothing more */
                    show();
                    sdevkit_irq_line(50);                /* in the entry: the channel runs, no byte */
                    sdevkit__vblank();
                    sdevkit__render(frame);
                    show();
                    declare = 2;
                    sdevkit__vblank();
                    sdevkit__render(frame);              /* the wrong channel */
                    show();
                    return 0;
                }
                """);
        assertEquals("lines 1 row 0 declared 00 0 ran 02 1\nlines 1 row 0 declared 00 0 ran 02 1\n"
                + "lines 1 row 0 declared 00 0 ran 02 1\nlines 2 row 0 declared 00 0 ran 02 1", out);
    }

    /**
     * The register shadow's layout (sdevkit/shadow.h) is the same on the PC as dampf's on the SNES:
     * no padding, the 16-bit fields first, and the groups the marks name each in one piece, in the
     * order lib/rachael/layers.c compares them.
     */
    @Test
    void theRegisterShadowHasNoPadding() throws Exception {
        String out = run("layout", """
                #include <stdio.h>
                #include <stddef.h>
                #include "rachael.h"
                #include "sdevkit/easy.h"
                int main(void)
                {
                    printf("%u %u %u %u %u %u\\n", (unsigned)sizeof(sdevkit_ppu_regs), (unsigned)offsetof(sdevkit_ppu_regs, m7a),
                           (unsigned)offsetof(sdevkit_ppu_regs, bgmode), (unsigned)offsetof(sdevkit_ppu_regs, w12sel),
                           (unsigned)offsetof(sdevkit_ppu_regs, inidisp), (unsigned)sizeof(sdevkit_input_state));
                    return 0;
                }
                """);
        assertEquals("58 18 33 45 57 8", out);
    }

    /**
     * Where an HDMA table's first entry lands: on row 0 of the picture (scanline 1), as in Mesen
     * (measured by Blubber Blasen with this program, 2026-11-19). The backdrop is black and adds
     * the fixed colour; HDMA writes COLDATA, black for three lines, then white. Rows 0 to 2 are
     * black and row 3 is the first white one.
     */
    @Test
    void anHdmaTablesFirstEntryLandsOnTheFirstRow() throws Exception {
        String out = run("hdma", """
                #include <stdio.h>
                #include "rachael.h"
                static const uint8_t table[] = { 3, 0xE0, 1, 0xFF, 0 };
                static uint16_t picture[SDEVKIT__W * SDEVKIT__H];
                int main(void)
                {
                    int y;
                    sdevkit__reset();                                /* the screen off */
                    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1);
                    sdevkit_cgram_set(0, 0);
                    sdevkit_reg_write(SDEVKIT_PPU_CGADSUB, 0x20);    /* math always, fixed colour, on the backdrop */
                    sdevkit_hdma_set(1, SDEVKIT_PPU_COLDATA, SDEVKIT_HDMA_ONE, table);
                    sdevkit_hdma_enable(SDEVKIT_HDMA_CHANNEL1);
                    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 0x0F);
                    sdevkit__vblank();
                    sdevkit__render(picture);
                    for (y = 0; y < 5; y++) {
                        printf("%04X ", picture[y * SDEVKIT__W]);
                    }
                    return 0;
                }
                """);
        assertEquals("0000 0000 0000 7FFF 7FFF", out);
    }

    /**
     * The renderer's tests (compiler/src/test/c/rachael_test.c, from the game's tests of its pseudo
     * SNES): tiles, palettes and flips, scrolling and 32 x 64 maps, layer order, sprites and the
     * per-line limits, colour math in windows, HDMA tables, brightness; the core's writes and ports;
     * and the optional layers' rules: the sprite list, the queue, the marks, the commit in game_init.
     */
    @Test
    void theRenderersTestsPass() throws Exception {
        Path exe = tmp.resolve("rachael_test");
        Processes.check(Path.of("."), Duration.ofMinutes(1), List.of("cc", "-std=c99", "-Wall", "-Wextra",
                "-Wno-unused-parameter", "-I", "lib/include", "-I", "lib/rachael", "compiler/src/test/c/rachael_test.c",
                "lib/rachael/ppu.c", "lib/rachael/render.c", "lib/rachael/dsp.c", "lib/rachael/layers.c", "lib/rachael/irq.c", "-o", exe.toString()));
        String out = Processes.check(tmp, Duration.ofMinutes(1), List.of(exe.toString()));
        assertEquals("all tests passed", out.strip());
    }
}
