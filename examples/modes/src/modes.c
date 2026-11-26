/*
 * modes: the picture modes of the SNES beyond mode 1, one screen each, so that the SNES and the
 * desktop can be shown to draw them the same, pixel for pixel (dev/compare_example.sh). A goes to
 * the next screen, B back; each is labelled at the top, in sprites, which every mode has:
 *
 *   0  mode 0: four layers of 4 colours, each with 32 colours of its own, scrolling apart
 *   1  mode 2: a scroll for each column of tiles from BG3's map (offset per tile), a wave
 *   2  mode 3: a picture of 256 colours (superdevkit png tiles8) in a frame of BG2
 *   3  mode 3 with direct colour: the pixels are colours themselves, no CGRAM
 *   4  mode 4: the picture again, its columns moved up, down and sideways by offsets per tile
 *   5  mosaic on two layers of mode 1, the block size growing
 *   6  mode 7's second layer (EXTBG): sprites pass behind the bright parts, in front of the rest
 *   7  mosaic on mode 7
 *   8  VRAM: a picture written as a bitmap through VMAIN's address remapping, and VRAM, CGRAM and
 *      OAM read back by DMA and written elsewhere (a copied text row, inverted colours, a copied
 *      label)
 *
 * It uses the console API's core (sdevkit/snes.h): a screen is set up in game_vblank with the
 * screen off, then the tick computes what moves and game_vblank writes it. The pictures are made
 * here, except the font and the picture of screens 2 and 4 (dev/art/modes.py).
 */
#include <stdint.h>
#include <string.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "font_gfx.h"
#include "picture_gfx.h"

#define SCREENS      9
#define VRAM_SPRITES 0x6000            /* the font, for every screen */
#define CENTER       512               /* the middle of mode 7's map */
#define EMPTY8       192               /* the empty tile after the picture's 192 */

/* sin(2 pi i / 256) in 8.8 fixed point, as examples/mode7 has it */
static const int16_t SIN[256] = {
       0,    6,   13,   19,   25,   31,   38,   44,   50,   56,   62,   68,   74,   80,   86,   92,
      98,  104,  109,  115,  121,  126,  132,  137,  142,  147,  152,  157,  162,  167,  172,  177,
     181,  185,  190,  194,  198,  202,  206,  209,  213,  216,  220,  223,  226,  229,  231,  234,
     237,  239,  241,  243,  245,  247,  248,  250,  251,  252,  253,  254,  255,  255,  256,  256,
     256,  256,  256,  255,  255,  254,  253,  252,  251,  250,  248,  247,  245,  243,  241,  239,
     237,  234,  231,  229,  226,  223,  220,  216,  213,  209,  206,  202,  198,  194,  190,  185,
     181,  177,  172,  167,  162,  157,  152,  147,  142,  137,  132,  126,  121,  115,  109,  104,
      98,   92,   86,   80,   74,   68,   62,   56,   50,   44,   38,   31,   25,   19,   13,    6,
       0,   -6,  -13,  -19,  -25,  -31,  -38,  -44,  -50,  -56,  -62,  -68,  -74,  -80,  -86,  -92,
     -98, -104, -109, -115, -121, -126, -132, -137, -142, -147, -152, -157, -162, -167, -172, -177,
    -181, -185, -190, -194, -198, -202, -206, -209, -213, -216, -220, -223, -226, -229, -231, -234,
    -237, -239, -241, -243, -245, -247, -248, -250, -251, -252, -253, -254, -255, -255, -256, -256,
    -256, -256, -256, -255, -255, -254, -253, -252, -251, -250, -248, -247, -245, -243, -241, -239,
    -237, -234, -231, -229, -226, -223, -220, -216, -213, -209, -206, -202, -198, -194, -190, -185,
    -181, -177, -172, -167, -162, -157, -152, -147, -142, -137, -132, -126, -121, -115, -109, -104,
     -98,  -92,  -86,  -80,  -74,  -68,  -62,  -56,  -50,  -44,  -38,  -31,  -25,  -19,  -13,   -6,
};

