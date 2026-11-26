/*
 * Rachael, PC side: the software renderer.
 *
 * It draws the picture from VRAM, CGRAM, OAM and the registers one line at
 * a time, in the order the SNES does, so that what depends on lines comes
 * out as on the console:
 *   1. HDMA: each enabled channel writes its table's data for this line.
 *   2. Sprites: the first 32 sprites on the line (in OAM order) are taken,
 *      the rest dropped ("range over"); their tiles are fetched from the
 *      last sprite to the first, and after 34 tiles the rest is dropped
 *      ("time over"), which hits the sprites with the lowest numbers.
 *      Sprites are drawn one line below their y, as on the SNES.
 *   3. Backgrounds: the layers of modes 0 to 4 with 4, 16 or 256 colours a
 *      tile (offset-per-tile in modes 2 and 4, direct colour for 256), or
 *      mode 7's layer, rotated and scaled, and its second layer (EXTBG);
 *      mosaic on any of them. Modes 5 and 6 are not drawn (reported).
 *   4. Main and sub screen: the frontmost pixel of each, by the mode's
 *      priority order, minus what the windows mask (TMW, TSW).
 *   5. Colour math against the fixed colour or the sub screen, limited by
 *      the colour window; then brightness.
 * Colours are BGR 5:5:5 as in CGRAM.
 */

#include "rachael.h"

#include <string.h>

#define W SDEVKIT__W
#define H SDEVKIT__H

enum { L_BG1, L_BG2, L_BG3, L_BG4, L_OBJ, L_BACK };

/* One layer's pixels on the current line. */
typedef struct {
    uint8_t color[W];     /* CGRAM index, or the colour of a direct-colour pixel */
    uint8_t opaque[W];
    uint8_t prio[W];      /* tile priority bit, or sprite priority 0..3 */
    uint8_t pal[W];       /* a 256-colour tile's palette bits, for direct colour (BG1 only) */
} Line;

static Line bg_line[4];
static Line obj_line;
static uint8_t   obj_pal[W];          /* sprite palette 0..7, for colour math on palettes 4..7 */
static uint8_t   win1[W], win2[W];

/* --- HDMA ---------------------------------------------------------------------------- */
typedef struct {
    const uint8_t *p;          /* next table byte */
    uint8_t        lines;      /* lines left of the current entry */
    uint8_t        repeat;     /* new data on every line */
    uint8_t        done;
    const uint8_t *data;       /* data of the current line */
} HdmaRun;

static HdmaRun hdma_run[8];

static void hdma_start(void)
{
    uint8_t c;
    for (c = 0; c < 8; c++) {
        HdmaRun *r = &hdma_run[c];
        r->p = sdevkit__rachael.hdma[c].table;
        r->lines = 0;
        r->done = (uint8_t)(!(sdevkit__rachael.hdma_enabled & (1 << c)) || !r->p);
    }
}

static void hdma_line(void)
{
    uint8_t c;
    for (c = 0; c < 8; c++) {
        HdmaRun *r = &hdma_run[c];
        const sdevkit__hdma_channel *h = &sdevkit__rachael.hdma[c];
        uint8_t size = (uint8_t)(h->two != SDEVKIT_HDMA_ONE ? 2 : 1), write = 0;
        if (r->done) {
            continue;
        }
        if (r->lines == 0) {
            uint8_t count = *r->p++;
            if (count == 0) {
                r->done = 1;
                continue;
            }
            r->repeat = (uint8_t)(count > 0x80);
            r->lines = (uint8_t)(count > 0x80 ? count - 0x80 : count);
            r->data = r->p;
            r->p += r->repeat ? (uint16_t)size * r->lines : size;
            write = 1;
        } else if (r->repeat) {
            r->data += size;
            write = 1;
        }
        if (write) {
            sdevkit__write_reg(h->reg, r->data[0]);
            if (h->two == SDEVKIT_HDMA_TWO_REGS) {
                sdevkit__write_reg((uint8_t)(h->reg + 1), r->data[1]);
            } else if (h->two == SDEVKIT_HDMA_TWICE) {
                sdevkit__write_reg(h->reg, r->data[1]);
            }
        }
        r->lines--;
    }
}

/* --- Tiles ------------------------------------------------------------------------------ */
/* Colour 0..255 (8 bpp), 0..15 (4 bpp) or 0..3 (2 bpp) of pixel (x, y) of the tile at this VRAM
 * word: two bit planes a word, the planes 2 and 3 eight words on, 4 to 7 sixteen and 24. */
