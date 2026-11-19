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
 *   3. Backgrounds of mode 1: BG1 and BG2 with 16 colours, BG3 with 4; or
 *      mode 7's one layer, rotated and scaled (draw_mode7).
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
    uint8_t color[W];     /* CGRAM index */
    uint8_t opaque[W];
    uint8_t prio[W];      /* tile priority bit, or sprite priority 0..3 */
} Line;

static Line bg_line[3];
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
/* Colour 0..15 (4 bpp) or 0..3 (2 bpp) of pixel (x, y) of the tile at this VRAM word. */
static uint8_t tile_pixel(uint16_t addr, uint8_t bpp, uint8_t x, uint8_t y)
{
    uint16_t w0 = sdevkit__rachael.vram[(uint16_t)(addr + y) & (SDEVKIT__VRAM_WORDS - 1)];
    uint8_t bit = (uint8_t)(7 - x);
    uint8_t c = (uint8_t)(((w0 >> bit) & 1) | (((w0 >> (8 + bit)) & 1) << 1));
    if (bpp == 4) {
        uint16_t w1 = sdevkit__rachael.vram[(uint16_t)(addr + 8 + y) & (SDEVKIT__VRAM_WORDS - 1)];
        c = (uint8_t)(c | (((w1 >> bit) & 1) << 2) | (((w1 >> (8 + bit)) & 1) << 3));
    }
    return c;
}