static const char FONT[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-:.";

static const char *const TITLE[SCREENS] = {
    "MODE 0: FOUR LAYERS",
    "MODE 2: OFFSET PER TILE",
    "MODE 3: 256 COLOURS",
    "MODE 3: DIRECT COLOUR",
    "MODE 4: 256 COLOURS AND OPT",
    "MOSAIC",
    "MODE 7: EXTBG",
    "MODE 7: MOSAIC",
    "VRAM: REMAP AND READ BACK",
};

/* What the screens are made of, in RAM: maps, generated tiles, mode 7's map, the bitmap of
 * screen 8, the sprite table, and what screen 8 reads back. */
static uint16_t map_a[32 * 32], map_b[32 * 32], map_c[32 * 32], map_d[32 * 32];
static uint8_t tiles[8 * 64];
static uint8_t m7_tiles[2 * 64];
static uint8_t m7_map[128 * 128];
static uint8_t bitmap[256 * 64 / 2];   /* 256 x 64 pixels of 4 bits, in the order of the remapped VRAM port */
static uint16_t pal[256];
static uint8_t oam[128 * 4 + 32];
static uint16_t back[33], back2[32];
static uint8_t cg_back[8];
static uint8_t oam_back[4 * 32];
static uint8_t label_sprites;          /* how many sprites the label and the footer take */

/* The state of the game: the screen, the ticks on it, and what game_vblank writes from it. */
static uint8_t screen, changed;
static uint16_t ticks, last_pad;
static uint16_t opt[64];               /* BG3's first two map rows: the offsets per tile */
static uint16_t scroll[4];             /* screen 0: BG1 and BG2 sideways, BG3 down; screen 1: BG1 */
static uint8_t mosaic;                 /* MOSAIC of screens 5 and 7 */
static int16_t m7[4];                  /* mode 7's matrix */
static uint8_t objects_x;              /* screen 6: the row of sprites passing */

/* --- Tiles -------------------------------------------------------------------------------- */

/* Pixel (x, y) of tile t in a tile buffer of bpp bits a pixel gets colour c: two bit planes a
 * row of two bytes, the planes 2-3 16 bytes on, 4-5 32 and 6-7 48. */
static void tile_set(uint8_t *buf, uint8_t bpp, uint8_t t, uint8_t x, uint8_t y, uint8_t c)
{
    uint8_t plane;
    uint8_t bit = (uint8_t)(0x80 >> x);
    uint16_t at = (uint16_t)((uint16_t)t * (uint16_t)(bpp * 8) + y * 2);

    for (plane = 0; plane < bpp; plane++) {
        uint16_t i = (uint16_t)(at + (plane >> 1) * 16 + (plane & 1));
        if ((c >> plane) & 1) {
            buf[i] |= bit;
        } else {
            buf[i] &= (uint8_t)~bit;
        }
    }
}

/* Mode 0 and 4's 2-bit tiles: 1 solid, 2 a checker of blocks, 3 a frame, 4 diagonal stripes. */
static void tiles2(void)
{
    uint8_t t, y, x;

    memset(tiles, 0, sizeof tiles);
    for (t = 1; t < 5; t++) {
        for (y = 0; y < 8; y++) {
            for (x = 0; x < 8; x++) {
                uint8_t c;
                if (t == 1) {
                    c = 1;
                } else if (t == 2) {
                    c = (uint8_t)((((x >> 1) ^ (y >> 1)) & 1) ? 2 : 1);
                } else if (t == 3) {
                    c = (uint8_t)(x == 0 || y == 0 || x == 7 || y == 7 ? 3 : x > 2 && x < 5 && y > 2 && y < 5 ? 0 : 1);
                } else {
                    c = (uint8_t)(((x + y) >> 1) % 3 + 1);
                }
                tile_set(tiles, 2, t, x, y, c);
            }
        }
    }
}

/* 4-bit tiles: 1 stripes of colours 1 to 8 with a gap (rows 6 and 7), 2 a checker of 9 and 10,
 * 3 squares around a hole in colours 1 to 7, 4 a frame in colours 1 to 3. */
static void tiles4(void)
{
    uint8_t t, y, x;

    memset(tiles, 0, sizeof tiles);
    for (t = 1; t < 5; t++) {
        for (y = 0; y < 8; y++) {
            for (x = 0; x < 8; x++) {
                uint8_t c;
                uint8_t dx = (uint8_t)(x < 4 ? 3 - x : x - 4), dy = (uint8_t)(y < 4 ? 3 - y : y - 4);
                uint8_t d = dx > dy ? dx : dy;
                if (t == 1) {
                    c = (uint8_t)(y >= 6 ? 0 : y + 1 + (x >> 2) * 2);
                } else if (t == 2) {
                    c = (uint8_t)((((x >> 2) ^ (y >> 2)) & 1) ? 10 : 9);
                } else if (t == 3) {
                    c = (uint8_t)(d == 0 ? 0 : d + 1 + ((x + y) & 3));
                } else {
                    c = (uint8_t)(x == 0 || y == 0 || x == 7 || y == 7 ? 1 : x == 1 || y == 1 || x == 6 || y == 6 ? 2 : 3);
                }
                tile_set(tiles, 4, t, x, y, c);
            }
        }
    }
}

/* Direct colour's 8-bit tiles: each pixel BBGGGRRR, red and green running across and down. */
static void tiles8_direct(void)
{
    uint8_t t, y, x;

    memset(tiles, 0, sizeof tiles);
    for (t = 0; t < 4; t++) {
        for (y = 0; y < 8; y++) {
            for (x = 0; x < 8; x++) {
                uint8_t r = t == 1 ? (uint8_t)(7 - x) : t == 3 ? y : x;
                uint8_t g = t == 2 ? (uint8_t)(7 - y) : t == 3 ? x : y;
                tile_set(tiles, 8, t, x, y, (uint8_t)(r | (g << 3) | (t << 6)));
            }
        }
    }
}

/* Mode 7: tile 0 a checker of colours 1 and 2, tile 1 a diagonal of 3 and 4 with bit 7 set (in
 * front of the sprites with EXTBG); the map rings of tile 1 on tile 0. */
static void make_mode7(void)
{
    uint16_t y, x;

    for (y = 0; y < 8; y++) {
        for (x = 0; x < 8; x++) {
            m7_tiles[y * 8 + x] = (uint8_t)((((x >> 2) ^ (y >> 2)) & 1) ? 2 : 1);
            m7_tiles[64 + y * 8 + x] = (uint8_t)(0x80 | (((x + y) & 4) ? 4 : 3));
        }
    }
    for (y = 0; y < 128; y++) {
        for (x = 0; x < 128; x++) {
            m7_map[y * 128 + x] = (uint8_t)((((x >> 2) + (y >> 2)) % 3) == 0 ? 1 : 0);
        }
    }
}

/* Screen 8's bitmap: 256 x 64 pixels, stripes in colours 1 to 15 with a frame, laid out row by
 * row as the VRAM port takes it with SDEVKIT_VMAIN_REMAP_4BPP: per row of pixels, for each tile
 * of 8 pixels a word of planes 0 and 1, then a word of planes 2 and 3. */
static void make_bitmap(void)
{
    uint16_t y, tc;
    uint8_t x;

    for (y = 0; y < 64; y++) {
        for (tc = 0; tc < 32; tc++) {
            uint8_t planes[4] = { 0, 0, 0, 0 };
            uint16_t at = (uint16_t)((y * 64 + tc * 2) * 2);
            for (x = 0; x < 8; x++) {
                uint16_t px = (uint16_t)(tc * 8 + x);
                uint8_t c = (uint8_t)((((px + y) >> 3) & 7) + ((px >> 5) & 1) * 7 + 1);
                uint8_t p;
                if (px == 0 || px == 255 || y == 0 || y == 63) {
                    c = 15;
                }
                for (p = 0; p < 4; p++) {
                    if ((c >> p) & 1) {
                        planes[p] |= (uint8_t)(0x80 >> x);
                    }
                }
            }
            bitmap[at] = planes[0];
            bitmap[at + 1] = planes[1];
            bitmap[at + 2] = planes[2];
            bitmap[at + 3] = planes[3];
        }
    }
}

/* --- Text in sprites and BG maps ---------------------------------------------------------- */

static uint8_t glyph(char ch)
{
    uint8_t i = 0;
    while (FONT[i] != 0 && FONT[i] != ch) {
        i++;
    }
    return FONT[i] != 0 ? i : 0;
}

/* Text as sprites from sprite n on, at (x, y), palette 7, priority prio; gives the next sprite. */
static uint8_t text(uint8_t n, uint8_t x, uint8_t y, const char *s, uint8_t prio)
{
    while (*s != 0) {
        uint8_t g = glyph(*s);
        if (g != 0) {
            oam[n * 4] = x;
            oam[n * 4 + 1] = y;
            oam[n * 4 + 2] = g;
            oam[n * 4 + 3] = SDEVKIT_OAM_ATTR(7, prio, 0, 0);
            n++;
        }
        x = (uint8_t)(x + 8);
        s++;
    }
    return n;
}

/* Text into a BG map at column x, row y, with palette p (the font in that layer's tiles). */
static void print(uint16_t *map, uint8_t x, uint8_t y, const char *s, uint8_t p)
{
    while (*s != 0) {
        map[(uint16_t)y * 32 + x] = (uint16_t)(glyph(*s) | SDEVKIT_BG_TIL_PAL(p));
        x++;
        s++;
    }
}

/* --- The screens ------------------------------------------------------------------------------ */

/* Every register a screen sets, back to 0, with the screen off. */
static void reset_registers(void)
{
    uint8_t r;
    static const uint8_t REGS[] = {
        SDEVKIT_PPU_BGMODE, SDEVKIT_PPU_MOSAIC, SDEVKIT_PPU_BG1SC, SDEVKIT_PPU_BG2SC, SDEVKIT_PPU_BG3SC,
        SDEVKIT_PPU_BG4SC, SDEVKIT_PPU_BG12NBA, SDEVKIT_PPU_BG34NBA, SDEVKIT_PPU_M7SEL, SDEVKIT_PPU_TM,
        SDEVKIT_PPU_TS, SDEVKIT_PPU_CGWSEL, SDEVKIT_PPU_CGADSUB, SDEVKIT_PPU_SETINI,
    };
    for (r = 0; r < sizeof REGS; r++) {
        sdevkit_reg_write(REGS[r], 0);
    }
    for (r = 0; r < 8; r++) {
        sdevkit_reg_write_twice((uint8_t)(SDEVKIT_PPU_BG1HOFS + r), 0);
    }
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7A, 0x100);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7B, 0);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7C, 0);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7D, 0x100);
    sdevkit_reg_write(SDEVKIT_PPU_OBSEL, SDEVKIT_OBSEL(VRAM_SPRITES, SDEVKIT_OBJ_SIZE8_L16));
}

