/*
 * Mandelbrot: the set drawn on the SNES, a tile at a time, in four views.
 *
 * It is the dev kit's example of code that computes: the inner loop is z = z * z + c in fixed
 * point, nothing else, so the cycles it takes are those of the arithmetic (and today mostly those
 * of dampf's 32-bit multiplication). The others (Schach, Steinschlag, Er-Tüp) compute on the side.
 *
 * Numbers are 4.12 fixed point in int16_t (4096 is 1.0, from -8 to 8); the products are int32_t,
 * 8.24. z stays small enough: a point is out once |z| > 2, and then |z * z + c| <= 6.
 *
 * The picture is 128 x 120 pixels in mode 7: one byte a pixel, tiles 0 to 239 (16 x 15), tile 240
 * empty for everything around it; the matrix shows it at twice the size. It is computed tile by
 * tile, in reading order, and a finished tile (64 bytes) is queued for VRAM, to be copied in the
 * next vertical blank. A tick runs at most ITERATIONS iterations, so each fits into a frame however deep the
 * view; a faster compiler shows as fewer cycles per tick (dev/bench_examples.sh), the picture as
 * a whole takes the same number of ticks.
 *
 * Before its iterations a point is tested for the two largest parts of the set: the main
 * cardioid and the circle of period 2 left of it. A point in them is inside, and would otherwise
 * run all its iterations: the test costs four products, the iterations up to three a step.
 *
 * When a view is done, its colours cycle and the picture turns slowly for a few seconds, then the
 * next view is drawn over it. A on the joypad goes to the next view at once, B to the one before.
 */
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

#define W            128                   /* the picture, pixels */
#define H            120
#define TILES        ((W / 8) * (H / 8))   /* 240 */
#define BLANK_TILE   TILES                 /* all colour 0: the backdrop shows */
#define ITERATIONS   7                     /* per tick: each costs three 32-bit products */
#define ESCAPE       ((int32_t)4 << 24)    /* |z|^2 > 4.0, in 8.24 */
#define COLOURS      48                    /* the iteration colours, CGRAM 1 to 48 */
#define SHOW_TICKS   480                   /* a finished view turns this long */
#define VIEWS        4

/* A view: the left and top edge of the picture and the step from one pixel to the next, 4.12;
 * and how many iterations a point may take before it counts as inside. */
typedef struct {
    int16_t left, top, step;
    uint8_t max_iter;
} View;

static const View VIEWS_[VIEWS] = {
    { -9216, -5760, 96, 32 },      /* the whole set: centre (-0.75, 0), 3.0 wide */
    { -3840, -228, 12, 48 },       /* seahorse valley: (-0.75, 0.12), 0.375 wide */
    { 717, -398, 8, 48 },          /* elephant valley: (0.3, 0.02), 0.25 wide */
    { -7316, -120, 2, 80 },        /* the small copy of the set on the needle: (-1.755, 0), 0.0625 wide */
};

static const int8_t SIN[256] = {
    0, 3, 6, 9, 12, 16, 19, 22, 25, 28, 31, 34, 37, 40, 43, 46,
    49, 51, 54, 57, 60, 63, 65, 68, 71, 73, 76, 78, 81, 83, 85, 88,
    90, 92, 94, 96, 98, 100, 102, 104, 106, 107, 109, 111, 112, 113, 115, 116,
    117, 118, 120, 121, 122, 122, 123, 124, 125, 125, 126, 126, 126, 127, 127, 127,
    127, 127, 127, 127, 126, 126, 126, 125, 125, 124, 123, 122, 122, 121, 120, 118,
    117, 116, 115, 113, 112, 111, 109, 107, 106, 104, 102, 100, 98, 96, 94, 92,
    90, 88, 85, 83, 81, 78, 76, 73, 71, 68, 65, 63, 60, 57, 54, 51,
    49, 46, 43, 40, 37, 34, 31, 28, 25, 22, 19, 16, 12, 9, 6, 3,
    0, -3, -6, -9, -12, -16, -19, -22, -25, -28, -31, -34, -37, -40, -43, -46,
    -49, -51, -54, -57, -60, -63, -65, -68, -71, -73, -76, -78, -81, -83, -85, -88,
    -90, -92, -94, -96, -98, -100, -102, -104, -106, -107, -109, -111, -112, -113, -115, -116,
    -117, -118, -120, -121, -122, -122, -123, -124, -125, -125, -126, -126, -126, -127, -127, -127,
    -127, -127, -127, -127, -126, -126, -126, -125, -125, -124, -123, -122, -122, -121, -120, -118,
    -117, -116, -115, -113, -112, -111, -109, -107, -106, -104, -102, -100, -98, -96, -94, -92,
    -90, -88, -85, -83, -81, -78, -76, -73, -71, -68, -65, -63, -60, -57, -54, -51,
    -49, -46, -43, -40, -37, -34, -31, -28, -25, -22, -19, -16, -12, -9, -6, -3,
};

