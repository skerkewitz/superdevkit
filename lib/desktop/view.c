/*
 * The desktop's debug view (ADR-6F3CK03Z): F1 opens a panel at the window's right edge, beside the
 * picture. Four tabs:
 *
 *   Game      the run (pause, a single tick, slow motion, fast) and what the game registered with
 *             sdevkit_dbg_toggle, _range, _color and _action, to change while it runs; the boxes
 *             of sdevkit_dbg_box over the picture (a switch); lost bytes, undrawn and unheard writes
 *   Palettes  the 256 colours of CGRAM; a click picks the palette the Tiles tab draws in
 *   Tiles     VRAM as 4 bpp tiles of 8 x 8, 16 a row, in that palette; the mouse wheel scrolls
 *   Sprites   the 128 entries of the sprite table that are on the screen; the one under the mouse
 *             is outlined on the picture
 *
 * The panel is drawn with raylib alone (no GUI library): buttons, check boxes and sliders are a
 * few rectangles each, handled with the mouse in the frame they are drawn (immediate mode). This
 * file is host code for the desktop's C compiler, not for dampf.
 */
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "rachael.h"
#include "view.h"

#define ROW   24
#define TEXT_SIZE 16

static const Color BG = { 22, 23, 28, 245 };
static const Color FG = { 225, 225, 230, 255 };
static const Color DIM = { 140, 140, 150, 255 };
static const Color BTN = { 48, 50, 60, 255 };
static const Color HOVER = { 70, 74, 90, 255 };
static const Color ACCENT = { 125, 155, 240, 255 };
static const Color LINE = { 80, 82, 95, 255 };
/* The colours of a box's kind, 0..7. */
static const Color KIND[8] = {
    { 255, 70, 70, 255 }, { 70, 230, 90, 255 }, { 90, 150, 255, 255 }, { 255, 225, 50, 255 },
    { 240, 90, 240, 255 }, { 80, 225, 240, 255 }, { 255, 160, 50, 255 }, { 255, 255, 255, 255 },
};
static const char *TABS[4] = { "Game", "Palettes", "Tiles", "Sprites" };

static int open_, tab;
static int boxes_on = 1;
static int pal_row;                   /* the CGRAM row (16 colours) the tiles are drawn in */
static int game_scroll, tile_scroll, sprite_scroll;
static int hover_sprite = -1;
static Texture2D tiles_tex;
static int tiles_loaded;
static unsigned char tiles_rgba[128 * 256 * 4];   /* 16 x 32 tiles */

/* The mouse in this frame. */
static Vector2 mouse;
static int clicked, held;
static float wheel;
static const char *changed;

int sdevkit__debug_is_open(void)
{
    return open_;
}

void sdevkit__debug_set_open(int open, int t)
{
    open_ = open;
    tab = t >= 0 && t < 4 ? t : 0;
}

void sdevkit__debug_keys(void)
{
    if (IsKeyPressed(KEY_F1)) {
        open_ = !open_;
    }
}

void sdevkit__debug_unload(void)
{
    if (tiles_loaded) {
        UnloadTexture(tiles_tex);
        tiles_loaded = 0;
    }
}

static int hit(Rectangle r)
{
    return CheckCollisionPointRec(mouse, r);
}

static void text(const char *s, float x, float y, Color c)
{
    DrawText(s, (int)x, (int)y, TEXT_SIZE, c);
}

static int button(float x, float y, float w, const char *label, int on)
{
    Rectangle r = { x, y, w, ROW - 3 };
    DrawRectangleRec(r, on ? ACCENT : hit(r) ? HOVER : BTN);
    DrawRectangleLinesEx(r, 1, LINE);
    DrawText(label, (int)(x + (w - (float)MeasureText(label, TEXT_SIZE)) / 2), (int)y + 3, TEXT_SIZE, on ? BLACK : FG);
    return clicked && hit(r);
}

static int checkbox(float x, float y, float w, const char *label, int on)
{
    Rectangle r = { x, y, w, ROW - 3 };
    Rectangle box = { x + 2, y + 3, 15, 15 };
    if (hit(r)) {
        DrawRectangleRec(r, HOVER);
    }
    DrawRectangleLinesEx(box, 1, FG);
    if (on) {
        DrawRectangle((int)box.x + 3, (int)box.y + 3, 9, 9, ACCENT);
    }
    text(label, x + 24, y + 3, FG);
    return clicked && hit(r);
}

/* A horizontal bar for a value in lo..hi; dragging on it sets the value. Returns 1 and the new
 * value in *out when it changed. */