/* The label and the footer, in front of everything (priority 3); the other sprites hidden. */
static void label(void)
{
    uint16_t i;

    for (i = 0; i < 128; i++) {
        oam[i * 4] = 0;
        oam[i * 4 + 1] = SDEVKIT_OAM_HIDE_Y;
        oam[i * 4 + 2] = 0;
        oam[i * 4 + 3] = 0;
    }
    memset(&oam[512], 0, 32);
    label_sprites = text(0, 8, 6, TITLE[screen], 3);
    label_sprites = text(label_sprites, 136, 214, "A NEXT B BACK", 3);
}

/* The colours: the sprites' palette 7, white letters with a dark shadow. */
static void sprite_colours(void)
{
    pal[240 + 1] = SDEVKIT_RGB5(31, 31, 31);
    pal[240 + 2] = SDEVKIT_RGB5(2, 2, 6);
}

/* Screen 0, mode 0: BG1 frames in bands, BG2 checkers, BG3 stripes, BG4 the background; each
 * layer's 8 palettes of 4 colours at (n - 1) * 32: BG1 reds, BG2 greens, BG3 blues, BG4 greys. */
static void setup_mode0(void)
{
    uint16_t i;
    uint8_t bg, p, c;

    tiles2();
    sdevkit_vram_copy(0x0000, tiles, 5 * 16);
    for (i = 0; i < 1024; i++) {
        uint8_t row = (uint8_t)(i >> 5), col = (uint8_t)(i & 31);
        map_a[i] = (uint16_t)(row >= 5 && row < 12 ? 3 | SDEVKIT_BG_TIL_PAL(col & 7) | (col >= 12 && col < 20 ? SDEVKIT_BG_TIL_PRIO : 0) : 0);
        map_b[i] = (uint16_t)(row >= 10 && row < 17 && (col & 4) ? 2 | SDEVKIT_BG_TIL_PAL(row & 7) : 0);
        map_c[i] = (uint16_t)(row >= 14 && row < 22 ? 4 | SDEVKIT_BG_TIL_PAL((row + col) & 7) | SDEVKIT_BG_TIL_PRIO : 0);
        map_d[i] = (uint16_t)(2 | SDEVKIT_BG_TIL_PAL((row >> 2) & 7));
    }
    sdevkit_vram_copy(0x1000, map_a, sizeof map_a);
    sdevkit_vram_copy(0x1400, map_b, sizeof map_b);
    sdevkit_vram_copy(0x1800, map_c, sizeof map_c);
    sdevkit_vram_copy(0x1C00, map_d, sizeof map_d);
    for (bg = 0; bg < 4; bg++) {
        for (p = 0; p < 8; p++) {
            for (c = 1; c < 4; c++) {
                uint8_t v = (uint8_t)(6 + p * 2 + c * 3);
                uint8_t lo = (uint8_t)(v >> 2);
                uint16_t col = bg == 0 ? SDEVKIT_RGB5(v, lo, lo) : bg == 1 ? SDEVKIT_RGB5(lo, v, lo)
                             : bg == 2 ? SDEVKIT_RGB5(lo, lo, v) : SDEVKIT_RGB5(v >> 1, v >> 1, v >> 1);
                pal[bg * 32 + p * 4 + c] = col;
            }
        }
    }
    pal[0] = SDEVKIT_RGB5(0, 0, 4);
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE0);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(0x1000, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG2SC, SDEVKIT_BGSC(0x1400, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG3SC, SDEVKIT_BGSC(0x1800, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG4SC, SDEVKIT_BGSC(0x1C00, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_BG3_ENABLE | SDEVKIT_BG4_ENABLE
                      | SDEVKIT_OBJ_ENABLE);
}

/* Screen 1, mode 2: BG1 stripes moved down by a wave, column by column; BG2 a checker behind,
 * shifted sideways by columns. BG3's map at 0x1800 holds the offsets (opt[], each frame). */
static void setup_mode2(void)
{
    uint16_t i;
    uint8_t c;

    tiles4();
    sdevkit_vram_copy(0x0000, tiles, 5 * 32);
    for (i = 0; i < 1024; i++) {
        map_a[i] = 1;
        map_b[i] = 2;
        map_c[i] = 0;
    }
    sdevkit_vram_copy(0x1000, map_a, sizeof map_a);
    sdevkit_vram_copy(0x1400, map_b, sizeof map_b);
    sdevkit_vram_copy(0x1800, map_c, sizeof map_c);
    for (c = 0; c < 8; c++) {
        pal[1 + c] = SDEVKIT_RGB5(c < 4 ? 31 - c * 6 : 4, c * 4, c < 4 ? 4 : (c - 4) * 7 + 6);
    }
    pal[9] = SDEVKIT_RGB5(10, 10, 12);
    pal[10] = SDEVKIT_RGB5(18, 18, 22);
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE2);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(0x1000, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG2SC, SDEVKIT_BGSC(0x1400, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG3SC, SDEVKIT_BGSC(0x1800, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_OBJ_ENABLE);
}

/* The picture of 256 colours as BG1 at 0x0000, its map at 0x2000: 16 x 12 tiles from column 8,
 * row 7, the empty tile around. Its colours are CGRAM 1 to 239. */
static void picture(void)
{
    uint16_t i;

    sdevkit_vram_copy(0x0000, picture_gfx_tiles, PICTURE_GFX_TILES_SIZE);
    sdevkit_vram_fill((uint16_t)(EMPTY8 * 32), 0, 64);
    for (i = 0; i < 1024; i++) {
        uint8_t row = (uint8_t)(i >> 5), col = (uint8_t)(i & 31);
        map_a[i] = (uint16_t)(row >= 7 && row < 19 && col >= 8 && col < 24 ? (row - 7) * 16 + (col - 8) : EMPTY8);
    }
    sdevkit_vram_copy(0x2000, map_a, sizeof map_a);
    memcpy(pal, picture_gfx_pal, 240 * 2);
    pal[0] = SDEVKIT_RGB5(2, 2, 4);
}

/* Screen 2, mode 3: the picture, and BG2 (16 colours, tiles at 0x4000) a frame around it in the
 * picture's colours 1 to 3. */
static void setup_mode3(void)
{
    uint16_t i;

    picture();
    tiles4();
    sdevkit_vram_copy(0x4000, tiles, 5 * 32);
    for (i = 0; i < 1024; i++) {
        uint8_t row = (uint8_t)(i >> 5), col = (uint8_t)(i & 31);
        uint8_t edge = (uint8_t)((row == 6 || row == 19) && col >= 7 && col < 25);
        edge = (uint8_t)(edge || ((col == 7 || col == 24) && row >= 6 && row < 20));
        map_b[i] = (uint16_t)(edge ? 4 : 0);
    }
    sdevkit_vram_copy(0x2400, map_b, sizeof map_b);
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE3);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(0x2000, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG2SC, SDEVKIT_BGSC(0x2400, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG12NBA, SDEVKIT_BGNBA(0x0000, 0x4000));
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_OBJ_ENABLE);
}

/* Screen 3, mode 3 with direct colour: four 8-bit tiles whose pixels are colours; the map entries'
 * palette bits add the low bit of each channel. */
static void setup_direct(void)
{
    uint16_t i;

    tiles8_direct();
    sdevkit_vram_copy(0x0000, tiles, 4 * 64);
    for (i = 0; i < 1024; i++) {
        uint8_t row = (uint8_t)(i >> 5), col = (uint8_t)(i & 31);
        map_a[i] = (uint16_t)((((col >> 2) + (row >> 2)) & 3) | SDEVKIT_BG_TIL_PAL((col + row) & 7));
    }
    sdevkit_vram_copy(0x2000, map_a, sizeof map_a);
    pal[0] = SDEVKIT_RGB5(0, 0, 0);
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE3);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(0x2000, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_CGWSEL, SDEVKIT_CM_DIRECT_COLOR);
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_OBJ_ENABLE);
}

