/*
 * mode7: the smallest example of mode 7 in superdevkit. A checkerboard of 1024 x 1024 pixels with
 * a red border and a mark that is not symmetric, so that turning and mirroring show, turns and
 * zooms around the middle of the screen, with a sprite in front of it. A changes what lies outside
 * the map (it repeats, is transparent, or is tile 0), B mirrors the screen. It is there to show
 * that the SNES and the desktop draw mode 7 the same, pixel for pixel (dev/compare_example.sh).
 *
 * Everything is computed here, no image files: four tiles of 8 x 8 pixels with a byte a pixel, the
 * map of 128 x 128 tiles, and the sprite. Mode 7's registers are written in game_vblank: on the
 * SNES a write during the picture takes effect from that line on.
 */
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

#define VRAM_SPRITES 0x4000            /* mode 7 fills the first 0x4000 words */
#define CENTER       512               /* the middle of the map */

/* sin(2 pi i / 256) in 8.8 fixed point */
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

static uint8_t tiles[4 * 64];
static uint8_t map[128 * 128];
static uint8_t sprite[4 * 32];         /* one 16 x 16 sprite: four 8 x 8 tiles of 4 bits a pixel */

static uint8_t angle, zoom_phase, outside, flip;
static uint16_t last_pad;
static int16_t ma, mb, mc, md;

static const uint8_t OUTSIDE[3] = { SDEVKIT_M7_WRAP, SDEVKIT_M7_TRANSPARENT, SDEVKIT_M7_TILE0 };

/* Tile 0 dark, 1 light, 2 a red frame, 3 a yellow diagonal: colours 1 to 6. */
static void make_tiles(void)
{
    uint16_t t, y, x;

    for (t = 0; t < 4; t++) {
        for (y = 0; y < 8; y++) {
            for (x = 0; x < 8; x++) {
                uint8_t c;
                if (t == 0) {
                    c = 1;
                } else if (t == 1) {
                    c = 2;
                } else if (t == 2) {
                    c = (uint8_t)(x == 0 || y == 0 || x == 7 || y == 7 ? 3 : 4);
                } else {
                    c = (uint8_t)(x == y || x + 1 == y ? 5 : 6);
                }
                tiles[t * 64 + y * 8 + x] = c;
            }
        }
    }
}

/* Squares of 8 x 8 tiles, a frame of tile 2 around the map, and an "L" of tile 3 near the middle. */
static void make_map(void)
{
    uint16_t ty, tx;

    for (ty = 0; ty < 128; ty++) {
        for (tx = 0; tx < 128; tx++) {
            uint8_t t = (uint8_t)(((tx >> 3) ^ (ty >> 3)) & 1);
            if (tx < 2 || ty < 2 || tx >= 126 || ty >= 126) {
                t = 2;
            }
            if ((tx >= 60 && tx < 62 && ty >= 52 && ty < 66) || (ty >= 64 && ty < 66 && tx >= 60 && tx < 70)) {
                t = 3;
            }
            map[ty * 128 + tx] = t;
        }
    }
}

/* A diamond of colour 1 with a border of colour 2, in the SNES's planar 4-bit format. */
static void make_sprite(void)
{
    uint16_t y, x;

    for (y = 0; y < 16; y++) {
        for (x = 0; x < 16; x++) {
            int16_t dx = (int16_t)(x < 8 ? 7 - x : x - 8), dy = (int16_t)(y < 8 ? 7 - y : y - 8);
            uint8_t c = (uint8_t)(dx + dy < 7 ? 1 : dx + dy < 8 ? 2 : 0);
            uint16_t tile = (uint16_t)((y >> 3) * 2 + (x >> 3));
            uint8_t bit = (uint8_t)(0x80 >> (x & 7));
            uint16_t row = (uint16_t)(tile * 32 + (y & 7) * 2);
            if (c & 1) {
                sprite[row] |= bit;
            }
            if (c & 2) {
                sprite[row + 1] |= bit;
            }
        }
    }
}

void game_init(uint16_t seed)
{
    (void)seed;
    make_tiles();
    make_map();
    make_sprite();
    sdevkit_set_mode(SDEVKIT_BG_MODE7, 0);
    sdevkit_mode7_load_tiles(tiles, 0, sizeof tiles);
    sdevkit_mode7_load_map(map, 0, sizeof map);
    sdevkit_set_palette_color(0, SDEVKIT_RGB5(2, 3, 8));      /* the backdrop */
    sdevkit_set_palette_color(1, SDEVKIT_RGB5(6, 9, 14));
    sdevkit_set_palette_color(2, SDEVKIT_RGB5(20, 24, 28));
    sdevkit_set_palette_color(3, SDEVKIT_RGB5(31, 4, 4));
    sdevkit_set_palette_color(4, SDEVKIT_RGB5(18, 2, 2));
    sdevkit_set_palette_color(5, SDEVKIT_RGB5(31, 28, 4));
    sdevkit_set_palette_color(6, SDEVKIT_RGB5(10, 12, 4));
    sdevkit_set_palette_color(128 + 1, SDEVKIT_RGB5(31, 31, 31));   /* sprite palette 0 */
    sdevkit_set_palette_color(128 + 2, SDEVKIT_RGB5(4, 20, 31));
    sdevkit_oam_init_gfx_attr(VRAM_SPRITES, SDEVKIT_OBJ_SIZE8_L16);
    sdevkit_dma_copy_vram(sprite, VRAM_SPRITES, 32);           /* tiles 0 and 1 */
    sdevkit_dma_copy_vram(sprite + 64, VRAM_SPRITES + 16 * 16, 32);   /* tiles 16 and 17, the lower half */
    sdevkit_dma_copy_vram(sprite + 32, VRAM_SPRITES + 16, 32);
    sdevkit_dma_copy_vram(sprite + 96, VRAM_SPRITES + 17 * 16, 32);
    sdevkit_oam_init();
    sdevkit_oam_set(0, 120, 103, 2, 0, 0, 0, 0);
    sdevkit_oam_set_ex(0, SDEVKIT_OBJ_LARGE, SDEVKIT_OBJ_SHOW);
    sdevkit_mode7_set_center(CENTER, CENTER);
    sdevkit_mode7_set_select(SDEVKIT_M7_WRAP);
    sdevkit_mode7_set_matrix(0x100, 0, 0, 0x100);
    sdevkit_bg_set_scroll(0, CENTER - 128, CENTER - 112);
    sdevkit_set_brightness(15);
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t pressed = (uint16_t)(pad[0] & ~last_pad);
    int16_t s, c, zoom;

    last_pad = pad[0];
    if (pressed & SDEVKIT_KEY_A) {
        outside = (uint8_t)(outside == 2 ? 0 : outside + 1);
    }
    if (pressed & SDEVKIT_KEY_B) {
        flip = (uint8_t)!flip;
    }
    angle++;
    zoom_phase = (uint8_t)(zoom_phase + 2);
    /* zoom between 1 and 6 map pixels per screen pixel: far enough out to see the map's edge */
    zoom = (int16_t)(0x380 + ((SIN[zoom_phase] * 5) >> 1));
    s = SIN[angle];
    c = SIN[(uint8_t)(angle + 64)];
    ma = (int16_t)(((int32_t)c * zoom) >> 8);
    mb = (int16_t)(((int32_t)s * zoom) >> 8);
    mc = (int16_t)-mb;
    md = ma;
}

void game_vblank(void)
{
    sdevkit_mode7_set_matrix(ma, mb, mc, md);
    sdevkit_mode7_set_select((uint8_t)(OUTSIDE[outside] | (flip ? SDEVKIT_M7_FLIPX : 0)));
}
