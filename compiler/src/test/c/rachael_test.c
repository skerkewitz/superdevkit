/*
 * Rachael (lib/rachael), the SNES rebuilt in C: memories, registers and the renderer, and the
 * console API's shadow state. Each test sets up a small scene through the console API (with the
 * screen off: the shadow state, the writes now), renders one frame without a window and checks
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
    sdevkit_vram_now(vram_word, t, 32);
}

static void put_map(uint16_t map, uint16_t index, uint16_t entry)
{
    uint8_t b[2];
    b[0] = (uint8_t)entry;
    b[1] = (uint8_t)(entry >> 8);
    sdevkit_vram_now((uint16_t)(map + index), b, 2);
}

static void color(uint8_t index, uint16_t c)
{
    sdevkit_color_now(index, c);
}

/* Sprite n of the table (written directly, as a game that keeps its slots does): at x, y as the
 * table has them (the SNES draws it one line lower), the large size or not. */
static void spr(uint8_t n, uint16_t x, uint8_t y, uint8_t prio, uint8_t hflip, uint8_t vflip, uint16_t tile, uint8_t pal,
                uint8_t large)
{
    uint8_t *h = &sdevkit_oam[512 + (n >> 2)];
    uint8_t shift = (uint8_t)((n & 3) * 2);
    sdevkit_oam[n * 4] = (uint8_t)x;
    sdevkit_oam[n * 4 + 1] = y;
    sdevkit_oam[n * 4 + 2] = (uint8_t)tile;
    sdevkit_oam[n * 4 + 3] = (uint8_t)(SDEVKIT_OAM_ATTR(pal, prio, hflip, vflip) | ((tile >> 8) & 1));
    *h = (uint8_t)((*h & ~(3 << shift)) | ((((x >> 8) & 1) | (large ? 2 : 0)) << shift));
}

static uint8_t brightness;      /* INIDISP of the next frame */

/* Mode 1, BG3 above all, sprites 8x8 / 16x16 at OBJ_CHR; the screen stays off for the test's
 * writes now until render switches it on. */
static void setup(void)
{
    sdevkit__reset();
    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1 | SDEVKIT_BG3_MODE1_PRIORITY_HIGH;
    sdevkit_ppu.tm = SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_BG3_ENABLE | SDEVKIT_OBJ_ENABLE;
    sdevkit_ppu.bgsc[0] = SDEVKIT_BGSC(BG1_MAP, SDEVKIT_SC_32x64);
    sdevkit_ppu.bgsc[2] = SDEVKIT_BGSC(BG3_MAP, SDEVKIT_SC_32x32);
    sdevkit_ppu.bgnba[0] = SDEVKIT_BGNBA(BG1_CHR, 0);
    sdevkit_ppu.bgnba[1] = SDEVKIT_BGNBA(BG3_CHR, 0);
    sdevkit_ppu.obsel = SDEVKIT_OBSEL(OBJ_CHR, SDEVKIT_OBJ_SIZE8_L16);
    color(0, SDEVKIT_RGB5(1, 2, 3));   /* backdrop */
    brightness = 15;
}