/* Screen 4, mode 4: the picture, BG2 (4 colours, tiles at 0x4000) diagonal stripes behind it, and
 * BG3's map at 0x2800 one row of offsets per tile for BG1 (opt[], each frame). */
static void setup_mode4(void)
{
    uint16_t i;

    picture();
    tiles2();
    sdevkit_vram_copy(0x4000, tiles, 5 * 16);
    for (i = 0; i < 1024; i++) {
        map_b[i] = 4;
        map_c[i] = 0;
    }
    sdevkit_vram_copy(0x2400, map_b, sizeof map_b);
    sdevkit_vram_copy(0x2800, map_c, sizeof map_c);
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE4);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(0x2000, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG2SC, SDEVKIT_BGSC(0x2400, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG3SC, SDEVKIT_BGSC(0x2800, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG12NBA, SDEVKIT_BGNBA(0x0000, 0x4000));
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_OBJ_ENABLE);
}

/* Screen 5, mode 1 with mosaic on BG1 (squares) and BG3 (stripes on the left, in front); BG2 a
 * checker along the bottom without it. */
static void setup_mosaic(void)
{
    uint16_t i;
    uint8_t c;

    tiles4();
    sdevkit_vram_copy(0x0000, tiles, 5 * 32);
    tiles2();
    sdevkit_vram_copy(0x3000, tiles, 5 * 16);
    for (i = 0; i < 1024; i++) {
        uint8_t row = (uint8_t)(i >> 5), col = (uint8_t)(i & 31);
        map_a[i] = 3;
        map_b[i] = (uint16_t)(row >= 22 ? 2 | SDEVKIT_BG_TIL_PRIO : 0);
        map_c[i] = (uint16_t)(col < 12 && row >= 4 ? 4 | SDEVKIT_BG_TIL_PAL(1) | SDEVKIT_BG_TIL_PRIO : 0);
    }
    sdevkit_vram_copy(0x1000, map_a, sizeof map_a);
    sdevkit_vram_copy(0x1400, map_b, sizeof map_b);
    sdevkit_vram_copy(0x1800, map_c, sizeof map_c);
    for (c = 0; c < 11; c++) {
        pal[1 + c] = SDEVKIT_RGB5(31 - c * 2, c * 3, 8 + c * 2);
    }
    pal[4 + 1] = SDEVKIT_RGB5(31, 31, 8);
    pal[4 + 2] = SDEVKIT_RGB5(8, 31, 31);
    pal[4 + 3] = SDEVKIT_RGB5(31, 8, 31);
    pal[0] = SDEVKIT_RGB5(0, 4, 2);
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1 | SDEVKIT_BG3_MODE1_PRIORITY_HIGH);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(0x1000, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG2SC, SDEVKIT_BGSC(0x1400, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG3SC, SDEVKIT_BGSC(0x1800, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG34NBA, SDEVKIT_BGNBA(0x3000, 0x0000));
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_BG3_ENABLE | SDEVKIT_OBJ_ENABLE);
}

