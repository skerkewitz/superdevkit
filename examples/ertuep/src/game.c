/*
 * Er-Tüp: the screen, the status line and the course of the game.
 *
 * Mode 1 with three layers. BG1 is the stage in 16 x 16 tiles (stage.c), BG2 the wall behind it,
 * scrolled at half the speed, BG3 the status line in 4 colours, in front of everything (its tiles
 * have the priority bit, and BGMODE puts such BG3 tiles above the sprites). Sprites are 8 x 8 and
 * 16 x 16 (draw.c).
 *
 * VRAM (16-bit words): BG1's tiles from 0x0000 (bar, cellar, kitchen, 0x800 words each), BG2's
 * from 0x2000 (the three walls), BG1's map at 0x4000 and BG2's at 0x4800 (64 x 32 each), BG3's tiles
 * at 0x5000 and its map at 0x5800, the sprites from 0x6000 (sheet A, then B at 0x7000).
 * CGRAM: BG palettes 1 to 3 the stage's rooms, 4 to 6 their walls; colours 1 to 3 the status line
 * (BG3 palette 0); sprite palette 0 sheet A, 1 sheet B, 2 sheet A one colour off, 3 white.
 *
 * The stutter power-up (Ruckel) is the joke of the genre's slowdown made into an item nobody
 * wants: for four seconds the picture changes only every fourth frame, every other enemy and
 * bullet flickers, the joypad answers six ticks late, and the logic runs on at full speed, so
 * dodging gets harder, not easier. Then it is over, and it gave nothing.
 *
 * Stage 3, das Festzelt, is mode 7 below the status line (festzelt.c): its picture takes the VRAM
 * below 0x4000 and CGRAM 33 to 127, its enemies sheet B's lower half and sprite palette 4; going
 * back to mode 1 (the title, the ending) puts the stage's tiles and sheet B back.
 */
#include "ertuep.h"
#include "stage_bar.h"
#include "stage_cellar.h"
#include "stage_kitchen.h"
#include "wall_bar.h"
#include "wall_cellar.h"
#include "wall_kitchen.h"
#include "sprites_a.h"
#include "sprites_b.h"
#include "hud.h"
#include "stage_brew.h"
#include "stage_mett.h"
#include "wall_brew.h"

#define BG1_TILES_16  (1 << 4)
#define BG2_TILES_16  (1 << 5)
#define VRAM_BG3_TILES 0x5000
#define VRAM_BG3_MAP   0x5800
#define VRAM_SPRITES   0x6000
#define HUD_PRIO       ((uint16_t)SDEVKIT_BG_TIL_PRIO)

enum { ST_TITLE, ST_PLAY, ST_DYING, ST_FADE_OUT, ST_FADE_IN, ST_GAMEOVER, ST_CLEAR, ST_ENDING };
enum { AF_TITLE, AF_NEW_GAME, AF_RESPAWN, AF_DEMO, AF_BOSS, AF_NEXT_STAGE, AF_ENDING };   /* what comes when a fade out has made it black */
#define DEMO_AT     2816                   /* the demo flies the kitchen's last waves: the busiest */
#define DEMO2_AT    2240                   /* the second: the brewhouse's densest waves and its kettle */
#define DEMO3_AT    2688                   /* the third: the tent's busiest wave and the carousel */

uint16_t pfand;
uint8_t lives;
uint16_t frame_count;
uint8_t ruckel;
uint8_t demo;

static uint8_t state;
static uint16_t wait;
static uint8_t brightness, shown_brightness;
static uint8_t after_fade;                 /* AF_*: what comes when the screen is black */
static uint8_t after_fade_in;              /* the state when the picture is back */
static uint16_t next_life_at;
static uint16_t last_cam;
static uint8_t message_time;
static uint16_t eff_last;
static uint16_t demo_last;                 /* the autopilot's joypad of the last frame */
static uint16_t pad_ring[8];
static uint8_t ring_pos;
static uint8_t start_stage;                /* chosen on the title with Select */
static uint8_t hdma_channels;              /* the HDMA channels the stage runs */

