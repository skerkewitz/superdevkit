/*
 * Everything that moves besides the player: enemies, their bullets, items and empties, particles;
 * and the pair loops that find what hits what.
 *
 * Each table is an array of Obj, reached through a pointer to its element (Obj *e = &enemies[i]);
 * a free slot has kind 0. Each kind of enemy is a state machine in enemies_update (a switch on the
 * kind, then on its state). Whatever stands on the ground (dice cups, barrels) moves left with the
 * camera, the rest flies in screen space. A destroyed enemy may leave an item (the wave says which)
 * and empties: bottles and cans that tumble down under gravity and are the score, as deposit.
 *
 * random16 is the only source of chance, seeded when a game starts: the same game on the SNES and
 * on the PC, tick for tick (the checks compare them). C leaves the order of a call's arguments
 * open, so no statement calls it twice.
 */
#include "ertuep.h"

Obj enemies[MAX_ENEMIES], bullets[MAX_BULLETS], items[MAX_ITEMS], parts[MAX_PARTS];
uint16_t rnd = 1;
static uint8_t part_next;
static uint8_t spawn_at;                   /* where enemy_spawn looks for a free slot first */                  /* the particle slot to use next: the oldest goes first */

uint16_t random16(void)
{
    uint16_t x = rnd;
    x ^= (uint16_t)(x << 7);
    x ^= (uint16_t)((x >> 8) >> 1);
    x ^= (uint16_t)(x << 8);
    rnd = x;
    return x;
}

void objects_clear(void)
{
    memset(shots, 0, sizeof shots);
    memset(enemies, 0, sizeof enemies);
    memset(bullets, 0, sizeof bullets);
    memset(items, 0, sizeof items);
    memset(parts, 0, sizeof parts);
    spawn_at = 0;
}

/* --- tables per kind ------------------------------------------------------------------------- */
/* half the width and height of what hits, in pixels */
static const uint8_t ENEMY_HW[E_KINDS] = { 0, 7, 6, 6, 7, 4, 14, 12, 22, 7, 6, 7, 7, 28 };
static const uint8_t ENEMY_HH[E_KINDS] = { 0, 6, 6, 6, 7, 4, 14, 14, 30, 6, 6, 6, 7, 13 };
static const uint8_t ENEMY_HP[E_KINDS] = { 0, 1, 1, 4, 6, 1, 16, 24, 80, 6, 2, 2, 3, 90 };
/* empties left behind: how many, and 1 when the last of them is a can */
static const uint8_t ENEMY_EMPTIES[E_KINDS] = { 0, 1, 1, 1, 2, 0, 3, 4, 6, 1, 1, 1, 1, 6 };
static const uint8_t ENEMY_CAN[E_KINDS] = { 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1 };
static const uint8_t SHOT_HW[5] = { 0, 3, 7, 6, 3 };
static const uint8_t SHOT_HH[5] = { 0, 3, 3, 5, 3 };
static const uint8_t SHOT_DAMAGE[5] = { 0, 1, 2, 0, 1 };   /* the cork's is in its hp */

/* A sine of 64 steps, 127 at the top. */
static const int8_t SINE[64] = {
    0, 12, 25, 37, 49, 60, 71, 81, 90, 98, 106, 112, 117, 122, 125, 126,
    127, 126, 125, 122, 117, 112, 106, 98, 90, 81, 71, 60, 49, 37, 25, 12,
    0, -12, -25, -37, -49, -60, -71, -81, -90, -98, -106, -112, -117, -122, -125, -126,
    -127, -126, -125, -122, -117, -112, -106, -98, -90, -81, -71, -60, -49, -37, -25, -12,
};

int8_t sine(uint8_t a)
{
    return SINE[a & 63];
}

/* 16 directions, 0 to the right, 4 down; one pixel a frame in 12.4 */
static const int8_t DIR_X[16] = { 16, 15, 11, 6, 0, -6, -11, -15, -16, -15, -11, -6, 0, 6, 11, 15 };
static const int8_t DIR_Y[16] = { 0, 6, 11, 15, 16, 15, 11, 6, 0, -6, -11, -15, -16, -15, -11, -6 };

/* The direction from (x, y) to the ship, one of 16, without a division: the slope compared with
 * the tangents of 11.25, 33.75, 56.25 and 78.75 degrees (1/5, 2/3, 3/2, 5). */