static uint8_t tile_pixel(uint16_t addr, uint8_t bpp, uint8_t x, uint8_t y)
{
    uint16_t w0 = sdevkit__rachael.vram[(uint16_t)(addr + y) & (SDEVKIT__VRAM_WORDS - 1)];
    uint8_t bit = (uint8_t)(7 - x);
    uint8_t c = (uint8_t)(((w0 >> bit) & 1) | (((w0 >> (8 + bit)) & 1) << 1));
    if (bpp >= 4) {
        uint16_t w1 = sdevkit__rachael.vram[(uint16_t)(addr + 8 + y) & (SDEVKIT__VRAM_WORDS - 1)];
        c = (uint8_t)(c | (((w1 >> bit) & 1) << 2) | (((w1 >> (8 + bit)) & 1) << 3));
        if (bpp == 8) {
            uint16_t w2 = sdevkit__rachael.vram[(uint16_t)(addr + 16 + y) & (SDEVKIT__VRAM_WORDS - 1)];
            uint16_t w3 = sdevkit__rachael.vram[(uint16_t)(addr + 24 + y) & (SDEVKIT__VRAM_WORDS - 1)];
            c = (uint8_t)(c | (((w2 >> bit) & 1) << 4) | (((w2 >> (8 + bit)) & 1) << 5)
                            | (((w3 >> bit) & 1) << 6) | (((w3 >> (8 + bit)) & 1) << 7));
        }
    }
    return c;
}

/* --- Backgrounds -------------------------------------------------------------------------- */
/* Mosaic (MOSAIC): blocks of size x size pixels from the picture's top left; a layer that has it
 * on shows the block's first pixel in each, the vertical counter starting at scanline 1 and
 * stepping a block at a time, as the PPU's does. */
static uint8_t  mosaic_size;          /* 1..16 */
static uint8_t  mosaic_counter;
static uint16_t mosaic_line;

/* A layer's map: where it is, its size in tiles, its tile size. */
typedef struct {
    uint16_t map, mapw, maph;
    uint8_t  shift;                   /* 3 for 8 x 8 tiles, 4 for 16 x 16 */
} MapSetup;

static void map_setup(uint8_t bg, MapSetup *m)
{
    uint8_t sc = sdevkit__rachael.regs[SDEVKIT_PPU_BG1SC + bg];
    m->map = (uint16_t)((sc & 0xFC) << 8);
    m->mapw = (uint16_t)((sc & 1) ? 64 : 32);
    m->maph = (uint16_t)((sc & 2) ? 64 : 32);
    m->shift = (uint8_t)(((sdevkit__rachael.regs[SDEVKIT_PPU_BGMODE] >> (4 + bg)) & 1) ? 4 : 3);   /* BGMODE: 16 x 16 tiles */
}

/* The map entry under the layer's pixel (px, py), both 10 bits. */
static uint16_t map_entry(const MapSetup *m, uint16_t px, uint16_t py)
{
    uint16_t tx = (uint16_t)((px >> m->shift) & (m->mapw - 1)), ty = (uint16_t)((py >> m->shift) & (m->maph - 1));
    uint16_t screen = (uint16_t)((tx >> 5) + (ty >> 5) * (m->mapw >> 5));
    return sdevkit__rachael.vram[(uint16_t)(m->map + screen * 0x400 + (ty & 31) * 32 + (tx & 31)) & (SDEVKIT__VRAM_WORDS - 1)];
}

/* The layer's pixels repeated over each mosaic block of the line. */
static void mosaic_x(Line *out)
{
    uint16_t x;
    for (x = 0; x < W; x++) {
        uint16_t from = (uint16_t)(x - x % mosaic_size);
        if (from != x) {
            out->opaque[x] = out->opaque[from];
            out->color[x] = out->color[from];
            out->prio[x] = out->prio[from];
            out->pal[x] = out->pal[from];
        }
    }
}

/* One layer of modes 0 to 4 on one line: bpp 2, 4 or 8 bits a pixel, its palettes from CGRAM
 * colour palbase on (mode 0 gives each layer 32 colours). opt is the mode when BG3 holds the
 * offsets per tile (2 and 4): from the second column of tiles on, BG3's map has a scroll for each
 * column, two rows of entries in mode 2 (horizontal, then vertical), one in mode 4 (bit 15: the
 * entry is vertical); bit 13 applies it to BG1, bit 14 to BG2. A horizontal one replaces the
 * layer's scroll but its lowest three bits. direct: keep the palette bits of a 256-colour tile. */
