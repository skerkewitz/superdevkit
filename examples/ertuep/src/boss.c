/*
 * Der Pfandautomat: stage 1's boss, a bottle-return machine as large as the screen.
 *
 * It is a picture on background layer 2, not sprites: when the camera reaches the end of the
 * stage the lights go out (a fade), the wall behind the stage is gone from layer 2, and the
 * machine's tiles, palette and map take its place (boss_start, with the screen off). Its scroll
 * registers move it: it slides in from the right and, once broken, rocks. Its picture has three
 * states (bossmap.c, drawn by dev/art/ertuep.py: shutter closed, intake open, front broken) that
 * share most cells; a change of state copies the rows of the map again.
 *
 * Its phases are a state machine: closed, it prints receipts at the player and takes no damage;
 * open, the intake takes damage, but it swallows what is shot into it and spits bottles back
 * ("Flasche wird nicht angenommen"); below half its strength the front breaks open and it
 * attacks faster; destroyed, it rains empties, the jackpot of deposit.
 */
#include "ertuep.h"
#include "boss_gfx.h"
#include "boss2_gfx.h"

#define VRAM_BG2_TILES 0x2000              /* over the bar's wall, which the kitchen does not show */
#define VRAM_BG2_MAP   0x4800
#define BOSS_W         10                  /* cells of 16 pixels */
#define BOSS_H         12
#define BOSS_HP        120
#define BOSS_Y         20                  /* its top under the status line, its plinth behind the floor */
#define PAL4           ((uint16_t)(4 << 10))

extern const uint8_t BOSS_MAP[3][12][10], BOSS2_MAP[3][12][10];

uint8_t boss_state;
static uint8_t kind;                       /* 0: der Pfandautomat, 1: die Sudpfanne, 2: das Kettenkarussell (festzelt.c) */
static const uint8_t (*maps)[12][10] = BOSS_MAP;   /* its picture in three looks */
static int16_t bx, by;                     /* the picture's upper left corner on the screen, pixels */
static uint8_t hp, timer, look, shown_look, rock;
static uint8_t spit_wait, swallowed, message_wait, hurt;
static uint16_t row_buf[BOSS_W];
static uint8_t rows_left;                  /* rows of the map still to copy after a change of look */
static uint8_t rise;                       /* the kettle's creature: how far its head is out, 0..28 */
static uint8_t spit;                       /* frames since the creature last spat */
static uint8_t reach;                      /* and how many segments its tentacles have, 0..TENTACLE */

/* --- the picture ------------------------------------------------------------------------------ */
static void build_row(uint8_t r)
{
    uint8_t c;

    for (c = 0; c < BOSS_W; c++) {
        uint8_t k = maps[look][r][c];
        uint16_t e = (uint16_t)(((uint16_t)(k & 0x7F) >> 3) * 32 + (uint16_t)(k & 7) * 2);
        if (k & 0x7F) {
            e |= PAL4;
        }
        row_buf[c] = (k & 0x80) ? (uint16_t)(e | SDEVKIT_BG_TIL_FLIPX) : e;
    }
}

/* With the screen off: the machine's tiles and colours, layer 2's map cleared but for it. */
void boss_start(void)
{
    static const uint16_t zero[32];
    uint8_t r;

    kind = stage_no;
    if (kind == 2) {                                /* in mode 7: festzelt.c */
        carousel_start();
        return;
    }
    maps = kind == 0 ? BOSS_MAP : BOSS2_MAP;
    if (kind == 0) {
        sdevkit_vram_copy(VRAM_BG2_TILES, boss_gfx_tiles, BOSS_GFX_TILES_SIZE);
        sdevkit_cgram_copy(64, boss_gfx_pal, BOSS_GFX_PAL_SIZE);
    } else {
        sdevkit_vram_copy(VRAM_BG2_TILES, boss2_gfx_tiles, BOSS2_GFX_TILES_SIZE);
        sdevkit_cgram_copy(64, boss2_gfx_pal, BOSS2_GFX_PAL_SIZE);
    }
    for (r = 0; r < 64; r++) {                      /* 64 rows of 32 entries: both halves */
        sdevkit_vram_copy((uint16_t)(VRAM_BG2_MAP + r * 32), zero, sizeof zero);
    }
    look = 0;
    for (r = 0; r < BOSS_H; r++) {
        build_row(r);
        sdevkit_vram_copy((uint16_t)(VRAM_BG2_MAP + r * 32), row_buf, sizeof row_buf);
    }
    shown_look = 0;
    rows_left = 0;
    bx = SCREEN_W;
    by = BOSS_Y;
    hp = BOSS_HP;
    timer = 0;
    rock = 0;
    swallowed = 0;
    spit_wait = 0;
    message_wait = 0;
    hurt = 0;
    boss_state = BS_ENTER;
    walls_off = 1;
    rise = 0;
    spit = 0;
    reach = 0;
    hud_message(kind == 0 ? "DER PFANDAUTOMAT" : "DIE SUDPFANNE", 150);
}

