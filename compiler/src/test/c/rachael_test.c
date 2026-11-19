/*
 * Rachael (lib/rachael), the SNES rebuilt in C: memories, registers and the renderer. Each test
 * sets up a small scene through the console API, renders one frame without a window and checks
 * pixels against what the SNES would show. Row y of the picture is scanline y + 1, as on the SNES.
 * From Blubber Blasen's tests/test_snes.c, which tested the game's pseudo SNES before it became
 * Rachael (2026-11-19); RachaelTest builds it with gcc and runs it.
 */

#include <stdio.h>
#include <string.h>

#include "rachael.h"

static int failures;

#define CHECK(cond, ...) do { if (!(cond)) { failures++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static uint16_t frame[SDEVKIT__W * SDEVKIT__H];

#define PX(x, y) frame[(y) * SDEVKIT__W + (x)]

/* VRAM layout of the tests (word addresses) */
#define BG1_MAP  0x0000   /* 32 x 64: 0x0000..0x07FF */
#define BG3_MAP  0x0800
#define BG1_CHR  0x1000
#define BG3_CHR  0x3000
#define OBJ_CHR  0x4000

/* A 4 bpp tile (32 bytes) from 64 colour numbers, row by row. */
static void tile4(uint8_t *out, const uint8_t px[64])
{
    int y, x;
    memset(out, 0, 32);
    for (y = 0; y < 8; y++) {
        for (x = 0; x < 8; x++) {
            uint8_t c = px[y * 8 + x], bit = (uint8_t)(0x80 >> x);
            if (c & 1) out[y * 2] |= bit;
            if (c & 2) out[y * 2 + 1] |= bit;
            if (c & 4) out[16 + y * 2] |= bit;
            if (c & 8) out[16 + y * 2 + 1] |= bit;
        }
    }
}

static void tile2(uint8_t *out, const uint8_t px[64])
{
    int y, x;
    memset(out, 0, 16);
    for (y = 0; y < 8; y++) {
        for (x = 0; x < 8; x++) {
            uint8_t c = px[y * 8 + x], bit = (uint8_t)(0x80 >> x);
            if (c & 1) out[y * 2] |= bit;
            if (c & 2) out[y * 2 + 1] |= bit;
        }
    }
}

static void solid_tile4(uint16_t vram_word, uint8_t color)
{
    uint8_t px[64], t[32];
    memset(px, color, sizeof px);
    tile4(t, px);
    sdevkit_dma_copy_vram(t, vram_word, 32);
}

static void put_map(uint16_t map, uint16_t index, uint16_t entry)
{
    uint8_t b[2];
    b[0] = (uint8_t)entry;
    b[1] = (uint8_t)(entry >> 8);
    sdevkit_dma_copy_vram(b, (uint16_t)(map + index), 2);
}

static void color(uint16_t index, uint16_t c)
{
    sdevkit_set_palette_color(index, c);
}

/* Mode 1, BG3 above all, screen on, sprites 8x8 / 16x16 at OBJ_CHR. */
static void setup(void)
{
    sdevkit__reset();
    sdevkit_set_mode(SDEVKIT_BG_MODE1, SDEVKIT_BG3_MODE1_PRIORITY_HIGH);
    sdevkit_bg_set_map_ptr(0, BG1_MAP, SDEVKIT_SC_32x64);
    sdevkit_bg_set_gfx_ptr(0, BG1_CHR);
    sdevkit_bg_set_map_ptr(2, BG3_MAP, SDEVKIT_SC_32x32);
    sdevkit_bg_set_gfx_ptr(2, BG3_CHR);
    sdevkit_oam_init_gfx_attr(OBJ_CHR, SDEVKIT_OBJ_SIZE8_L16);
    sdevkit_oam_init();
    color(0, SDEVKIT_RGB5(1, 2, 3));   /* backdrop */
    sdevkit_set_screen_on();
    sdevkit__vblank();             /* VRAM and CGRAM only take writes in the vertical blank */
}

/* One frame, then the next vertical blank, so the test may write again. */
static void render(void)
{
    sdevkit__vblank();
    sdevkit__render(frame);
    sdevkit__vblank();
}

