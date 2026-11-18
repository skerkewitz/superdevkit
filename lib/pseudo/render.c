/*
 * Pseudo SNES, PC side: the software renderer.
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
 *   3. Backgrounds of mode 1: BG1 and BG2 with 16 colours, BG3 with 4.
 *   4. Main and sub screen: the frontmost pixel of each, by the mode 1
 *      priority order, minus what the windows mask (TMW, TSW).
 *   5. Colour math against the fixed colour or the sub screen, limited by
 *      the colour window; then brightness.
 * Colours are BGR 5:5:5 as in CGRAM.
 */

#include "pseudo.h"

#include <string.h>

#define W SNES_W
#define H SNES_H

enum { L_BG1, L_BG2, L_BG3, L_BG4, L_OBJ, L_BACK };

/* One layer's pixels on the current line. */
typedef struct {
    u8 color[W];     /* CGRAM index */
    u8 opaque[W];
    u8 prio[W];      /* tile priority bit, or sprite priority 0..3 */
} Line;

static Line bg_line[3];
static Line obj_line;
static u8   obj_pal[W];          /* sprite palette 0..7, for colour math on palettes 4..7 */
static u8   win1[W], win2[W];

/* --- HDMA ---------------------------------------------------------------------------- */
typedef struct {
    const u8 *p;          /* next table byte */
    u8        lines;      /* lines left of the current entry */
    u8        repeat;     /* new data on every line */
    u8        done;
    const u8 *data;       /* data of the current line */
} HdmaRun;

static HdmaRun hdma_run[8];

static void hdma_start(void)
{
    u8 c;
    for (c = 0; c < 8; c++) {
        HdmaRun *r = &hdma_run[c];
        r->p = g_snes.hdma[c].table;
        r->lines = 0;
        r->done = (u8)(!(g_snes.hdma_enabled & (1 << c)) || !r->p);
    }
}

static void hdma_line(void)
{
    u8 c;
    for (c = 0; c < 8; c++) {
        HdmaRun *r = &hdma_run[c];
        const SnesHdma *h = &g_snes.hdma[c];
        u8 size = (u8)(h->two ? 2 : 1), write = 0;
        if (r->done) {
            continue;
        }
        if (r->lines == 0) {
            u8 count = *r->p++;
            if (count == 0) {
                r->done = 1;
                continue;
            }
            r->repeat = (u8)(count > 0x80);
            r->lines = (u8)(count > 0x80 ? count - 0x80 : count);
            r->data = r->p;
            r->p += r->repeat ? (u16)size * r->lines : size;
            write = 1;
        } else if (r->repeat) {
            r->data += size;
            write = 1;
        }
        if (write) {
            snes_write_reg(h->reg, r->data[0]);
            if (h->two) {
                snes_write_reg((u8)(h->reg + 1), r->data[1]);
            }
        }
        r->lines--;
    }
}

/* --- Tiles ------------------------------------------------------------------------------ */
/* Colour 0..15 (4 bpp) or 0..3 (2 bpp) of pixel (x, y) of the tile at this VRAM word. */
static u8 tile_pixel(u16 addr, u8 bpp, u8 x, u8 y)
{
    u16 w0 = g_snes.vram[(u16)(addr + y) & (SNES_VRAM_WORDS - 1)];
    u8 bit = (u8)(7 - x);
    u8 c = (u8)(((w0 >> bit) & 1) | (((w0 >> (8 + bit)) & 1) << 1));
    if (bpp == 4) {
        u16 w1 = g_snes.vram[(u16)(addr + 8 + y) & (SNES_VRAM_WORDS - 1)];
        c = (u8)(c | (((w1 >> bit) & 1) << 2) | (((w1 >> (8 + bit)) & 1) << 3));
    }
    return c;
}