static void draw_bg(uint8_t bg, uint16_t line, uint8_t bpp, uint8_t palbase, uint8_t opt, uint8_t direct)
{
    Line *out = &bg_line[bg];
    MapSetup m, bg3;
    uint8_t nba = sdevkit__rachael.regs[bg < 2 ? SDEVKIT_PPU_BG12NBA : SDEVKIT_PPU_BG34NBA];
    uint16_t chr = (uint16_t)(((bg & 1) ? nba >> 4 : nba & 0x0F) << 12);
    uint16_t hofs = sdevkit__rachael.hofs[bg], vofs = sdevkit__rachael.vofs[bg];
    uint16_t valid = (uint16_t)(bg == 0 ? 0x2000 : 0x4000);
    uint8_t words = (uint8_t)(bpp * 4);   /* VRAM words a tile */
    int16_t x = (int16_t)-(hofs & 7);   /* the first tile may start left of the screen */
    uint8_t column = 0;

    map_setup(bg, &m);
    map_setup(2, &bg3);                /* the offsets' map, for opt */
    /* one tile (8 pixels) at a time: its map entry and row are read once */
    for (; x < W; x = (int16_t)(x + 8), column++) {
        uint16_t h = hofs, v = vofs;
        uint16_t px, py, entry, addr, tile;
        uint8_t fy, pal, prio, i, base, flip;
        if (opt && column > 0) {
            uint16_t ox = (uint16_t)(((column - 1) * 8 + (sdevkit__rachael.hofs[2] & ~7)) & 0x3FF);
            uint16_t hval = map_entry(&bg3, ox, sdevkit__rachael.vofs[2]);
            if (opt == 4) {
                if (hval & valid) {
                    if (hval & 0x8000) {
                        v = (uint16_t)(hval & 0x3FF);
                    } else {
                        h = (uint16_t)((hval & 0x3F8) | (hofs & 7));
                    }
                }
            } else {
                uint16_t vval = map_entry(&bg3, ox, (uint16_t)((sdevkit__rachael.vofs[2] + 8) & 0x3FF));
                if (hval & valid) {
                    h = (uint16_t)((hval & 0x3F8) | (hofs & 7));
                }
                if (vval & valid) {
                    v = (uint16_t)(vval & 0x3FF);
                }
            }
        }
        px = (uint16_t)((x + h) & 0x3FF);
        py = (uint16_t)((line + v) & 0x3FF);
        entry = map_entry(&m, px, py);
        fy = (uint8_t)(py & 7);
        pal = (uint8_t)((entry >> 10) & 7);
        prio = (uint8_t)((entry >> 13) & 1);
        tile = (uint16_t)(entry & 0x3FF);
        if (m.shift == 4) {            /* a 16 x 16 tile is the 8 x 8 tiles n, n + 1, n + 16, n + 17 */
            uint8_t right = (uint8_t)((px >> 3) & 1), lower = (uint8_t)((py >> 3) & 1);
            if (entry & SDEVKIT_BG_TIL_FLIPX) right = (uint8_t)!right;
            if (entry & SDEVKIT_BG_TIL_FLIPY) lower = (uint8_t)!lower;
            tile = (uint16_t)(tile + right + lower * 16);
        }
        if (entry & SDEVKIT_BG_TIL_FLIPY) fy = (uint8_t)(7 - fy);
        addr = (uint16_t)(chr + tile * words);
        base = (uint8_t)(bpp == 8 ? 0 : bpp == 4 ? palbase + pal * 16 : palbase + pal * 4);
        flip = (uint8_t)((entry & SDEVKIT_BG_TIL_FLIPX) ? 7 : 0);
        for (i = 0; i < 8; i++) {
            int16_t sx = (int16_t)(x + i);
            uint8_t c;
            if (sx < 0 || sx >= W) {
                continue;
            }
            c = tile_pixel(addr, bpp, (uint8_t)(i ^ flip), fy);
            out->opaque[sx] = (uint8_t)(c != 0);
            out->color[sx] = (uint8_t)(base + c);
            out->prio[sx] = prio;
            if (direct) {
                out->pal[sx] = pal;
            }
        }
    }
    if (mosaic_size > 1 && (sdevkit__rachael.regs[SDEVKIT_PPU_MOSAIC] & (1 << bg))) {
        mosaic_x(out);
    }
}

/* --- Sprites -------------------------------------------------------------------------------- */
/* --- Mode 7 ----------------------------------------------------------------------------- */
/* A 13-bit value with its sign, as the PPU reads the centre and the scroll. */
static int32_t sign13(uint16_t v)
{
    return (int32_t)((v & 0x1FFF) ^ 0x1000) - 0x1000;
}

/* scroll - centre as the PPU takes it: 10 bits, or all ones above them when bit 13 is set */
static int32_t m7_clip(int32_t n)
{
    return (n & 0x2000) ? (n | ~(int32_t)0x3FF) : (n & 0x3FF);
}

/* Mode 7 on one line, with the PPU's arithmetic: the start of the line from the matrix, the
 * centre and the scroll, each product cut to whole 1/4 pixels (& ~63), then a step of (a, c) per
 * pixel, the map position the sum >> 8. The map is the low byte of VRAM words 0..0x3FFF, the
 * pixels of tile t the high bytes of words t * 64 ... BG1 is the pixel; with EXTBG (SETINI bit 6)
 * BG2 is the same pixel, bit 7 its priority and bits 0-6 its colour. */
