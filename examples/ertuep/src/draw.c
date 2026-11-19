/*
 * The sprites: every object of the tables written into the kit's copy of the sprite table
 * (sdevkit_oam_memory), which the main loop copies into the PPU in the vertical blank.
 *
 * The entries are written here directly, four bytes and two bits each, not with
 * sdevkit_oam_set: that call costs 315 to 427 cycles (lib/README.md), and a busy frame has eighty
 * sprites. The 32 bytes of the high table (x bit 8, size) are cleared each frame and the bits of
 * every sprite set.
 *
 * Sprite sizes: small 8 x 8, large 16 x 16 (OBSEL). Sheet A (the player and items, palette 0) is
 * tiles 0 to 255, sheet B (enemies, palette 1) tiles 256 to 511; a 16 x 16 cell k of a sheet is
 * tile (k / 8) * 32 + (k % 8) * 2. Palette 2 is sheet A's with one colour off (the stutter
 * power-up), palette 3 all white (an enemy just hit). In stage 3 sheet B's lower half (cells 32 to
 * 63) is sheet C, stage 3's enemies (C(k) is B(32 + k)), with palette 4.
 *
 * The order is the order of importance: a sprite with a lower number is drawn in front, and is the
 * last to go when a line has more than 32. The ship first, the particles last.
 */
#include "ertuep.h"

#define A(k)        ((uint16_t)(((k) >> 3) * 32 + ((k) & 7) * 2))
#define B(k)        ((uint16_t)(256 + ((k) >> 3) * 32 + ((k) & 7) * 2))
#define PRIO        0x30
#define HFLIP       0x40
#define VFLIP       0x80
#define PAL_A       0x00
#define PAL_B       0x02
#define PAL_RUCKEL  0x04
#define PAL_WHITE   0x06
#define PAL_C       0x08
#define C(k)        B(32 + (k))
#define REEL_X      6                      /* the slot machine's reels in its picture */
#define REEL_Y      26
#define REEL_STEP   14

static uint8_t n_spr;                      /* sprites written this frame */
static uint8_t last_n;                     /* and the frame before: the rest is hidden */

static const uint8_t HI_X[4] = { 0x01, 0x04, 0x10, 0x40 };
static const uint8_t HI_LARGE[4] = { 0x02, 0x08, 0x20, 0x80 };

/* One sprite with its upper left corner at screen pixel (x, y); outside the screen it is left
 * out. The SNES draws a sprite one line lower than its y, hence y - 1. */
static void spr(int16_t x, int16_t y, uint16_t tile, uint8_t attr, uint8_t large)
{
    uint8_t *o;
    uint8_t n = n_spr;
    uint8_t bits;

    /* on the screen, -16 < x < 256 and -16 < y < 224: two unsigned comparisons instead of four
     * signed ones, each of which dampf compiles with an eor #$8000 more */
    if (n >= 128 || (uint16_t)(x + 15) >= SCREEN_W + 15 || (uint16_t)(y + 15) >= SCREEN_H + 15) {
        return;
    }
    o = &sdevkit_oam_memory[(uint16_t)n * 4];
    o[0] = (uint8_t)x;
    o[1] = (uint8_t)(y - 1);
    o[2] = (uint8_t)tile;
    o[3] = (uint8_t)(attr | PRIO | (uint8_t)(tile >> 8));
    bits = large ? HI_LARGE[n & 3] : 0;
    if (x < 0) {
        bits |= HI_X[n & 3];
    }
    if (bits) {
        sdevkit_oam_memory[512 + (n >> 2)] |= bits;
    }
    n_spr = (uint8_t)(n + 1);
}

/* A 16 x 16 sprite around the centre (x, y) in 12.4. */
static void big(int16_t x, int16_t y, uint16_t tile, uint8_t attr)
{
    spr((int16_t)(PX(x) - 8), (int16_t)(PX(y) - 8), tile, attr, 1);
}

/* An 8 x 8 sprite around the centre. */
static void small(int16_t x, int16_t y, uint16_t tile, uint8_t attr)
{
    spr((int16_t)(PX(x) - 4), (int16_t)(PX(y) - 4), tile, attr, 0);
}

