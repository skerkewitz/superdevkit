/*
 * Steinschlag: the screen, the miner, the camera and the course of the game.
 *
 * Two background layers. Layer 1 is the cave, in 16 x 16 tiles (a bit of BGMODE), so one map
 * entry is one cell: a map of 64 x 32 entries is 1024 x 512 pixels and holds the whole cave
 * (640 x 352), and the camera only has to set the scroll registers, in x and in y. Layer 2 is the
 * status line in 8 x 8 tiles; its entries have the priority bit, so it stays in front of the cave,
 * and it does not scroll. The miner is a sprite and moves smoothly from cell to cell; everything
 * else lives in the cave's cells.
 *
 * VRAM (16-bit words): the cave's tiles from 0x0000 (sheet a, then b at 0x0200, c at 0x0400), the
 * cave's map at 0x2000 (two 32 x 32 halves, left and right), the font at 0x3000, the status line's
 * map at 0x3800, the sprites at 0x4000. CGRAM: palettes 0..2 the cave's sheets, 3 the font,
 * sprite palette 0 the miner.
 *
 * Animation by tiles: all gems on the map name the same tile; every few frames the next frame of
 * the gem is copied over that tile in VRAM, and every gem in the cave sparkles. The same for the
 * open exit and the beetles' legs. The map changes only where cells change.
 */
#include "steinschlag.h"
#include "cave_a_gfx.h"
#include "cave_b_gfx.h"
#include "cave_c_gfx.h"
#include "miner_gfx.h"
#include "font_gfx.h"

#define BG1_TILES_16   (1 << 4)        /* BGMODE: layer 1 has 16 x 16 tiles */
#define VRAM_CAVE_A    0x0000
#define VRAM_CAVE_B    0x0200
#define VRAM_CAVE_C    0x0400
#define VRAM_CAVE_MAP  0x2000          /* the right half 0x400 words later */
#define VRAM_FONT      0x3000
#define VRAM_HUD_MAP   0x3800
#define VRAM_SPRITES   0x4000

#define TICK_FRAMES    8               /* the cave moves every 8 frames */
#define ROWS_PER_FRAME 3               /* rows of the cave's physics per frame: 22 rows in 8 frames */
#define VIEW_TOP       16              /* the cave starts below the status line */
#define VIEW_H         (224 - VIEW_TOP)
#define MAX_CAM_X      (CAVE_W * 16 - 256)
#define MAX_CAM_Y      (CAVE_H * 16 - VIEW_H)

enum { ST_TITLE, ST_PLAY, ST_DEAD, ST_WON, ST_OVER };

/* The map entry of each kind of cell: a 16 x 16 tile (2 numbers apart a step) and its palette. */
#define PAL(n) ((uint16_t)((n) << 10))
static const uint16_t KIND_TILE[12] = {
    0,                  /* space */
    2,                  /* dirt */
    4,                  /* brick */
    6,                  /* steel */
    8,                  /* boulder */
    32 + 0 + PAL(1),    /* gem (animated) */
    10,                 /* the closed exit, which looks like steel */
    32 + 8 + PAL(1),    /* the open exit (animated) */
    64 + 0 + PAL(2),    /* beetle (animated) */
    0,                  /* the miner's cell: space, the sprite is in front */
    64 + 4 + PAL(2),    /* blast: + 2 per stage */
    64 + 4 + PAL(2),    /* blast that leaves gems */
};

static uint16_t cave_map[2][CAVE_H * 32];   /* the cave's map in RAM, left and right half */
static uint8_t dirty_from = 0xFF, dirty_to;  /* the rows of it to copy in the next vertical blank */
static uint16_t hud_map[2 * 32];
static uint8_t hud_dirty;