static void draw_mode7(uint8_t line, uint8_t bg1, uint8_t bg2)
{
    const uint16_t *m = sdevkit__rachael.m7;
    int32_t a = (int16_t)m[0], b = (int16_t)m[1], c = (int16_t)m[2], d = (int16_t)m[3];
    int32_t cx = sign13(m[4]), cy = sign13(m[5]);
    int32_t h = m7_clip(sign13(sdevkit__rachael.m7hofs) - cx), v = m7_clip(sign13(sdevkit__rachael.m7vofs) - cy);
    uint8_t sel = sdevkit__rachael.regs[SDEVKIT_PPU_M7SEL];
    uint8_t outside = (uint8_t)(sel >> 6);
    int32_t y = (sel & SDEVKIT_M7_FLIPY) ? 255 - line : line;
    int32_t ox = ((a * h) & ~63) + ((b * v) & ~63) + ((b * y) & ~63) + (cx * 256);
    int32_t oy = ((c * h) & ~63) + ((d * v) & ~63) + ((d * y) & ~63) + (cy * 256);
    uint16_t sx;

    for (sx = 0; sx < W; sx++) {
        int32_t x = (sel & SDEVKIT_M7_FLIPX) ? 255 - sx : sx;
        int32_t px = (ox + a * x) >> 8, py = (oy + c * x) >> 8;
        uint8_t off = (uint8_t)(((px | py) & ~1023) != 0);
        uint16_t tile = 0, pixel = 0;
        if (!(off && outside == 3)) {
            tile = (uint16_t)(sdevkit__rachael.vram[((py >> 3) & 127) * 128 + ((px >> 3) & 127)] & 0xFF);
        }
        if (!(off && outside == 2)) {
            pixel = (uint16_t)(sdevkit__rachael.vram[(tile << 6) | ((py & 7) << 3) | (px & 7)] >> 8);
        }
        if (bg1) {
            bg_line[0].opaque[sx] = (uint8_t)(pixel != 0);
            bg_line[0].color[sx] = (uint8_t)pixel;
            bg_line[0].prio[sx] = 0;
            bg_line[0].pal[sx] = 0;
        }
        if (bg2) {
            bg_line[1].opaque[sx] = (uint8_t)((pixel & 0x7F) != 0);
            bg_line[1].color[sx] = (uint8_t)(pixel & 0x7F);
            bg_line[1].prio[sx] = (uint8_t)(pixel >> 7);
        }
    }
    if (mosaic_size > 1) {
        if (bg1 && (sdevkit__rachael.regs[SDEVKIT_PPU_MOSAIC] & 1)) {
            mosaic_x(&bg_line[0]);
        }
        if (bg2 && (sdevkit__rachael.regs[SDEVKIT_PPU_MOSAIC] & 2)) {
            mosaic_x(&bg_line[1]);
        }
    }
}

static const uint8_t obj_sizes[8][2] = {
    { 8, 16 }, { 8, 32 }, { 8, 64 }, { 16, 32 }, { 16, 64 }, { 32, 64 }, { 16, 32 }, { 16, 32 }
};