static void test_bg_tiles_palettes_flips(void)
{
    uint8_t px[64], t[32];
    int i, x, y;
    setup();
    for (i = 0; i < 64; i++) {
        px[i] = (uint8_t)((i % 8 + i / 8) & 15);   /* colour = x + y */
    }
    tile4(t, px);
    sdevkit_dma_copy_vram(t, BG1_CHR + 16, 32);          /* tile 1 */
    for (i = 1; i < 16; i++) {
        color((uint16_t)(2 * 16 + i), SDEVKIT_RGB5(i, 31 - i, 0));
    }
    /* map row 1: scanlines 8..15, picture rows 7..14 (row y of the picture is scanline y + 1,
     * as on the SNES, so row 0 of the map would lose its first line) */
    put_map(BG1_MAP, 32, SDEVKIT_BG_TIL_NUM(1) | SDEVKIT_BG_TIL_PAL(2));
    put_map(BG1_MAP, 33, SDEVKIT_BG_TIL_NUM(1) | SDEVKIT_BG_TIL_PAL(2) | SDEVKIT_BG_TIL_FLIPX);
    put_map(BG1_MAP, 34, SDEVKIT_BG_TIL_NUM(1) | SDEVKIT_BG_TIL_PAL(2) | SDEVKIT_BG_TIL_FLIPY);
    render();
    for (y = 0; y < 8; y++) {
        for (x = 0; x < 8; x++) {
            uint8_t c = (uint8_t)((x + y) & 15);
            uint16_t want = c ? SDEVKIT_RGB5(c, 31 - c, 0) : SDEVKIT_RGB5(1, 2, 3);
            CHECK(PX(x, 7 + y) == want, "tile pixel %d,%d: %04x, want %04x", x, y, PX(x, 7 + y), want);
            CHECK(PX(8 + 7 - x, 7 + y) == want, "flipped x: %d,%d", x, y);
            CHECK(PX(16 + x, 7 + 7 - y) == want, "flipped y: %d,%d", x, y);
        }
    }
}

static void test_bg_scroll_and_tall_map(void)
{
    setup();
    solid_tile4(BG1_CHR + 16, 5);
    color(5, SDEVKIT_RGB5(31, 0, 0));
    /* row 40 of a 32 x 64 map lives in the second screen (1K words further) */
    put_map(BG1_MAP, (uint16_t)(0x400 + (40 - 32) * 32 + 3), SDEVKIT_BG_TIL_NUM(1));
    sdevkit_bg_set_scroll(0, 0, 40 * 8);
    render();
    CHECK(PX(24, 0) == SDEVKIT_RGB5(31, 0, 0), "row 40 scrolled to the top");
    CHECK(PX(32, 0) == SDEVKIT_RGB5(1, 2, 3), "next tile is empty");
    /* scrolling wraps after 512 lines */
    sdevkit_bg_set_scroll(0, 0, 40 * 8 + 512);
    render();
    CHECK(PX(24, 0) == SDEVKIT_RGB5(31, 0, 0), "vertical scroll wraps at 512");
    /* the scroll registers, written twice (low, high) */
    sdevkit_reg_write(SDEVKIT_PPU_BG1HOFS, 8);
    sdevkit_reg_write(SDEVKIT_PPU_BG1HOFS, 0);
    sdevkit_reg_write(SDEVKIT_PPU_BG1VOFS, (uint8_t)(40 * 8));
    sdevkit_reg_write(SDEVKIT_PPU_BG1VOFS, (uint8_t)((40 * 8) >> 8));
    render();
    CHECK(PX(16, 0) == SDEVKIT_RGB5(31, 0, 0), "scroll written through the registers");
}