/* One frame: the commit (every group), the picture, and the screen off again for the next writes. */
static void render(void)
{
    sdevkit_ppu.inidisp = brightness;
    sdevkit_marks = SDEVKIT_MARK_ALL;
    sdevkit__vblank();
    sdevkit__render(frame);
    sdevkit_screen_off_now();
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
    sdevkit_vram_now(BG1_CHR + 16, t, 32);           /* tile 1 */
    for (i = 1; i < 16; i++) {
        color((uint8_t)(2 * 16 + i), SDEVKIT_RGB5(i, 31 - i, 0));
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
    sdevkit_ppu.vofs[0] = 40 * 8;
    render();
    CHECK(PX(24, 0) == SDEVKIT_RGB5(31, 0, 0), "row 40 scrolled to the top");
    CHECK(PX(32, 0) == SDEVKIT_RGB5(1, 2, 3), "next tile is empty");
    /* scrolling wraps after 512 lines */
    sdevkit_ppu.vofs[0] = 40 * 8 + 512;
    render();
    CHECK(PX(24, 0) == SDEVKIT_RGB5(31, 0, 0), "vertical scroll wraps at 512");
    /* the scroll registers, written twice (low, high) by the commit */
    sdevkit_ppu.hofs[0] = 8;
    sdevkit_ppu.vofs[0] = 40 * 8;
    render();
    CHECK(PX(16, 0) == SDEVKIT_RGB5(31, 0, 0), "a horizontal scroll of 8");
    CHECK(sdevkit__rachael.hofs[0] == 8 && sdevkit__rachael.vofs[0] == 320, "the registers: %u %u",
          sdevkit__rachael.hofs[0], sdevkit__rachael.vofs[0]);
}

static void test_layers_and_priorities(void)
{
    uint8_t px[64], t[16];
    setup();
    solid_tile4(BG1_CHR + 16, 1);
    color(1, SDEVKIT_RGB5(0, 0, 31));                      /* BG1: blue */
    memset(px, 3, sizeof px);
    tile2(t, px);
    sdevkit_vram_now(BG3_CHR + 8, t, 16);                  /* BG3 tile 1, colour 3 */
    color(4 + 3, SDEVKIT_RGB5(0, 31, 0));                  /* BG3 palette 1: green */
    solid_tile4(OBJ_CHR + 16, 2);
    color(128 + 2, SDEVKIT_RGB5(31, 0, 0));                /* sprite palette 0: red */

    put_map(BG1_MAP, 0, SDEVKIT_BG_TIL_NUM(1));
    put_map(BG1_MAP, 1, SDEVKIT_BG_TIL_NUM(1));
    put_map(BG3_MAP, 0, SDEVKIT_BG_TIL_NUM(1) | SDEVKIT_BG_TIL_PAL(1) | SDEVKIT_BG_TIL_PRIO);   /* high: above everything */
    put_map(BG3_MAP, 1, SDEVKIT_BG_TIL_NUM(1) | SDEVKIT_BG_TIL_PAL(1));                 /* low: below sprites and BG1 */
    spr(0, 0, 0xFF, 0, 0, 0, 1, 0, 0);                     /* y 255: shown from line 0 */
    spr(1, 8, 0xFF, 0, 0, 0, 1, 0, 0);
    render();
    CHECK(PX(2, 2) == SDEVKIT_RGB5(0, 31, 0), "BG3 with priority is in front of the sprite");
    CHECK(PX(10, 2) == SDEVKIT_RGB5(0, 0, 31), "a sprite with priority 0 is behind BG1");
    spr(1, 8, 0xFF, 3, 0, 0, 1, 0, 0);
    render();
    CHECK(PX(10, 2) == SDEVKIT_RGB5(31, 0, 0), "a sprite with priority 3 is in front of BG1");
    sdevkit_ppu.tm = (uint8_t)(sdevkit_ppu.tm & ~SDEVKIT_BG1_ENABLE);
    spr(1, 8, 0xFF, 0, 0, 0, 1, 0, 0);
    render();
    CHECK(PX(10, 2) == SDEVKIT_RGB5(31, 0, 0), "BG1 off: a priority 0 sprite is still in front of BG3 without priority");
}

static void test_sprites(void)
{
    uint8_t px[64], t[32];
    int i;
    setup();
    /* a large sprite from tiles 0, 1, 16, 17: colours 1, 2, 3, 4 */
    for (i = 0; i < 4; i++) {
        solid_tile4((uint16_t)(OBJ_CHR + ((i & 1) + (i >> 1) * 16) * 16), (uint8_t)(i + 1));
        color((uint8_t)(128 + 16 + i + 1), SDEVKIT_RGB5(i * 8, 0, 0));
    }
    spr(0, 10, 20, 0, 0, 0, 0, 1, 1);
    render();
    /* a sprite at y is on scanline y + 1, which is row y of the picture */
    CHECK(PX(10, 19) == SDEVKIT_RGB5(1, 2, 3), "the row above stays empty");
    CHECK(PX(10, 20) == SDEVKIT_RGB5(0, 0, 0) && PX(18, 20) == SDEVKIT_RGB5(8, 0, 0), "top tiles");
    CHECK(PX(10, 28) == SDEVKIT_RGB5(16, 0, 0) && PX(25, 35) == SDEVKIT_RGB5(24, 0, 0), "bottom tiles");
    CHECK(PX(10, 36) == SDEVKIT_RGB5(1, 2, 3), "16 lines tall");

    spr(0, 10, 20, 0, 1, 1, 0, 1, 1);                       /* flipped both ways */
    render();
    CHECK(PX(10, 20) == SDEVKIT_RGB5(24, 0, 0) && PX(25, 35) == SDEVKIT_RGB5(0, 0, 0), "flipped sprite");

    spr(0, 512 - 4, 20, 0, 0, 0, 0, 1, 1);                  /* x = -4 */
    render();
    CHECK(PX(0, 21) == SDEVKIT_RGB5(0, 0, 0) && PX(4, 21) == SDEVKIT_RGB5(8, 0, 0), "x -4: the left 4 pixels are cut off");

    /* the sprite table only reaches the PPU at the commit */
    sdevkit_ppu.inidisp = 15;
    sdevkit__vblank();
    spr(0, 100, 100, 0, 0, 0, 0, 1, 1);
    sdevkit__render(frame);
    CHECK(PX(100, 101) == SDEVKIT_RGB5(1, 2, 3), "a sprite written after the commit is not shown yet");
    sdevkit_screen_off_now();

    /* the lower number is in front, whatever its priority */
    memset(px, 9, sizeof px);
    tile4(t, px);
    sdevkit_vram_now(OBJ_CHR + 2 * 16, t, 32);
    color(128 + 9, SDEVKIT_RGB5(0, 31, 31));
    spr(0, 50, 50, 0, 0, 0, 2, 0, 0);
    spr(1, 50, 50, 3, 0, 0, 0, 1, 1);
    render();
    CHECK(PX(52, 52) == SDEVKIT_RGB5(0, 31, 31), "sprite 0 is in front of sprite 1");
}

/* The sprite list: the position where a background scrolled by (0, 0) has its pixel (x, y), which
 * is row y - 1 of the picture (the kit writes y - 1 into the table); the order; out of the picture
 * left out; and after the tick the entries of the tick before that were not filled again hidden. */
static void test_sprite_list(void)
{
    setup();
    solid_tile4(OBJ_CHR + 16, 1);
    color(128 + 1, SDEVKIT_RGB5(31, 31, 31));
    sdevkit_sprite(40, 30, 1, SDEVKIT_OAM_ATTR(0, 3, 0, 0), 0);
    sdevkit_sprite(-4, 60, 1, SDEVKIT_OAM_ATTR(0, 3, 0, 0), 0);
    sdevkit_sprite(500, 60, 1, SDEVKIT_OAM_ATTR(0, 3, 0, 0), 0);   /* out of the picture: left out */
    sdevkit_sprite(80, 30, 1, SDEVKIT_OAM_ATTR(0, 3, 0, 0), 0);
    (void)sdevkit__tick_hash();
    render();
    CHECK(PX(40, 29) == SDEVKIT_RGB5(31, 31, 31) && PX(40, 28) == SDEVKIT_RGB5(1, 2, 3), "the first from row 29");
    CHECK(PX(0, 59) == SDEVKIT_RGB5(31, 31, 31) && PX(4, 59) == SDEVKIT_RGB5(1, 2, 3), "x -4: four pixels left");
    CHECK(sdevkit_oam[2 * 4] == 80, "the third call takes entry 2: %u", sdevkit_oam[2 * 4]);
    sdevkit_sprite(40, 30, 1, SDEVKIT_OAM_ATTR(0, 3, 0, 0), 0);
    (void)sdevkit__tick_hash();
    render();
    CHECK(PX(80, 29) == SDEVKIT_RGB5(1, 2, 3) && PX(0, 59) == SDEVKIT_RGB5(1, 2, 3), "the others of the tick before are hidden");
    CHECK(sdevkit_oam[1 * 4 + 1] == SDEVKIT_OAM_HIDE_Y && sdevkit_oam[2 * 4 + 1] == SDEVKIT_OAM_HIDE_Y, "entries 1 and 2 hidden");
    (void)sdevkit__tick_hash();
    render();
    CHECK(PX(40, 29) == SDEVKIT_RGB5(1, 2, 3) && sdevkit_oam[1] == SDEVKIT_OAM_HIDE_Y, "a tick without sprites hides the last");
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
        spr((uint8_t)i, (uint16_t)(i * 7), 99, 0, 0, 0, 1, 0, 0);
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
        spr((uint8_t)i, (uint16_t)(i * 14), 99, 0, 0, 0, 0, 0, 1);
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
    spr(0, 60, 0xFF, 3, 0, 0, 1, 0, 0);

    /* add the fixed colour inside window 1 (x 50..99), on BG1 and the backdrop */
    sdevkit_ppu.wh[0] = 50;
    sdevkit_ppu.wh[1] = 99;
    sdevkit_ppu.wobjsel = 0x20;                            /* colour window = window 1 */
    sdevkit_ppu.cgwsel = SDEVKIT_CM_MATWIN_ENABLE;
    sdevkit_ppu.cgadsub = SDEVKIT_CM_MSCR_BG1 | SDEVKIT_CM_MSCR_BACK;
    sdevkit_ppu.coldata = SDEVKIT_RGB5(0, 0, 25);
    render();
    CHECK(PX(40, 2) == SDEVKIT_RGB5(10, 10, 10), "outside the window: unchanged");
    CHECK(PX(70, 2) == SDEVKIT_RGB5(10, 10, 31), "inside: blue added, clamped at 31 (%04x)", PX(70, 2));
    CHECK(PX(62, 2) == SDEVKIT_RGB5(20, 20, 20), "sprites take no colour math");
    CHECK(PX(70, 10) == SDEVKIT_RGB5(1, 2, 28), "the backdrop takes it too");

    /* subtract and halve: (bg - c) / 2 */
    sdevkit_ppu.cgadsub = SDEVKIT_CM_SUB_MAINSUB | SDEVKIT_CM_DIV2_DIVIDE | SDEVKIT_CM_MSCR_BG1;
    sdevkit_ppu.coldata = SDEVKIT_RGB5(4, 4, 4);
    render();
    CHECK(PX(70, 2) == SDEVKIT_RGB5(3, 3, 3), "subtract and halve: %04x", PX(70, 2));

    /* the sub screen instead of the fixed colour: half of BG1 + sprite */
    sdevkit_ppu.cgwsel = SDEVKIT_CM_SUBBGOBJ_ENABLE;
    sdevkit_ppu.cgadsub = SDEVKIT_CM_DIV2_DIVIDE | SDEVKIT_CM_MSCR_BG1;
    sdevkit_ppu.tm = SDEVKIT_BG1_ENABLE;
    sdevkit_ppu.ts = SDEVKIT_OBJ_ENABLE;
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
    sdevkit_ppu.wobjsel = 0xA0;                            /* colour window = window 1 or window 2 */
    sdevkit_ppu.cgwsel = SDEVKIT_CM_MATWIN_ENABLE;
    sdevkit_ppu.cgadsub = SDEVKIT_CM_MSCR_BACK;
    sdevkit_ppu.coldata = SDEVKIT_RGB5(20, 0, 0);
    sdevkit_hdma[4].table = w1;
    sdevkit_hdma[4].dmap = SDEVKIT_HDMA_TWO_REGS;
    sdevkit_hdma[4].bbad = SDEVKIT_PPU_WH0;
    sdevkit_hdma[5].table = w2;
    sdevkit_hdma[5].dmap = SDEVKIT_HDMA_TWO_REGS;
    sdevkit_hdma[5].bbad = SDEVKIT_PPU_WH2;
    sdevkit_ppu.hdmaen = SDEVKIT_HDMA_CHANNEL4 | SDEVKIT_HDMA_CHANNEL5;
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
    render();
    CHECK(PX(0, 0) == SDEVKIT_RGB5(30, 15, 3), "full brightness");
    brightness = 5;
    render();
    CHECK(PX(0, 0) == SDEVKIT_RGB5(10, 5, 1), "brightness 5 of 15: %04x", PX(0, 0));
    brightness = SDEVKIT_FORCED_BLANK;
    render();
    CHECK(PX(0, 0) == 0, "forced blank is black");
}

/* A write now reaches VRAM and CGRAM only while the screen is off; with it on, its bytes are lost
 * and counted, as on the SNES, where the PPU drops what comes while it draws. */
static void test_writes_now(void)
{
    static const uint8_t data[64] = { 0x55, 0x55 };
    setup();
    render();
    sdevkit_ppu.inidisp = 15;
    sdevkit__vblank();                             /* the screen on */
    sdevkit__render(frame);
    sdevkit_vram_now(0x2000, data, 2);
    sdevkit_color_now(7, 0x1234);
    CHECK(sdevkit_dbg_vram()[0x2000] == 0 && sdevkit__cgram()[7] == 0, "with the screen on nothing arrives");
    CHECK(sdevkit__stats().dma_lost == 4, "and the bytes are counted as lost (%u)", sdevkit__stats().dma_lost);
    sdevkit_screen_off_now();
    CHECK((sdevkit_ppu.inidisp & SDEVKIT_FORCED_BLANK) && (sdevkit__reg(SDEVKIT_PPU_INIDISP) & 0x80), "off at once, in the shadow too");
    sdevkit_vram_now(0x2000, data, 2);
    CHECK(sdevkit_dbg_vram()[0x2000] == 0x5555, "with the screen off it arrives");
    /* the copies as the SNES's ports make them: a column, mode 7's low and high bytes */
    sdevkit_vram_column_now(0x3000, data, 4);
    CHECK(sdevkit_dbg_vram()[0x3000] == 0x5555 && sdevkit_dbg_vram()[0x3020] == 0, "down a column: %04x %04x",
          sdevkit_dbg_vram()[0x3000], sdevkit_dbg_vram()[0x3020]);
    sdevkit_mode7_map_now(0x0100, data, 2);
    sdevkit_mode7_tiles_now(0x0100, data + 2, 2);
    CHECK(sdevkit_dbg_vram()[0x0100] == 0x0055 && sdevkit_dbg_vram()[0x0101] == 0x0055, "mode 7: low bytes, then high: %04x",
          sdevkit_dbg_vram()[0x0100]);
}

/* The queue: copied at the next commit, in order; what does not fit into a vertical blank is
 * refused when the game asks, the same on both platforms; the count starts again each frame. */
static void test_queue(void)
{
    static const uint8_t data[SDEVKIT_QUEUE_BYTES] = { 0x34, 0x12 };
    uint16_t room;
    uint8_t ok;
    setup();
    render();
    CHECK(sdevkit_queue_vram(0x2000, data, 64) && sdevkit_queue_cgram(16, data, 2) && sdevkit_queue_color(17, 0x0BCD),
          "three copies queued");
    CHECK(sdevkit_dbg_vram()[0x2000] == 0 && sdevkit__cgram()[16] == 0, "nothing arrives before the commit");
    render();
    CHECK(sdevkit_dbg_vram()[0x2000] == 0x1234 && sdevkit__cgram()[16] == 0x1234 && sdevkit__cgram()[17] == 0x0BCD,
          "the commit copies them");
    CHECK(sdevkit__stats().dma_bytes == 68, "68 bytes in that vertical blank, counted %u", sdevkit__stats().dma_bytes);
    room = sdevkit_queue_room();
    CHECK(room == SDEVKIT_QUEUE_BYTES - SDEVKIT_QUEUE_COST, "an empty queue: %u bytes for one copy", room);
    CHECK(sdevkit_queue_vram(0x3000, data, room), "exactly the room fits");
    ok = sdevkit_queue_vram(0x4000, data, 2);
    CHECK(!ok && sdevkit_queue_room() == 0, "then nothing more");
    render();
    CHECK(sdevkit_dbg_vram()[0x4000] == 0 && sdevkit__rachael.refused == 1, "the refused copy is not made, and counted");
    CHECK(sdevkit_queue_vram(0x4000, data, 2), "the next frame has room again");
    CHECK(!sdevkit_queue_vram(0x5000, data, 0), "a copy of no bytes is refused (the SNES would copy 64 KB)");
}

/* The marks: a group the tick changed is committed only with its mark, and Rachael counts a change
 * without it, once; INIDISP needs none. */
static void test_marks(void)
{
    setup();
    render();
    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE7;
    sdevkit_ppu.hofs[1] = 0x123;
    sdevkit_ppu.inidisp = 7;
    sdevkit__vblank();
    CHECK(sdevkit__reg(SDEVKIT_PPU_BGMODE) == (SDEVKIT_BG_MODE1 | SDEVKIT_BG3_MODE1_PRIORITY_HIGH),
          "BGMODE without its mark: not committed (%02X)", sdevkit__reg(SDEVKIT_PPU_BGMODE));
    CHECK(sdevkit__rachael.hofs[1] != 0x123, "the scroll without its mark: not committed either");
    CHECK(sdevkit__rachael.unmarked == 2 && sdevkit__rachael.unmarked_group == SDEVKIT_MARK_SETUP,
          "both counted, setup first (%u)", sdevkit__rachael.unmarked);
    CHECK(sdevkit__reg(SDEVKIT_PPU_INIDISP) == 7, "INIDISP needs no mark");
    sdevkit__vblank();
    CHECK(sdevkit__rachael.unmarked == 2, "a change is counted once (%u)", sdevkit__rachael.unmarked);
    sdevkit_marks = SDEVKIT_MARK_SETUP | SDEVKIT_MARK_SCROLL;
    sdevkit__vblank();
    CHECK(sdevkit__reg(SDEVKIT_PPU_BGMODE) == SDEVKIT_BG_MODE7 && sdevkit__rachael.hofs[1] == 0x123, "with their marks they are");
    CHECK(sdevkit_marks == 0 && sdevkit__rachael.unmarked == 2, "the commit clears the marks");
    sdevkit_hdma[2].table = (const uint8_t *)"\0";
    sdevkit__vblank();
    CHECK(sdevkit__rachael.unmarked == 3, "an HDMA channel without its mark is counted too");
}

/* sdevkit_commit_now in game_init: the shadow and the queue at once, the sprite table not; in a
 * tick it does nothing. */
static void test_commit_now(void)
{
    static const uint8_t data[2] = { 0x78, 0x56 };
    setup();
    sdevkit_ppu.inidisp = 9;
    sdevkit_ppu.hofs[0] = 77;
    sdevkit_oam[0] = 99;
    CHECK(sdevkit_queue_vram(0x2000, data, 2), "queued");
    sdevkit_commit_now();
    CHECK(sdevkit__reg(SDEVKIT_PPU_INIDISP) == 9 && sdevkit__rachael.hofs[0] == 77 && sdevkit_dbg_vram()[0x2000] == 0x5678,
          "committed");
    CHECK(sdevkit__oam()[0] != 99, "but not the sprite table");
    sdevkit__rachael.in_tick = 1;
    sdevkit_ppu.hofs[0] = 78;
    sdevkit_commit_now();
    CHECK(sdevkit__rachael.hofs[0] == 77, "in a tick it does nothing");
    sdevkit__rachael.in_tick = 0;
}

int main(void)
{
    test_bg_tiles_palettes_flips();
    test_bg_scroll_and_tall_map();
    test_layers_and_priorities();
    test_sprites();
    test_sprite_list();
    test_sprite_line_limits();
    test_color_math_in_window();
    test_hdma_windows();
    test_brightness_and_blank();
    test_writes_now();
    test_queue();
    test_marks();
    test_commit_now();
    if (failures) {
        printf("%d check(s) failed\n", failures);
        return 1;
    }
    printf("all tests passed\n");
    return 0;
}
