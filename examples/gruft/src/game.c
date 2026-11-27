/*
 * Gruft: the screen, the joypad and the course of the game.
 *
 * One background layer of 8 x 8 tiles: two lines of status at the top, below them a window of
 * 32 x 26 cells on the dungeon around the hero. After every turn the cells that changed are drawn
 * into a map in RAM (draw_view), which game_vblank copies into VRAM. What the hero sees now is
 * drawn in palette 0, with the creatures and things; what it has seen before in the dim palette
 * 1, without them; the rest stays dark.
 *
 * VRAM (16-bit words): the tiles from 0x0000, the map at 0x1000. CGRAM: palette 0 the sheet's,
 * palette 1 the dim one.
 *
 * It uses the console API's core: the tick changes the game's own memory, game_vblank copies
 * what changed into VRAM.
 */
#include "gruft.h"
#include "dungeon_gfx.h"
#include "pal_dim.h"

#define VRAM_TILES 0x0000
#define VRAM_MAP   0x1000
#define TOP        2               /* the status lines */
#define VIEW_W     32
#define VIEW_H     26
#define REPEAT     8               /* a held direction takes a step every 8 frames */
#define DIM        ((uint16_t)(1 << 10))
#define TILE_HERO  4
#define TILE_FONT  32

enum { ST_TITLE, ST_PLAY, ST_OVER };