static void draw_sprites(uint8_t line)
{
    uint8_t list[32], count = 0, tiles = 0, range_over = 0, time_over = 0;
    uint8_t obsel = sdevkit__rachael.regs[SDEVKIT_PPU_OBSEL];
    uint16_t base = (uint16_t)((obsel & 7) << 13);
    uint16_t gap = (uint16_t)((((obsel >> 3) & 3) + 1) << 12);
    int i;

    memset(obj_line.opaque, 0, sizeof obj_line.opaque);

    /* range: the first 32 sprites on this line */
    for (i = 0; i < 128; i++) {
        const uint8_t *o = &sdevkit__rachael.oam[i * 4];
        uint8_t hi = (uint8_t)(sdevkit__rachael.oam[512 + (i >> 2)] >> ((i & 3) * 2));
        uint8_t size = obj_sizes[obsel >> 5][hi & 2 ? 1 : 0];
        int16_t x = (int16_t)(o[0] | ((hi & 1) << 8));
        uint8_t row = (uint8_t)(line - o[1] - 1);   /* drawn one line below y */
        if (x >= 256) x = (int16_t)(x - 512);
        if (row >= size || x <= -size || x >= W) {
            continue;
        }
        if (count == 32) {
            range_over = 1;
            break;
        }
        list[count++] = (uint8_t)i;
    }

    /* time: tiles are fetched from the last sprite in the list to the first */
    {
        uint8_t allowed[32][8];   /* per sprite, per 8-pixel column */
        int k;
        memset(allowed, 0, sizeof allowed);
        for (k = count - 1; k >= 0; k--) {
            const uint8_t *o = &sdevkit__rachael.oam[list[k] * 4];
            uint8_t hi = (uint8_t)(sdevkit__rachael.oam[512 + (list[k] >> 2)] >> ((list[k] & 3) * 2));
            uint8_t size = obj_sizes[obsel >> 5][hi & 2 ? 1 : 0];
            int16_t x = (int16_t)(o[0] | ((hi & 1) << 8));
            uint8_t col;
            if (x >= 256) x = (int16_t)(x - 512);
            for (col = 0; col < size / 8; col++) {
                int16_t cx = (int16_t)(x + col * 8);
                if (cx <= -8 || cx >= W) {
                    continue;          /* off screen: not fetched */
                }
                if (tiles == 34) {
                    time_over = 1;
                    continue;
                }
                tiles++;
                allowed[k][col] = 1;
            }
        }

        /* draw from the last to the first: lower numbers end up in front */
        for (k = count - 1; k >= 0; k--) {
            const uint8_t *o = &sdevkit__rachael.oam[list[k] * 4];
            uint8_t hi = (uint8_t)(sdevkit__rachael.oam[512 + (list[k] >> 2)] >> ((list[k] & 3) * 2));
            uint8_t size = obj_sizes[obsel >> 5][hi & 2 ? 1 : 0];
            int16_t x = (int16_t)(o[0] | ((hi & 1) << 8));
            uint8_t attr = o[3];
            uint16_t name = (uint16_t)(o[2] | ((attr & 1) << 8));
            uint8_t row = (uint8_t)(line - o[1] - 1), sx;
            if (x >= 256) x = (int16_t)(x - 512);
            if (attr & 0x80) row = (uint8_t)(size - 1 - row);
            for (sx = 0; sx < size; sx++) {
                int16_t px = (int16_t)(x + sx);
                uint8_t fx = (uint8_t)((attr & 0x40) ? size - 1 - sx : sx);
                uint16_t t, addr;
                uint8_t c;
                if (px < 0 || px >= W || !allowed[k][sx >> 3]) {
                    continue;
                }
                /* the tile of this 8x8 part: the name table wraps within 16 x 16 tiles */
                t = (uint16_t)((name & 0x100) | ((((name >> 4) + (row >> 3)) & 0x0F) << 4) | (((name & 0x0F) + (fx >> 3)) & 0x0F));
                addr = (uint16_t)(base + ((t & 0x100) ? gap : 0) + (t & 0xFF) * 16);
                c = tile_pixel(addr, 4, (uint8_t)(fx & 7), (uint8_t)(row & 7));
                if (c) {
                    obj_line.opaque[px] = 1;
                    obj_line.color[px] = (uint8_t)(128 + ((attr >> 1) & 7) * 16 + c);
                    obj_line.prio[px] = (uint8_t)((attr >> 4) & 3);
                    obj_pal[px] = (uint8_t)((attr >> 1) & 7);
                }
            }
        }
    }

    sdevkit__rachael.stats.range_over_lines = (uint8_t)(sdevkit__rachael.stats.range_over_lines + range_over);
    sdevkit__rachael.stats.time_over_lines = (uint8_t)(sdevkit__rachael.stats.time_over_lines + time_over);
    if (count > sdevkit__rachael.stats.most_sprites) sdevkit__rachael.stats.most_sprites = count;
    if (tiles > sdevkit__rachael.stats.most_tiles) sdevkit__rachael.stats.most_tiles = tiles;
}

/* --- Windows ---------------------------------------------------------------------------------- */
/* Is x in the area of a layer whose window select nibble is sel (bit 0 window 1
 * inverted, bit 1 window 1 on, bit 2 window 2 inverted, bit 3 window 2 on)? */
static uint8_t in_area(uint8_t sel, uint8_t logic, uint16_t x)
{
    uint8_t a = (uint8_t)(win1[x] ^ (sel & 1)), b = (uint8_t)(win2[x] ^ ((sel >> 2) & 1));
    uint8_t on1 = (uint8_t)(sel & 2), on2 = (uint8_t)(sel & 8);
    if (!on1 && !on2) return 0;
    if (!on2) return a;
    if (!on1) return b;
    switch (logic & 3) {
    case 0:  return (uint8_t)(a | b);
    case 1:  return (uint8_t)(a & b);
    case 2:  return (uint8_t)(a ^ b);
    default: return (uint8_t)!(a ^ b);
    }
}

static uint8_t layer_sel(uint8_t layer)
{
    switch (layer) {
    case L_BG1: return (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_W12SEL] & 0x0F);
    case L_BG2: return (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_W12SEL] >> 4);
    case L_BG3: return (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_W34SEL] & 0x0F);
    case L_BG4: return (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_W34SEL] >> 4);
    case L_OBJ: return (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_WOBJSEL] & 0x0F);
    default:    return (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_WOBJSEL] >> 4);   /* colour window */
    }
}