/* In the vertical blank: the next row of a changed picture, and where the machine is. */
void boss_vblank(void)
{
    if (rows_left > 0) {
        uint8_t r = (uint8_t)(BOSS_H - rows_left);
        build_row(r);
        sdevkit_vram_copy((uint16_t)(VRAM_BG2_MAP + r * 32), row_buf, sizeof row_buf);
        rows_left--;
    }
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG2HOFS, (uint16_t)(-bx & 0x3FF));
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG2VOFS, (uint16_t)((-by - 1) & 0x3FF));
}

static void set_look(uint8_t l)
{
    if (look != l) {
        look = l;
        rows_left = BOSS_H;
    }
}

/* --- its attacks ------------------------------------------------------------------------------- */
#define TENTACLE  7                        /* the kettle's creature: knots a tentacle */
#define HEAD_X    (bx + 80)                /* its head above the kettle's rim */
#define HEAD_Y    (by + 40 - rise)     /* the kettle's opening is 40 pixels down its picture */
#define INTAKE_X (bx + 64)
#define INTAKE_Y (by + 96)
#define SLOT_X   (bx + 116)
#define SLOT_Y   (by + 104)

static void receipts(void)
{
    fire_at_ship(B_COIN, FX(SLOT_X - 120), FX(SLOT_Y), 0);
}

static void spit_bottles(uint8_t n)
{
    int8_t t;

    for (t = (int8_t)-(n >> 1); t <= (int8_t)(n >> 1); t++) {
        fire_at_ship(B_BOTTLE, FX(INTAKE_X - 20), FX(INTAKE_Y), t);
    }
}

static void coin_ring(void)
{
    uint8_t d;

    for (d = 0; d < 16; d += 2) {
        bullet_dir(B_COIN, FX(INTAKE_X - 16), FX(INTAKE_Y), d);
    }
}

static void machine_update(void)
{
    switch (boss_state) {
    case BS_ENTER:                                  /* in from the right, a pixel a frame */
        bx--;
        if (bx <= 96) {
            boss_state = BS_CLOSED;
            timer = 0;
        }
        break;
    case BS_CLOSED:                                 /* the shutter is down: receipts */
        set_look(0);
        if ((timer & 31) == 0 && ship_alive) {
            receipts();
        }
        if (timer == 150) {
            boss_state = BS_OPEN;
            timer = 0;
            hud_message("BITTE FLASCHEN EINWERFEN", 120);
        }
        break;
    case BS_OPEN:                                   /* the intake is open: it can be hurt */
        set_look(1);
        if (timer % 70 == 0 && ship_alive) {
            spit_bottles(3);
        }
        if (hp <= BOSS_HP / 2) {
            boss_state = BS_BROKEN;
            timer = 0;
            hud_message("STOERUNG! BITTE PERSONAL RUFEN", 180);
            part_spawn(P_PUFF, FX(INTAKE_X), FX(INTAKE_Y - 20), -16, -16);
            part_spawn(P_PUFF, FX(INTAKE_X + 20), FX(INTAKE_Y), 16, -8);
        } else if (timer == 240) {
            boss_state = BS_CLOSED;
            timer = 0;
        }
        break;
    case BS_BROKEN:                                 /* broken open, rocking, everything at once */
        set_look(2);
        rock++;
        by = (int16_t)(BOSS_Y + (SINE_AT(rock) >> 5));
        if ((timer & 63) == 0 && ship_alive) {
            spit_bottles(5);
        } else if ((timer & 63) == 32 && ship_alive) {
            coin_ring();
        }
        if ((timer & 15) == 0) {
            part_spawn(P_SPARK, FX(INTAKE_X + (int16_t)(random16() & 31) - 16), FX(INTAKE_Y - 24), 0, -16);
        }
        break;
    case BS_DYING:                                  /* bursts all over it, then the deposit rains */
        if ((timer & 3) == 0) {
            int16_t x = (int16_t)(bx + 16 + (int16_t)(random16() & 127));
            int16_t y = (int16_t)(by + 24 + (int16_t)(random16() & 127));
            part_spawn(P_PUFF, FX(x), FX(y), 0, -12);
        }
        if (timer == 150) {
            uint8_t i;
            for (i = 0; i < 10; i++) {
                item_spawn(i < 4 ? I_CAN : I_BOTTLE, FX(INTAKE_X - 24 + i * 4), FX(INTAKE_Y));
            }
            bx = SCREEN_W + 64;                     /* gone */
            boss_state = BS_DONE;
            timer = 0;
        }
        break;
    default:
        break;
    }
}