/* Screens 6 and 7, mode 7: the rings, turning. With EXTBG (screen 6) BG2 shows the ring tiles,
 * whose pixels have bit 7, in front of a row of sprites of priority 1, in colours 3 and 4, and
 * BG1 the rest behind them; without (screen 7) BG1 shows the rings in colours 131 and 132. */
static void setup_mode7(uint8_t extbg)
{
    sdevkit_mode7_tiles_copy(0, m7_tiles, sizeof m7_tiles);
    sdevkit_mode7_map_copy(0, m7_map, sizeof m7_map);
    pal[0] = SDEVKIT_RGB5(1, 1, 3);
    pal[1] = SDEVKIT_RGB5(4, 8, 18);
    pal[2] = SDEVKIT_RGB5(8, 14, 26);
    pal[3] = SDEVKIT_RGB5(31, 26, 4);
    pal[4] = SDEVKIT_RGB5(28, 14, 2);
    pal[128 + 3] = extbg ? SDEVKIT_RGB5(31, 0, 0) : SDEVKIT_RGB5(6, 26, 8);   /* red: EXTBG not drawn */
    pal[128 + 4] = extbg ? SDEVKIT_RGB5(20, 0, 0) : SDEVKIT_RGB5(2, 16, 4);
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE7);
    sdevkit_reg_write(SDEVKIT_PPU_M7SEL, SDEVKIT_M7_WRAP);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7X, CENTER);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7Y, CENTER);
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, CENTER - 128);
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, CENTER - 112);
    if (extbg) {
        sdevkit_reg_write(SDEVKIT_PPU_SETINI, SDEVKIT_SETINI_EXTBG);
        sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_OBJ_ENABLE);
        text(label_sprites, 0, 108, "OBJECTS", 1);
    } else {
        sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_OBJ_ENABLE);
    }
}