static uint8_t aim(int16_t x, int16_t y)
{
    int16_t dx = (int16_t)(PX(ship_x) - PX(x));
    int16_t dy = (int16_t)(PX(ship_y) - PX(y));
    int16_t ax = dx < 0 ? (int16_t)-dx : dx;
    int16_t ay = dy < 0 ? (int16_t)-dy : dy;
    uint8_t q;

    if (ay * 5 < ax) {
        q = 0;
    } else if (ay * 3 < ax * 2) {
        q = 1;
    } else if (ay * 2 < ax * 3) {
        q = 2;
    } else if (ay < ax * 5) {
        q = 3;
    } else {
        q = 4;
    }
    if (dx >= 0) {
        return dy >= 0 ? q : (uint8_t)((16 - q) & 15);
    }
    return dy >= 0 ? (uint8_t)(8 - q) : (uint8_t)(8 + q);
}

/* --- spawning ---------------------------------------------------------------------------------- */
/* The search for a free slot starts after the slot taken last (spawn_at): a wave of ten takes one
 * pass over the table, not ten. */
Obj *enemy_spawn(uint8_t kind, int16_t x, int16_t y, uint8_t param)
{
    uint8_t i;

    for (i = 0; i < MAX_ENEMIES; i++) {
        Obj *e = &enemies[spawn_at];
        spawn_at = (uint8_t)((spawn_at + 1) & (MAX_ENEMIES - 1));
        if (e->kind == E_NONE) {
            e->kind = kind;
            e->x = x;
            e->y = y;
            e->vx = 0;
            e->vy = 0;
            e->state = param;
            e->timer = 0;
            e->hp = ENEMY_HP[kind];
            e->anim = 0;
            e->flags = 0;
            switch (kind) {
            case E_BEERMAT:
                e->vy = y;                          /* the middle of its wave */
                break;
            case E_DICECUP:
                if (param) {
                    e->flags = F_CEILING;
                }
                e->state = 0;
                e->timer = (uint8_t)(40 + (random16() & 31));
                break;
            case E_BARREL:
                e->vx = -12;
                break;
            case E_PRETZEL:
                e->vx = -22;
                break;
            case E_DARTBOARD:
            case E_STEIN:
                e->vx = -16;
                e->state = 0;
                break;
            case E_METTIGEL:
                if (param) {
                    e->flags = F_CEILING;
                }
                e->state = 0;
                e->vx = -10;
                e->timer = (uint8_t)(60 + (random16() & 63));
                break;
            case E_HOP:
                e->vy = y;                          /* the middle of its wave, as a beer mat's */
                break;
            case E_SLOT:
            case E_TUBA:
                e->vx = -16;
                e->state = 0;
                scroll_step = 0;                    /* the stage waits for it */
                break;
            case E_HENDL:
                e->vy = y;                          /* the middle of its flight, as a beer mat's */
                break;
            case E_HEART:
                e->vy = y;                          /* where its string hangs from */
                e->vx = -12;
                break;
            default:
                e->vx = -20;
                break;
            }
            return e;
        }
    }
    return 0;
}

static Obj *bullet_new(uint8_t kind, int16_t x, int16_t y, int16_t vx, int16_t vy)
{
    uint8_t i;

    for (i = 0; i < MAX_BULLETS; i++) {
        Obj *b = &bullets[i];
        if (b->kind == B_NONE) {
            b->kind = kind;
            b->x = x;
            b->y = y;
            b->vx = vx;
            b->vy = vy;
            b->anim = 0;
            return b;
        }
    }
    return 0;
}

/* A bullet in one of the 16 directions, a pixel and a half a frame. */
void bullet_dir(uint8_t kind, int16_t x, int16_t y, uint8_t dir)
{
    int16_t vx = DIR_X[dir & 15];
    int16_t vy = DIR_Y[dir & 15];

    bullet_new(kind, x, y, (int16_t)(vx + (vx >> 1)), (int16_t)(vy + (vy >> 1)));
}

/* A bullet toward the ship, a pixel and a half a frame, turned by `turn` sixteenths. */
void fire_at_ship(uint8_t kind, int16_t x, int16_t y, int8_t turn)
{
    uint8_t d = (uint8_t)((aim(x, y) + turn) & 15);
    int16_t vx = DIR_X[d];
    int16_t vy = DIR_Y[d];

    bullet_new(kind, x, y, (int16_t)(vx + (vx >> 1)), (int16_t)(vy + (vy >> 1)));
}

void part_spawn(uint8_t kind, int16_t x, int16_t y, int16_t vx, int16_t vy)
{
    Obj *p = &parts[part_next];

    part_next = (uint8_t)(part_next + 1 == MAX_PARTS ? 0 : part_next + 1);
    p->kind = kind;
    p->x = x;
    p->y = y;
    p->vx = vx;
    p->vy = vy;
    p->anim = 0;
    p->timer = kind == P_PUFF ? 16 : kind == P_EXHAUST ? 16 : kind == P_SPARK ? 12 : 28;
}