static uint8_t layer_logic(uint8_t layer)
{
    if (layer < L_OBJ) return (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_WBGLOG] >> (layer * 2)) & 3);
    if (layer == L_OBJ) return (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_WOBJLOG] & 3);
    return (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_WOBJLOG] >> 2) & 3);
}

/* --- Main and sub screen ---------------------------------------------------------------------- */
/* Mode 1, front to back: which layer at which priority. */
typedef struct { uint8_t layer, prio; } Slot;
static const Slot order_bg3_high[] = {
    { L_BG3, 1 }, { L_OBJ, 3 }, { L_BG1, 1 }, { L_BG2, 1 }, { L_OBJ, 2 }, { L_BG1, 0 }, { L_BG2, 0 },
    { L_OBJ, 1 }, { L_OBJ, 0 }, { L_BG3, 0 }
};
static const Slot order_normal[] = {
    { L_OBJ, 3 }, { L_BG1, 1 }, { L_BG2, 1 }, { L_OBJ, 2 }, { L_BG1, 0 }, { L_BG2, 0 },
    { L_OBJ, 1 }, { L_BG3, 1 }, { L_OBJ, 0 }, { L_BG3, 0 }
};
/* Mode 7: sprites of priority 1 to 3 in front of its one layer, 0 behind it. */
static const Slot order_mode7[] = {
    { L_OBJ, 3 }, { L_OBJ, 2 }, { L_OBJ, 1 }, { L_BG1, 0 }, { L_OBJ, 0 }
};
/* Mode 7 with EXTBG: BG2's pixels with bit 7 in front of BG1, the others behind it. */
static const Slot order_mode7_extbg[] = {
    { L_OBJ, 3 }, { L_OBJ, 2 }, { L_BG2, 1 }, { L_OBJ, 1 }, { L_BG1, 0 }, { L_BG2, 0 }, { L_OBJ, 0 }
};
/* Mode 0: four layers. */
static const Slot order_mode0[] = {
    { L_OBJ, 3 }, { L_BG1, 1 }, { L_BG2, 1 }, { L_OBJ, 2 }, { L_BG1, 0 }, { L_BG2, 0 },
    { L_OBJ, 1 }, { L_BG3, 1 }, { L_BG4, 1 }, { L_OBJ, 0 }, { L_BG3, 0 }, { L_BG4, 0 }
};
/* Modes 2 to 4: two layers. */
static const Slot order_two[] = {
    { L_OBJ, 3 }, { L_BG1, 1 }, { L_OBJ, 2 }, { L_BG2, 1 }, { L_OBJ, 1 }, { L_BG1, 0 },
    { L_OBJ, 0 }, { L_BG2, 0 }
};

/* The order of the line being drawn, chosen once a line by its mode. */
static const Slot *order;
static uint8_t order_n;
static uint8_t direct_line;           /* BG1's 256 colours are direct colours on this line */

#define ORDER(o) (order = (o), order_n = (uint8_t)(sizeof (o) / sizeof (o)[0]))

/* The frontmost layer at x on a screen with these layers (TM or TS) and
 * window masks (TMW or TSW). Returns the layer; *color is its CGRAM index. */
static uint8_t front(uint16_t x, uint8_t shown, uint8_t masked, uint8_t *color)
{
    uint8_t i;
    for (i = 0; i < order_n; i++) {
        uint8_t layer = order[i].layer;
        const Line *l = layer == L_OBJ ? &obj_line : &bg_line[layer];
        if (!(shown & (1 << layer)) || !l->opaque[x] || l->prio[x] != order[i].prio) {
            continue;
        }
        if ((masked & (1 << layer)) && in_area(layer_sel(layer), layer_logic(layer), x)) {
            continue;
        }
        *color = l->color[x];
        return layer;
    }
    *color = 0;
    return L_BACK;
}

/* The colour of the front pixel: CGRAM's, or for a direct-colour pixel of BG1 its own, from its
 * eight bits BBGGGRRR and the tile's palette bits bgr: BBb00 GGGg0 RRRr0. */
static uint16_t colour_of(uint8_t layer, uint16_t x, uint8_t index)
{
    if (layer == L_BG1 && direct_line) {
        uint8_t p = bg_line[0].pal[x];
        uint16_t r = (uint16_t)(((index & 7) << 2) | ((p & 1) << 1));
        uint16_t g = (uint16_t)((((index >> 3) & 7) << 2) | (p & 2));
        uint16_t b = (uint16_t)(((index >> 6) << 3) | (p & 4));
        return (uint16_t)(r | (g << 5) | (b << 10));
    }
    return sdevkit__rachael.cgram[index];
}