/* A 32 x 32 enemy: four 16 x 16 sprites, cell k the upper left of its block in sheet B. */
static void huge(int16_t x, int16_t y, uint8_t k, uint8_t attr)
{
    int16_t px = (int16_t)(PX(x) - 16), py = (int16_t)(PX(y) - 16);

    spr(px, py, B(k), attr, 1);
    spr((int16_t)(px + 16), py, B(k + 1), attr, 1);
    spr(px, (int16_t)(py + 16), B(k + 8), attr, 1);
    spr((int16_t)(px + 16), (int16_t)(py + 16), B(k + 9), attr, 1);
}

/* The slot machine: 48 x 64, twelve 16 x 16 sprites (sheet B, cells 32 on, frame 1 three cells
 * further), its three reels in front as 8 x 8 symbols (cell 38: mug, pretzel, bell, seven). */
static void slot_machine(const Obj *e, uint8_t pal)
{
    int16_t x0 = (int16_t)(PX(e->x) - 24), y0 = (int16_t)(PX(e->y) - 32);
    uint8_t base = (uint8_t)(32 + ((e->anim >> 4) & 1) * 3);
    uint8_t r, c;

    for (c = 0; c < 3; c++) {                       /* the reels first: in front */
        uint8_t s = slot_reel(e, c);
        spr((int16_t)(x0 + REEL_X + c * REEL_STEP), (int16_t)(y0 + REEL_Y), (uint16_t)(B(38) + (s & 1) + (s & 2) * 8), PAL_B, 0);
    }
    for (r = 0; r < 4; r++) {
        for (c = 0; c < 3; c++) {
            spr((int16_t)(x0 + c * 16), (int16_t)(y0 + r * 16), B(base + r * 8 + c), pal, 1);
        }
    }
}

/* Die Tuba: 64 x 32, eight 16 x 16 sprites (sheet C, cells 8 to 11 and 16 to 19), blowing (cells 12
 * on) while the floor jumps. */
static void tuba(const Obj *e, uint8_t pal)
{
    int16_t x0 = (int16_t)(PX(e->x) - 32), y0 = (int16_t)(PX(e->y) - 16);
    uint8_t base = (uint8_t)(oompah > 0 ? 12 : 8);
    uint8_t c;

    for (c = 0; c < 4; c++) {
        spr((int16_t)(x0 + c * 16), y0, C(base + c), pal, 1);
        spr((int16_t)(x0 + c * 16), (int16_t)(y0 + 16), C(base + 8 + c), pal, 1);
    }
}

static void begin(void)
{
    uint8_t i;

    n_spr = 0;
    for (i = 0; i < 32; i++) {                      /* memset is a byte loop of its own, with a call */
        sdevkit_oam_memory[512 + i] = 0;
    }
}

static void finish(void)
{
    uint8_t n;

    for (n = n_spr; n < last_n; n++) {
        sdevkit_oam_memory[(uint16_t)n * 4 + 1] = SDEVKIT_ATTR2_DISABLED;
    }
    last_n = n_spr;
}

void draw_sprite(int16_t x, int16_t y, uint16_t tile, uint8_t pal, uint8_t large)
{
    spr(x, y, tile, (uint8_t)(pal << 1), large);
}

void sprites_hide_all(void)
{
    begin();
    last_n = 128;
    finish();
}

/* --- the player ------------------------------------------------------------------------------ */
static void draw_player(void)
{
    if (ship_alive && (ship_blink & 4) == 0) {
        uint16_t t = A(ship_tilt * 2);
        int16_t x = (int16_t)(PX(ship_x) - 16), y = (int16_t)(PX(ship_y) - 8);
        spr(x, y, t, PAL_A, 1);
        spr((int16_t)(x + 16), y, (uint16_t)(t + 2), PAL_A, 1);
    }
    if (crate_state != C_NONE) {
        uint8_t k = crate_bottles == 0 ? 6 : crate_bottles <= 6 ? 7 : crate_bottles <= 12 ? 8 : 9;
        big(crate_x, crate_y, A(k), (uint8_t)(PAL_A | (crate_state == C_BACK ? HFLIP : 0)));
    }
    if (ship_alive && charge > 8) {                 /* the Sekt shakes above the ship */
        uint8_t k = charge < 60 ? 13 : charge < 120 ? 14 : 15;
        int16_t shake = (frame_count & 2) ? (int16_t)(charge >> 5) : (int16_t)-(charge >> 5);
        spr((int16_t)(PX(ship_x) - 4 + shake), (int16_t)(PX(ship_y) - 22), A(k), PAL_A, 1);
    }
}