/* The height the demo's pilot aims at: the intake, while there is a machine. */
int16_t boss_aim_y(void)
{
    if (boss_state < BS_CLOSED || boss_state > BS_BROKEN) {
        return -1;
    }
    if (kind == 2) {
        return carousel_aim_y();
    }
    return (int16_t)(kind == 0 ? INTAKE_Y : HEAD_Y);
}

/* --- what hits it ------------------------------------------------------------------------------ */
static uint8_t in_box(int16_t x, int16_t y, int16_t x0, int16_t y0, int16_t x1, int16_t y1)
{
    return (uint8_t)(x >= x0 && x < x1 && y >= y0 && y < y1);
}

static void damage(uint8_t n)
{
    if (hp <= n) {
        hp = 0;
        boss_state = BS_DYING;
        timer = 0;
        hud_message(kind == 0 ? "PFANDAUTOMAT AUSSER BETRIEB" : "DAS METT IST ALLE", 255);
        return;
    }
    hp = (uint8_t)(hp - n);
}

static void machine_collide(void)
{
    uint8_t i;
    uint8_t open = (uint8_t)(boss_state == BS_OPEN || boss_state == BS_BROKEN);

    if (boss_state == BS_NONE || boss_state >= BS_DYING) {
        return;
    }
    for (i = 0; i < MAX_SHOTS; i++) {
        Obj *s = &shots[i];
        int16_t sx, sy;
        if (s->kind == S_NONE) {
            continue;
        }
        sx = PX(s->x);
        sy = PX(s->y);
        if (open && in_box(sx, sy, INTAKE_X - 20, INTAKE_Y - 20, INTAKE_X + 20, INTAKE_Y + 20)) {
            if (s->kind == S_CORK) {                /* a cork: no deposit on it, it hurts */
                if (hurt == 0) {
                    damage(s->hp);
                    hurt = 8;
                    part_spawn(P_PUFF, s->x, s->y, -8, -8);
                }
                continue;
            }
            damage(s->kind == S_BOTTLE ? 2 : 1);    /* swallowed */
            s->kind = S_NONE;
            if (++swallowed >= 4) {                 /* and given back */
                swallowed = 0;
                spit_wait = 20;
            }
        } else if (open && sy > INTAKE_Y - 20 && sy < INTAKE_Y + 20 && sx < INTAKE_X) {
            continue;                               /* on its way into the open intake */
        } else if (in_box(sx, sy, bx + 6, by + 8, bx + 156, by + 180)) {
            part_spawn(P_SPARK, s->x, s->y, -16, 0);    /* steel */
            s->kind = S_NONE;
        }
    }
    if (spit_wait > 0 && --spit_wait == 0 && ship_alive) {
        fire_at_ship(B_BOTTLE, FX(INTAKE_X - 20), FX(INTAKE_Y), 0);
        if (message_wait == 0) {
            hud_message("FLASCHE WIRD NICHT ANGENOMMEN", 120);
            message_wait = 240;
        }
    }
    if (ship_alive && !demo && in_box(PX(ship_x) + 12, PX(ship_y), bx + 6, by + 8, bx + 156, by + 180)) {
        ship_hit();
    }
}