/* --- Colour math --------------------------------------------------------------------------------- */
static uint16_t add_sub(uint16_t m, uint16_t s, uint8_t sub, uint8_t half)
{
    uint16_t out = 0;
    uint8_t shift;
    for (shift = 0; shift <= 10; shift = (uint8_t)(shift + 5)) {
        int16_t a = (int16_t)((m >> shift) & 31), b = (int16_t)((s >> shift) & 31), v;
        if (sub) {
            v = (int16_t)(a - b);
            if (v < 0) v = 0;
            if (half) v = (int16_t)(v >> 1);
        } else {
            v = (int16_t)(a + b);
            if (half) v = (int16_t)(v >> 1);
            if (v > 31) v = 31;
        }
        out = (uint16_t)(out | (v << shift));
    }
    return out;
}

static uint8_t region(uint8_t mode, uint8_t in_window)
{
    switch (mode & 3) {
    case 0:  return 0;               /* never */
    case 1:  return (uint8_t)!in_window;  /* outside the colour window */
    case 2:  return in_window;       /* inside */
    default: return 1;               /* always */
    }
}

static uint16_t brighten(uint16_t c, uint8_t level)
{
    uint16_t r = (uint16_t)((c & 31) * level / 15), g = (uint16_t)(((c >> 5) & 31) * level / 15), b = (uint16_t)(((c >> 10) & 31) * level / 15);
    return (uint16_t)(r | (g << 5) | (b << 10));
}

/* The line's layer line used for a layer: the mosaic block's first when it has mosaic on. */
static uint16_t layer_line(uint8_t bg, uint16_t line)
{
    return (mosaic_size > 1 && (sdevkit__rachael.regs[SDEVKIT_PPU_MOSAIC] & (1 << bg))) ? mosaic_line : line;
}

/* The backgrounds of one line by the mode: each layer the screens show, at its bits a pixel. */
static void draw_layers(uint8_t shown, uint16_t line)
{
    uint8_t bgmode = sdevkit__rachael.regs[SDEVKIT_PPU_BGMODE], mode = (uint8_t)(bgmode & 7), bg;
    uint8_t direct = (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_CGWSEL] & 1) != 0);

    direct_line = 0;
    if (mode == 7) {
        uint8_t extbg = (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_SETINI] & 0x40) != 0);
        uint8_t bg1 = (uint8_t)((shown & 1) != 0), bg2 = (uint8_t)(extbg && (shown & 2));
        if (extbg) {
            ORDER(order_mode7_extbg);
        } else {
            ORDER(order_mode7);
        }
        direct_line = direct;
        if (bg1 || bg2) {
            /* both layers take the line of BG1's vertical mosaic */
            draw_mode7((uint8_t)layer_line(0, line), bg1, bg2);
        }
        if (!bg1) {
            memset(bg_line[0].opaque, 0, sizeof bg_line[0].opaque);
        }
        if (!bg2) {
            memset(bg_line[1].opaque, 0, sizeof bg_line[1].opaque);
        }
        return;
    }
    if (mode == 0) {
        ORDER(order_mode0);
    } else if (mode == 1 || mode > 4) {   /* modes 5 and 6 are not drawn: as mode 1, reported */
        if (bgmode & SDEVKIT_BG3_MODE1_PRIORITY_HIGH) {
            ORDER(order_bg3_high);
        } else {
            ORDER(order_normal);
        }
    } else {
        ORDER(order_two);
    }
    for (bg = 0; bg < 4; bg++) {
        uint8_t bpp = 0, opt = 0;
        switch (mode) {
        case 0:  bpp = 2; break;
        case 2:  bpp = (uint8_t)(bg < 2 ? 4 : 0); opt = 2; break;
        case 3:  bpp = (uint8_t)(bg == 0 ? 8 : bg == 1 ? 4 : 0); break;
        case 4:  bpp = (uint8_t)(bg == 0 ? 8 : bg == 1 ? 2 : 0); opt = 4; break;
        default: bpp = (uint8_t)(bg < 2 ? 4 : bg == 2 ? 2 : 0); break;
        }
        if (bpp && (shown & (1 << bg))) {
            uint8_t d = (uint8_t)(bpp == 8 && direct);
            draw_bg(bg, layer_line(bg, line), bpp, (uint8_t)(mode == 0 ? bg * 32 : 0), opt, d);
            if (d) {
                direct_line = 1;
            }
        } else {
            memset(bg_line[bg].opaque, 0, sizeof bg_line[bg].opaque);
        }
    }
}