void item_spawn(uint8_t kind, int16_t x, int16_t y)
{
    uint8_t i;

    for (i = 0; i < MAX_ITEMS; i++) {
        Obj *it = &items[i];
        if (it->kind == I_NONE) {
            it->kind = kind;
            it->x = x;
            it->y = y;
            it->anim = 0;
            it->timer = 0;
            it->state = 0;
            if (kind >= I_BOTTLE) {                 /* empties fly up and tumble down */
                it->vx = (int16_t)((random16() & 31) - 20);
                it->vy = (int16_t)(-24 - (int16_t)(random16() & 15));
            } else {
                it->vx = -8;
                it->vy = 0;
            }
            return;
        }
    }
}

static void enemy_destroyed(Obj *e)
{
    uint8_t n = ENEMY_EMPTIES[e->kind];
    uint8_t drop = (uint8_t)((e->flags & F_DROP) >> 4);
    uint8_t i;

    part_spawn(P_PUFF, e->x, e->y, 0, -6);
    if (e->kind == E_DARTBOARD || e->kind == E_STEIN) {
        part_spawn(P_PUFF, (int16_t)(e->x - FX(10)), (int16_t)(e->y + FX(8)), -8, -4);
        part_spawn(P_PUFF, (int16_t)(e->x + FX(10)), (int16_t)(e->y - FX(8)), 8, -4);
    }
    if (n == 1 && (random16() & 3) != 0 && e->kind != E_DICECUP && e->kind != E_METTIGEL) {
        n = 0;                                      /* small fry leaves an empty one time in four */
    }
    for (i = 0; i < n; i++) {
        item_spawn(i + 1 == n && ENEMY_CAN[e->kind] ? I_CAN : I_BOTTLE, e->x, e->y);
    }
    if (drop != I_NONE) {
        item_spawn(drop, e->x, e->y);
    }
    if (e->kind == E_SLOT) {
        scroll_step = 8;
        hud_message("SPIELSUCHT BESIEGT!", 120);
    } else if (e->kind == E_TUBA) {
        scroll_step = 8;
        hud_message("BLASKAPELLE VERSTUMMT!", 120);
    }
    e->kind = E_NONE;
}

/* A hit on an enemy; returns 1 when it was destroyed. */
static uint8_t enemy_hit(Obj *e, uint8_t damage)
{
    e->flags = (uint8_t)((e->flags & ~F_FLASH) | 6);
    if (e->hp <= damage) {
        enemy_destroyed(e);
        return 1;
    }
    e->hp = (uint8_t)(e->hp - damage);
    return 0;
}

/* --- the enemies' state machines ----------------------------------------------------------------- */
static void beermat(Obj *e)
{
    int16_t s = SINE[(uint8_t)(e->anim & 63)];

    e->x = (int16_t)(e->x - 22);
    e->y = (int16_t)(e->vy + (e->state == 2 ? s * 4 : s * 2));
    e->anim = (uint8_t)(e->anim + 1);
}

static void pretzel(Obj *e)
{
    e->x = (int16_t)(e->x + e->vx);
    e->y = (int16_t)(e->y + e->vy);
    e->timer++;
    if (e->timer > 36) {                            /* then it turns toward the ship */
        if (ship_y > (int16_t)(e->y + FX(4)) && e->vy < 20) {   /* int16_t: a 16-bit comparison (C_STYLE.md) */
            e->vy = (int16_t)(e->vy + 2);
        } else if (ship_y < (int16_t)(e->y - FX(4)) && e->vy > -20) {
            e->vy = (int16_t)(e->vy - 2);
        }
    }
    e->anim++;
}

static void dicecup(Obj *e)
{
    e->x = (int16_t)(e->x - scrolled);
    if (e->state == 0) {
        if (--e->timer == 0) {                      /* the cup lifts: a peanut */
            e->state = 1;
            e->timer = 16;
            if (PX(e->x) < SCREEN_W - 8 && ship_alive) {
                fire_at_ship(B_PEANUT, e->x, (int16_t)(e->y + ((e->flags & F_CEILING) ? FX(4) : -FX(4))), 0);
            }
        }
    } else if (--e->timer == 0) {
        e->state = 0;
        e->timer = 100;
    }
}