/* --- die Sudpfanne: the brew kettle and the Mett creature in it ------------------------------------ */
/* The kettle is the picture on layer 2; the creature is sprites: a head of 32 x 32 that rises from
 * the kettle when the lid opens, and two tentacles of knots that wave toward the player. Only the
 * head can be hurt; the knots hurt the ship (shots pass through them: they are not a shield). Closed, the kettle blows steam (foam
 * balls) out of its vent. */
static int16_t knot_x[2][TENTACLE], knot_y[2][TENTACLE];

/* Tables instead of signed divisions in the loop over the knots (C_STYLE.md, slow-signed-divide):
 * the wave along a tentacle (sine / 40) and a knot's step of 10 pixels in each of 16 directions. */
static const int8_t WAVE[64] = {
    0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 0, 0, 0,
    0, 0, 0, 0, -1, -1, -1, -2, -2, -2, -2, -2, -2, -3, -3, -3, -3, -3, -3, -3, -2, -2, -2, -2, -2, -2, -1, -1, -1, 0, 0, 0,
};
static const int8_t KNOT_DX[16] = { 10, 9, 7, 4, 0, -4, -7, -9, -10, -9, -7, -4, 0, 4, 7, 9 };
static const int8_t KNOT_DY[16] = { 0, 4, 7, 9, 10, 9, 7, 4, 0, -4, -7, -9, -10, -9, -7, -4 };

static void tentacles(void)
{
    uint8_t t, i;
    /* the wave's speed: a multiplication by a constant each, not by a variable (slow-multiply-variable) */
    uint8_t phase = boss_state == BS_BROKEN ? (uint8_t)(timer * 3) : (uint8_t)(timer * 2);

    for (t = 0; t < 2; t++) {
        int16_t x = (int16_t)(bx + 56 + t * 48), y = (int16_t)(by + 30);
        for (i = 0; i < TENTACLE; i++) {
            /* each knot turns a little more than the one before: a wave along it */
            uint8_t d = (uint8_t)((8 + t * 2 - 1 + WAVE[(uint8_t)(phase + i * 9 + t * 21) & 63]) & 15);
            x = (int16_t)(x + KNOT_DX[d]);
            y = (int16_t)(y + KNOT_DY[d]);
            knot_x[t][i] = x;
            knot_y[t][i] = y;
        }
    }
}

static void kettle_update(void)
{
    switch (boss_state) {
    case BS_ENTER:
        bx--;
        if (bx <= 96) {
            boss_state = BS_CLOSED;
            timer = 0;
        }
        break;
    case BS_CLOSED:                                 /* the lid is down: steam, and it hides */
        set_look(0);
        if (rise > 0) {
            rise--;
        }
        if (reach > 0 && (timer & 3) == 0) {
            reach--;
        }
        if ((timer & 15) == 0 && ship_alive) {
            bullet_dir(B_FOAMBALL, FX(bx + 40), FX(by + 40), (uint8_t)(9 + ((timer >> 4) & 3)));
        }
        if (timer == 120) {
            boss_state = BS_OPEN;
            timer = 0;
            hud_message("DAS METT IST WACH", 90);
        }
        break;
    case BS_OPEN:                                   /* the lid is up: the head comes out */
    case BS_BROKEN:
        set_look(boss_state == BS_OPEN ? 1 : 2);
        if (rise < 28) {
            rise++;
        }
        if (reach < TENTACLE && (timer & 3) == 0) {
            reach++;
        }
        if (++spit == (boss_state == BS_OPEN ? 50 : 30)) {     /* a counter: a remainder by a variable divides */
            spit = 0;
        }
        if (spit == 0 && ship_alive) {
            fire_at_ship(B_FOAMBALL, FX(HEAD_X - 12), FX(HEAD_Y + 6), 0);
            if (boss_state == BS_BROKEN) {
                fire_at_ship(B_FOAMBALL, FX(HEAD_X - 12), FX(HEAD_Y + 6), -2);
                fire_at_ship(B_FOAMBALL, FX(HEAD_X - 12), FX(HEAD_Y + 6), 2);
            }
        }
        if (boss_state == BS_OPEN) {
            if (hp <= BOSS_HP / 2) {
                boss_state = BS_BROKEN;
                timer = 0;
                hud_message("DER KESSEL PLATZT!", 150);
                part_spawn(P_PUFF, FX(bx + 80), FX(by + 100), -16, -16);
            } else if (timer == 250) {
                boss_state = BS_CLOSED;
                timer = 0;
            }
        } else {
            rock++;
            by = (int16_t)(BOSS_Y + (SINE_AT(rock) >> 5));
        }
        break;
    default:                                        /* dying and done: as the machine */
        rise = 0;
        reach = 0;
        machine_update();
        return;
    }
    tentacles();
}