/* Screen 8: BG1 a bitmap at 0x0000 written through the remapped port, shown at rows 4 to 11; BG2
 * the font at 0x2000 and a text row 17 at 0x1400, which is read back twice and written to rows 19
 * and 21 (the second as it comes); palette 1 of BG2 read back from CGRAM and written as palette 2 with its colours 1 and 2
 * swapped (row 23); the label's sprites read back from OAM and written lower, at y 198. */
static void setup_vram(void)
{
    uint16_t i;
    uint8_t n;
    uint16_t c;

    /* the bitmap: one run of addresses through the remapped port */
    sdevkit_reg_write(SDEVKIT_PPU_VMAIN, 0x80 | SDEVKIT_VMAIN_REMAP_4BPP);
    sdevkit_reg_write(SDEVKIT_PPU_VMADDL, 0);
    sdevkit_reg_write(SDEVKIT_PPU_VMADDH, 0);
    sdevkit_dma(0x01, SDEVKIT_PPU_VMDATAL, bitmap, sizeof bitmap);
    sdevkit_vram_copy(0x2000, font_gfx_tiles, FONT_GFX_TILES_SIZE);
    for (i = 0; i < 1024; i++) {
        uint8_t row = (uint8_t)(i >> 5);
        map_a[i] = (uint16_t)(row >= 4 && row < 12 ? (row - 4) * 32 + (i & 31) : 0x3FF);
        map_b[i] = 0;
    }
    print(map_b, 2, 15, "READ BACK BY DMA:", 1);
    print(map_b, 2, 17, "SNES PICTURE MODES", 1);
    print(map_b, 2, 23, "CGRAM READ BACK", 2);
    sdevkit_vram_fill(0x3FF * 16, 0, 32);   /* BG1's empty tile */
    sdevkit_vram_copy(0x1000, map_a, sizeof map_a);
    sdevkit_vram_copy(0x1400, map_b, sizeof map_b);
    for (c = 1; c < 16; c++) {
        pal[c] = SDEVKIT_RGB5(c * 2, 31 - c * 2, 10 + c);
    }
    pal[16 + 1] = SDEVKIT_RGB5(31, 31, 31);
    pal[16 + 2] = SDEVKIT_RGB5(4, 4, 12);
    pal[0] = SDEVKIT_RGB5(3, 3, 6);
    sprite_colours();
    sdevkit_cgram_copy(0, pal, sizeof pal);

    /* VRAM read back: the text row, 33 words, of which the first is the word the port fetched
     * when the address was set and the second the same again, so the row is back[1..32]; and the
     * row's 32 words written as they come, one tile to the right */
    sdevkit_reg_write(SDEVKIT_PPU_VMAIN, 0x80);
    sdevkit_reg_write(SDEVKIT_PPU_VMADDL, (uint8_t)(0x1400 + 17 * 32));
    sdevkit_reg_write(SDEVKIT_PPU_VMADDH, (uint8_t)((0x1400 + 17 * 32) >> 8));
    sdevkit_dma(SDEVKIT_DMA_FROM_PPU | 0x01, SDEVKIT_PPU_VMDATALREAD, back, sizeof back);
    sdevkit_vram_copy(0x1400 + 19 * 32, &back[1], 64);
    sdevkit_reg_write(SDEVKIT_PPU_VMAIN, 0x80);
    sdevkit_reg_write(SDEVKIT_PPU_VMADDL, (uint8_t)(0x1400 + 17 * 32));
    sdevkit_reg_write(SDEVKIT_PPU_VMADDH, (uint8_t)((0x1400 + 17 * 32) >> 8));
    sdevkit_dma(SDEVKIT_DMA_FROM_PPU | 0x01, SDEVKIT_PPU_VMDATALREAD, back2, sizeof back2);
    sdevkit_vram_copy(0x1400 + 21 * 32, back2, sizeof back2);

    /* CGRAM read back: BG2's palette 1, colours 0 to 3, as palette 2 with 1 and 2 swapped */
    sdevkit_reg_write(SDEVKIT_PPU_CGADD, 16);
    sdevkit_dma(SDEVKIT_DMA_FROM_PPU, SDEVKIT_PPU_CGDATAREAD, cg_back, sizeof cg_back);
    for (n = 1; n < 8; n = (uint8_t)(n + 2)) {
        cg_back[n] &= 0x7F;              /* bit 7 of a colour's high byte is not defined */
    }
    sdevkit_cgram_set(32 + 1, (uint16_t)(cg_back[4] | (cg_back[5] << 8)));
    sdevkit_cgram_set(32 + 2, (uint16_t)(cg_back[2] | (cg_back[3] << 8)));

    /* OAM read back: the label's sprites, written again as sprites 64 on, 184 lines lower */
    sdevkit_oam_copy(0, oam, sizeof oam);
    sdevkit_reg_write(SDEVKIT_PPU_OAMADDL, 0);
    sdevkit_reg_write(SDEVKIT_PPU_OAMADDH, 0);
    n = (uint8_t)(label_sprites < 32 ? label_sprites : 32);
    sdevkit_dma(SDEVKIT_DMA_FROM_PPU, SDEVKIT_PPU_OAMDATAREAD, oam_back, (uint16_t)(n * 4));
    for (i = 0; i < n; i++) {
        if (oam_back[i * 4 + 1] == 6) {
            oam_back[i * 4 + 1] = 198;
        }
    }
    sdevkit_oam_copy(64 * 4, oam_back, (uint16_t)(n * 4));

    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(0x1000, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG2SC, SDEVKIT_BGSC(0x1400, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG12NBA, SDEVKIT_BGNBA(0x0000, 0x2000));
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_OBJ_ENABLE);
}