static void draw_bullets(uint8_t flicker)
{
    uint8_t i;

    for (i = 0; i < MAX_BULLETS; i++) {
        Obj *b = &bullets[i];
        uint16_t t;
        if (b->kind == B_NONE || (flicker && ((i ^ frame_count) & 1))) {
            continue;
        }
        if (b->kind == B_BOTTLE) {                  /* an empty given back: tumbling */
            uint8_t f = (uint8_t)((b->anim >> 2) & 7);
            big(b->x, b->y, A(16 + (f & 3)), (uint8_t)(PAL_A | ((f & 4) ? HFLIP | VFLIP : 0)));
            continue;
        }
        switch (b->kind) {
        case B_PEANUT:
            t = (uint16_t)(B(14) + ((b->anim >> 3) & 1));
            break;
        case B_DART:
            t = (uint16_t)(B(14) + 16);
            break;
        case B_COIN:
            t = (uint16_t)(B(39) + ((b->anim >> 2) & 1));
            break;
        case B_NOTE:                                /* a quarter or an eighth note, by turns */
            small(b->x, b->y, (uint16_t)(C(4) + ((b->anim & 8) ? 16 : 1)), PAL_C);
            continue;
        case B_BREZEL:
            small(b->x, b->y, C(5), PAL_C);
            continue;
        default:
            t = (uint16_t)(B(14) + 17);
            break;
        }
        small(b->x, b->y, t, PAL_B);
    }
}

static void draw_items(void)
{
    uint8_t i;

    for (i = 0; i < MAX_ITEMS; i++) {
        Obj *it = &items[i];
        switch (it->kind) {
        case I_NONE:
            break;
        case I_SIXPACK:
            big(it->x, it->y, A(26 + ((it->anim >> 4) & 1)), PAL_A);
            break;
        case I_RUCKEL:                              /* the same six-pack, one colour off */
            big(it->x, it->y, A(26 + ((it->anim >> 4) & 1)), PAL_RUCKEL);
            break;
        case I_SEKT:
            big(it->x, it->y, A(28), PAL_A);
            break;
        case I_ESPRESSO:
            big(it->x, it->y, A(29), PAL_A);
            break;
        case I_BOTTLE: {                            /* tumbling: 8 steps, the second half flipped */
            uint8_t f = (uint8_t)((it->anim >> 2) & 7);
            big(it->x, it->y, A(16 + (f & 3)), (uint8_t)(PAL_A | ((f & 4) ? HFLIP | VFLIP : 0)));
            break;
        }
        default:
            big(it->x, it->y, A(20 + ((it->anim >> 3) & 1)), (uint8_t)(PAL_A | ((it->anim & 16) ? HFLIP : 0)));
            break;
        }
    }
}

static void draw_shots(void)
{
    uint8_t i;

    for (i = 0; i < MAX_SHOTS; i++) {
        Obj *s = &shots[i];
        uint8_t flip = s->vx < 0 ? HFLIP : 0;
        switch (s->kind) {
        case S_NONE:
            break;
        case S_CAP:
            small(s->x, s->y, (uint16_t)(A(30) + ((s->anim >> 2) & 1)), PAL_A);
            break;
        case S_BOTTLE:
            big(s->x, s->y, A(10 + ((s->anim >> 3) & 1)), (uint8_t)(PAL_A | flip | (s->vy > 0 ? VFLIP : 0)));
            break;
        case S_CORK:
            big(s->x, s->y, A(12), (uint8_t)(PAL_A | flip));
            break;
        default:
            small(s->x, s->y, (uint16_t)(A(30) + 16 + ((s->anim >> 3) & 1)), PAL_A);
            break;
        }
    }
}

