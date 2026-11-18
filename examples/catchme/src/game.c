/*
 * CatchMe: the course of the game, and the frame rhythm of superdevkit (sdevkit/loop.h).
 *
 *   game_init     once, with the screen off: graphics and palettes into VRAM and CGRAM
 *   game_tick     once per frame: the game logic, the sprite table, what the map should show
 *   game_vblank   in the vertical blank: the map into VRAM when it changed, palette changes
 *
 * VRAM: the background tiles from word 0x0000, the background map at 0x1000 (32 x 32 entries),
 * the sprite tiles at 0x4000. CGRAM: background palette 0 (colours 0..15), sprite palettes 0..6
 * (colours 128..239): the mouse, the four cats, the scared cats, the mouse after a chili.
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
static uint16_t last_pad;
static uint16_t rng = 0xACE1;
static uint32_t score;
static uint32_t best = 5000;
static uint8_t lives;
static uint8_t extra_given;
static uint8_t caught_in_row;          /* cats caught on one chili: 200, 400, 800, 1600 */
static uint8_t eaten;                  /* crumbs eaten in this level, for the cheese */
static uint16_t cheese_left;           /* frames the cheese stays */
static uint8_t blink, blink_dirty;     /* the walls blink after a level, in the vertical blank */

/* A 16-bit linear feedback shift register: the same seed gives the same game. */
uint16_t random16(void)
{
    uint16_t bit = (uint16_t)(((rng >> 0) ^ (rng >> 2) ^ (rng >> 3) ^ (rng >> 5)) & 1);
    rng = (uint16_t)((rng >> 1) | (bit << 15));
    return rng;
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
    sdevkit_oam_set_visible(ID_BONUS, SDEVKIT_OBJ_HIDE);
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
    state = ST_READY;
    timer = 120;
    actors_reset();
    caught_in_row = 0;
    cheese_left = 0;
    sdevkit_oam_set_visible(ID_BONUS, SDEVKIT_OBJ_HIDE);
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
    level = 1;
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
            sdevkit_oam_set(ID_BONUS, 7 * 16 + 8, 8 * 16 + MAZE_TOP - 1, 3, 0, 0, SPRITE_CHEESE,
                            PAL_HOT);
            sdevkit_oam_set_ex(ID_BONUS, SDEVKIT_OBJ_SMALL, SDEVKIT_OBJ_SHOW);
        } else {
            sdevkit_oam_set_visible(ID_BONUS, SDEVKIT_OBJ_HIDE);
        }
    }
    return 0;
}

/* --- the frame rhythm ------------------------------------------------------------------------ */
void game_init(uint16_t seed)
{
    rng = (uint16_t)(rng ^ seed);
    sdevkit_set_mode(SDEVKIT_BG_MODE1, 0);
    sdevkit_bg_set_disable(1);
    sdevkit_bg_set_disable(2);
    sdevkit_bg_init_tile_set(0, maze_gfx_tiles, maze_gfx_pal, 0, MAZE_GFX_TILES_SIZE,
                             MAZE_GFX_PAL_SIZE, SDEVKIT_BG_16COLORS, VRAM_BG_TILES);
    sdevkit_bg_set_map_ptr(0, VRAM_BG_MAP, SDEVKIT_SC_32x32);
    sdevkit_bg_set_scroll(0, 0, 0x3FF);          /* the first line of the map on the first line of the screen */
    sdevkit_oam_init_gfx_set(sprites_gfx_tiles, SPRITES_GFX_TILES_SIZE, sprites_gfx_pal,
                             SPRITES_GFX_PAL_SIZE, 0, VRAM_SPRITES, SDEVKIT_OBJ_SIZE16_L32);
    sdevkit_dma_copy_cgram(pal_ginger_pal, 128 + 16 * 1, PAL_GINGER_PAL_SIZE);
    sdevkit_dma_copy_cgram(pal_black_pal, 128 + 16 * 2, PAL_BLACK_PAL_SIZE);
    sdevkit_dma_copy_cgram(pal_white_pal, 128 + 16 * 3, PAL_WHITE_PAL_SIZE);
    sdevkit_dma_copy_cgram(pal_brown_pal, 128 + 16 * 4, PAL_BROWN_PAL_SIZE);
    sdevkit_dma_copy_cgram(pal_scared_pal, 128 + 16 * 5, PAL_SCARED_PAL_SIZE);
    sdevkit_dma_copy_cgram(pal_hot_pal, 128 + 16 * 6, PAL_HOT_PAL_SIZE);
    sdevkit_oam_init();
    level = 1;
    title();
    sdevkit_dma_copy_vram((const uint8_t *)bg_map, VRAM_BG_MAP, MAP_BYTES);   /* the screen is still off */
    bg_map_dirty = 0;
    sdevkit_set_brightness(15);
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t pressed = (uint16_t)(pad[0] & ~last_pad);

    last_pad = pad[0];
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
        mouse_move(pad[0]);
        cats_move();
        if (collisions()) {
            state = ST_DYING;
            timer = 90;
        } else if (maze_crumbs == 0) {
            state = ST_CLEAR;
            timer = 120;
            actors_hide();
            sdevkit_oam_set_visible(ID_BONUS, SDEVKIT_OBJ_HIDE);
        }
        actors_draw(frame);
        break;
    case ST_DYING:                     /* the mouse spins, the cats are gone */
        actors_hide();
        mouse.dir = (uint8_t)((timer >> 2) & 3);
        actors_draw(0);
        sdevkit_oam_set_visible(4, SDEVKIT_OBJ_HIDE);
        sdevkit_oam_set_visible(8, SDEVKIT_OBJ_HIDE);
        sdevkit_oam_set_visible(12, SDEVKIT_OBJ_HIDE);
        sdevkit_oam_set_visible(16, SDEVKIT_OBJ_HIDE);
        if (--timer == 0) {
            lives--;
            hud();
            if (lives == 0) {
                state = ST_OVER;
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
}

void game_vblank(void)
{
    if (bg_map_dirty) {
        sdevkit_dma_copy_vram((const uint8_t *)bg_map, VRAM_BG_MAP, MAP_BYTES);
        bg_map_dirty = 0;
    }
    if (blink_dirty) {
        maze_blink(blink);
        blink_dirty = 0;
    }
}