static void barrel(Obj *e)
{
    int16_t px, py;

    e->x = (int16_t)(e->x + e->vx - scrolled);
    e->y = (int16_t)(e->y + e->vy);
    px = PX(e->x);
    py = PX(e->y);
    if (solid_at(px, (int16_t)(py + 8))) {          /* on the ground: rolls */
        e->y = FX(((py + 8) & ~15) - 8);
        e->vy = 0;
    } else if (e->vy < 64) {
        e->vy = (int16_t)(e->vy + 3);
    }
    if (solid_at((int16_t)(px + (e->vx < 0 ? -8 : 8)), py)) {
        e->vx = (int16_t)-e->vx;                   /* off a wall */
    }
    e->anim = (uint8_t)(e->anim + (e->vx < 0 ? 1 : 255));
}

static void fly(Obj *e)
{
    if ((e->timer & 15) == 0) {                     /* a new idea every 16 frames */
        int16_t r = (int16_t)(random16() & 31);
        e->vx = (int16_t)(-28 + r);
        e->vy = ship_y > e->y ? 10 : -10;
        e->vy = (int16_t)(e->vy + (int16_t)(random16() & 15) - 8);
    }
    e->x = (int16_t)(e->x + e->vx);
    e->y = (int16_t)(e->y + e->vy);
    e->timer++;
    e->anim++;
}

static void dartboard(Obj *e)
{
    e->anim++;
    switch (e->state) {
    case 0:                                         /* in, until it is well on the screen */
        e->x = (int16_t)(e->x + e->vx);
        if (e->x < FX(196)) {
            e->state = 1;
            e->timer = 30;
            e->vy = 8;
        }
        break;
    case 1:                                         /* up and down, darts every second */
        e->y = (int16_t)(e->y + e->vy);
        if (e->y < FX(PLAY_TOP + 24) || e->y > FX(SCREEN_H - 40)) {
            e->vy = (int16_t)-e->vy;
        }
        if (--e->timer == 0) {
            bullet_new(B_DART, (int16_t)(e->x - FX(12)), e->y, -40, -10);
            bullet_new(B_DART, (int16_t)(e->x - FX(12)), e->y, -42, 0);
            bullet_new(B_DART, (int16_t)(e->x - FX(12)), e->y, -40, 10);
            e->timer = 60;
            e->vx++;                                /* vx counts the volleys now */
            if (e->vx > -8) {                       /* six volleys, then it leaves */
                e->state = 2;
            }
        }
        break;
    default:
        e->x = (int16_t)(e->x - 40);
        break;
    }
}

static void stein(Obj *e)
{
    e->anim++;
    switch (e->state) {
    case 0:
        e->x = (int16_t)(e->x + e->vx);
        if (e->x < FX(200)) {
            e->state = 1;
            e->timer = 40;
            e->vy = 0;
        }
        break;
    case 1:                                         /* a fan of foam balls toward the ship */
        e->y = (int16_t)(e->y + (SINE[(uint8_t)(e->anim & 63)] / 32));
        if (--e->timer == 0) {
            if (ship_alive) {
                fire_at_ship(B_FOAMBALL, (int16_t)(e->x - FX(10)), (int16_t)(e->y - FX(8)), -2);
                fire_at_ship(B_FOAMBALL, (int16_t)(e->x - FX(10)), (int16_t)(e->y - FX(8)), -1);
                fire_at_ship(B_FOAMBALL, (int16_t)(e->x - FX(10)), (int16_t)(e->y - FX(8)), 0);
                fire_at_ship(B_FOAMBALL, (int16_t)(e->x - FX(10)), (int16_t)(e->y - FX(8)), 1);
                fire_at_ship(B_FOAMBALL, (int16_t)(e->x - FX(10)), (int16_t)(e->y - FX(8)), 2);
            }
            e->timer = 90;
            e->vy++;
            if (e->vy == 6) {
                e->state = 2;
            }
        }
        break;
    default:
        e->x = (int16_t)(e->x - 24);
        break;
    }
}

/* The Mettigel (a hedgehog of minced pork, onion rings for spikes) crawls along the floor or the
 * ceiling with the ground and throws onion rings at the ship now and then. */
static void mettigel(Obj *e)
{
    e->x = (int16_t)(e->x + e->vx - scrolled);
    e->anim++;
    if (--e->timer == 0) {
        e->timer = 110;
        if (ship_alive && PX(e->x) < SCREEN_W - 8) {
            fire_at_ship(B_COIN, e->x, (int16_t)(e->y + ((e->flags & F_CEILING) ? FX(4) : -FX(4))), 0);
        }
    }
}