/* --- Backgrounds -------------------------------------------------------------------------- */
static void draw_bg(u8 bg, u8 line)
{
    Line *out = &bg_line[bg];
    u8 bpp = (u8)(bg < 2 ? 4 : 2);
    u8 sc = g_snes.regs[PPU_BG1SC + bg];
    u16 map = (u16)((sc & 0xFC) << 8);
    u16 mapw = (u16)((sc & 1) ? 64 : 32), maph = (u16)((sc & 2) ? 64 : 32);
    u8 nba = g_snes.regs[bg < 2 ? PPU_BG12NBA : PPU_BG34NBA];
    u16 chr = (u16)(((bg & 1) ? nba >> 4 : nba & 0x0F) << 12);
    u16 py = (u16)((line + g_snes.vofs[bg]) & 0x3FF);
    u8 big = (u8)((g_snes.regs[PPU_BGMODE] >> (4 + bg)) & 1);   /* BGMODE: this layer has 16 x 16 tiles */
    u8 shift = (u8)(big ? 4 : 3);
    u16 ty = (u16)((py >> shift) & (maph - 1));
    u16 hofs = g_snes.hofs[bg];
    s16 x = (s16)-(hofs & 7);   /* the first tile may start left of the screen */

    /* one tile (8 pixels) at a time: its map entry and row are read once */
    for (; x < W; x = (s16)(x + 8)) {
        u16 px = (u16)((x + hofs) & 0x3FF);
        u16 tx = (u16)((px >> shift) & (mapw - 1));
        u16 screen = (u16)((tx >> 5) + (ty >> 5) * (mapw >> 5));
        u16 entry = g_snes.vram[(u16)(map + screen * 0x400 + (ty & 31) * 32 + (tx & 31)) & (SNES_VRAM_WORDS - 1)];
        u8 fy = (u8)(py & 7), pal = (u8)((entry >> 10) & 7), prio = (u8)((entry >> 13) & 1), i;
        u16 addr;
        u16 tile = (u16)(entry & 0x3FF);
        if (big) {                     /* a 16 x 16 tile is the 8 x 8 tiles n, n + 1, n + 16, n + 17 */
            u8 right = (u8)((px >> 3) & 1), lower = (u8)((py >> 3) & 1);
            if (entry & BG_TIL_FLIPX) right = (u8)!right;
            if (entry & BG_TIL_FLIPY) lower = (u8)!lower;
            tile = (u16)(tile + right + lower * 16);
        }
        if (entry & BG_TIL_FLIPY) fy = (u8)(7 - fy);
        addr = (u16)(chr + tile * (bpp == 4 ? 16 : 8));
        for (i = 0; i < 8; i++) {
            s16 sx = (s16)(x + i);
            u8 c;
            if (sx < 0 || sx >= W) {
                continue;
            }
            c = tile_pixel(addr, bpp, (u8)((entry & BG_TIL_FLIPX) ? 7 - i : i), fy);
            out->opaque[sx] = (u8)(c != 0);
            out->color[sx] = (u8)(bpp == 4 ? pal * 16 + c : pal * 4 + c);
            out->prio[sx] = prio;
        }
    }
}

/* --- Sprites -------------------------------------------------------------------------------- */
static const u8 obj_sizes[8][2] = {
    { 8, 16 }, { 8, 32 }, { 8, 64 }, { 16, 32 }, { 16, 64 }, { 32, 64 }, { 16, 32 }, { 16, 32 }
};