static int slider(float x, float y, float w, int value, int lo, int hi, int *out)
{
    Rectangle r = { x, y, w, ROW - 3 };
    float f = hi > lo ? (float)(value - lo) / (float)(hi - lo) : 0;
    DrawRectangle((int)x, (int)y + 7, (int)w, 8, BTN);
    DrawRectangle((int)x, (int)y + 7, (int)(w * f), 8, ACCENT);
    DrawRectangle((int)(x + w * f) - 2, (int)y + 3, 5, 16, FG);
    if (held && hit(r)) {
        float t = (mouse.x - x) / w;
        int v = lo + (int)(t * (float)(hi - lo) + 0.5f);
        v = v < lo ? lo : v > hi ? hi : v;
        if (v != value) {
            *out = v;
            return 1;
        }
    }
    return 0;
}

/* --- the tabs ---------------------------------------------------------------------------------- */
static float game_tab(float x, float y, float w, float bottom)
{
    char s[96];
    int i;
    float y0 = y - (float)game_scroll;

    BeginScissorMode((int)x, (int)y, (int)w, (int)(bottom - y));
    y = y0;
    if (checkbox(x, y, w, "boxes over the picture", boxes_on)) {
        boxes_on = !boxes_on;
    }
    y += ROW;
    if (sdevkit__dbg_count == 0) {
        text("The game registers nothing to change.", x, y + 4, DIM);
        text("(sdevkit_dbg_toggle, _range, _color, _action,", x, y + 4 + ROW, DIM);
        text(" _text, _value in game_init)", x, y + 4 + 2 * ROW, DIM);
        y += 3 * ROW;
    }
    for (i = 0; i < sdevkit__dbg_count; i++) {
        sdevkit__dbg_entry *e = &sdevkit__dbg_entries[i];
        switch (e->kind) {
        case SDEVKIT__DBG_TOGGLE: {
            uint8_t *f = (uint8_t *)e->p;
            if (checkbox(x, y, w, e->name, *f != 0)) {
                *f = (uint8_t)(*f == 0);
                changed = e->name;
            }
            y += ROW;
            break;
        }
        case SDEVKIT__DBG_RANGE: {
            int16_t *v = (int16_t *)e->p;
            int nv;
            snprintf(s, sizeof s, "%s  %d", e->name, *v);
            text(s, x + 2, y + 3, FG);
            y += ROW;
            if (button(x, y, 26, "-", 0) && *v > e->lo) {
                (*v)--;
                changed = e->name;
            }
            if (button(x + w - 26, y, 26, "+", 0) && *v < e->hi) {
                (*v)++;
                changed = e->name;
            }
            if (slider(x + 34, y, w - 68, *v, e->lo, e->hi, &nv)) {
                *v = (int16_t)nv;
                changed = e->name;
            }
            y += ROW;
            break;
        }
        case SDEVKIT__DBG_COLOR: {
            uint16_t *c = (uint16_t *)e->p;
            int part, nv;
            int r = *c & 31, g = (*c >> 5) & 31, b = (*c >> 10) & 31;
            snprintf(s, sizeof s, "%s  $%04X", e->name, *c);
            text(s, x + 2, y + 3, FG);
            DrawRectangle((int)(x + w - 40), (int)y + 2, 38, 18,
                          (Color){ (unsigned char)(r << 3 | r >> 2), (unsigned char)(g << 3 | g >> 2),
                                   (unsigned char)(b << 3 | b >> 2), 255 });
            y += ROW;
            for (part = 0; part < 3; part++) {
                int value = (*c >> (5 * part)) & 31;
                text(part == 0 ? "R" : part == 1 ? "G" : "B", x + 8, y + 3, DIM);
                if (slider(x + 30, y, w - 40, value, 0, 31, &nv)) {
                    *c = (uint16_t)((*c & ~(31u << (5 * part))) | (unsigned)nv << (5 * part));
                    changed = e->name;
                }
                y += ROW;
            }
            break;
        }
        case SDEVKIT__DBG_TEXT:
            snprintf(s, sizeof s, "%s: %s", e->name, (const char *)e->p);
            text(s, x + 2, y + 3, FG);
            y += ROW;
            break;
        case SDEVKIT__DBG_VALUE: {
            uint16_t value = *(const uint16_t *)e->p;
            snprintf(s, sizeof s, "%s  %u  $%04X", e->name, value, value);
            text(s, x + 2, y + 3, FG);
            y += ROW;
            break;
        }
        default: {                                    /* SDEVKIT__DBG_ACTION */
            uint8_t *p = (uint8_t *)e->p;
            if (button(x, y, w, e->name, *p != 0)) {
                *p = 1;
                changed = e->name;
            }
            y += ROW;
            break;
        }
        }
    }
    EndScissorMode();
    if (hit((Rectangle){ x, y0 + (float)game_scroll, w, bottom - y0 - (float)game_scroll }) && wheel != 0) {
        float content = y - y0, room = bottom - (y0 + (float)game_scroll);
        game_scroll -= (int)(wheel * ROW * 2);
        if (game_scroll > (int)(content - room)) {
            game_scroll = (int)(content - room);
        }
        if (game_scroll < 0) {
            game_scroll = 0;
        }
    }
    return y;
}