/* The colours of the iterations: four ramps of twelve, BGR 5:5:5 (night blue, cyan, gold, red). */
static const uint8_t KEYS[5][3] = { { 2, 2, 10 }, { 4, 20, 28 }, { 31, 30, 18 }, { 30, 12, 2 }, { 2, 2, 10 } };

static uint16_t base_colours[COLOURS];
static uint8_t palette[COLOURS * 2];       /* this frame's colours, low byte first */
static uint8_t palette_shift, palette_dirty;

/* --- the computation ------------------------------------------------------------------------- */
static uint8_t view, showing;
static uint16_t show_time;
static uint8_t tile, pixel;                /* the tile being computed, its pixel (0..63) */
static int16_t row_left, cx, cy, zx, zy;   /* c and z of the pixel */
static uint8_t iter;                       /* 0: not tested yet (in_bulbs), then the iteration */
static uint8_t tile_buf[64];               /* the tile being computed */
static uint8_t upload[64];                 /* a finished tile, for the vertical blank */
static uint8_t upload_tile, upload_ready;
static uint8_t angle;

/* The first pixel of the tile: c at its upper left corner, z = c (the first iteration). */
static void tile_start(void)
{
    const View *v = &VIEWS_[view];
    uint8_t tx = (uint8_t)(tile & 15), ty = (uint8_t)(tile >> 4);

    row_left = (int16_t)(v->left + (int16_t)(tx * 8) * v->step);
    cx = row_left;
    cy = (int16_t)(v->top + (int16_t)(ty * 8) * v->step);
    zx = cx;
    zy = cy;
    iter = 0;
    pixel = 0;
}

/* Is c in the main cardioid or the circle of period 2? With x' = x - 1/4 and q = x'^2 + y^2 the
 * cardioid is q (q + x') <= y^2 / 4; the circle is (x + 1)^2 + y^2 <= 1/16. q in 4.12 so that its
 * product with q + x' fits 32 bits: the cardioid lies within -0.75 <= x <= 0.375 and
 * |y| <= 0.66, where q stays below 1.5; outside that box it is not tested. */
static uint8_t in_bulbs(void)
{
    int32_t y2 = (int32_t)cy * cy;
    int16_t xb = (int16_t)(cx + 4096);

    if (cx >= -3072 && cx <= 1536 && cy >= -2704 && cy <= 2704) {
        int16_t xq = (int16_t)(cx - 1024);
        int16_t q = (int16_t)(((int32_t)xq * xq + y2) >> 12);
        if ((int32_t)q * (int16_t)(q + xq) <= (y2 >> 2)) {
            return 1;
        }
    }
    return (uint8_t)((int32_t)xb * xb + y2 <= ((int32_t)1 << 20));
}

static void view_start(uint8_t v)
{
    view = v;
    tile = 0;
    showing = 0;
    show_time = 0;
    angle = 0;
    tile_start();
}

/* The pixel is decided: its colour into the tile, then on to the next pixel (and tile). Returns
 * 1 when the tile is finished. */
static uint8_t pixel_done(uint8_t inside)
{
    const View *v = &VIEWS_[view];
    uint8_t colour = 0;

    if (!inside) {
        colour = iter;
        if (colour > COLOURS) {                     /* at most 80 iterations: once is enough */
            colour = (uint8_t)(colour - COLOURS);
        }
    }
    tile_buf[pixel] = colour;
    pixel++;
    if ((pixel & 7) == 0) {                         /* the next row of the tile */
        cx = row_left;
        cy = (int16_t)(cy + v->step);
    } else {
        cx = (int16_t)(cx + v->step);
    }
    zx = cx;
    zy = cy;
    iter = 0;
    return (uint8_t)(pixel == 64);
}

/* Up to ITERATIONS iterations of z = z * z + c, pixel after pixel; stops early when a tile is
 * finished, so at most one tile a tick goes to VRAM. */
static void compute(void)
{
    uint8_t n;
    uint8_t max_iter = VIEWS_[view].max_iter;

    for (n = 0; n < ITERATIONS; n++) {
        int32_t x2, y2;
        uint8_t finished;
        if (iter == 0) {                            /* a new point: the test counts as a step */
            if (in_bulbs()) {
                finished = pixel_done(1);
                if (finished) {
                    break;
                }
                continue;
            }
            iter = 1;
            continue;
        }
        x2 = (int32_t)zx * zx;
        y2 = (int32_t)zy * zy;
        if (x2 + y2 > ESCAPE) {
            finished = pixel_done(0);
        } else if (iter == max_iter) {
            finished = pixel_done(1);
        } else {
            int32_t xy = (int32_t)zx * zy;
            zx = (int16_t)((int16_t)((x2 - y2) >> 12) + cx);
            zy = (int16_t)((int16_t)(xy >> 11) + cy);
            iter++;
            continue;
        }
        if (finished) {
            break;
        }
    }
    if (pixel == 64) {                              /* the tile is finished: to VRAM, the next */
        uint8_t i;
        for (i = 0; i < 64; i++) {
            upload[i] = tile_buf[i];
        }
        upload_tile = tile;
        upload_ready = 1;
        tile++;
        if (tile == TILES) {
            showing = 1;
        } else {
            tile_start();
        }
    }
}