static void draw_sprites(u8 line)
{
    u8 list[32], count = 0, tiles = 0, range_over = 0, time_over = 0;
    u8 obsel = g_snes.regs[PPU_OBSEL];
    u16 base = (u16)((obsel & 7) << 13);
    u16 gap = (u16)((((obsel >> 3) & 3) + 1) << 12);
    int i;

    memset(obj_line.opaque, 0, sizeof obj_line.opaque);

    /* range: the first 32 sprites on this line */
    for (i = 0; i < 128; i++) {
        const u8 *o = &g_snes.oam[i * 4];
        u8 hi = (u8)(g_snes.oam[512 + (i >> 2)] >> ((i & 3) * 2));
        u8 size = obj_sizes[obsel >> 5][hi & 2 ? 1 : 0];
        s16 x = (s16)(o[0] | ((hi & 1) << 8));
        u8 row = (u8)(line - o[1] - 1);   /* drawn one line below y */
        if (x >= 256) x = (s16)(x - 512);
        if (row >= size || x <= -size || x >= W) {
            continue;
        }
        if (count == 32) {
            range_over = 1;
            break;
        }
        list[count++] = (u8)i;
    }

    /* time: tiles are fetched from the last sprite in the list to the first */
    {
        u8 allowed[32][8];   /* per sprite, per 8-pixel column */
        int k;
        memset(allowed, 0, sizeof allowed);
        for (k = count - 1; k >= 0; k--) {
            const u8 *o = &g_snes.oam[list[k] * 4];
            u8 hi = (u8)(g_snes.oam[512 + (list[k] >> 2)] >> ((list[k] & 3) * 2));
            u8 size = obj_sizes[obsel >> 5][hi & 2 ? 1 : 0];
            s16 x = (s16)(o[0] | ((hi & 1) << 8));
            u8 col;
            if (x >= 256) x = (s16)(x - 512);
            for (col = 0; col < size / 8; col++) {
                s16 cx = (s16)(x + col * 8);
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
            const u8 *o = &g_snes.oam[list[k] * 4];
            u8 hi = (u8)(g_snes.oam[512 + (list[k] >> 2)] >> ((list[k] & 3) * 2));
            u8 size = obj_sizes[obsel >> 5][hi & 2 ? 1 : 0];
            s16 x = (s16)(o[0] | ((hi & 1) << 8));
            u8 attr = o[3];
            u16 name = (u16)(o[2] | ((attr & 1) << 8));
            u8 row = (u8)(line - o[1] - 1), sx;
            if (x >= 256) x = (s16)(x - 512);
            if (attr & 0x80) row = (u8)(size - 1 - row);
            for (sx = 0; sx < size; sx++) {
                s16 px = (s16)(x + sx);
                u8 fx = (u8)((attr & 0x40) ? size - 1 - sx : sx);
                u16 t, addr;
                u8 c;
                if (px < 0 || px >= W || !allowed[k][sx >> 3]) {
                    continue;
                }
                /* the tile of this 8x8 part: the name table wraps within 16 x 16 tiles */
                t = (u16)((name & 0x100) | ((((name >> 4) + (row >> 3)) & 0x0F) << 4) | (((name & 0x0F) + (fx >> 3)) & 0x0F));
                addr = (u16)(base + ((t & 0x100) ? gap : 0) + (t & 0xFF) * 16);
                c = tile_pixel(addr, 4, (u8)(fx & 7), (u8)(row & 7));
                if (c) {
                    obj_line.opaque[px] = 1;
                    obj_line.color[px] = (u8)(128 + ((attr >> 1) & 7) * 16 + c);
                    obj_line.prio[px] = (u8)((attr >> 4) & 3);
                    obj_pal[px] = (u8)((attr >> 1) & 7);
                }
            }
        }
    }

    g_snes.stats.range_over_lines = (u8)(g_snes.stats.range_over_lines + range_over);
    g_snes.stats.time_over_lines = (u8)(g_snes.stats.time_over_lines + time_over);
    if (count > g_snes.stats.most_sprites) g_snes.stats.most_sprites = count;
    if (tiles > g_snes.stats.most_tiles) g_snes.stats.most_tiles = tiles;
}

/* --- Windows ---------------------------------------------------------------------------------- */
/* Is x in the area of a layer whose window select nibble is sel (bit 0 window 1
 * inverted, bit 1 window 1 on, bit 2 window 2 inverted, bit 3 window 2 on)? */
static u8 in_area(u8 sel, u8 logic, u16 x)
{
    u8 a = (u8)(win1[x] ^ (sel & 1)), b = (u8)(win2[x] ^ ((sel >> 2) & 1));
    u8 on1 = (u8)(sel & 2), on2 = (u8)(sel & 8);
    if (!on1 && !on2) return 0;
    if (!on2) return a;
    if (!on1) return b;
    switch (logic & 3) {
    case 0:  return (u8)(a | b);
    case 1:  return (u8)(a & b);
    case 2:  return (u8)(a ^ b);
    default: return (u8)!(a ^ b);
    }
}

static u8 layer_sel(u8 layer)
{
    switch (layer) {
    case L_BG1: return (u8)(g_snes.regs[PPU_W12SEL] & 0x0F);
    case L_BG2: return (u8)(g_snes.regs[PPU_W12SEL] >> 4);
    case L_BG3: return (u8)(g_snes.regs[PPU_W34SEL] & 0x0F);
    case L_BG4: return (u8)(g_snes.regs[PPU_W34SEL] >> 4);
    case L_OBJ: return (u8)(g_snes.regs[PPU_WOBJSEL] & 0x0F);
    default:    return (u8)(g_snes.regs[PPU_WOBJSEL] >> 4);   /* colour window */
    }
}

static u8 layer_logic(u8 layer)
{
    if (layer < L_OBJ) return (u8)((g_snes.regs[PPU_WBGLOG] >> (layer * 2)) & 3);
    if (layer == L_OBJ) return (u8)(g_snes.regs[PPU_WOBJLOG] & 3);
    return (u8)((g_snes.regs[PPU_WOBJLOG] >> 2) & 3);
}

/* --- Main and sub screen ---------------------------------------------------------------------- */
/* Mode 1, front to back: which layer at which priority. */
typedef struct { u8 layer, prio; } Slot;
static const Slot order_bg3_high[] = {
    { L_BG3, 1 }, { L_OBJ, 3 }, { L_BG1, 1 }, { L_BG2, 1 }, { L_OBJ, 2 }, { L_BG1, 0 }, { L_BG2, 0 },
    { L_OBJ, 1 }, { L_OBJ, 0 }, { L_BG3, 0 }
};
static const Slot order_normal[] = {
    { L_OBJ, 3 }, { L_BG1, 1 }, { L_BG2, 1 }, { L_OBJ, 2 }, { L_BG1, 0 }, { L_BG2, 0 },
    { L_OBJ, 1 }, { L_BG3, 1 }, { L_OBJ, 0 }, { L_BG3, 0 }
};

/* The frontmost layer at x on a screen with these layers (TM or TS) and
 * window masks (TMW or TSW). Returns the layer; *color is its CGRAM index. */
static u8 front(u16 x, u8 shown, u8 masked, u8 *color)
{
    u8 high = (u8)((g_snes.regs[PPU_BGMODE] & BG3_MODE1_PRIORITY_HIGH) != 0);
    const Slot *order = high ? order_bg3_high : order_normal;
    u8 n = (u8)(high ? sizeof order_bg3_high / sizeof order_bg3_high[0] : sizeof order_normal / sizeof order_normal[0]);
    u8 i;
    for (i = 0; i < n; i++) {
        u8 layer = order[i].layer;
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
static u16 add_sub(u16 m, u16 s, u8 sub, u8 half)
{
    u16 out = 0;
    u8 shift;
    for (shift = 0; shift <= 10; shift = (u8)(shift + 5)) {
        s16 a = (s16)((m >> shift) & 31), b = (s16)((s >> shift) & 31), v;
        if (sub) {
            v = (s16)(a - b);
            if (v < 0) v = 0;
            if (half) v = (s16)(v >> 1);
        } else {
            v = (s16)(a + b);
            if (half) v = (s16)(v >> 1);
            if (v > 31) v = 31;
        }
        out = (u16)(out | (v << shift));
    }
    return out;
}

static u8 region(u8 mode, u8 in_window)
{
    switch (mode & 3) {
    case 0:  return 0;               /* never */
    case 1:  return (u8)!in_window;  /* outside the colour window */
    case 2:  return in_window;       /* inside */
    default: return 1;               /* always */
    }
}

static u16 brighten(u16 c, u8 level)
{
    u16 r = (u16)((c & 31) * level / 15), g = (u16)(((c >> 5) & 31) * level / 15), b = (u16)(((c >> 10) & 31) * level / 15);
    return (u16)(r | (g << 5) | (b << 10));
}

void snes_render(u16 *out)
{
    u16 y, x;

    memset(&g_snes.stats, 0, sizeof g_snes.stats);
    g_snes.in_vblank = 0;   /* the picture is being drawn: VRAM and CGRAM are closed */
    hdma_start();

    /* Row y of the picture is scanline y + 1: the SNES never shows scanline 0, so a background
     * scrolled to 0 shows its line 1 first, and a sprite at y appears on row y. (Taken over from
     * Blubber Blasen's pseudo SNES, which drew row y as scanline y, a line lower than the SNES.) */
    for (y = 0; y < H; y++) {
        u8 inidisp, tm, ts, tmw, tsw, cgwsel, cgadsub, cw_sel, cw_logic;
        u16 wl1, wr1, wl2, wr2, fixed;
        u16 *row = &out[y * W];

        hdma_line();
        inidisp = g_snes.regs[PPU_INIDISP];
        if (inidisp & 0x80) {
            memset(row, 0, W * sizeof row[0]);
            continue;
        }
        tm = g_snes.regs[PPU_TM];
        ts = g_snes.regs[PPU_TS];
        tmw = g_snes.regs[PPU_TMW];
        tsw = g_snes.regs[PPU_TSW];
        cgwsel = g_snes.regs[PPU_CGWSEL];
        cgadsub = g_snes.regs[PPU_CGADSUB];
        cw_sel = layer_sel(L_BACK);
        cw_logic = layer_logic(L_BACK);
        fixed = (u16)(g_snes.fixed_r | (g_snes.fixed_g << 5) | (g_snes.fixed_b << 10));

        wl1 = g_snes.regs[PPU_WH0];
        wr1 = g_snes.regs[PPU_WH1];
        wl2 = g_snes.regs[PPU_WH2];
        wr2 = g_snes.regs[PPU_WH3];
        for (x = 0; x < W; x++) {
            win1[x] = (u8)(x >= wl1 && x <= wr1);
            win2[x] = (u8)(x >= wl2 && x <= wr2);
        }

        for (x = 0; x < 3; x++) {
            if ((tm | ts) & (1 << x)) {
                draw_bg((u8)x, (u8)(y + 1));
            } else {
                memset(bg_line[x].opaque, 0, sizeof bg_line[x].opaque);
            }
        }
        if ((tm | ts) & OBJ_ENABLE) {
            draw_sprites((u8)(y + 1));
        } else {
            memset(obj_line.opaque, 0, sizeof obj_line.opaque);
        }

        for (x = 0; x < W; x++) {
            u8 mc, sc, cw = in_area(cw_sel, cw_logic, x);
            u8 ml = front(x, tm, tmw, &mc);
            u16 color = g_snes.cgram[mc];
            u8 clipped = region((u8)(cgwsel >> 6), cw);
            u8 math_on = (u8)!region((u8)(cgwsel >> 4), cw);
            u8 layer_math = (u8)(ml == L_BACK ? (cgadsub & CM_MSCR_BACK) != 0
                               : ml == L_OBJ ? (cgadsub & CM_MSCR_PAL47) && obj_pal[x] >= 4
                               : (cgadsub >> ml) & 1);
            if (clipped) {
                color = 0;
            }
            if (math_on && layer_math) {
                u16 sub = fixed;
                u8 half = (u8)((cgadsub & CM_DIV2_DIVIDE) && !clipped);
                if (cgwsel & CM_SUBBGOBJ_ENABLE) {
                    u8 sl = front(x, ts, tsw, &sc);
                    if (sl == L_BACK) {
                        half = 0;                    /* empty sub screen: the fixed colour, not halved */
                    } else {
                        sub = g_snes.cgram[sc];
                    }
                }
                color = add_sub(color, sub, (u8)((cgadsub & CM_SUB_MAINSUB) != 0), half);
            }
            row[x] = brighten(color, (u8)(inidisp & 0x0F));
        }
    }
}