static void test_layers_and_priorities(void)
{
    uint8_t px[64], t[16];
    setup();
    solid_tile4(BG1_CHR + 16, 1);
    color(1, SDEVKIT_RGB5(0, 0, 31));                      /* BG1: blue */
    memset(px, 3, sizeof px);
    tile2(t, px);
    sdevkit_dma_copy_vram(t, BG3_CHR + 8, 16);               /* BG3 tile 1, colour 3 */
    color(4 + 3, SDEVKIT_RGB5(0, 31, 0));                  /* BG3 palette 1: green */
    solid_tile4(OBJ_CHR + 16, 2);
    color(128 + 2, SDEVKIT_RGB5(31, 0, 0));                /* sprite palette 0: red */

    put_map(BG1_MAP, 0, SDEVKIT_BG_TIL_NUM(1));
    put_map(BG1_MAP, 1, SDEVKIT_BG_TIL_NUM(1));
    put_map(BG3_MAP, 0, SDEVKIT_BG_TIL_NUM(1) | SDEVKIT_BG_TIL_PAL(1) | SDEVKIT_BG_TIL_PRIO);   /* high: above everything */
    put_map(BG3_MAP, 1, SDEVKIT_BG_TIL_NUM(1) | SDEVKIT_BG_TIL_PAL(1));                 /* low: below sprites and BG1 */
    sdevkit_oam_set(0, 0, 0xFF, 0, 0, 0, 1, 0);             /* y 255: shown from line 0 */
    sdevkit_oam_set(4, 8, 0xFF, 0, 0, 0, 1, 0);
    render();
    CHECK(PX(2, 2) == SDEVKIT_RGB5(0, 31, 0), "BG3 with priority is in front of the sprite");
    CHECK(PX(10, 2) == SDEVKIT_RGB5(0, 0, 31), "a sprite with priority 0 is behind BG1");
    sdevkit_oam_set(4, 8, 0xFF, 3, 0, 0, 1, 0);
    render();
    CHECK(PX(10, 2) == SDEVKIT_RGB5(31, 0, 0), "a sprite with priority 3 is in front of BG1");
    sdevkit_bg_set_disable(0);
    sdevkit_oam_set(4, 8, 0xFF, 0, 0, 0, 1, 0);
    render();
    CHECK(PX(10, 2) == SDEVKIT_RGB5(31, 0, 0), "BG1 off: a priority 0 sprite is still in front of BG3 without priority");
}

static void test_sprites(void)
{
    uint8_t px[64], t[32];
    int i;
    setup();
    sdevkit_oam_init_gfx_attr(OBJ_CHR, SDEVKIT_OBJ_SIZE8_L16);
    /* a large sprite from tiles 0, 1, 16, 17: colours 1, 2, 3, 4 */
    for (i = 0; i < 4; i++) {
        solid_tile4((uint16_t)(OBJ_CHR + ((i & 1) + (i >> 1) * 16) * 16), (uint8_t)(i + 1));
        color((uint16_t)(128 + 16 + i + 1), SDEVKIT_RGB5(i * 8, 0, 0));
    }
    sdevkit_oam_set(0, 10, 20, 0, 0, 0, 0, 1);
    sdevkit_oam_set_ex(0, SDEVKIT_OBJ_LARGE, SDEVKIT_OBJ_SHOW);
    render();
    /* a sprite at y is on scanline y + 1, which is row y of the picture */
    CHECK(PX(10, 19) == SDEVKIT_RGB5(1, 2, 3), "the row above stays empty");
    CHECK(PX(10, 20) == SDEVKIT_RGB5(0, 0, 0) && PX(18, 20) == SDEVKIT_RGB5(8, 0, 0), "top tiles");
    CHECK(PX(10, 28) == SDEVKIT_RGB5(16, 0, 0) && PX(25, 35) == SDEVKIT_RGB5(24, 0, 0), "bottom tiles");
    CHECK(PX(10, 36) == SDEVKIT_RGB5(1, 2, 3), "16 lines tall");

    sdevkit_oam_set(0, 10, 20, 0, 1, 1, 0, 1);              /* flipped both ways */
    render();
    CHECK(PX(10, 20) == SDEVKIT_RGB5(24, 0, 0) && PX(25, 35) == SDEVKIT_RGB5(0, 0, 0), "flipped sprite");

    sdevkit_oam_set(0, 512 - 4, 20, 0, 0, 0, 0, 1);         /* x = -4 */
    render();
    CHECK(PX(0, 21) == SDEVKIT_RGB5(0, 0, 0) && PX(4, 21) == SDEVKIT_RGB5(8, 0, 0), "x -4: the left 4 pixels are cut off");

    /* the shadow only reaches the PPU at the vertical blank */
    sdevkit_oam_set(0, 100, 100, 0, 0, 0, 0, 1);
    sdevkit__render(frame);
    sdevkit__vblank();
    CHECK(PX(100, 101) == SDEVKIT_RGB5(1, 2, 3), "oamSet before the vertical blank is not shown yet");

    /* the lower number is in front, whatever its priority */
    memset(px, 9, sizeof px);
    tile4(t, px);
    sdevkit_dma_copy_vram(t, OBJ_CHR + 2 * 16, 32);
    color(128 + 9, SDEVKIT_RGB5(0, 31, 31));
    sdevkit_oam_clear(0, 0);
    sdevkit_oam_set(0, 50, 50, 0, 0, 0, 2, 0);
    sdevkit_oam_set(4, 50, 50, 3, 0, 0, 0, 1);
    render();
    CHECK(PX(52, 52) == SDEVKIT_RGB5(0, 31, 31), "sprite 0 is in front of sprite 1");
}