void sdevkit__render(uint16_t *out)
{
    uint16_t y, x;

    memset(&sdevkit__rachael.stats, 0, sizeof sdevkit__rachael.stats);
    sdevkit__rachael.in_vblank = 0;   /* the picture is being drawn: VRAM and CGRAM are closed */
    hdma_start();

    /* Row y of the picture is scanline y + 1: the SNES never shows scanline 0, so a background
     * scrolled to 0 shows its line 1 first, and a sprite at y appears on row y. (Taken over from
     * Blubber Blasen's pseudo SNES, which drew row y as scanline y, a line lower than the SNES.) */
    for (y = 0; y < H; y++) {
        uint8_t inidisp, tm, ts, tmw, tsw, cgwsel, cgadsub, cw_sel, cw_logic;
        uint16_t wl1, wr1, wl2, wr2, fixed;
        uint16_t *row = &out[y * W];

        hdma_line();
        if (sdevkit__rachael.irq_on && sdevkit__rachael.irq_row == y && sdevkit__irq_handler != NULL) {
            /* the game's IRQ in the horizontal blank before this row, after its HDMA, as on the
             * SNES (sdevkit/irq.h); a row it sets below this one comes in this picture */
            sdevkit__rachael.in_irq = 1;
            sdevkit__rachael.irq_waited = 0;
            sdevkit__irq_handler();
            sdevkit__rachael.in_irq = 0;
        }
        /* the vertical mosaic counter: a block's lines show its first line */
        mosaic_size = (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_MOSAIC] >> 4) + 1);
        if (y == 0) {
            mosaic_counter = mosaic_size;
            mosaic_line = 1;
        } else if (--mosaic_counter == 0) {
            mosaic_counter = mosaic_size;
            mosaic_line = (uint16_t)(mosaic_line + mosaic_size);
        }
        inidisp = sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP];
        if (inidisp & 0x80) {
            memset(row, 0, W * sizeof row[0]);
            continue;
        }
        tm = sdevkit__rachael.regs[SDEVKIT_PPU_TM];
        ts = sdevkit__rachael.regs[SDEVKIT_PPU_TS];
        tmw = sdevkit__rachael.regs[SDEVKIT_PPU_TMW];
        tsw = sdevkit__rachael.regs[SDEVKIT_PPU_TSW];
        cgwsel = sdevkit__rachael.regs[SDEVKIT_PPU_CGWSEL];
        cgadsub = sdevkit__rachael.regs[SDEVKIT_PPU_CGADSUB];
        cw_sel = layer_sel(L_BACK);
        cw_logic = layer_logic(L_BACK);
        fixed = (uint16_t)(sdevkit__rachael.fixed_r | (sdevkit__rachael.fixed_g << 5) | (sdevkit__rachael.fixed_b << 10));

        wl1 = sdevkit__rachael.regs[SDEVKIT_PPU_WH0];
        wr1 = sdevkit__rachael.regs[SDEVKIT_PPU_WH1];
        wl2 = sdevkit__rachael.regs[SDEVKIT_PPU_WH2];
        wr2 = sdevkit__rachael.regs[SDEVKIT_PPU_WH3];
        for (x = 0; x < W; x++) {
            win1[x] = (uint8_t)(x >= wl1 && x <= wr1);
            win2[x] = (uint8_t)(x >= wl2 && x <= wr2);
        }

        draw_layers((uint8_t)(tm | ts), (uint16_t)(y + 1));
        if ((tm | ts) & SDEVKIT_OBJ_ENABLE) {
            draw_sprites((uint8_t)(y + 1));
        } else {
            memset(obj_line.opaque, 0, sizeof obj_line.opaque);
        }

        for (x = 0; x < W; x++) {
            uint8_t mc, sc, cw = in_area(cw_sel, cw_logic, x);
            uint8_t ml = front(x, tm, tmw, &mc);
            uint16_t color = colour_of(ml, x, mc);
            uint8_t clipped = region((uint8_t)(cgwsel >> 6), cw);
            uint8_t math_on = (uint8_t)!region((uint8_t)(cgwsel >> 4), cw);
            uint8_t layer_math = (uint8_t)(ml == L_BACK ? (cgadsub & SDEVKIT_CM_MSCR_BACK) != 0
                               : ml == L_OBJ ? (cgadsub & SDEVKIT_CM_MSCR_PAL47) && obj_pal[x] >= 4
                               : (cgadsub >> ml) & 1);
            if (clipped) {
                color = 0;
            }
            if (math_on && layer_math) {
                uint16_t sub = fixed;
                uint8_t half = (uint8_t)((cgadsub & SDEVKIT_CM_DIV2_DIVIDE) && !clipped);
                if (cgwsel & SDEVKIT_CM_SUBBGOBJ_ENABLE) {
                    uint8_t sl = front(x, ts, tsw, &sc);
                    if (sl == L_BACK) {
                        half = 0;                    /* empty sub screen: the fixed colour, not halved */
                    } else {
                        sub = colour_of(sl, x, sc);
                    }
                }
                color = add_sub(color, sub, (uint8_t)((cgadsub & SDEVKIT_CM_SUB_MAINSUB) != 0), half);
            }
            row[x] = brighten(color, (uint8_t)(inidisp & 0x0F));
        }
    }
}