/* A hop cone flies in a wave, like a beer mat, but quicker and with a turn toward the ship. */
static void hop(Obj *e)
{
    int16_t s = SINE[(uint8_t)(e->anim & 63)];

    e->x = (int16_t)(e->x - 26);
    if (ship_y > (int16_t)(e->vy + FX(2))) {
        e->vy = (int16_t)(e->vy + 4);
    } else if (ship_y < (int16_t)(e->vy - FX(2))) {
        e->vy = (int16_t)(e->vy - 4);
    }
    e->y = (int16_t)(e->vy + (e->state == 2 ? s * 3 : s * 2));
    e->anim = (uint8_t)(e->anim + 2);
}

/* Der Spielautomat, the mid-boss: a slot machine that pays out in shots. Its three reels are two
 * bits each in vx (once it stands), spinning until their stop: 90, 110 and 130 frames into a
 * round. Three of a kind pay: beer mugs a fan of foam, pretzels three pretzels, bells a ring of
 * coins, sevens the jackpot, to the player; anything else three coins. The reels are rigged
 * toward a match, as the real ones are not. */
static void slot(Obj *e)
{
    uint8_t r0, r1, r2;

    e->anim++;
    if (e->state == 0) {
        e->x = (int16_t)(e->x + e->vx);
        if (e->x < FX(188)) {
            e->state = 1;
            e->timer = 0;
            e->vx = 0;
        }
        return;
    }
    e->y = (int16_t)(e->y + (SINE[(uint8_t)(e->anim & 63)] / 64));
    e->timer++;
    if (e->timer == 90) {
        e->vx = (int16_t)(random16() & 3);
    } else if (e->timer == 110) {
        r0 = (uint8_t)(e->vx & 3);
        r1 = (random16() & 1) ? r0 : (uint8_t)(random16() & 3);
        e->vx = (int16_t)(e->vx | (r1 << 2));
    } else if (e->timer == 130) {
        r0 = (uint8_t)(e->vx & 3);
        r2 = (random16() & 1) ? r0 : (uint8_t)(random16() & 3);
        e->vx = (int16_t)(e->vx | (r2 << 4));
        r1 = (uint8_t)((e->vx >> 2) & 3);
        if (r0 == r1 && r1 == r2) {
            uint8_t i;
            switch (r0) {
            case 0:
                for (i = 0; i < 7; i++) {
                    bullet_dir(B_FOAMBALL, (int16_t)(e->x - FX(20)), e->y, (uint8_t)(5 + i));
                }
                hud_message("PROST!", 60);
                break;
            case 1:
                enemy_spawn(E_PRETZEL, (int16_t)(e->x - FX(16)), (int16_t)(e->y - FX(24)), 0);
                enemy_spawn(E_PRETZEL, (int16_t)(e->x - FX(16)), e->y, 0);
                enemy_spawn(E_PRETZEL, (int16_t)(e->x - FX(16)), (int16_t)(e->y + FX(24)), 0);
                break;
            case 2:
                for (i = 0; i < 16; i += 2) {
                    bullet_dir(B_COIN, e->x, e->y, i);
                }
                break;
            default:
                for (i = 0; i < 5; i++) {
                    item_spawn(I_CAN, (int16_t)(e->x - FX(16)), e->y);
                }
                hud_message("JACKPOT!", 90);
                break;
            }
        } else if (ship_alive) {
            fire_at_ship(B_COIN, (int16_t)(e->x - FX(16)), (int16_t)(e->y + FX(20)), -1);
            fire_at_ship(B_COIN, (int16_t)(e->x - FX(16)), (int16_t)(e->y + FX(20)), 0);
            fire_at_ship(B_COIN, (int16_t)(e->x - FX(16)), (int16_t)(e->y + FX(20)), 1);
        }
    } else if (e->timer == 190) {
        e->timer = 0;
        e->vx = 0;
    }
}

/* --- das Festzelt ------------------------------------------------------------------------------------ */
/* A roast chicken that learned to fly: in a wave like a beer mat's, then, level with the ship, it
 * dives at it. */
static void hendl(Obj *e)
{
    e->anim++;
    if (e->state == 0) {
        e->x = (int16_t)(e->x - 20);
        e->y = (int16_t)(e->vy + SINE[(uint8_t)((e->anim << 1) & 63)] * 2);
        if (e->x < (int16_t)(ship_x + FX(72)) && ship_alive) {
            e->state = 1;
            e->vx = -36;
            e->vy = ship_y > e->y ? 12 : -12;
        }
        return;
    }
    e->x = (int16_t)(e->x + e->vx);
    e->y = (int16_t)(e->y + e->vy);
}