/* The screen set up from scratch, with the screen off; game_vblank switches it on after its
 * writes of the frame. */
static void setup(void)
{
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, SDEVKIT_FORCED_BLANK);
    reset_registers();
    memset(pal, 0, sizeof pal);
    label();
    switch (screen) {
    case 0:  setup_mode0(); break;
    case 1:  setup_mode2(); break;
    case 2:  setup_mode3(); break;
    case 3:  setup_direct(); break;
    case 4:  setup_mode4(); break;
    case 5:  setup_mosaic(); break;
    case 6:  setup_mode7(1); break;
    case 7:  setup_mode7(0); break;
    default: setup_vram(); break;
    }
    if (screen != 8) {                 /* screen 8 wrote its colours and sprites itself */
        sprite_colours();
        sdevkit_cgram_copy(0, pal, sizeof pal);
        sdevkit_oam_copy(0, oam, sizeof oam);
    }
}

/* --- The frame rhythm ------------------------------------------------------------------------- */

void game_init(uint16_t seed)
{
    (void)seed;
    make_mode7();
    make_bitmap();
    sdevkit_vram_copy(VRAM_SPRITES, font_gfx_tiles, FONT_GFX_TILES_SIZE);
    screen = 0;
    changed = 1;
}

/* What moves, for the screen on: scrolls, the offsets per tile, the mosaic, mode 7's matrix. */
void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t pressed = (uint16_t)(pad[0] & ~last_pad);
    uint8_t k;

    last_pad = pad[0];
    if (pressed & SDEVKIT_KEY_A) {
        screen = (uint8_t)(screen == SCREENS - 1 ? 0 : screen + 1);
        changed = 1;
    }
    if (pressed & SDEVKIT_KEY_B) {
        screen = (uint8_t)(screen == 0 ? SCREENS - 1 : screen - 1);
        changed = 1;
    }
    ticks = changed ? 0 : (uint16_t)(ticks + 1);
    if (screen == 0) {
        scroll[0] = ticks;
        scroll[1] = (uint16_t)-ticks;
        scroll[2] = (uint16_t)(ticks >> 1);
    } else if (screen == 1) {
        for (k = 0; k < 32; k++) {
            uint8_t a = (uint8_t)(k * 8 + ticks * 4);
            opt[k] = (uint16_t)(SDEVKIT_OPT_BG2 | ((k * 12 + ticks) & 0x3F8));
            opt[32 + k] = (uint16_t)(SDEVKIT_OPT_BG1 | ((uint16_t)(SIN[a] >> 4) & 0x3FF));
        }
        scroll[0] = (uint16_t)(ticks >> 1);
    } else if (screen == 4) {
        for (k = 0; k < 32; k++) {
            uint8_t a = (uint8_t)(k * 16 + ticks * 3);
            if (k & 1) {
                opt[k] = (uint16_t)(SDEVKIT_OPT_BG1 | SDEVKIT_OPT_VERTICAL | ((uint16_t)(SIN[a] >> 3) & 0x3FF));
            } else {
                opt[k] = (uint16_t)(SDEVKIT_OPT_BG1 | ((uint16_t)((SIN[a] >> 2) & 0x3F8)));
            }
        }
    } else if (screen == 5) {
        mosaic = SDEVKIT_MOSAIC(1 + ((ticks >> 3) & 15), SDEVKIT_BG1_ENABLE | SDEVKIT_BG3_ENABLE);
    } else if (screen == 6 || screen == 7) {
        uint8_t angle = (uint8_t)ticks;
        int16_t s = SIN[angle], c = SIN[(uint8_t)(angle + 64)];
        m7[0] = c;
        m7[1] = s;
        m7[2] = (int16_t)-s;
        m7[3] = c;
        objects_x = (uint8_t)(ticks * 2);
        mosaic = SDEVKIT_MOSAIC(1 + ((ticks >> 4) & 7), SDEVKIT_BG1_ENABLE);
    }
    /* for the checks: the screen and what the next picture is made of */
    sdevkit_dbg_hash(&screen, 1);
    sdevkit_dbg_hash(&ticks, 2);
    sdevkit_dbg_hash(opt, sizeof opt);
    sdevkit_dbg_hash(scroll, sizeof scroll);
    sdevkit_dbg_hash(m7, sizeof m7);
    sdevkit_dbg_hash(&mosaic, 1);
}

