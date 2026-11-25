/*
 * CatchMe: the course of the game, and the frame rhythm of superdevkit (sdevkit/loop.h).
 *
 *   game_init     once, with the screen off: graphics and palettes into VRAM and CGRAM at once
 *   game_tick     once per frame: the game logic, the sprite table, the map, and the copies of
 *                 what changed (the map, the walls' colours) queued for the vertical blank
 *   game_vblank   the sprites' slots into OAM, and the optional layers' commit (sdevkit/easy.h)
 *
 * It mixes the layers and the core: the registers, the copies and the buttons pressed go through
 * the layers, the six sprites keep fixed slots in a table of CatchMe's own (actors.c), and
 * game_vblank copies just those slots.
 *
 * VRAM: the background tiles from word 0x0000, the background map at 0x1000 (32 x 32 entries),
 * the sprite tiles at 0x4000. CGRAM: background palette 0 (colours 0..15), sprite palettes 0..6
 * (colours 128..239): the mouse, the four cats, the scared cats, the mouse after a chili.
 *
 * The high score is kept in the cartridge's save RAM (sdevkit_sram, superdevkit.toml: sram = 8).
 * The chase music plays from a level's start to the game's end (on the desktop; on the SNES the
 * calls do nothing yet).
 * On the desktop, the debug view (F1) can freeze the cats, clear a level and pick the level a new
 * game starts with, and shows the actors' boxes; on the SNES these calls are empty.
 */
#include "catchme.h"
#include "maze_gfx.h"
#include "sprites_gfx.h"
#include "pal_ginger.h"
#include "pal_black.h"
#include "pal_white.h"
#include "pal_brown.h"
#include "pal_scared.h"
#include "pal_hot.h"
#include "music.h"

enum { ST_TITLE, ST_READY, ST_PLAY, ST_DYING, ST_CLEAR, ST_OVER };

#define VRAM_BG_TILES  0x0000
#define VRAM_BG_MAP    0x1000
#define VRAM_SPRITES   0x4000
#define MAP_BYTES      (32 * 32 * 2)

#define SPRITE_CHEESE  76
#define PAL_HOT        6
#define ID_BONUS       20

uint8_t level;

static uint8_t state;
static uint16_t timer;
static uint8_t frame;
static uint16_t rng = 0xACE1;
static uint32_t score;
static uint32_t best = 5000;
static uint8_t lives;
static uint8_t extra_given;
static uint8_t caught_in_row;          /* cats caught on one chili: 200, 400, 800, 1600 */
static uint8_t eaten;                  /* crumbs eaten in this level, for the cheese */
static uint16_t cheese_left;           /* frames the cheese stays */
static uint8_t blink, blink_dirty;     /* the walls blink after a level, queued for the vertical blank */

/* A 16-bit linear feedback shift register: the same seed gives the same game. */
uint16_t random16(void)
{
    uint16_t bit = (uint16_t)(((rng >> 0) ^ (rng >> 2) ^ (rng >> 3) ^ (rng >> 5)) & 1);
    rng = (uint16_t)((rng >> 1) | (bit << 15));
    return rng;
}

/* --- the high score in save RAM -------------------------------------------------------------- */
/* 8 bytes: 'C', 'M', the score (32 bits, low byte first), 0, and a check byte, the sum of the
 * seven before it. Anything else (a new cartridge, whose RAM holds what it holds) means no high
 * score yet, and the game starts with its own. */
#define SAVE_BYTES 8

static uint8_t save_sum(const uint8_t *s)
{
    uint8_t sum = 0;
    uint8_t i;

    for (i = 0; i < SAVE_BYTES - 1; i++) {
        sum = (uint8_t)(sum + s[i]);
    }
    return sum;
}

static void best_load(void)
{
    const uint8_t *s = sdevkit_sram();

    if (s[0] == 'C' && s[1] == 'M' && s[SAVE_BYTES - 1] == save_sum(s)) {
        memcpy(&best, s + 2, 4);            /* little endian on the SNES and the PC alike */
    }
}

static void best_save(void)
{
    uint8_t *s = sdevkit_sram();

    s[0] = 'C';
    s[1] = 'M';
    memcpy(s + 2, &best, 4);
    s[6] = 0;
    s[SAVE_BYTES - 1] = save_sum(s);
}

/* --- the debug view (the desktop's; empty on the SNES) ---------------------------------------- */
static uint8_t dbg_cats_frozen;        /* the cats stand still */
static uint8_t dbg_clear_level;        /* the view's button: the level counts as eaten */
static int16_t dbg_start_level = 1;    /* the level a new game starts with */

static void debug_register(void)
{
    sdevkit_dbg_toggle("cats frozen", &dbg_cats_frozen);
    sdevkit_dbg_action("clear the level", &dbg_clear_level);
    sdevkit_dbg_range("start level", &dbg_start_level, 1, 20);
}