/* A gingerbread heart from a stall, swinging on its ribbon as it drifts by: 40 pixels up and down
 * about where it came in, a pretzel at the ship at the bottom of each swing. */
static void heart(Obj *e)
{
    e->anim++;
    e->x = (int16_t)(e->x + e->vx);
    e->y = (int16_t)(e->vy + SINE[(uint8_t)(e->anim & 63)] * 5);
    if ((e->anim & 63) == 16 && ship_alive) {
        fire_at_ship(B_BREZEL, e->x, e->y, 0);
    }
}

/* Die Tuba, the mid-boss: the band's tuba, blown by nobody. It blows notes at the ship, and every
 * third time a ring of them; each blast makes the floor jump (oompah). The stage waits for it. */
static void tuba(Obj *e)
{
    e->anim++;
    if (e->state == 0) {
        e->x = (int16_t)(e->x + e->vx);
        if (e->x < FX(192)) {
            e->state = 1;
            e->timer = 0;
            e->vx = 0;
        }
        return;
    }
    e->y = (int16_t)(e->y + (SINE[(uint8_t)(e->anim & 63)] / 64));
    e->timer++;
    if (e->timer == 60 || e->timer == 75 || e->timer == 90) {
        oompah = 8;
        if (ship_alive) {
            fire_at_ship(B_NOTE, (int16_t)(e->x - FX(28)), e->y, -1);
            fire_at_ship(B_NOTE, (int16_t)(e->x - FX(28)), e->y, 0);
            fire_at_ship(B_NOTE, (int16_t)(e->x - FX(28)), e->y, 1);
        }
    } else if (e->timer == 130) {
        uint8_t d;
        oompah = 14;
        for (d = 0; d < 16; d++) {
            bullet_dir(B_NOTE, (int16_t)(e->x - FX(28)), e->y, d);
        }
    } else if (e->timer == 170) {
        e->timer = 40;
    }
}

/* The symbol reel i shows: spinning until its stop, then where it stopped. */
uint8_t slot_reel(const Obj *e, uint8_t i)
{
    uint8_t stop = (uint8_t)(90 + i * 20);

    if (e->state == 0 || e->timer < stop) {
        return (uint8_t)(((e->anim >> 2) + i * 3) & 3);
    }
    return (uint8_t)((e->vx >> (i * 2)) & 3);
}

void enemies_update(void)
{
    uint8_t i;

    for (i = 0; i < MAX_ENEMIES; i++) {
        Obj *e = &enemies[i];
        int16_t px, py;
        if (e->kind == E_NONE) {
            continue;
        }
        if (e->flags & F_FLASH) {
            e->flags--;
        }
        switch (e->kind) {
        case E_BEERMAT:
            beermat(e);
            break;
        case E_PRETZEL:
            pretzel(e);
            break;
        case E_DICECUP:
            dicecup(e);
            break;
        case E_BARREL:
            barrel(e);
            break;
        case E_FLY:
            fly(e);
            break;
        case E_DARTBOARD:
            dartboard(e);
            break;
        case E_SLOT:
            slot(e);
            break;
        case E_METTIGEL:
            mettigel(e);
            break;
        case E_HOP:
            hop(e);
            break;
        case E_HENDL:
            hendl(e);
            break;
        case E_HEART:
            heart(e);
            break;
        case E_TUBA:
            tuba(e);
            break;
        default:
            stein(e);
            break;
        }
        px = PX(e->x);
        py = PX(e->y);
        if (px < -40 || py < -32 || py > SCREEN_H + 32) {
            e->kind = E_NONE;                       /* gone past */
        }
        if ((e->kind == E_SLOT || e->kind == E_TUBA) && e->y > FX(SCREEN_H - 48)) {
            e->y = FX(SCREEN_H - 48);
        }
    }
}

void bullets_update(void)
{
    uint8_t i;

    for (i = 0; i < MAX_BULLETS; i++) {
        Obj *b = &bullets[i];
        int16_t px, py;
        if (b->kind == B_NONE) {
            continue;
        }
        b->x = (int16_t)(b->x + b->vx);
        b->y = (int16_t)(b->y + b->vy);
        b->anim++;
        px = PX(b->x);
        py = PX(b->y);
        if (px < -8 || px > SCREEN_W + 8 || py < PLAY_TOP - 4 || py > SCREEN_H + 4 || ((b->anim & 3) == 0 && solid_at(px, py))) {
            b->kind = B_NONE;
        }
    }
}