/* --- Backgrounds -------------------------------------------------------------------------- */
static void draw_bg(uint8_t bg, uint8_t line)
{
    Line *out = &bg_line[bg];
    uint8_t bpp = (uint8_t)(bg < 2 ? 4 : 2);
    uint8_t sc = sdevkit__rachael.regs[SDEVKIT_PPU_BG1SC + bg];
    uint16_t map = (uint16_t)((sc & 0xFC) << 8);
    uint16_t mapw = (uint16_t)((sc & 1) ? 64 : 32), maph = (uint16_t)((sc & 2) ? 64 : 32);
    uint8_t nba = sdevkit__rachael.regs[bg < 2 ? SDEVKIT_PPU_BG12NBA : SDEVKIT_PPU_BG34NBA];
    uint16_t chr = (uint16_t)(((bg & 1) ? nba >> 4 : nba & 0x0F) << 12);
    uint16_t py = (uint16_t)((line + sdevkit__rachael.vofs[bg]) & 0x3FF);
    uint8_t big = (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_BGMODE] >> (4 + bg)) & 1);   /* BGMODE: this layer has 16 x 16 tiles */
    uint8_t shift = (uint8_t)(big ? 4 : 3);
    uint16_t ty = (uint16_t)((py >> shift) & (maph - 1));
    uint16_t hofs = sdevkit__rachael.hofs[bg];
    int16_t x = (int16_t)-(hofs & 7);   /* the first tile may start left of the screen */

    /* one tile (8 pixels) at a time: its map entry and row are read once */
    for (; x < W; x = (int16_t)(x + 8)) {
        uint16_t px = (uint16_t)((x + hofs) & 0x3FF);
        uint16_t tx = (uint16_t)((px >> shift) & (mapw - 1));
        uint16_t screen = (uint16_t)((tx >> 5) + (ty >> 5) * (mapw >> 5));
        uint16_t entry = sdevkit__rachael.vram[(uint16_t)(map + screen * 0x400 + (ty & 31) * 32 + (tx & 31)) & (SDEVKIT__VRAM_WORDS - 1)];
        uint8_t fy = (uint8_t)(py & 7), pal = (uint8_t)((entry >> 10) & 7), prio = (uint8_t)((entry >> 13) & 1), i;
        uint16_t addr;
        uint16_t tile = (uint16_t)(entry & 0x3FF);
        if (big) {                     /* a 16 x 16 tile is the 8 x 8 tiles n, n + 1, n + 16, n + 17 */
            uint8_t right = (uint8_t)((px >> 3) & 1), lower = (uint8_t)((py >> 3) & 1);
            if (entry & SDEVKIT_BG_TIL_FLIPX) right = (uint8_t)!right;
            if (entry & SDEVKIT_BG_TIL_FLIPY) lower = (uint8_t)!lower;
            tile = (uint16_t)(tile + right + lower * 16);
        }
        if (entry & SDEVKIT_BG_TIL_FLIPY) fy = (uint8_t)(7 - fy);
        addr = (uint16_t)(chr + tile * (bpp == 4 ? 16 : 8));
        for (i = 0; i < 8; i++) {
            int16_t sx = (int16_t)(x + i);
            uint8_t c;
            if (sx < 0 || sx >= W) {
                continue;
            }
            c = tile_pixel(addr, bpp, (uint8_t)((entry & SDEVKIT_BG_TIL_FLIPX) ? 7 - i : i), fy);
            out->opaque[sx] = (uint8_t)(c != 0);
            out->color[sx] = (uint8_t)(bpp == 4 ? pal * 16 + c : pal * 4 + c);
            out->prio[sx] = prio;
        }
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

/* BG1 of mode 7 on one line, with the PPU's arithmetic: the start of the line from the matrix,
 * the centre and the scroll, each product cut to whole 1/4 pixels (& ~63), then a step of (a, c)
 * per pixel, the map position the sum >> 8. The map is the low byte of VRAM words 0..0x3FFF, the
 * pixels of tile t the high bytes of words t * 64 ... */
static void draw_mode7(uint8_t line)
{
    Line *out = &bg_line[0];
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
        out->opaque[sx] = (uint8_t)(pixel != 0);
        out->color[sx] = (uint8_t)pixel;
        out->prio[sx] = 0;
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

/* The frontmost layer at x on a screen with these layers (TM or TS) and
 * window masks (TMW or TSW). Returns the layer; *color is its CGRAM index. */
static uint8_t front(uint16_t x, uint8_t shown, uint8_t masked, uint8_t *color)
{
    uint8_t mode7 = (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_BGMODE] & 7) == 7);
    uint8_t high = (uint8_t)(!mode7 && (sdevkit__rachael.regs[SDEVKIT_PPU_BGMODE] & SDEVKIT_BG3_MODE1_PRIORITY_HIGH) != 0);
    const Slot *order = mode7 ? order_mode7 : high ? order_bg3_high : order_normal;
    uint8_t n = (uint8_t)(mode7 ? sizeof order_mode7 / sizeof order_mode7[0]
                        : high ? sizeof order_bg3_high / sizeof order_bg3_high[0] : sizeof order_normal / sizeof order_normal[0]);
    uint8_t i;
    for (i = 0; i < n; i++) {
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

        for (x = 0; x < 3; x++) {
            uint8_t mode7 = (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_BGMODE] & 7) == 7);
            if (((tm | ts) & (1 << x)) && (!mode7 || x == 0)) {
                if (mode7) {
                    draw_mode7((uint8_t)(y + 1));
                } else {
                    draw_bg((uint8_t)x, (uint8_t)(y + 1));
                }
            } else {
                memset(bg_line[x].opaque, 0, sizeof bg_line[x].opaque);
            }
        }
        if ((tm | ts) & SDEVKIT_OBJ_ENABLE) {
            draw_sprites((uint8_t)(y + 1));
        } else {
            memset(obj_line.opaque, 0, sizeof obj_line.opaque);
        }

        for (x = 0; x < W; x++) {
            uint8_t mc, sc, cw = in_area(cw_sel, cw_logic, x);
            uint8_t ml = front(x, tm, tmw, &mc);
            uint16_t color = sdevkit__rachael.cgram[mc];
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
                        sub = sdevkit__rachael.cgram[sc];
                    }
                }
                color = add_sub(color, sub, (uint8_t)((cgadsub & SDEVKIT_CM_SUB_MAINSUB) != 0), half);
            }
            row[x] = brighten(color, (uint8_t)(inidisp & 0x0F));
        }
    }
}