/* The mouse's box in yellow, the cats' in red, blue while they are scared. */
static void debug_boxes(void)
{
    uint8_t i;

    sdevkit_dbg_box(mouse.x, (int16_t)(mouse.y + MAZE_TOP), 16, 16, 3);
    for (i = 0; i < CATS; i++) {
        const Actor *c = &cats[i];
        if (c->mode != CAT_HOME && c->mode != CAT_EYES) {
            sdevkit_dbg_box(c->x, (int16_t)(c->y + MAZE_TOP), 16, 16, c->mode == CAT_SCARED ? 2 : 0);
        }
    }
}

/* --- the status line ------------------------------------------------------------------------- */
static void hud(void)
{
    text(1, 0, "SCORE", 0);
    number(7, 0, score, 6);
    text(16, 0, "HI", 1);
    number(19, 0, best, 6);
    text(1, 1, "LIVES", 0);
    number(7, 1, lives, 1);
    text(16, 1, "LEVEL", 0);
    number(22, 1, level, 2);
}

static void add_score(uint16_t points)
{
    score += points;
    if (!extra_given && score >= 10000) {
        extra_given = 1;
        lives++;
    }
    if (score > best) {
        best = score;
        best_save();
    }
    hud();
}

/* --- the title ------------------------------------------------------------------------------- */
static void clear_map(void)
{
    uint16_t i;

    for (i = 0; i < 32 * 32; i++) {
        bg_map[i] = 0;
    }
    bg_map_dirty = 1;
}

static void title(void)
{
    state = ST_TITLE;
    timer = 0;
    clear_map();
    text(12, 6, "CATCHME", 1);
    text(6, 9, "A SUPERDEVKIT EXAMPLE", 0);
    text(5, 12, "EAT THE CRUMBS. A CHILI", 0);
    text(5, 13, "LETS YOU CATCH THE CATS.", 0);
    text(10, 22, "HI", 1);
    number(13, 22, best, 6);
    actors_hide();
    sprite_hide(ID_BONUS);
}

/* The mouse runs across the title, the cats after it. */
static void title_parade(void)
{
    int16_t x = (int16_t)((timer * 2) % 400) - 72;
    uint8_t i;

    mouse.x = x;
    mouse.y = 136;
    mouse.dir = DIR_RIGHT;
    for (i = 0; i < CATS; i++) {
        cats[i].x = (int16_t)(x - 40 - i * 20);
        cats[i].y = 136;
        cats[i].dir = DIR_RIGHT;
        cats[i].mode = CAT_CHASE;
    }
    scared_left = 0;
    actors_draw(frame);
    text(10, 17, (timer & 32) ? "           " : "PRESS START", 1);
}

/* --- levels and lives ------------------------------------------------------------------------ */
static void ready(void)
{
    sdevkit_music_play(MUSIC_CHASE);
    state = ST_READY;
    timer = 120;
    actors_reset();
    caught_in_row = 0;
    cheese_left = 0;
    sprite_hide(ID_BONUS);
    text(13, 19, "READY!", 1);
}

static void start_level(void)
{
    maze_reset();
    clear_map();
    maze_draw();
    hud();
    eaten = 0;
    ready();
}

static void new_game(void)
{
    score = 0;
    lives = 3;
    level = (uint8_t)dbg_start_level;
    extra_given = 0;
    start_level();
}

/* --- eating and catching --------------------------------------------------------------------- */
void eat(uint8_t x, uint8_t y)
{
    uint8_t cell = maze[y][x];

    if (cell == CELL_CRUMB) {
        maze_clear_cell(x, y);
        add_score(10);
        eaten++;
        if (eaten == 40 || eaten == 80) {
            cheese_left = 540;
        }
    } else if (cell == CELL_CHILI) {
        maze_clear_cell(x, y);
        add_score(50);
        caught_in_row = 0;
        actors_scare();
    }
}

static uint8_t touches(const Actor *a, int16_t x, int16_t y)
{
    int16_t dx = (int16_t)(a->x - x);
    int16_t dy = (int16_t)(a->y - y);
    return dx > -8 && dx < 8 && dy > -8 && dy < 8;
}

/* Returns 1 when a cat caught the mouse. */
static uint8_t collisions(void)
{
    uint8_t i;

    for (i = 0; i < CATS; i++) {
        Actor *c = &cats[i];
        if (c->mode == CAT_HOME || c->mode == CAT_EYES || !touches(c, mouse.x, mouse.y)) {
            continue;
        }
        if (c->mode == CAT_SCARED) {
            add_score((uint16_t)(200u << (caught_in_row < 3 ? caught_in_row : 3)));
            caught_in_row++;
            c->mode = CAT_EYES;
        } else {
            return 1;
        }
    }
    if (cheese_left > 0) {
        cheese_left--;
        if (touches(&mouse, 7 * 16 + 8, 8 * 16)) {
            add_score((uint16_t)(level < 9 ? 100 * level : 900));
            cheese_left = 0;
        }
        if (cheese_left > 0) {
            sprite_set(ID_BONUS, 7 * 16 + 8, 8 * 16 + MAZE_TOP, SPRITE_CHEESE, PAL_HOT);
        } else {
            sprite_hide(ID_BONUS);
        }
    }
    return 0;
}