void items_update(void)
{
    uint8_t i;

    for (i = 0; i < MAX_ITEMS; i++) {
        Obj *it = &items[i];
        int16_t px, py;
        if (it->kind == I_NONE) {
            continue;
        }
        it->anim++;
        if (it->kind >= I_BOTTLE) {                 /* an empty: gravity, one bounce, then it falls */
            it->vy = (int16_t)(it->vy + 2);
            if (it->vy > 64) {
                it->vy = 64;
            }
            it->x = (int16_t)(it->x + it->vx);
            it->y = (int16_t)(it->y + it->vy);
            if (it->vy > 0 && it->state == 0 && solid_at(PX(it->x), (int16_t)(PX(it->y) + 6))) {
                it->vy = (int16_t)-(it->vy >> 1);
                it->state = 1;
            }
        } else {                                    /* a pickup drifts, and bobs */
            it->x = (int16_t)(it->x + it->vx);
            it->y = (int16_t)(it->y + (SINE[(uint8_t)((it->anim << 1) & 63)] / 32));
        }
        px = PX(it->x);
        py = PX(it->y);
        if (px < -16 || px > SCREEN_W + 32 || py > SCREEN_H + 16) {
            it->kind = I_NONE;
        }
    }
}

void parts_update(void)
{
    uint8_t i;

    for (i = 0; i < MAX_PARTS; i++) {
        Obj *p = &parts[i];
        if (p->kind == P_NONE) {
            continue;
        }
        p->x = (int16_t)(p->x + p->vx);
        p->y = (int16_t)(p->y + p->vy);
        if (p->kind == P_FOAM || p->kind == P_SHARD) {
            p->vy = (int16_t)(p->vy + 2);
        }
        p->anim++;
        if (--p->timer == 0) {
            p->kind = P_NONE;
        }
    }
}

/* --- what hits what ------------------------------------------------------------------------------ */
/* Do two boxes overlap? x first: most pairs are apart on x, and y is then not looked at. */
static uint8_t overlap(int16_t ax, int16_t ay, uint8_t aw, uint8_t ah, int16_t bx, int16_t by, uint8_t bw, uint8_t bh)
{
    int16_t d = (int16_t)(ax - bx);

    if (d < 0) {
        d = (int16_t)-d;
    }
    if (d >= aw + bw) {
        return 0;
    }
    d = (int16_t)(ay - by);
    if (d < 0) {
        d = (int16_t)-d;
    }
    return (uint8_t)(d < ah + bh);
}

static void collect(Obj *it)
{
    switch (it->kind) {
    case I_SIXPACK:
        crate_grow(6);
        hud_message(crate_bottles <= 6 && crate_state == C_RETURN ? "EIN KASTEN BIER!" : "NACHSCHUB!", 90);
        break;
    case I_SEKT:
        has_sekt = 1;
        hud_message("SEKT! B HALTEN", 150);
        hud_sekt();
        break;
    case I_ESPRESSO:
        if (ship_speed < 3) {
            ship_speed++;
        }
        hud_message("ESPRESSO!", 90);
        break;
    case I_RUCKEL:
        start_ruckel();
        break;
    case I_BOTTLE:
        add_pfand(8);
        break;
    default:                                        /* a can */
        add_pfand(25);
        break;
    }
    part_spawn(P_SPARK, it->x, it->y, 0, -8);
    it->kind = I_NONE;
}

/* The live shots of this frame, gathered once for the pair loop: their pixel positions and half
 * sizes, and which shot each is, in the order of the table. And by x: the screen in buckets of 32
 * pixels (from -64 on), a bit per live shot in each bucket its box reaches. An enemy looks only at
 * the shots of the buckets its own box reaches, one to three, instead of at all twelve: a shot and
 * an enemy that overlap share a bucket, so nothing is missed, and the order is the table's. In the
 * busiest wave this was 192 pairs a frame at about 85 cycles each, a sixth of the frame. */
#define BUCKETS 14
static int16_t live_x[MAX_SHOTS], live_y[MAX_SHOTS];
static uint8_t live_w[MAX_SHOTS], live_h[MAX_SHOTS], live_shot[MAX_SHOTS];
static uint16_t bucket_shots[BUCKETS];

static uint8_t bucket(int16_t x)
{
    uint16_t u = (uint16_t)(x + 64);                /* -64 .. 383 is 0 .. 447 */

    if (u <= 447) {
        return (uint8_t)(u >> 5);
    }
    return (uint8_t)(u >= 0x8000 ? 0 : BUCKETS - 1);   /* left of -64 (or far right) */
}