static void test_sprite_line_limits(void)
{
    int i;
    sdevkit__frame_stats st;
    setup();
    solid_tile4(OBJ_CHR + 16, 1);
    color(128 + 1, SDEVKIT_RGB5(31, 31, 31));
    /* 33 small sprites on one line: the 33rd is not drawn */
    for (i = 0; i < 33; i++) {
        sdevkit_oam_set((uint16_t)(i * 4), (uint16_t)(i * 7), 99, 0, 0, 0, 1, 0);
    }
    render();
    st = sdevkit__stats();
    CHECK(PX(31 * 7, 100) == SDEVKIT_RGB5(31, 31, 31), "sprite 32 is drawn");
    CHECK(PX(32 * 7 + 1, 100) == SDEVKIT_RGB5(1, 2, 3), "sprite 33 on a line is dropped");
    CHECK(st.range_over_lines == 8 && st.most_sprites == 32, "range over on 8 lines (%u), 32 sprites (%u)",
          st.range_over_lines, st.most_sprites);

    /* 18 large sprites = 36 tiles: two tiles are lost, from sprite 0 */
    setup();
    for (i = 0; i < 4; i++) {
        solid_tile4((uint16_t)(OBJ_CHR + ((i & 1) + (i >> 1) * 16) * 16), 1);
    }
    color(128 + 1, SDEVKIT_RGB5(31, 31, 31));
    for (i = 0; i < 18; i++) {
        sdevkit_oam_set((uint16_t)(i * 4), (uint16_t)(i * 14), 99, 0, 0, 0, 0, 0);
        sdevkit_oam_set_ex((uint16_t)(i * 4), SDEVKIT_OBJ_LARGE, SDEVKIT_OBJ_SHOW);
    }
    render();
    st = sdevkit__stats();
    CHECK(PX(17 * 14 + 2, 100) == SDEVKIT_RGB5(31, 31, 31), "the last sprite keeps its tiles");
    CHECK(PX(2, 100) == SDEVKIT_RGB5(1, 2, 3), "time over drops sprite 0's tiles");
    CHECK(st.time_over_lines == 16 && st.most_tiles == 34, "time over on 16 lines (%u), 34 tiles (%u)",
          st.time_over_lines, st.most_tiles);
}