static void kettle_collide(void)
{
    uint8_t i, t;
    uint8_t vulnerable = (uint8_t)(rise > 16);

    if (boss_state == BS_NONE || boss_state >= BS_DYING) {
        return;
    }
    for (i = 0; i < MAX_SHOTS; i++) {
        Obj *s = &shots[i];
        int16_t sx, sy;
        if (s->kind == S_NONE) {
            continue;
        }
        sx = PX(s->x);
        sy = PX(s->y);
        if (vulnerable && in_box(sx, sy, HEAD_X - 16, HEAD_Y - 16, HEAD_X + 16, HEAD_Y + 16)) {
            if (s->kind == S_CORK) {
                if (hurt == 0) {
                    damage(s->hp);
                    hurt = 8;
                }
                continue;
            }
            damage(s->kind == S_BOTTLE ? 2 : 1);
            part_spawn(P_SPARK, s->x, s->y, -8, -8);
            s->kind = S_NONE;
            continue;
        }
        if (s->kind != S_NONE && s->kind != S_CORK && in_box(sx, sy, bx + 10, by + 40, bx + 150, by + 180)) {
            part_spawn(P_SPARK, s->x, s->y, -16, 0);    /* copper */
            s->kind = S_NONE;
        }
    }
    if (!ship_alive || demo) {
        return;
    }
    {
        int16_t px = PX(ship_x), py = PX(ship_y);
        for (t = 0; t < 2; t++) {
            uint8_t k;
            for (k = 0; k < reach; k++) {
                if (in_box(px, py, knot_x[t][k] - 7, knot_y[t][k] - 5, knot_x[t][k] + 7, knot_y[t][k] + 5)) {
                    ship_hit();
                    return;
                }
            }
        }
        if (in_box(px + 12, py, bx + 10, by + 40, bx + 150, by + 180)) {
            ship_hit();
        }
    }
}

void boss_update(void)
{
    timer++;
    if (message_wait > 0) {
        message_wait--;
    }
    if (hurt > 0) {
        hurt--;
    }
    if (kind == 0) {
        machine_update();
    } else if (kind == 1) {
        kettle_update();
    } else {
        carousel_update();
    }
}

void boss_collide(void)
{
    if (kind == 0) {
        machine_collide();
    } else if (kind == 1) {
        kettle_collide();
    } else {
        carousel_collide();
    }
}

/* The creature's sprites: the knots first, then the head over them. */
void boss_draw(void)
{
    uint8_t t, k;

    if (kind == 2) {
        carousel_draw();
        return;
    }
    if (kind == 0 || boss_state < BS_CLOSED || boss_state > BS_BROKEN) {
        return;
    }
    if (rise > 0) {
        uint16_t base = (uint16_t)(SPRITE_A(56) + (spit < 12 ? 8 : 0));   /* the mouth open after it spat */
        int16_t x = (int16_t)(HEAD_X - 16), y = (int16_t)(HEAD_Y - 16);
        draw_sprite(x, y, base, 0, 1);
        draw_sprite((int16_t)(x + 16), y, (uint16_t)(base + 2), 0, 1);
        draw_sprite(x, (int16_t)(y + 16), (uint16_t)(base + 4), 0, 1);
        draw_sprite((int16_t)(x + 16), (int16_t)(y + 16), (uint16_t)(base + 6), 0, 1);
    }
    for (t = 0; t < 2; t++) {
        for (k = 0; k < reach; k++) {
            draw_sprite((int16_t)(knot_x[t][k] - 8), (int16_t)(knot_y[t][k] - 8),
                        k + 1 == reach ? SPRITE_B(63) : SPRITE_B(62), 1, 1);
        }
    }
}