/* --- the picture ------------------------------------------------------------------------------- */
static void make_palette(void)
{
    uint8_t k, i, n = 0;

    for (k = 0; k < 4; k++) {
        for (i = 0; i < 12; i++) {
            uint8_t r = (uint8_t)((KEYS[k][0] * (12 - i) + KEYS[k + 1][0] * i) / 12);
            uint8_t g = (uint8_t)((KEYS[k][1] * (12 - i) + KEYS[k + 1][1] * i) / 12);
            uint8_t b = (uint8_t)((KEYS[k][2] * (12 - i) + KEYS[k + 1][2] * i) / 12);
            base_colours[n] = SDEVKIT_RGB5(r, g, b);
            n++;
        }
    }
}

/* The colours shifted by palette_shift: they flow outward while a view is shown. */
static void cycle_palette(void)
{
    uint8_t i, j = palette_shift;

    for (i = 0; i < COLOURS; i++) {
        uint16_t c = base_colours[j];
        palette[i * 2] = (uint8_t)c;
        palette[i * 2 + 1] = (uint8_t)(c >> 8);
        j++;
        if (j == COLOURS) {
            j = 0;
        }
    }
    palette_dirty = 1;
}

/* The matrix: the picture at twice the size, turned by angle while it is shown. */
static void set_matrix(void)
{
    int16_t s = SIN[angle], c = SIN[(uint8_t)(angle + 64)];

    sdevkit_ppu.m7a = c;                            /* 127 is 0.5 map pixels a screen pixel */
    sdevkit_ppu.m7b = (int16_t)-s;
    sdevkit_ppu.m7c = s;
    sdevkit_ppu.m7d = c;
    sdevkit_marks |= SDEVKIT_MARK_MODE7;
}

void game_init(uint16_t seed)
{
    static const uint8_t ZERO[64];
    uint8_t row[128];
    uint8_t r, c;

    (void)seed;
    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE7;
    sdevkit_ppu.tm = SDEVKIT_BG1_ENABLE;            /* no sprites */
    for (r = 0; r < 128; r++) {                     /* the map: the picture's tiles, blank around */
        for (c = 0; c < 128; c++) {
            row[c] = (uint8_t)(r < H / 8 && c < W / 8 ? r * 16 + c : BLANK_TILE);
        }
        sdevkit_mode7_map_now((uint16_t)(r * 128), row, sizeof row);
    }
    for (r = 0; r <= TILES; r++) {
        sdevkit_mode7_tiles_now((uint16_t)(r * 64), ZERO, sizeof ZERO);
    }
    make_palette();
    cycle_palette();
    sdevkit_cgram_now(1, palette, sizeof palette);
    palette_dirty = 0;
    sdevkit_color_now(0, SDEVKIT_RGB5(0, 0, 3));
    sdevkit_ppu.m7sel = SDEVKIT_M7_WRAP;
    sdevkit_ppu.m7x = W / 2;
    sdevkit_ppu.m7y = H / 2;
    sdevkit_ppu.hofs[0] = (uint16_t)((W / 2 - 128) & 0x1FFF);
    sdevkit_ppu.vofs[0] = (uint16_t)((H / 2 - 112) & 0x1FFF);
    view_start(0);
    set_matrix();
    sdevkit_ppu.inidisp = 15;
}

void game_tick(void)
{
    uint16_t pressed = sdevkit_input.pressed[0];

    if (pressed & SDEVKIT_KEY_A) {
        view_start((uint8_t)(view + 1 == VIEWS ? 0 : view + 1));
    } else if (pressed & SDEVKIT_KEY_B) {
        view_start((uint8_t)(view == 0 ? VIEWS - 1 : view - 1));
    }
    if (!showing) {
        compute();
    } else {
        show_time++;
        if ((show_time & 3) == 0) {                 /* the colours flow */
            palette_shift = (uint8_t)(palette_shift + 1 == COLOURS ? 0 : palette_shift + 1);
            cycle_palette();
        }
        angle++;                                    /* a slow turn, back to upright at the end */
        if (show_time >= SHOW_TICKS && angle == 0) {
            view_start((uint8_t)(view + 1 == VIEWS ? 0 : view + 1));
        }
    }
    set_matrix();
    if (upload_ready && sdevkit_queue_mode7_tiles((uint16_t)(upload_tile * 64), upload, sizeof upload)) {
        upload_ready = 0;
    }
    if (palette_dirty && sdevkit_queue_cgram(1, palette, sizeof palette)) {
        palette_dirty = 0;
    }
    /* the state for the checks: the computation and the tile it works on */
    sdevkit_dbg_hash(tile_buf, sizeof tile_buf);
    sdevkit_dbg_hash(&zx, sizeof zx);
    sdevkit_dbg_hash(&zy, sizeof zy);
    sdevkit_dbg_hash(&tile, sizeof tile);
    sdevkit_dbg_hash(&pixel, sizeof pixel);
    sdevkit_dbg_hash(&iter, sizeof iter);
    sdevkit_dbg_hash(&view, sizeof view);
    sdevkit_dbg_hash(&angle, sizeof angle);
}