static void palettes_tab(float x, float y)
{
    const uint16_t *cg = sdevkit__cgram();
    char s[96];
    int i, hover = -1;
    const float cell = 19;

    for (i = 0; i < 256; i++) {
        uint16_t c = cg[i];
        int r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
        Rectangle cr = { x + (float)(i % 16) * cell, y + (float)(i / 16) * cell, cell - 1, cell - 1 };
        DrawRectangleRec(cr, (Color){ (unsigned char)(r << 3 | r >> 2), (unsigned char)(g << 3 | g >> 2),
                                      (unsigned char)(b << 3 | b >> 2), 255 });
        if (hit(cr)) {
            hover = i;
            if (clicked) {
                pal_row = i / 16;
            }
        }
    }
    DrawRectangleLinesEx((Rectangle){ x - 1, y + (float)pal_row * cell - 1, 16 * cell + 1, cell + 1 }, 2, ACCENT);
    y += 16 * cell + 8;
    if (hover >= 0) {
        uint16_t c = cg[hover];
        snprintf(s, sizeof s, "colour %d: $%04X  r %d g %d b %d", hover, c, c & 31, (c >> 5) & 31, (c >> 10) & 31);
        text(s, x, y, FG);
    } else {
        snprintf(s, sizeof s, "palette %d for the tiles (click a row)", pal_row);
        text(s, x, y, DIM);
    }
}

/* VRAM's 4 bpp tile t: rows of two words, planes 0 and 1 in word r, planes 2 and 3 in word r + 8. */
static void draw_tile(int t, int px, int py)
{
    const uint16_t *vram = sdevkit__rachael.vram;
    const uint16_t *cg = sdevkit__cgram();
    int r, c;

    for (r = 0; r < 8; r++) {
        uint16_t lo = vram[(t * 16 + r) & 0x7FFF], hi = vram[(t * 16 + r + 8) & 0x7FFF];
        for (c = 0; c < 8; c++) {
            int bit = 7 - c;
            int v = ((lo >> bit) & 1) | ((lo >> (bit + 8)) & 1) << 1 | ((hi >> bit) & 1) << 2 | ((hi >> (bit + 8)) & 1) << 3;
            unsigned char *o = tiles_rgba + ((py + r) * 128 + px + c) * 4;
            if (v == 0) {
                unsigned char d = ((px + c) / 4 + (py + r) / 4) % 2 ? 30 : 42;
                o[0] = o[1] = o[2] = d;
            } else {
                uint16_t col = cg[pal_row * 16 + v];
                int cr = col & 31, cgc = (col >> 5) & 31, cb = (col >> 10) & 31;
                o[0] = (unsigned char)(cr << 3 | cr >> 2);
                o[1] = (unsigned char)(cgc << 3 | cgc >> 2);
                o[2] = (unsigned char)(cb << 3 | cb >> 2);
            }
            o[3] = 255;
        }
    }
}