void collide(void)
{
    uint8_t i, j, live = 0;
    uint16_t live_near, bit = 1;
    int16_t sx = PX(ship_x), sy = PX(ship_y);
    int16_t cx = PX(crate_x), cy = PX(crate_y);
    uint8_t crate = (uint8_t)(crate_state != C_NONE);
    uint8_t vulnerable = (uint8_t)(ship_alive && ship_blink == 0 && !demo);

    for (j = 0; j < BUCKETS; j++) {
        bucket_shots[j] = 0;                        /* words: memset is a byte loop */
    }
    for (j = 0; j < MAX_SHOTS; j++) {
        const Obj *s = &shots[j];
        if (s->kind != S_NONE) {
            int16_t x = PX(s->x);
            uint8_t w = SHOT_HW[s->kind];
            live_x[live] = x;
            live_y[live] = PX(s->y);
            live_w[live] = w;
            live_h[live] = SHOT_HH[s->kind];
            live_shot[live] = j;
            bucket_shots[bucket((int16_t)(x - w))] |= bit;
            bucket_shots[bucket((int16_t)(x + w))] |= bit;
            bit = (uint16_t)(bit << 1);
            live++;
        }
    }
    for (i = 0; i < MAX_ENEMIES; i++) {
        Obj *e = &enemies[i];
        int16_t ex, ey;
        uint8_t hw, hh;
        if (e->kind == E_NONE) {
            continue;
        }
        ex = PX(e->x);
        ey = PX(e->y);
        if (ex > SCREEN_W + 8) {
            continue;                               /* not on the screen yet: nothing hits it */
        }
        hw = ENEMY_HW[e->kind];
        hh = ENEMY_HH[e->kind];
        {
            uint8_t b = bucket((int16_t)(ex - hw)), last = bucket((int16_t)(ex + hw));
            uint16_t near = 0;
            for (; b <= last; b++) {
                near |= bucket_shots[b];
            }
            live_near = near;
        }
        for (j = 0; live_near != 0; j++, live_near >>= 1) {
            Obj *s;
            int16_t d;
            if ((live_near & 1) == 0) {
                continue;
            }
            d = (int16_t)(live_x[j] - ex);
            if (d < 0) {
                d = (int16_t)-d;
            }
            if (d >= live_w[j] + hw) {
                continue;
            }
            d = (int16_t)(live_y[j] - ey);
            if (d < 0) {
                d = (int16_t)-d;
            }
            if (d >= live_h[j] + hh) {
                continue;
            }
            s = &shots[live_shot[j]];
            if (s->kind == S_NONE) {
                continue;                           /* it hit something before, this frame */
            }
            if (s->kind == S_CORK) {                /* through everything, each enemy once */
                if ((e->flags & F_FLASH) == 0 && enemy_hit(e, s->hp)) {
                    break;
                }
                continue;
            }
            if (s->kind == S_BOTTLE) {
                part_spawn(P_SHARD, s->x, s->y, -12, -24);
            }
            {
                uint8_t damage = SHOT_DAMAGE[s->kind];
                s->kind = S_NONE;
                if (enemy_hit(e, damage)) {
                    break;
                }
            }
        }
        if (e->kind == E_NONE) {
            continue;
        }
        if (crate && (e->flags & F_FLASH) == 0 && overlap(cx, cy, 7, 7, ex, ey, hw, hh)) {
            if (enemy_hit(e, 2)) {
                continue;
            }
        }
        if (vulnerable && overlap(sx, sy, 6, 3, ex, ey, hw, hh)) {
            ship_hit();
            vulnerable = 0;
        }
    }
    for (i = 0; i < MAX_BULLETS; i++) {
        Obj *b = &bullets[i];
        int16_t bx, by;
        if (b->kind == B_NONE) {
            continue;
        }
        bx = PX(b->x);
        by = PX(b->y);
        if (crate && overlap(cx, cy, 8, 8, bx, by, 3, 3)) {
            b->kind = B_NONE;                       /* the crate takes it */
            part_spawn(P_SPARK, b->x, b->y, 0, 0);
        } else if (vulnerable && overlap(sx, sy, 5, 2, bx, by, 2, 2)) {
            b->kind = B_NONE;
            ship_hit();
            vulnerable = 0;
        }
    }
    if (!ship_alive) {
        return;
    }
    for (i = 0; i < MAX_ITEMS; i++) {
        Obj *it = &items[i];
        int16_t ix, iy;
        if (it->kind == I_NONE) {
            continue;
        }
        ix = PX(it->x);
        iy = PX(it->y);
        if (overlap(sx, sy, 14, 8, ix, iy, 7, 7) || (crate && it->kind >= I_BOTTLE && overlap(cx, cy, 8, 8, ix, iy, 6, 6))) {
            collect(it);
        }
    }
}