static void test_color_math_in_window(void)
{
    int x;
    setup();
    solid_tile4(BG1_CHR + 16, 1);
    color(1, SDEVKIT_RGB5(10, 10, 10));
    for (x = 0; x < 32; x++) {
        put_map(BG1_MAP, (uint16_t)x, SDEVKIT_BG_TIL_NUM(1));
    }
    solid_tile4(OBJ_CHR + 16, 1);
    color(128 + 1, SDEVKIT_RGB5(20, 20, 20));
    sdevkit_oam_set(0, 60, 0xFF, 3, 0, 0, 1, 0);

    /* add the fixed colour inside window 1 (x 50..99), on BG1 and the backdrop */
    sdevkit_reg_write(SDEVKIT_PPU_WH0, 50);
    sdevkit_reg_write(SDEVKIT_PPU_WH1, 99);
    sdevkit_reg_write(SDEVKIT_PPU_WOBJSEL, 0x20);               /* colour window = window 1 */
    sdevkit_set_color_effect(SDEVKIT_CM_MATWIN_ENABLE, SDEVKIT_CM_MSCR_BG1 | SDEVKIT_CM_MSCR_BACK);
    sdevkit_set_color_intensity(SDEVKIT_CM_APPLY_RED | SDEVKIT_CM_APPLY_GREEN | SDEVKIT_CM_APPLY_BLUE, 0);
    sdevkit_set_color_intensity(SDEVKIT_CM_APPLY_BLUE, 25);
    render();
    CHECK(PX(40, 2) == SDEVKIT_RGB5(10, 10, 10), "outside the window: unchanged");
    CHECK(PX(70, 2) == SDEVKIT_RGB5(10, 10, 31), "inside: blue added, clamped at 31 (%04x)", PX(70, 2));
    CHECK(PX(62, 2) == SDEVKIT_RGB5(20, 20, 20), "sprites take no colour math");
    CHECK(PX(70, 10) == SDEVKIT_RGB5(1, 2, 28), "the backdrop takes it too");

    /* subtract and halve: (bg - c) / 2 */
    sdevkit_set_color_effect(SDEVKIT_CM_MATWIN_ENABLE, SDEVKIT_CM_SUB_MAINSUB | SDEVKIT_CM_DIV2_DIVIDE | SDEVKIT_CM_MSCR_BG1);
    sdevkit_set_color_intensity(SDEVKIT_CM_APPLY_RED | SDEVKIT_CM_APPLY_GREEN | SDEVKIT_CM_APPLY_BLUE, 4);
    render();
    CHECK(PX(70, 2) == SDEVKIT_RGB5(3, 3, 3), "subtract and halve: %04x", PX(70, 2));

    /* the sub screen instead of the fixed colour: half of BG1 + sprite */
    sdevkit_set_color_effect(SDEVKIT_CM_SUBBGOBJ_ENABLE, SDEVKIT_CM_DIV2_DIVIDE | SDEVKIT_CM_MSCR_BG1);
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE);
    sdevkit_reg_write(SDEVKIT_PPU_TS, SDEVKIT_OBJ_ENABLE);
    render();
    CHECK(PX(62, 2) == SDEVKIT_RGB5(15, 15, 15), "BG1 half-blended with the sprite on the sub screen: %04x", PX(62, 2));
    CHECK(PX(40, 2) == SDEVKIT_RGB5(14, 14, 14), "empty sub screen: the fixed colour (4), not halved: %04x", PX(40, 2));
}

static void test_hdma_windows(void)
{
    /* window 1 is x 0..255 on lines 0..9, then empty; window 2 is empty on
     * lines 0..9, then moves one pixel per line for three lines */
    static const uint8_t w1[] = { 10, 0, 255, 1, 255, 0, 0 };
    static const uint8_t w2[] = { 10, 255, 0, 0x83, 10, 20, 11, 21, 12, 22, 0 };
    setup();
    sdevkit_reg_write(SDEVKIT_PPU_WOBJSEL, 0xA0);               /* colour window = window 1 or window 2 */
    sdevkit_set_color_effect(SDEVKIT_CM_MATWIN_ENABLE, SDEVKIT_CM_MSCR_BACK);
    sdevkit_set_color_intensity(SDEVKIT_CM_APPLY_RED, 20);
    sdevkit_hdma_set(4, SDEVKIT_PPU_WH0, 1, w1);
    sdevkit_hdma_set(5, SDEVKIT_PPU_WH2, 1, w2);
    sdevkit_hdma_enable(SDEVKIT_HDMA_CHANNEL4 | SDEVKIT_HDMA_CHANNEL5);
    render();
    CHECK(PX(200, 5) == SDEVKIT_RGB5(21, 2, 3), "window 1 on line 5");
    CHECK(PX(200, 15) == SDEVKIT_RGB5(1, 2, 3), "window 1 empty from line 10");
    CHECK(PX(10, 10) == SDEVKIT_RGB5(21, 2, 3) && PX(9, 10) == SDEVKIT_RGB5(1, 2, 3), "window 2 on line 10: x 10..20");
    CHECK(PX(11, 11) == SDEVKIT_RGB5(21, 2, 3) && PX(10, 11) == SDEVKIT_RGB5(1, 2, 3), "line 11: x 11..21");
    CHECK(PX(22, 12) == SDEVKIT_RGB5(21, 2, 3) && PX(23, 12) == SDEVKIT_RGB5(1, 2, 3), "line 12: x 12..22");
    CHECK(PX(12, 100) == SDEVKIT_RGB5(21, 2, 3) && PX(11, 100) == SDEVKIT_RGB5(1, 2, 3),
          "after the table the registers keep the last value");
}

