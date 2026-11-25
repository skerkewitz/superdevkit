/*
 * mode7: the smallest example of mode 7 in superdevkit. A checkerboard of 1024 x 1024 pixels with
 * a red border and a mark that is not symmetric, so that turning and mirroring show, turns and
 * zooms around the middle of the screen, with a sprite in front of it. A changes what lies outside
 * the map (it repeats, is transparent, or is tile 0), B mirrors the screen. It is there to show
 * that the SNES and the desktop draw mode 7 the same, pixel for pixel (dev/compare_example.sh).
 *
 * Everything is computed here, no image files: four tiles of 8 x 8 pixels with a byte a pixel, the
 * map of 128 x 128 tiles, and the sprite. It uses the console API's core: the tick computes the
 * matrix, game_vblank writes it into mode 7's registers (on the SNES a write during the picture
 * takes effect from that line on), and the sprite is written once into OAM.
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
static int16_t m7[4];                  /* the matrix, a b c d, for game_vblank */

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

/* One sprite's table: the diamond in front of the layer (priority 2), large (16 x 16); the SNES
 * draws it one line lower than its y, at row 104. The others stay hidden (the kit's reset). */
static const uint8_t SPRITE_ENTRY[4] = { 120, 103, 0, SDEVKIT_OAM_ATTR(0, 2, 0, 0) };
static const uint8_t SPRITE_HIGH[1] = { 0x02 };

void game_init(uint16_t seed)
{
    (void)seed;
    make_tiles();
    make_map();
    make_sprite();
    /* the screen is off: everything at once */
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE7);
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_OBJ_ENABLE);
    sdevkit_mode7_tiles_copy(0, tiles, sizeof tiles);
    sdevkit_mode7_map_copy(0, map, sizeof map);
    sdevkit_cgram_set(0, SDEVKIT_RGB5(2, 3, 8));               /* the backdrop */
    sdevkit_cgram_set(1, SDEVKIT_RGB5(6, 9, 14));
    sdevkit_cgram_set(2, SDEVKIT_RGB5(20, 24, 28));
    sdevkit_cgram_set(3, SDEVKIT_RGB5(31, 4, 4));
    sdevkit_cgram_set(4, SDEVKIT_RGB5(18, 2, 2));
    sdevkit_cgram_set(5, SDEVKIT_RGB5(31, 28, 4));
    sdevkit_cgram_set(6, SDEVKIT_RGB5(10, 12, 4));
    sdevkit_cgram_set(128 + 1, SDEVKIT_RGB5(31, 31, 31));      /* sprite palette 0 */
    sdevkit_cgram_set(128 + 2, SDEVKIT_RGB5(4, 20, 31));
    sdevkit_reg_write(SDEVKIT_PPU_OBSEL, SDEVKIT_OBSEL(VRAM_SPRITES, SDEVKIT_OBJ_SIZE8_L16));
    sdevkit_vram_copy(VRAM_SPRITES, sprite, 32);               /* tiles 0 and 1 */
    sdevkit_vram_copy(VRAM_SPRITES + 16 * 16, sprite + 64, 32);   /* tiles 16 and 17, the lower half */
    sdevkit_vram_copy(VRAM_SPRITES + 16, sprite + 32, 32);
    sdevkit_vram_copy(VRAM_SPRITES + 17 * 16, sprite + 96, 32);
    sdevkit_oam_copy(0, SPRITE_ENTRY, sizeof SPRITE_ENTRY);
    sdevkit_oam_copy(512, SPRITE_HIGH, sizeof SPRITE_HIGH);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7X, CENTER);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7Y, CENTER);
    sdevkit_reg_write(SDEVKIT_PPU_M7SEL, SDEVKIT_M7_WRAP);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7A, 0x100);           /* the identity */
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7D, 0x100);
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, CENTER - 128);
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, CENTER - 112);
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);                /* the screen on, at full brightness */
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
    m7[0] = (int16_t)(((int32_t)c * zoom) >> 8);
    m7[1] = (int16_t)(((int32_t)s * zoom) >> 8);
    m7[2] = (int16_t)-m7[1];
    m7[3] = m7[0];
    sdevkit_dbg_hash(m7, sizeof m7);           /* for the checks: the matrix of the next picture */
}

void game_vblank(void)
{
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7A, (uint16_t)m7[0]);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7B, (uint16_t)m7[1]);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7C, (uint16_t)m7[2]);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7D, (uint16_t)m7[3]);
    sdevkit_reg_write(SDEVKIT_PPU_M7SEL, (uint8_t)(OUTSIDE[outside] | (flip ? SDEVKIT_M7_FLIPX : 0)));
}