static void draw_enemies(uint8_t flicker)
{
    uint8_t i;

    for (i = 0; i < MAX_ENEMIES; i++) {
        Obj *e = &enemies[i];
        uint8_t pal;
        if (e->kind == E_NONE || (flicker && ((i ^ frame_count) & 1))) {
            continue;
        }
        pal = (e->flags & 2) ? PAL_WHITE : PAL_B;     /* a hit blinks: white every other two frames */
        switch (e->kind) {
        case E_BEERMAT:
            big(e->x, e->y, B((e->anim >> 2) & 3), pal);
            break;
        case E_PRETZEL:
            big(e->x, e->y, B(4 + ((e->anim >> 3) & 1)), pal);
            break;
        case E_DICECUP:
            big(e->x, e->y, B(6 + e->state), (uint8_t)(pal | ((e->flags & F_CEILING) ? VFLIP : 0)));
            break;
        case E_BARREL:
            big(e->x, e->y, B(8 + ((e->anim >> 3) & 3)), pal);
            break;
        case E_FLY:
            big(e->x, e->y, B(12 + ((e->anim >> 1) & 1)), (uint8_t)(pal | (e->vx > 0 ? HFLIP : 0)));
            break;
        case E_DARTBOARD:
            huge(e->x, e->y, (uint8_t)(16 + ((e->anim >> 3) & 1) * 2), pal);
            break;
        case E_SLOT:
            slot_machine(e, pal);
            break;
        case E_METTIGEL:
            big(e->x, e->y, B(46 + ((e->anim >> 3) & 1)), (uint8_t)(pal | ((e->flags & F_CEILING) ? VFLIP : 0)));
            break;
        case E_HOP:
            big(e->x, e->y, B(54 + ((e->anim >> 2) & 1)), pal);
            break;
        case E_HENDL:
            big(e->x, e->y, C((e->anim >> 2) & 1), (e->flags & 2) ? PAL_WHITE : PAL_C);
            break;
        case E_HEART:
            big(e->x, e->y, C(2 + ((e->anim >> 5) & 1)), (e->flags & 2) ? PAL_WHITE : PAL_C);
            break;
        case E_TUBA:
            tuba(e, (e->flags & 2) ? PAL_WHITE : PAL_C);
            break;
        default:
            huge(e->x, e->y, (uint8_t)(20 + ((e->anim >> 4) & 1) * 2), pal);
            break;
        }
    }
}

static void draw_parts(void)
{
    uint8_t i;

    for (i = 0; i < MAX_PARTS; i++) {
        Obj *p = &parts[i];
        switch (p->kind) {
        case P_NONE:
            break;
        case P_PUFF:
            big(p->x, p->y, A(22 + ((16 - p->timer) >> 2)), PAL_A);
            break;
        case P_FOAM:
            small(p->x, p->y, (uint16_t)(A(30) + (p->timer > 14 ? 16 : 17)), PAL_A);
            break;
        case P_SHARD:
            small(p->x, p->y, (uint16_t)(A(31) + ((p->anim >> 2) & 1)), PAL_A);
            break;
        case P_EXHAUST:
            small(p->x, p->y, (uint16_t)(A(31) + 16), PAL_A);
            break;
        default:
            small(p->x, p->y, (uint16_t)(A(31) + 17), PAL_A);
            break;
        }
    }
}

/* Everything, in the order of importance. With flicker (the stutter) every other enemy and bullet
 * is left out, the others the next time. */
void draw_all(uint8_t flicker)
{
    begin();
    draw_player();
    draw_bullets(flicker);
    draw_items();
    draw_shots();
    draw_enemies(flicker);
    boss_draw();
    draw_parts();
    finish();
}

/* The title: "ER-TÜP" in big letters (sheet A, rows 4 and 5) and the subtitle (row 6). */
void draw_title_logo(void)
{
    uint8_t k;

    begin();
    for (k = 0; k < 8; k++) {
        spr((int16_t)(64 + k * 16), 40, A(32 + k), PAL_A, 1);
        spr((int16_t)(64 + k * 16), 56, A(40 + k), PAL_A, 1);
        spr((int16_t)(64 + k * 16), 76, A(48 + k), PAL_A, 1);
    }
    finish();
}