static uint8_t state;
static uint8_t cave_number;
static uint8_t lives;
static uint16_t score;                       /* 16 bits: cheaper to print than 32 */
static uint8_t frame;                        /* frames into the current tick */
static uint8_t seconds_frames;
static uint16_t timer;                       /* frames since the start, for the animations */
static uint16_t wait;                        /* frames left of a pause (a life lost, game over) */
static int16_t cam_x, cam_y;
static uint8_t from_x, from_y;               /* the miner's cell before its last step */
static int16_t px, py;                       /* the miner's sprite in the cave, in pixels */
static int8_t step_x, step_y;                /* pixels per frame on the way to the next cell */
static uint8_t facing_left, walking;
static uint8_t anim, anim_dirty;
static uint16_t last_pad;

/* --- the map --------------------------------------------------------------------------------- */
void cell_changed(uint8_t x, uint8_t y)
{
    uint8_t c = cave[y][x];
    uint8_t k = KIND(c);
    uint16_t entry = KIND_TILE[k];

    if (k == K_BLAST || k == K_BLAST_GEM) {
        entry = (uint16_t)(entry + EXTRA(c) * 2);
    }
    cave_map[x >> 5][(uint16_t)y * 32 + (x & 31)] = entry;
    if (dirty_from == 0xFF || y < dirty_from) {
        dirty_from = y;
    }
    if (y > dirty_to) {
        dirty_to = y;
    }
}

static void draw_cave(void)
{
    uint8_t x, y;

    for (y = 0; y < CAVE_H; y++) {
        for (x = 0; x < CAVE_W; x++) {
            cell_changed(x, y);
        }
    }
}