static const uint16_t CELL_TILE[4] = { 0, 2, 1, 3 };            /* rock, wall, floor, stairs */
static const uint16_t DIM_TILE[4] = { 0, 2 | DIM, 1 | DIM, 3 | DIM }; /* the same, remembered */
static const uint16_t THING_TILE[6] = { 0, 5, 6, 7, 8, 9 };
static const char FONT[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-:.";

static uint16_t screen[28 * 32];   /* the map in RAM, copied in the vertical blank when changed */
static uint8_t screen_dirty;
static uint8_t state;
static uint16_t frames;            /* since the start: the seed of the first dungeon */
static uint16_t last_pad;
static uint8_t held;               /* frames the direction has been held */

/* --- text ------------------------------------------------------------------------------------ */
static void text(uint8_t x, uint8_t y, const char *s)
{
    while (*s != 0 && x < 32) {
        uint8_t i = 0;
        while (FONT[i] != 0 && FONT[i] != *s) {
            i++;
        }
        screen[y * 32 + x] = (uint16_t)(TILE_FONT + (FONT[i] != 0 ? i : 0));
        x++;
        s++;
    }
    screen_dirty = 1;
}

static void number(uint8_t x, uint8_t y, uint16_t value, uint8_t digits)
{
    char s[6];
    uint8_t i;

    s[digits] = 0;
    for (i = digits; i > 0; i--) {
        s[i - 1] = (char)('0' + value % 10);
        value /= 10;
    }
    text(x, y, s);
}

static void clear_lines(uint8_t from, uint8_t to)
{
    uint16_t i;

    for (i = (uint16_t)from * 32; i < (uint16_t)to * 32; i++) {
        screen[i] = 0;
    }
    screen_dirty = 1;
}

/* --- the dungeon on the screen --------------------------------------------------------------- */
static uint8_t cam_x, cam_y;       /* the dungeon's cell in the window's top left corner */
static uint8_t drawn_depth;        /* the level the window shows */

static uint8_t clamp(int16_t v, uint8_t max)
{
    return v < 0 ? 0 : v > max ? max : (uint8_t)v;
}

/* The cells of a part of the window as the hero remembers them: seen ones dim, the rest dark.
 * Each line reads two rows of the dungeon through pointers; those rows may lie in bank $7F,
 * which the program does not need to know. */
static void draw_seen(uint8_t x0, uint8_t y0, uint8_t w, uint8_t h)
{
    uint8_t line, col;

    for (line = 0; line < h; line++) {
        const uint8_t *cell = &tiles[y0 + line][x0];
        const uint8_t *was_seen = &seen[y0 + line][x0];
        uint16_t *out = &screen[(y0 + line - cam_y + TOP) * 32 + (x0 - cam_x)];
        for (col = 0; col < w; col++) {
            out[col] = was_seen[col] != 0 ? DIM_TILE[cell[col]] : 0;
        }
    }
}

/* What the hero sees now, lit, with the creatures and things on it, and the hero. */
static void draw_sight(void)
{
    uint8_t line, col;

    for (line = 0; line <= 2 * SIGHT; line++) {
        uint8_t y = (uint8_t)(hero_y - SIGHT + line);
        const uint8_t *cell = &tiles[y][hero_x - SIGHT];
        const uint8_t *who = &occupant[y][hero_x - SIGHT];
        uint16_t *out = &screen[(y - cam_y + TOP) * 32 + (hero_x - SIGHT - cam_x)];
        for (col = 0; col <= 2 * SIGHT; col++) {
            out[col] = who[col] != 0 ? THING_TILE[things[who[col] - 1].kind] : CELL_TILE[cell[col]];
        }
    }
    screen[(hero_y - cam_y + TOP) * 32 + (hero_x - cam_x)] = TILE_HERO;
}

/* The window after a turn. While the hero stays in its middle, only the cells around the hero
 * change: what it saw a step before turns dim, what it sees now is lit. When it leaves the
 * middle, or on a new level, the window moves to it and is drawn whole. */
static void draw_view(void)
{
    if (drawn_depth != depth || hero_x < cam_x + 8 || hero_x > cam_x + 23 || hero_y < cam_y + 7 || hero_y > cam_y + 18) {
        cam_x = clamp((int16_t)(hero_x - VIEW_W / 2), MAP_W - VIEW_W);
        cam_y = clamp((int16_t)(hero_y - VIEW_H / 2), MAP_H - VIEW_H);
        drawn_depth = depth;
        draw_seen(cam_x, cam_y, VIEW_W, VIEW_H);
    } else {
        /* the hero's sight one step before lies within one cell around its sight now */
        draw_seen((uint8_t)(hero_x - SIGHT - 1), (uint8_t)(hero_y - SIGHT - 1), 2 * SIGHT + 3, 2 * SIGHT + 3);
    }
    draw_sight();
    screen_dirty = 1;
}

static void status(void)
{
    clear_lines(0, TOP);
    text(1, 0, "HP");
    number(4, 0, hero_hp, 2);
    text(9, 0, "GOLD");
    number(14, 0, gold, 5);
    text(22, 0, "DEPTH");
    number(28, 0, depth, 2);
    if (message != 0) {
        text(1, 1, message);
    }
}

/* --- the course of the game ------------------------------------------------------------------ */
static void title(void)
{
    state = ST_TITLE;
    clear_lines(0, 28);
    text(13, 10, "GRUFT");
    text(6, 13, "A DUNGEON OF 160 X 128");
    text(10, 17, "PRESS START");
}

static void start(void)
{
    hero_hp = HP_MAX;
    gold = 0;
    depth = 1;
    drawn_depth = 0;
    dungeon_make(frames);
    message = "FIND THE STAIRS DOWN";
    state = ST_PLAY;
    draw_view();
    status();
}

/* The direction the joypad points to, or 0xFF; a held one repeats every REPEAT frames. */
static uint8_t direction(uint16_t pad)
{
    uint8_t dir = 0xFF;

    if (pad & SDEVKIT_KEY_RIGHT) {
        dir = 0;
    } else if (pad & SDEVKIT_KEY_UP) {
        dir = 1;
    } else if (pad & SDEVKIT_KEY_LEFT) {
        dir = 2;
    } else if (pad & SDEVKIT_KEY_DOWN) {
        dir = 3;
    }
    if (dir == 0xFF) {
        held = 0;
        return dir;
    }
    if (held++ % REPEAT != 0) {
        return 0xFF;
    }
    return dir;
}

void game_init(uint16_t seed)
{
    (void)seed;
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1);
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE);
    sdevkit_reg_write(SDEVKIT_PPU_BG12NBA, SDEVKIT_BGNBA(VRAM_TILES, 0));
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(VRAM_MAP, SDEVKIT_SC_32x32));
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, 0x3FF);       /* the map's first line on the screen's first */
    sdevkit_vram_copy(VRAM_TILES, dungeon_gfx_tiles, DUNGEON_GFX_TILES_SIZE);
    sdevkit_cgram_copy(0, dungeon_gfx_pal, DUNGEON_GFX_PAL_SIZE);
    sdevkit_cgram_copy(16, pal_dim_pal, PAL_DIM_PAL_SIZE);
    title();
    sdevkit_vram_copy(VRAM_MAP, screen, sizeof screen);
    screen_dirty = 0;
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t pressed = (uint16_t)(pad[0] & ~last_pad);
    uint8_t dir;

    last_pad = pad[0];
    frames++;
    switch (state) {
    case ST_TITLE:
        if (pressed & SDEVKIT_KEY_START) {
            start();
        }
        break;
    case ST_PLAY:
        dir = direction(pad[0]);
        if (dir != 0xFF && hero_turn(dir)) {
            draw_view();
            status();
            if (hero_hp == 0) {
                state = ST_OVER;
                text(1, 1, "YOU DIED. PRESS START          ");
            }
        }
        break;
    default:                       /* ST_OVER */
        if (pressed & SDEVKIT_KEY_START) {
            title();
        }
        break;
    }
    /* the state for the checks, which compare the SNES and the PC after every tick
     * (sdevkit_dbg_hash): the screen, what the hero has seen, the creatures and things */
    sdevkit_dbg_hash(screen, sizeof screen);
    sdevkit_dbg_hash(seen, sizeof seen);
    sdevkit_dbg_hash(things, sizeof things);
}

void game_vblank(void)
{
    if (screen_dirty) {
        sdevkit_vram_copy(VRAM_MAP, screen, sizeof screen);
        screen_dirty = 0;
    }
}