/* --- the status line (BG3) ------------------------------------------------------------------- */
/* The font's tiles from 32 on, in the order of dev/art/ertuep.py; the ASCII characters here stand
 * for the ones a C string of dampf's subset should not hold: $ is the euro, ^ U umlaut, [ A
 * umlaut, ] O umlaut, @ the trademark. */
static const char CHARSET[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-:.,$^[]@?/'";
#define TILE_BAR       1
#define TILE_EDGE      2
#define TILE_SLOT      3
#define TILE_SLOT_FULL 4
#define TILE_GAUGE_L   5
#define TILE_GAUGE_R   6
#define TILE_SEKT      7                   /* the body, then the neck: empty, half, full, the cork */
#define TILE_LIFE      12
#define TILE_FONT      32

static uint16_t hud_map[32 * 28];          /* the whole map of BG3: the title uses more than two rows */
static uint8_t hud_dirty;
static uint8_t char_tile[96];

static void hud_put(uint8_t x, uint8_t y, uint16_t tile)
{
    hud_map[(uint16_t)y * 32 + x] = (uint16_t)(tile | HUD_PRIO);
    hud_dirty = 1;
}

/* Text on the status line; a counted loop (at most a row), so the timing report can bound it. */
static void hud_text(uint8_t x, uint8_t y, const char *s)
{
    uint8_t i;

    for (i = 0; i < 32 && s[i] != 0; i++) {
        uint8_t c = (uint8_t)((uint8_t)s[i] - ' ');
        hud_put((uint8_t)(x + i), y, (uint16_t)(TILE_FONT + (c < 96 ? char_tile[c] : 0)));
    }
}

/* The deposit as euros: "  0,08$" (the $ is the euro sign), right-aligned in 7 tiles at (x, y). */
static void hud_pfand_at(uint8_t x, uint8_t y)
{
    char s[8];
    uint16_t euros = pfand / 100;
    uint8_t cents = (uint8_t)(pfand % 100);

    s[0] = (char)(euros >= 100 ? '0' + euros / 100 : ' ');
    s[1] = (char)(euros >= 10 ? '0' + (euros / 10) % 10 : ' ');
    s[2] = (char)('0' + euros % 10);
    s[3] = ',';
    s[4] = (char)('0' + cents / 10);
    s[5] = (char)('0' + cents % 10);
    s[6] = '$';
    s[7] = 0;
    hud_text(x, y, s);
}

static void hud_pfand(void)
{
    hud_pfand_at(7, 0);
}

/* The crate's six slots, four bottles each. */
void hud_crate(void)
{
    uint8_t i;

    if (crate_state == C_NONE && crate_bottles == 0) {
        for (i = 15; i < 23; i++) {
            hud_put(i, 0, TILE_BAR);
        }
        return;
    }
    hud_put(15, 0, TILE_GAUGE_L);
    for (i = 0; i < 6; i++) {
        hud_put((uint8_t)(16 + i), 0, crate_bottles > i * 4 ? TILE_SLOT_FULL : TILE_SLOT);
    }
    hud_put(22, 0, TILE_GAUGE_R);
}

/* The Sekt bottle: its neck fills as the charge grows. */
void hud_sekt(void)
{
    if (!has_sekt) {
        hud_put(24, 0, TILE_BAR);
        hud_put(25, 0, TILE_BAR);
        hud_put(26, 0, TILE_BAR);
        return;
    }
    hud_put(24, 0, TILE_SEKT);
    hud_put(25, 0, (uint16_t)(TILE_SEKT + 1 + (charge >= 100 ? 2 : charge >= 40 ? 1 : 0)));
    hud_put(26, 0, charge >= 100 ? TILE_SEKT + 4 : TILE_BAR);
}

static void hud_lives(void)
{
    uint8_t i;

    for (i = 0; i < 3; i++) {
        hud_put((uint8_t)(28 + i), 0, i < lives ? TILE_LIFE : TILE_BAR);
    }
}

static void hud_clear_row1(void)
{
    uint8_t i;

    for (i = 0; i < 32; i++) {
        hud_put(i, 1, TILE_EDGE);
    }
}

void hud_message(const char *text, uint8_t frames)
{
    uint8_t n;

    for (n = 0; n < 32 && text[n] != 0; n++) {
    }
    hud_clear_row1();
    hud_text((uint8_t)(16 - (n >> 1)), 1, text);
    message_time = frames;
}

static void hud_play(void)
{
    uint8_t i;

    for (i = 0; i < 32; i++) {
        hud_put(i, 0, TILE_BAR);
    }
    hud_clear_row1();
    hud_text(1, 0, "PFAND");
    hud_pfand();
    hud_crate();
    hud_sekt();
    hud_lives();
}

static void hud_blank_below(void)
{
    uint16_t i;

    for (i = 64; i < 32 * 28; i++) {
        hud_map[i] = 0;
    }
}

/* --- the score and the stutter --------------------------------------------------------------------- */
void add_pfand(uint8_t cents)
{
    if (pfand < 65000) {
        pfand = (uint16_t)(pfand + cents);
    }
    hud_pfand();
    if (pfand >= next_life_at) {                     /* every five euros of deposit: a life */
        next_life_at = (uint16_t)(next_life_at + 500);
        if (lives < 9) {
            lives++;
            hud_lives();
            hud_message("PFAND EINGELOEST: 1UP", 120);
        }
    }
}

void start_ruckel(void)
{
    ruckel = 240;
    hud_message("AUTHENTIC SLOWDOWN@", 240);
}

/* --- the course of the game -------------------------------------------------------------------------- */
static void fade_to(uint8_t then)
{
    after_fade = then;
    state = ST_FADE_OUT;
}

/* With the screen off: the tiles and colours of the stage's layers 1 and 2 (also after a boss,
 * whose picture took the place of a wall). */
static void load_stage_gfx(void)
{
    if (stage_no == 2) {
        festzelt_load();
        hdma_channels = SDEVKIT_HDMA_CHANNEL3 | SDEVKIT_HDMA_CHANNEL4 | SDEVKIT_HDMA_CHANNEL5;
        boss_state = BS_NONE;
        return;
    }
    if (festzelt_on) {                              /* back from mode 7 */
        festzelt_unload();
        sdevkit_set_mode(SDEVKIT_BG_MODE1, BG1_TILES_16 | BG2_TILES_16 | SDEVKIT_BG3_MODE1_PRIORITY_HIGH);
        sdevkit_dma_copy_vram(sprites_b_tiles + 4096, VRAM_SPRITES + 0x1800, 4096);
    }
    hdma_channels = stage_no == 1 ? SDEVKIT_HDMA_CHANNEL3 : 0;
    if (stage_no == 0) {
        sdevkit_dma_copy_vram(stage_bar_tiles, 0x0000, STAGE_BAR_TILES_SIZE);
        sdevkit_dma_copy_cgram(stage_bar_pal, 16, STAGE_BAR_PAL_SIZE);
        sdevkit_dma_copy_vram(stage_cellar_tiles, 0x0800, STAGE_CELLAR_TILES_SIZE);
        sdevkit_dma_copy_cgram(stage_cellar_pal, 32, STAGE_CELLAR_PAL_SIZE);
        sdevkit_dma_copy_vram(stage_kitchen_tiles, 0x1000, STAGE_KITCHEN_TILES_SIZE);
        sdevkit_dma_copy_cgram(stage_kitchen_pal, 48, STAGE_KITCHEN_PAL_SIZE);
        sdevkit_dma_copy_vram(wall_bar_tiles, 0x2000, WALL_BAR_TILES_SIZE);
        sdevkit_dma_copy_cgram(wall_bar_pal, 64, WALL_BAR_PAL_SIZE);
        sdevkit_dma_copy_vram(wall_cellar_tiles, 0x2800, WALL_CELLAR_TILES_SIZE);
        sdevkit_dma_copy_cgram(wall_cellar_pal, 80, WALL_CELLAR_PAL_SIZE);
        sdevkit_dma_copy_vram(wall_kitchen_tiles, 0x3000, WALL_KITCHEN_TILES_SIZE);
        sdevkit_dma_copy_cgram(wall_kitchen_pal, 96, WALL_KITCHEN_PAL_SIZE);
        sdevkit_hdma_enable(0);
    } else {
        sdevkit_dma_copy_vram(stage_brew_tiles, 0x0000, STAGE_BREW_TILES_SIZE);
        sdevkit_dma_copy_cgram(stage_brew_pal, 16, STAGE_BREW_PAL_SIZE);
        sdevkit_dma_copy_vram(stage_mett_tiles, 0x0800, STAGE_METT_TILES_SIZE);
        sdevkit_dma_copy_cgram(stage_mett_pal, 32, STAGE_METT_PAL_SIZE);
        sdevkit_dma_copy_vram(wall_brew_tiles, 0x2000, WALL_BREW_TILES_SIZE);
        sdevkit_dma_copy_cgram(wall_brew_pal, 64, WALL_BREW_PAL_SIZE);
    }
    sdevkit_set_palette_color(0, SDEVKIT_RGB8(26, 14, 8));
    boss_state = BS_NONE;
}

/* --- the sway of the brewhouse and of the tent, the Mett's pulse ----------------------------------- */
/* Layer 1's horizontal scroll per band of sixteen lines (fourteen bands: eight-line bands cost twice
 * the time, a tenth of a frame in the densest waves), from an HDMA table written each frame into
 * the half the PPU is not reading (SDEVKIT_HDMA_TWICE: BG1HOFS takes two writes; in mode 7 it is
 * mode 7's scroll). */
#define SWAY_BANDS 14
static uint8_t sway_table[2][SWAY_BANDS * 3 + 1];
static uint8_t sway_half, sway_phase, sway_ready;
static uint8_t mett_frame, mett_dirty;

/* An offset in pixels for each step of the turn: the tent's by how drunk the picture is (none, 2, 4
 * and 6 pixels), and last the brewhouse's 3 (sine(a) / 32; tables, since a signed division keeps its
 * rounding correction, C_STYLE.md, slow-signed-divide, and this runs 14 times a frame). */
#define SWAY_BREW 4
static const int8_t SWAY[5][64] = {
    { 0 },
    { 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 0, 0,
      0, 0, 0, -1, -1, -1, -1, -1, -1, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -1, -1, -1, -1, -1, -1, 0, 0 },
    { 0, 0, 1, 1, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3, 2, 2, 2, 1, 1, 0,
      0, 0, -1, -1, -2, -2, -2, -3, -3, -3, -3, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -3, -3, -3, -3, -2, -2, -2, -1, -1, 0 },
    { 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 5, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1,
      0, -1, -1, -2, -2, -3, -3, -4, -4, -5, -5, -5, -6, -6, -6, -6, -6, -6, -6, -6, -6, -5, -5, -5, -4, -4, -3, -3, -2, -2, -1, -1 },
    { 0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 1, 1, 1, 0, 0,
      0, 0, 0, -1, -1, -1, -2, -2, -2, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -2, -2, -2, -1, -1, -1, 0, 0 },
};

/* The table of the next frame: base the scroll without sway, wave one of SWAY, step how far the
 * wave goes from one band to the next. */
static void sway(uint16_t base, uint16_t mask, const int8_t *wave, uint8_t step)
{
    uint8_t *t = sway_table[sway_half ^ 1];
    uint8_t i;
    uint8_t a = sway_phase;

    sway_phase++;
    for (i = 0; i < SWAY_BANDS; i++) {              /* the count bytes are there (sway_init) */
        uint16_t x = (uint16_t)((base + (uint16_t)wave[a & 63]) & mask);
        t[1] = (uint8_t)x;
        t[2] = (uint8_t)(x >> 8);
        t += 3;
        a = (uint8_t)(a + step);
    }
    sway_ready = 1;
}

/* The tables' count bytes, which never change: 16 lines a band, 0 at the end. */
static void sway_init(void)
{
    uint8_t h, i;

    for (h = 0; h < 2; h++) {
        for (i = 0; i < SWAY_BANDS; i++) {
            sway_table[h][i * 3] = 16;
        }
        sway_table[h][SWAY_BANDS * 3] = 0;
    }
}

/* What moves the picture besides the camera: the brewhouse's sway and Mett, the tent's view. */
static void effects(void)
{
    if (stage_no == 1) {
        sway(shown_cam, 0x3FF, SWAY[SWAY_BREW], 12);
        if ((frame_count & 7) == 0) {
            mett_frame = (uint8_t)((mett_frame + 1) & 3);
            mett_dirty = 1;
        }
    } else if (stage_no == 2 && festzelt_on) {
        uint8_t drunk;
        if (boss_state != BS_NONE) {
            carousel_view();
        } else {
            festzelt_view();
        }
        drunk = festzelt_drunk();
        sway(festzelt_hofs(), 0x1FFF, SWAY[drunk], 8);
    }
}

static void effects_vblank(void)
{
    if (sway_ready) {
        sway_half ^= 1;
        sdevkit_hdma_set(3, SDEVKIT_PPU_BG1HOFS, SDEVKIT_HDMA_TWICE, sway_table[sway_half]);
        sdevkit_hdma_enable(hdma_channels);
        sway_ready = 0;
    }
    if (mett_dirty) {                               /* frame k of the Mett over cells 1 to 3 */
        const uint8_t *src = stage_mett_tiles + (uint16_t)(mett_frame * 32 + 2) * 32;
        sdevkit_dma_copy_vram(src, 0x0800 + 2 * 16, 6 * 32);
        sdevkit_dma_copy_vram(src + 16 * 32, 0x0800 + 18 * 16, 6 * 32);
        mett_dirty = 0;
    }
}

/* With the screen off: the stage from a checkpoint, the player, the status line. */
static void respawn(uint16_t at)
{
    sdevkit_set_brightness(0);
    shown_brightness = 0;
    objects_clear();
    stage_start(at);
    scroll_step = 8;
    load_stage_gfx();
    last_cam = at;
    player_start();
    ruckel = 0;
    message_time = 0;
    hud_play();
    hud_blank_below();
    sdevkit_dma_copy_vram((const uint8_t *)hud_map, VRAM_BG3_MAP, sizeof hud_map);
    hud_dirty = 0;
    sprites_hide_all();
    state = ST_FADE_IN;
    after_fade_in = ST_PLAY;
}

/* The stage a new game starts with, chosen on the title with Select. */
static void title_stage(void)
{
    static const char *const NAMES[STAGES] = { "  1: DIE KNEIPE  ", " 2: DAS SUDHAUS  ", " 3: DAS FESTZELT " };

    hud_text(7, 19, NAMES[start_stage]);
}

static void title(void)
{
    uint8_t i;

    sdevkit_set_brightness(0);
    shown_brightness = 0;
    objects_clear();
    scroll_step = 8;
    stage_select(0);
    load_stage_gfx();
    stage_start(0);
    last_cam = 0;
    for (i = 0; i < 64; i++) {
        hud_map[i] = 0;
    }
    hud_blank_below();
    hud_text(10, 17, "PRESS START");
    title_stage();
    hud_text(4, 21, "B: SCHIESSEN  A: KASTEN");
    hud_text(6, 25, "MADE WITH SUPERDEVKIT");
    sdevkit_dma_copy_vram((const uint8_t *)hud_map, VRAM_BG3_MAP, sizeof hud_map);
    hud_dirty = 0;
    draw_title_logo();
    state = ST_FADE_IN;
    after_fade_in = ST_TITLE;
    demo = 0;
    wait = 420;                                     /* seven seconds, then the demo */
}

/* The end of the game: back in the pub, which has closed; the deposit collected. */
static void ending(void)
{
    sdevkit_set_brightness(0);
    shown_brightness = 0;
    objects_clear();
    scroll_step = 4;
    stage_select(0);
    load_stage_gfx();
    stage_start(0);
    last_cam = 0;
    hud_play();
    hud_clear_row1();
    hud_blank_below();
    hud_text(12, 6, "FEIERABEND");
    hud_text(5, 9, "ALLE DREI STAGES GESCHAFFT");
    hud_text(7, 13, "PFAND:");
    hud_pfand_at(14, 13);
    hud_text(6, 17, "DANKE FUERS SPIELEN!");
    hud_text(13, 21, "PROST!");
    sdevkit_dma_copy_vram((const uint8_t *)hud_map, VRAM_BG3_MAP, sizeof hud_map);
    hud_dirty = 0;
    sprites_hide_all();
    state = ST_FADE_IN;
    after_fade_in = ST_ENDING;
    wait = 900;
}

/* The title's demo, flown by the autopilot with a full crate and the Sekt, always the same (its
 * own seed): by turns the kitchen's last waves and der Pfandautomat, the brewhouse's densest waves
 * and die Sudpfanne, and the tent's busiest wave and das Kettenkarussell, drunk with a full crate.
 * They are the game's benchmark: the checks measure every tick of them (check.txt). */
static uint8_t demo_count;

static void start_demo(void)
{
    static const uint16_t AT[STAGES] = { DEMO_AT, DEMO2_AT, DEMO3_AT };
    static const uint16_t FRAMES[STAGES] = { 2400, 3800, 2400 };   /* to the end of each boss */
    uint8_t which = demo_count;

    demo_count = (uint8_t)(demo_count + 1 == STAGES ? 0 : demo_count + 1);
    lives = 1;
    pfand = 0;
    next_life_at = 65535;
    has_sekt = 1;
    demo = 1;
    stage_select(which);
    rnd = 0x2B1D;
    respawn(AT[which]);
    crate_grow(24);
    crate_x = ship_x;
    hud_message("DEMO - PRESS START", 255);
    wait = FRAMES[which];
}

static void new_game(void)
{
    stage_select(start_stage);
    demo = 0;
    lives = 3;
    pfand = 0;
    next_life_at = 500;
    has_sekt = 0;
    rnd = (uint16_t)(frame_count | 1);
    respawn(0);
}

void lose_life(void)
{
    lives--;
    hud_lives();
    state = ST_DYING;
    wait = 110;
}

/* The end of the stage: when the last wave is gone the lights go out and the deposit machine
 * comes; when it is broken and its deposit is collected (or gone), the pub closes. */
static void end_of_stage(void)
{
    uint8_t i, left = 0;

    if (cam_x < STAGE_END) {
        return;
    }
    for (i = 0; i < MAX_ENEMIES; i++) {
        left = (uint8_t)(left | enemies[i].kind);
    }
    if (boss_state == BS_NONE && left == 0 && ship_alive) {
        fade_to(AF_BOSS);
    } else if (boss_state == BS_DONE) {
        for (i = 0; i < MAX_ITEMS; i++) {
            left = (uint8_t)(left | items[i].kind);
        }
        if (left == 0 && ship_alive) {
            hud_message(stage_no == 0 ? "DIE KNEIPE HAT ZU!" : stage_no == 1 ? "SUDHAUS SAUBER - PROST!" : "DAS FESTZELT HAT ZU!", 255);
            state = ST_CLEAR;
            wait = 300;
        }
    }
}

static void play(uint16_t pad, uint16_t pressed, uint16_t released)
{
    stage_scroll();
    waves_spawn();
    player_update(pad, pressed, released);
    shots_update();
    enemies_update();
    bullets_update();
    items_update();
    parts_update();
    collide();
    if (boss_state != BS_NONE) {
        boss_update();
        boss_collide();
    }
    if (cam_x != last_cam) {
        last_cam = cam_x;
        if (checkpoint_reached(cam_x)) {
            hud_message("ZWISCHENSTAND", 90);
        }
    }
}

/* The picture of this frame: with the stutter only every fourth one, and with flicker. */
static void picture(void)
{
    if (ruckel > 0) {
        ruckel--;
        if (ruckel == 0) {
            message_time = 1;
        }
        if ((frame_count & 3) != 0) {
            return;
        }
        draw_all(1);
    } else {
        draw_all(0);
    }
    shown_cam = cam_x;
}

void game_init(uint16_t seed)
{
    uint8_t i;

    (void)seed;
    sdevkit_set_mode(SDEVKIT_BG_MODE1, BG1_TILES_16 | BG2_TILES_16 | SDEVKIT_BG3_MODE1_PRIORITY_HIGH);
    sdevkit_bg_set_gfx_ptr(0, 0x0000);
    sdevkit_bg_set_gfx_ptr(1, 0x2000);
    sdevkit_bg_set_map_ptr(0, 0x4000, SDEVKIT_SC_64x32);
    sdevkit_bg_set_map_ptr(1, 0x4800, SDEVKIT_SC_64x32);
    sdevkit_bg_init_tile_set(2, hud_tiles, hud_pal, 0, HUD_TILES_SIZE, HUD_PAL_SIZE, SDEVKIT_BG_4COLORS, VRAM_BG3_TILES);
    sdevkit_bg_set_map_ptr(2, VRAM_BG3_MAP, SDEVKIT_SC_32x32);
    sdevkit_bg_set_scroll(2, 0, 0x3FF);
    sdevkit_oam_init_gfx_set(sprites_a_tiles, SPRITES_A_TILES_SIZE, sprites_a_pal, SPRITES_A_PAL_SIZE, 0, VRAM_SPRITES,
                             SDEVKIT_OBJ_SIZE8_L16);
    sdevkit_dma_copy_vram(sprites_b_tiles, VRAM_SPRITES + 0x1000, SPRITES_B_TILES_SIZE);
    sdevkit_dma_copy_cgram(sprites_b_pal, 144, SPRITES_B_PAL_SIZE);
    sdevkit_dma_copy_cgram(sprites_a_pal, 160, SPRITES_A_PAL_SIZE);   /* the stutter's six-pack ... */
    sdevkit_set_palette_color(160 + 10, SDEVKIT_RGB8(44, 150, 150));  /* ... one colour off */
    for (i = 1; i < 16; i++) {
        sdevkit_set_palette_color((uint16_t)(176 + i), i == 1 ? SDEVKIT_RGB8(160, 160, 176) : SDEVKIT_RGB8(255, 255, 255));
    }
    sdevkit_oam_init();
    for (i = 0; CHARSET[i] != 0; i++) {
        char_tile[(uint8_t)((uint8_t)CHARSET[i] - ' ')] = i;
    }
    sway_init();
    title();
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    uint16_t raw = pad[0];
    uint16_t eff, pressed, released;

    frame_count++;
    /* the joypad, six ticks late while the stutter lasts */
    pad_ring[ring_pos & 7] = raw;
    eff = ruckel > 0 ? pad_ring[(uint8_t)(ring_pos - 6) & 7] : raw;
    ring_pos++;
    pressed = (uint16_t)(eff & ~eff_last);
    released = (uint16_t)(~eff & eff_last);
    eff_last = eff;

    switch (state) {
    case ST_TITLE:
        stage_scroll();
        shown_cam = cam_x;
        if (pressed & SDEVKIT_KEY_START) {
            fade_to(AF_NEW_GAME);
        } else if (pressed & SDEVKIT_KEY_SELECT) {
            start_stage = (uint8_t)(start_stage + 1 == STAGES ? 0 : start_stage + 1);
            title_stage();
            wait = 420;
        } else if (--wait == 0) {
            fade_to(AF_DEMO);
        }
        break;
    case ST_PLAY:
        if (demo) {
            uint16_t ap = autopilot();
            play(ap, (uint16_t)(ap & ~demo_last), (uint16_t)(~ap & demo_last));
            demo_last = ap;
            if ((pressed & SDEVKIT_KEY_START) || --wait == 0) {
                fade_to(AF_TITLE);
            } else {
                end_of_stage();                     /* the demo meets the machine too */
            }
            picture();
            break;
        }
        play(eff, pressed, released);
        picture();
        end_of_stage();
        break;
    case ST_DYING:                                  /* the world goes on without the ship */
        play(0, 0, 0);
        picture();
        if (--wait == 0) {
            if (demo) {
                fade_to(AF_TITLE);
            } else if (lives == 0) {
                hud_message("GAME OVER", 255);
                state = ST_GAMEOVER;
                wait = 240;
            } else {
                fade_to(AF_RESPAWN);
            }
        }
        break;
    case ST_GAMEOVER:
    case ST_CLEAR:
        parts_update();
        picture();
        if (--wait == 0) {
            if (state == ST_CLEAR && !demo) {
                fade_to(stage_no + 1 < STAGES ? AF_NEXT_STAGE : AF_ENDING);
            } else {
                fade_to(AF_TITLE);
            }
        }
        break;
    case ST_ENDING:                                 /* the pub at closing time, the score */
        stage_scroll();
        shown_cam = cam_x;
        if ((pressed & SDEVKIT_KEY_START) || --wait == 0) {
            fade_to(AF_TITLE);
        }
        break;
    case ST_FADE_OUT:
        if (brightness > 0) {
            brightness--;
        } else if (after_fade == AF_TITLE) {
            title();
        } else if (after_fade == AF_NEW_GAME) {
            new_game();
        } else if (after_fade == AF_DEMO) {
            start_demo();
        } else if (after_fade == AF_NEXT_STAGE) {   /* on to the next stage, with what was won */
            uint8_t bottles = crate_state != C_NONE ? crate_bottles : 0;
            uint8_t speed = ship_speed;
            stage_select((uint8_t)(stage_no + 1));
            respawn(0);
            ship_speed = speed;
            if (bottles > 0) {
                crate_grow(bottles);
            }
            hud_message(stage_no == 1 ? "STAGE 2: DAS SUDHAUS" : "STAGE 3: DAS FESTZELT", 180);
        } else if (after_fade == AF_ENDING) {
            ending();
        } else if (after_fade == AF_BOSS) {         /* the lights go out: the boss comes */
            boss_start();
            sdevkit_set_palette_color(0, SDEVKIT_RGB8(8, 6, 10));
            state = ST_FADE_IN;
            after_fade_in = ST_PLAY;
        } else {
            respawn(checkpoint_before(cam_x));
        }
        break;
    default:                                        /* ST_FADE_IN */
        if (brightness < 15) {
            brightness++;
        } else {
            state = after_fade_in;
        }
        break;
    }
    effects();
    if (message_time > 0 && --message_time == 0) {
        hud_clear_row1();
        if (ruckel > 0) {
            hud_message("AUTHENTIC SLOWDOWN@", ruckel);
        }
    }
    /* the state for the checks (sdevkit_dbg_hash): the tables, the ship, the camera, the score */
    sdevkit_dbg_hash(shots, sizeof shots);
    sdevkit_dbg_hash(enemies, sizeof enemies);
    sdevkit_dbg_hash(bullets, sizeof bullets);
    sdevkit_dbg_hash(items, sizeof items);
    sdevkit_dbg_hash(parts, sizeof parts);
    sdevkit_dbg_hash(&ship_x, sizeof ship_x);
    sdevkit_dbg_hash(&ship_y, sizeof ship_y);
    sdevkit_dbg_hash(&cam_x, sizeof cam_x);
    sdevkit_dbg_hash(&pfand, sizeof pfand);
    sdevkit_dbg_hash(&rnd, sizeof rnd);
    if (stage_no == 2) {
        festzelt_hash();
    }
}

void game_vblank(void)
{
    stage_vblank();
    effects_vblank();
    if (hud_dirty) {
        sdevkit_dma_copy_vram((const uint8_t *)hud_map, VRAM_BG3_MAP, 128);
        hud_dirty = 0;
    }
    if (brightness != shown_brightness) {
        sdevkit_set_brightness(brightness);
        shown_brightness = brightness;
    }
}