/* --- the frame rhythm ------------------------------------------------------------------------ */
void game_init(uint16_t seed)
{
    rng = (uint16_t)(rng ^ seed);
    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;
    sdevkit_ppu.tm = SDEVKIT_BG1_ENABLE | SDEVKIT_OBJ_ENABLE;
    sdevkit_ppu.bgnba[0] = SDEVKIT_BGNBA(VRAM_BG_TILES, 0);
    sdevkit_ppu.bgsc[0] = SDEVKIT_BGSC(VRAM_BG_MAP, SDEVKIT_SC_32x32);
    sdevkit_ppu.vofs[0] = 0x3FF;                 /* the first line of the map on the first line of the screen */
    sdevkit_ppu.obsel = SDEVKIT_OBSEL(VRAM_SPRITES, SDEVKIT_OBJ_SIZE16_L32);
    sdevkit_vram_copy(VRAM_BG_TILES, maze_gfx_tiles, MAZE_GFX_TILES_SIZE);   /* the screen is off */
    sdevkit_cgram_copy(0, maze_gfx_pal, MAZE_GFX_PAL_SIZE);
    sdevkit_vram_copy(VRAM_SPRITES, sprites_gfx_tiles, SPRITES_GFX_TILES_SIZE);
    sdevkit_cgram_copy(128, sprites_gfx_pal, SPRITES_GFX_PAL_SIZE);
    sdevkit_cgram_copy(128 + 16 * 1, pal_ginger_pal, PAL_GINGER_PAL_SIZE);
    sdevkit_cgram_copy(128 + 16 * 2, pal_black_pal, PAL_BLACK_PAL_SIZE);
    sdevkit_cgram_copy(128 + 16 * 3, pal_white_pal, PAL_WHITE_PAL_SIZE);
    sdevkit_cgram_copy(128 + 16 * 4, pal_brown_pal, PAL_BROWN_PAL_SIZE);
    sdevkit_cgram_copy(128 + 16 * 5, pal_scared_pal, PAL_SCARED_PAL_SIZE);
    sdevkit_cgram_copy(128 + 16 * 6, pal_hot_pal, PAL_HOT_PAL_SIZE);
    best_load();
    debug_register();
    level = 1;
    title();
    sdevkit_vram_copy(VRAM_BG_MAP, bg_map, MAP_BYTES);   /* the screen is still off */
    bg_map_dirty = 0;
    sdevkit_ppu.inidisp = 15;
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t pressed;

    sdevkit_input_update(pad);
    pressed = sdevkit_input.pressed[0];
    sdevkit_dbg_hash(sdevkit_sram(), SAVE_BYTES);   /* the checks compare the save RAM too */
    frame++;
    switch (state) {
    case ST_TITLE:
        timer++;
        title_parade();
        if (pressed & SDEVKIT_KEY_START) {
            rng = (uint16_t)(rng ^ timer);
            new_game();
        }
        break;
    case ST_READY:
        actors_draw(0);
        if (--timer == 0) {
            text(13, 19, "      ", 0);
            state = ST_PLAY;
        }
        break;
    case ST_PLAY:
        mouse_move(sdevkit_input.pad[0]);
        if (!dbg_cats_frozen) {
            cats_move();
        }
        debug_boxes();
        if (dbg_clear_level) {
            dbg_clear_level = 0;
            maze_crumbs = 0;
        }
        if (collisions()) {
            state = ST_DYING;
            timer = 90;
        } else if (maze_crumbs == 0) {
            state = ST_CLEAR;
            timer = 120;
            actors_hide();
            sprite_hide(ID_BONUS);
        }
        actors_draw(frame);
        break;
    case ST_DYING:                     /* the mouse spins, the cats are gone */
        actors_hide();
        mouse.dir = (uint8_t)((timer >> 2) & 3);
        actors_draw(0);
        sprite_hide(4);
        sprite_hide(8);
        sprite_hide(12);
        sprite_hide(16);
        if (--timer == 0) {
            lives--;
            hud();
            if (lives == 0) {
                state = ST_OVER;
                sdevkit_music_stop();
                timer = 180;
                actors_hide();
                text(11, 19, "GAME OVER", 1);
            } else {
                ready();
            }
        }
        break;
    case ST_CLEAR:
        if ((timer & 15) == 0) {
            blink = (uint8_t)!blink;
            blink_dirty = 1;
        }
        if (--timer == 0) {
            blink = 0;
            blink_dirty = 1;
            level++;
            start_level();
        }
        break;
    default:                           /* ST_OVER */
        if (--timer == 0) {
            title();
        }
        break;
    }
    /* what changed, for the vertical blank; what does not fit is queued again in the next tick */
    if (bg_map_dirty && sdevkit_queue_vram(VRAM_BG_MAP, bg_map, MAP_BYTES)) {
        bg_map_dirty = 0;
    }
    if (blink_dirty && maze_blink(blink)) {
        blink_dirty = 0;
    }
}

void game_vblank(void)
{
    sprites_copy();
    sdevkit_commit();
}