static void test_brightness_and_blank(void)
{
    setup();
    color(0, SDEVKIT_RGB5(30, 15, 3));
    sdevkit_set_brightness(15);
    render();
    CHECK(PX(0, 0) == SDEVKIT_RGB5(30, 15, 3), "full brightness");
    sdevkit_set_brightness(5);
    render();
    CHECK(PX(0, 0) == SDEVKIT_RGB5(10, 5, 1), "brightness 5 of 15: %04x", PX(0, 0));
    sdevkit_set_screen_off();
    render();
    CHECK(PX(0, 0) == 0, "forced blank is black");
}

/* VRAM and CGRAM take writes only in the vertical blank, and only about
 * 5.7 KB of them, unless the screen is off; the rest is lost and counted. */
static void test_vblank_rules(void)
{
    static const uint8_t data[4096] = { 0x55, 0x55 };
    setup();
    sdevkit__render(frame);                        /* the picture is drawn: VRAM is closed */
    sdevkit_dma_copy_vram(data, 0x2000, 2);
    CHECK(sdevkit_dbg_vram()[0x2000] == 0, "a write while the picture is drawn must not arrive");
    CHECK(sdevkit__stats().dma_lost == 2, "and is counted as lost (%u)", sdevkit__stats().dma_lost);
    sdevkit__vblank();
    sdevkit_dma_copy_vram(data, 0x2000, 2);
    CHECK(sdevkit_dbg_vram()[0x2000] == 0x5555, "in the vertical blank it arrives");
    sdevkit_dma_copy_vram(data, 0x3000, 4096);
    sdevkit_dma_copy_vram(data, 0x4000, 4096);           /* 2 + 4096 + 4096 is over the budget */
    CHECK(sdevkit_dbg_vram()[0x4000] == 0 && sdevkit__stats().dma_lost == 4096, "over the budget the rest is lost (%u)",
          sdevkit__stats().dma_lost);
    sdevkit_set_screen_off();
    sdevkit__render(frame);
    sdevkit_dma_copy_vram(data, 0x5000, 4096);
    sdevkit_dma_copy_vram(data, 0x6000, 4096);
    CHECK(sdevkit_dbg_vram()[0x6000] == 0x5555, "with the screen off there is no limit");
}

static void test_dma_count(void)
{
    static const uint8_t data[64] = { 0 };
    setup();
    sdevkit__vblank();
    sdevkit_dma_copy_vram(data, 0x2000, 64);
    sdevkit_dma_copy_cgram(data, 16, 32);
    CHECK(sdevkit__stats().dma_bytes == 96, "96 bytes in this vertical blank, counted %u", sdevkit__stats().dma_bytes);
    sdevkit__vblank();
    CHECK(sdevkit__stats().dma_bytes == 0, "the count starts again each frame");
}

int main(void)
{
    test_bg_tiles_palettes_flips();
    test_bg_scroll_and_tall_map();
    test_layers_and_priorities();
    test_sprites();
    test_sprite_line_limits();
    test_color_math_in_window();
    test_hdma_windows();
    test_brightness_and_blank();
    test_dma_count();
    test_vblank_rules();
    if (failures) {
        printf("%d check(s) failed\n", failures);
        return 1;
    }
    printf("all tests passed\n");
    return 0;
}