static void tiles_tab(float x, float y, float bottom)
{
    char s[96];
    int i;
    float scale = 2;
    Rectangle area;

    if (!tiles_loaded) {
        Image img = { tiles_rgba, 128, 256, 1, PIXELFORMAT_UNCOMPRESSED_R8G8B8A8 };
        tiles_tex = LoadTextureFromImage(img);
        tiles_loaded = 1;
    }
    for (i = 0; i < 16 * 32; i++) {
        draw_tile(tile_scroll * 16 + i, (i % 16) * 8, (i / 16) * 8);
    }
    UpdateTexture(tiles_tex, tiles_rgba);
    snprintf(s, sizeof s, "tiles %d-%d, palette %d (wheel scrolls)", tile_scroll * 16, tile_scroll * 16 + 511, pal_row);
    text(s, x, y, DIM);
    y += ROW;
    area = (Rectangle){ x, y, 128 * scale, bottom - y };
    BeginScissorMode((int)area.x, (int)area.y, (int)area.width, (int)area.height);
    DrawTextureEx(tiles_tex, (Vector2){ x, y }, 0, scale, WHITE);
    EndScissorMode();
    if (hit(area)) {
        int col = (int)((mouse.x - x) / (8 * scale)), row = (int)((mouse.y - y) / (8 * scale));
        int t = (tile_scroll + row) * 16 + col;
        if (row < 32 && t < 2048) {
            snprintf(s, sizeof s, "tile %d, VRAM word $%04X", t, t * 16);
            text(s, x, bottom - ROW + 4 > y ? bottom - ROW + 4 : y, FG);
        }
        if (wheel != 0) {
            tile_scroll -= (int)wheel * 2;
            tile_scroll = tile_scroll < 0 ? 0 : tile_scroll > 128 - 32 ? 128 - 32 : tile_scroll;
        }
    }
}

/* A sprite's size from OBSEL ($2101, bits 5-7) and its size bit: small or large, width and height. */
static void sprite_size(int large, int *w, int *h)
{
    static const unsigned char SIZES[8][4] = {
        { 8, 8, 16, 16 }, { 8, 8, 32, 32 }, { 8, 8, 64, 64 }, { 16, 16, 32, 32 },
        { 16, 16, 64, 64 }, { 32, 32, 64, 64 }, { 16, 32, 32, 64 }, { 16, 32, 32, 32 },
    };
    int s = sdevkit__reg(0x01) >> 5;
    *w = SIZES[s][large ? 2 : 0];
    *h = SIZES[s][large ? 3 : 1];
}

/* The screen position and size of sprite i, as the table copied at the last vertical blank has it. */
static int sprite(int i, int *x, int *y, int *w, int *h)
{
    const uint8_t *oam = sdevkit__oam();
    uint8_t high = (uint8_t)(oam[512 + i / 4] >> ((i % 4) * 2));
    *x = oam[i * 4] | (high & 1) << 8;
    if (*x >= 256) {
        *x -= 512;
    }
    *y = oam[i * 4 + 1];
    sprite_size(high & 2, w, h);
    return *y < SDEVKIT__H || *y + *h > 256;            /* on the screen, or wrapping from the bottom */
}

static void sprites_tab(float x, float y, float bottom)
{
    const uint8_t *oam = sdevkit__oam();
    char s[96];
    int i, shown = 0, listed = 0;

    hover_sprite = -1;
    text("#    x    y  tile pal pri size", x, y, DIM);
    y += ROW;
    for (i = 0; i < 128; i++) {
        int sx, sy, w, h;
        if (!sprite(i, &sx, &sy, &w, &h)) {
            continue;
        }
        shown++;
        if (listed++ < sprite_scroll) {
            continue;
        }
        if (y + ROW > bottom) {
            continue;
        }
        {
            Rectangle r = { x, y, SDEVKIT__PANEL_W - 24, ROW - 4 };
            uint8_t a = oam[i * 4 + 3];
            if (hit(r)) {
                DrawRectangleRec(r, HOVER);
                hover_sprite = i;
            }
            snprintf(s, sizeof s, "%-3d %4d %4d  %4d  %d   %d  %dx%d%s%s", i, sx, sy > 239 ? sy - 256 : sy,
                     oam[i * 4 + 2] | (a & 1) << 8, (a >> 1) & 7, (a >> 4) & 3, w, h,
                     a & 0x40 ? " h" : "", a & 0x80 ? " v" : "");
            text(s, x, y + 2, FG);
            y += ROW - 4;
        }
    }
    if (shown == 0) {
        text("No sprite on the screen.", x, y, DIM);
    }
    if (hit((Rectangle){ x, y, SDEVKIT__PANEL_W, bottom - y }) || hover_sprite >= 0) {
        if (wheel != 0) {
            sprite_scroll -= (int)wheel * 3;
            sprite_scroll = sprite_scroll < 0 ? 0 : sprite_scroll > shown - 1 ? (shown > 0 ? shown - 1 : 0) : sprite_scroll;
        }
    }
}