void game_vblank(void)
{
    uint8_t k;

    uint8_t was_changed = changed;

    if (changed) {
        changed = 0;
        setup();
    }
    switch (screen) {
    case 0:
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, scroll[0]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG2HOFS, scroll[1]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG3VOFS, scroll[2]);
        break;
    case 1:
        sdevkit_vram_copy(0x1800, opt, sizeof opt);
        sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, scroll[0]);
        break;
    case 4:
        sdevkit_vram_copy(0x2800, opt, 64);
        break;
    case 5:
        sdevkit_reg_write(SDEVKIT_PPU_MOSAIC, mosaic);
        break;
    case 6:
    case 7:
        sdevkit_reg_write_twice(SDEVKIT_PPU_M7A, (uint16_t)m7[0]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_M7B, (uint16_t)m7[1]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_M7C, (uint16_t)m7[2]);
        sdevkit_reg_write_twice(SDEVKIT_PPU_M7D, (uint16_t)m7[3]);
        if (screen == 6) {
            for (k = 0; k < 7; k++) {
                oam[(label_sprites + k) * 4] = (uint8_t)(objects_x + k * 8);
            }
            sdevkit_oam_copy((uint16_t)(label_sprites * 4), &oam[label_sprites * 4], 7 * 4);
        } else {
            sdevkit_reg_write(SDEVKIT_PPU_MOSAIC, mosaic);
        }
        break;
    default:
        break;
    }
    if (was_changed) {
        sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);   /* the screen on, after the copies of the frame */
    }
}