/* --- the status line ------------------------------------------------------------------------- */
static const char FONT[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-:.";
#define TILE_BAR  1
#define TILE_FONT 32

static void hud_text(uint8_t x, uint8_t y, const char *s)
{
    while (*s != 0) {
        uint8_t i = 0;
        while (FONT[i] != 0 && FONT[i] != *s) {
            i++;
        }
        hud_map[y * 32 + x] = (uint16_t)((TILE_FONT + (FONT[i] != 0 ? i : 0)) | PAL(3) | SDEVKIT_BG_TIL_PRIO);
        x++;
        s++;
    }
    hud_dirty = 1;
}

static void hud_number(uint8_t x, uint8_t y, uint16_t value, uint8_t digits)
{
    char s[8];
    uint8_t i;

    s[digits] = 0;
    for (i = digits; i > 0; i--) {
        s[i - 1] = (char)('0' + value % 10);
        value /= 10;
    }
    hud_text(x, y, s);
}

static void hud_clear(void)
{
    uint8_t i;

    for (i = 0; i < 64; i++) {
        hud_map[i] = (uint16_t)(TILE_BAR | PAL(3) | SDEVKIT_BG_TIL_PRIO);
    }
    hud_dirty = 1;
}

/* The fields of the status line, each written when it changes: a division per digit is not free
 * on the 65816 (it is a routine of the runtime), so nothing is printed that did not change. */
static void hud_gems(void)
{
    hud_number(14, 0, gems_got, 2);
}

static void hud_time(void)
{
    hud_number(28, 0, time_left, 3);
}

static void hud_score(void)
{
    hud_number(7, 1, score, 5);
}

static void hud_lives(void)
{
    hud_number(29, 1, lives, 1);
}

static void hud(void)
{
    hud_clear();
    hud_text(1, 0, "CAVE");
    hud_number(6, 0, (uint16_t)(cave_number + 1), 1);
    hud_text(9, 0, "GEMS");
    hud_gems();
    hud_text(17, 0, "NEED");
    hud_number(22, 0, gems_needed, 2);
    hud_text(26, 0, "T");
    hud_time();
    hud_text(1, 1, "SCORE");
    hud_score();
    hud_text(23, 1, "LIVES");
    hud_lives();
}

void add_score(uint16_t points)
{
    score = (uint16_t)(score + points);
    hud_score();
    hud_gems();
}

/* --- the camera and the miner ---------------------------------------------------------------- */
static int16_t approach(int16_t now, int16_t want, int16_t max)
{
    if (want < 0) {
        want = 0;
    } else if (want > max) {
        want = max;
    }
    if (want > now + 4) {
        return (int16_t)(now + 4);
    }
    if (want < now - 4) {
        return (int16_t)(now - 4);
    }
    return want;
}

static void camera(uint8_t jump)
{
    int16_t wx = (int16_t)(px - 120);
    int16_t wy = (int16_t)(py - VIEW_H / 2 + 8);

    if (jump) {
        cam_x = approach(wx, wx, MAX_CAM_X);
        cam_y = approach(wy, wy, MAX_CAM_Y);
    } else {
        cam_x = approach(cam_x, wx, MAX_CAM_X);
        cam_y = approach(cam_y, wy, MAX_CAM_Y);
    }
}

static void draw_miner(void)
{
    uint16_t tile = walking ? (uint16_t)(((frame >> 2) & 1) * 2) : (uint16_t)(4 + ((timer >> 5) & 1) * 2);
    int16_t sx = (int16_t)(px - cam_x);
    int16_t sy = (int16_t)(py - cam_y + VIEW_TOP - 1);

    if (miner_dead) {
        sdevkit_oam_set_visible(0, SDEVKIT_OBJ_HIDE);
        return;
    }
    sdevkit_oam_set(0, (uint16_t)(sx & 0x1FF), (uint16_t)sy, 2, walking ? facing_left : 0, 0, tile,
                    0);
    sdevkit_oam_set_ex(0, SDEVKIT_OBJ_SMALL, SDEVKIT_OBJ_SHOW);
}

/* The joypad at the start of a tick: a direction moves the miner (or digs and takes, with B or Y
 * held, without moving). */
static void miner_input(uint16_t pad)
{
    uint8_t dir = 0xFF;
    uint8_t grab = (uint8_t)((pad & (SDEVKIT_KEY_B | SDEVKIT_KEY_Y)) != 0);

    from_x = miner_x;
    from_y = miner_y;
    px = (int16_t)(miner_x * 16);            /* on the cell: the last step is complete */
    py = (int16_t)(miner_y * 16);
    step_x = 0;
    step_y = 0;
    if (pad & SDEVKIT_KEY_UP) {
        dir = 1;
    } else if (pad & SDEVKIT_KEY_DOWN) {
        dir = 3;
    } else if (pad & SDEVKIT_KEY_LEFT) {
        dir = 2;
    } else if (pad & SDEVKIT_KEY_RIGHT) {
        dir = 0;
    }
    walking = 0;
    if (dir != 0xFF && !miner_dead) {
        if (dir == 0 || dir == 2) {
            facing_left = (uint8_t)(dir == 2);
        }
        if (miner_step(dir, grab)) {
            walking = 1;
            step_x = (int8_t)((miner_x - from_x) * 2);   /* 16 pixels in the 8 frames of a tick */
            step_y = (int8_t)((miner_y - from_y) * 2);
        }
    }
}

/* --- the course of the game ------------------------------------------------------------------ */
static void start_cave(void)
{
    cave_start(cave_number);
    draw_cave();
    from_x = miner_x;
    from_y = miner_y;
    px = (int16_t)(miner_x * 16);
    py = (int16_t)(miner_y * 16);
    step_x = 0;
    step_y = 0;
    frame = 0;
    seconds_frames = 0;
    walking = 0;
    camera(1);
    hud();
    state = ST_PLAY;
}

static void title(void)
{
    state = ST_TITLE;
    cave_number = 0;
    cave_start(0);
    draw_cave();
    hud_clear();
    hud_text(10, 0, "STEINSCHLAG");
    hud_text(10, 1, "PRESS START");
    sdevkit_oam_set_visible(0, SDEVKIT_OBJ_HIDE);
    timer = 0;
    cam_x = 0;
    cam_y = 0;
}

static void play(uint16_t pad)
{
    if (frame == 0) {
        cave_tick_begin();
        miner_input(pad);
    }
    cave_scan_rows((uint8_t)(frame * ROWS_PER_FRAME), (uint8_t)(frame * ROWS_PER_FRAME + ROWS_PER_FRAME));
    if (++seconds_frames == 60) {
        seconds_frames = 0;
        if (time_left > 0) {
            time_left--;
            hud_time();
        }
        if (time_left == 0 && !miner_dead) {
            cave[miner_y][miner_x] = K_BLAST;     /* out of time: the miner's lamp goes off */
            cell_changed(miner_x, miner_y);
            miner_dead = 1;
        }
    }
    camera(0);
    draw_miner();
    px = (int16_t)(px + step_x);
    py = (int16_t)(py + step_y);
    if (miner_dead) {
        state = ST_DEAD;
        wait = 150;
    } else if (cave_won) {
        state = ST_WON;
        sdevkit_oam_set_visible(0, SDEVKIT_OBJ_HIDE);
    }
    frame = (uint8_t)((frame + 1) & (TICK_FRAMES - 1));
}

void game_init(uint16_t seed)
{
    (void)seed;
    sdevkit_set_mode(SDEVKIT_BG_MODE1, BG1_TILES_16);
    sdevkit_bg_set_disable(2);
    sdevkit_bg_init_tile_set(0, cave_a_gfx_tiles, cave_a_gfx_pal, 0, CAVE_A_GFX_TILES_SIZE,
                             CAVE_A_GFX_PAL_SIZE, SDEVKIT_BG_16COLORS, VRAM_CAVE_A);
    sdevkit_dma_copy_vram(cave_b_gfx_tiles, VRAM_CAVE_B, CAVE_B_GFX_TILES_SIZE);
    sdevkit_dma_copy_cgram(cave_b_gfx_pal, 16, CAVE_B_GFX_PAL_SIZE);
    sdevkit_dma_copy_vram(cave_c_gfx_tiles, VRAM_CAVE_C, CAVE_C_GFX_TILES_SIZE);
    sdevkit_dma_copy_cgram(cave_c_gfx_pal, 32, CAVE_C_GFX_PAL_SIZE);
    sdevkit_bg_set_map_ptr(0, VRAM_CAVE_MAP, SDEVKIT_SC_64x32);
    sdevkit_bg_init_tile_set(1, font_gfx_tiles, font_gfx_pal, 3, FONT_GFX_TILES_SIZE,
                             FONT_GFX_PAL_SIZE, SDEVKIT_BG_16COLORS, VRAM_FONT);
    sdevkit_bg_set_map_ptr(1, VRAM_HUD_MAP, SDEVKIT_SC_32x32);
    sdevkit_bg_set_scroll(1, 0, 0x3FF);
    sdevkit_oam_init_gfx_set(miner_gfx_tiles, MINER_GFX_TILES_SIZE, miner_gfx_pal,
                             MINER_GFX_PAL_SIZE, 0, VRAM_SPRITES, SDEVKIT_OBJ_SIZE16_L32);
    sdevkit_oam_init();
    title();
    /* the screen is still off: copy the whole maps at once */
    sdevkit_dma_copy_vram((const uint8_t *)cave_map[0], VRAM_CAVE_MAP, sizeof cave_map[0]);
    sdevkit_dma_copy_vram((const uint8_t *)cave_map[1], VRAM_CAVE_MAP + 0x400, sizeof cave_map[1]);
    sdevkit_dma_copy_vram((const uint8_t *)hud_map, VRAM_HUD_MAP, sizeof hud_map);
    dirty_from = 0xFF;
    dirty_to = 0;
    hud_dirty = 0;
    sdevkit_set_brightness(15);
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t pressed = (uint16_t)(pad[0] & ~last_pad);

    last_pad = pad[0];
    timer++;
    if ((timer & 7) == 0) {
        anim = (uint8_t)((anim + 1) & 3);
        anim_dirty = 1;
    }
    switch (state) {
    case ST_TITLE:                           /* the camera wanders through the first cave */
        cam_x = (int16_t)((timer >> 1) % (MAX_CAM_X * 2));
        if (cam_x > MAX_CAM_X) {
            cam_x = (int16_t)(MAX_CAM_X * 2 - cam_x);
        }
        cam_y = (int16_t)(MAX_CAM_Y / 2);
        if (pressed & SDEVKIT_KEY_START) {
            lives = 3;
            score = 0;
            cave_number = 0;
            start_cave();
        }
        break;
    case ST_PLAY:
        play(pad[0]);
        break;
    case ST_DEAD:                            /* the blast plays out, then the cave starts again */
        if (frame == 0) {
            cave_tick_begin();
        }
        cave_scan_rows((uint8_t)(frame * ROWS_PER_FRAME), (uint8_t)(frame * ROWS_PER_FRAME + ROWS_PER_FRAME));
        frame = (uint8_t)((frame + 1) & (TICK_FRAMES - 1));
        if (--wait == 0) {
            lives--;
            hud_lives();
            if (lives == 0) {
                state = ST_OVER;
                wait = 180;
                hud_text(11, 0, " GAME OVER ");
            } else {
                start_cave();
            }
        }
        break;
    case ST_WON:                             /* the time left counts as points, then the next cave */
        if (time_left > 0) {
            time_left--;
            hud_time();
            add_score(1);
        } else {
            cave_number = (uint8_t)((cave_number + 1) % CAVES);
            start_cave();
        }
        break;
    default:                                 /* ST_OVER */
        if (--wait == 0) {
            title();
        }
        break;
    }
    /* the state for the checks, which compare the SNES and the PC after every tick
     * (sdevkit_dbg_hash): the cave and the score */
    sdevkit_dbg_hash(cave, sizeof cave);
    sdevkit_dbg_hash(&score, sizeof score);
    sdevkit_dbg_hash(&time_left, sizeof time_left);
}

void game_vblank(void)
{
    if (dirty_from != 0xFF) {
        uint16_t first = (uint16_t)dirty_from * 32;
        uint16_t bytes = (uint16_t)((dirty_to - dirty_from + 1) * 64);
        sdevkit_dma_copy_vram((const uint8_t *)&cave_map[0][first],
                              (uint16_t)(VRAM_CAVE_MAP + first), bytes);
        sdevkit_dma_copy_vram((const uint8_t *)&cave_map[1][first],
                              (uint16_t)(VRAM_CAVE_MAP + 0x400 + first), bytes);
        dirty_from = 0xFF;
        dirty_to = 0;
    }
    if (hud_dirty) {
        sdevkit_dma_copy_vram((const uint8_t *)hud_map, VRAM_HUD_MAP, sizeof hud_map);
        hud_dirty = 0;
    }
    if (anim_dirty) {                        /* the next frame over the tiles the map names */
        sdevkit_dma_copy_vram(cave_b_gfx_tiles + anim * 64, VRAM_CAVE_B, 64);
        sdevkit_dma_copy_vram(cave_b_gfx_tiles + 512 + anim * 64, VRAM_CAVE_B + 256, 64);
        sdevkit_dma_copy_vram(cave_b_gfx_tiles + (4 + (anim & 1)) * 64, VRAM_CAVE_B + 8 * 16, 64);
        sdevkit_dma_copy_vram(cave_b_gfx_tiles + 512 + (4 + (anim & 1)) * 64,
                              VRAM_CAVE_B + 256 + 8 * 16, 64);
        sdevkit_dma_copy_vram(cave_c_gfx_tiles + (anim & 1) * 64, VRAM_CAVE_C, 64);
        sdevkit_dma_copy_vram(cave_c_gfx_tiles + 512 + (anim & 1) * 64, VRAM_CAVE_C + 256, 64);
        anim_dirty = 0;
    }
    sdevkit_bg_set_scroll(0, (uint16_t)cam_x, (uint16_t)((cam_y - VIEW_TOP - 1) & 0x3FF));
}