/* --- the panel and the picture's overlays ------------------------------------------------------- */
/* An arrow from a by (dx, dy) window pixels, with a head of three SNES pixels. */
static void arrow(Vector2 a, float dx, float dy, float scale, float thick, Color c)
{
    float len = sqrtf(dx * dx + dy * dy), head = 3 * scale, ux, uy;
    Vector2 tip = { a.x + dx, a.y + dy };

    if (len == 0) {
        DrawCircleV(a, thick, c);
        return;
    }
    ux = dx / len;
    uy = dy / len;
    DrawLineEx(a, tip, thick, c);
    DrawLineEx(tip, (Vector2){ tip.x - head * (ux + uy * 0.6f), tip.y - head * (uy - ux * 0.6f) }, thick, c);
    DrawLineEx(tip, (Vector2){ tip.x - head * (ux - uy * 0.6f), tip.y - head * (uy + ux * 0.6f) }, thick, c);
}

const char *sdevkit__debug_draw(Rectangle dst, float scale, float window_w, float window_h, sdevkit__run_state *run)
{
    float x, y, w, bottom;
    char s[96];
    int i;

    if (!open_) {
        return NULL;
    }
    mouse = GetMousePosition();
    clicked = IsMouseButtonPressed(MOUSE_BUTTON_LEFT);
    held = IsMouseButtonDown(MOUSE_BUTTON_LEFT);
    wheel = GetMouseWheelMove();
    changed = NULL;

    if (boxes_on) {
        for (i = 0; i < sdevkit__dbg_nboxes; i++) {
            const sdevkit__dbg_rect *b = &sdevkit__dbg_boxes[i];
            Rectangle r = { dst.x + (float)b->x * scale, dst.y + (float)b->y * scale,
                            (float)b->w * scale, (float)b->h * scale };
            float thick = scale > 2 ? 2.0f : 1.0f;
            if (b->style == SDEVKIT__DBG_FILLED) {
                DrawRectangleRec(r, Fade(KIND[b->kind & 7], 0.45f));
            } else if (b->style == SDEVKIT__DBG_ARROW) {
                arrow((Vector2){ r.x, r.y }, (float)b->dx * scale, (float)b->dy * scale, scale, thick, KIND[b->kind & 7]);
            } else {
                DrawRectangleLinesEx(r, thick, KIND[b->kind & 7]);
            }
        }
    }

    x = window_w - SDEVKIT__PANEL_W;
    DrawRectangle((int)x, 0, SDEVKIT__PANEL_W, (int)window_h, BG);
    DrawLine((int)x, 0, (int)x, (int)window_h, LINE);
    x += 12;
    w = SDEVKIT__PANEL_W - 24;
    y = 10;
    bottom = window_h - 10;

    snprintf(s, sizeof s, "tick %ld", run->tick);
    text(s, x, y + 3, FG);
    {
        float bx = x + 110, bw = (w - 110) / 4;
        if (button(bx, y, bw - 3, run->paused ? "Go" : "Pause", run->paused)) {
            run->paused = !run->paused;
        }
        if (button(bx + bw, y, bw - 3, "Step", 0)) {
            run->paused = 1;
            run->step = 1;
        }
        if (button(bx + 2 * bw, y, bw - 3, "Slow", run->slow)) {
            run->slow = !run->slow;
            run->fast = 0;
        }
        if (button(bx + 3 * bw, y, bw - 3, "Fast", run->fast)) {
            run->fast = !run->fast;
            run->slow = 0;
        }
    }
    y += ROW + 6;
    for (i = 0; i < 4; i++) {
        float tw = w / 4;
        if (button(x + (float)i * tw, y, tw - 3, TABS[i], tab == i)) {
            tab = i;
        }
    }
    y += ROW + 8;

    if (tab == 0) {
        float end;
        snprintf(s, sizeof s, "lost %u  undrawn %u  unheard %u", run->lost, sdevkit__rachael.undrawn,
                 sdevkit__sound.unheard);
        text(s, x, bottom - 18, run->lost || sdevkit__rachael.undrawn || sdevkit__sound.unheard ? KIND[0] : DIM);
        end = game_tab(x, y, w, bottom - 26);
        (void)end;
    } else if (tab == 1) {
        palettes_tab(x, y);
    } else if (tab == 2) {
        tiles_tab(x, y, bottom);
    } else {
        sprites_tab(x, y, bottom);
        if (hover_sprite >= 0) {
            int sx, sy, sw, sh;
            sprite(hover_sprite, &sx, &sy, &sw, &sh);
            if (sy > 239) {
                sy -= 256;
            }
            DrawRectangleLinesEx((Rectangle){ dst.x + (float)sx * scale, dst.y + (float)sy * scale,
                                              (float)sw * scale, (float)sh * scale }, 2, ACCENT);
        }
    }
    return changed;
}
