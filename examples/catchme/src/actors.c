/*
 * The mouse and the four cats.
 *
 * Every actor moves one pixel at a time; its speed is in sixteenths of a pixel per frame, and
 * what is left of a pixel carries over to the next frame (sub). Only where an actor stands exactly
 * on a cell (aligned) may it turn: the mouse turns where the joypad points, a cat chooses the open
 * cell that brings it closest to its target. A cat never turns back on its own, only when its mode
 * changes.
 *
 * The tunnel: on TUNNEL_ROW an actor may leave the maze on one side and come back on the other.
 * Its x then runs from -16 to 256, where the cell just off the screen is the same on both sides.
 */
#include "catchme.h"

Actor mouse;
Actor cats[CATS];
uint16_t scared_left;

static uint8_t want = DIR_NONE;    /* the direction the joypad last pointed to */
static uint16_t phase;             /* frames into the level: the cats chase, or go to their corners */
static uint8_t was_scatter;

static const int8_t DX[4] = { 1, 0, -1, 0 };
static const int8_t DY[4] = { 0, -1, 0, 1 };

/* Where each cat starts, its first wait in the house, and the corner it goes to. */
static const uint8_t START_X[CATS] = { 7, 7, 8, 8 };
static const uint8_t START_Y[CATS] = { 4, 6, 6, 6 };
static const uint8_t START_WAIT[CATS] = { 0, 60, 240, 420 };
static const uint8_t CORNER_X[CATS] = { 15, 0, 15, 0 };
static const uint8_t CORNER_Y[CATS] = { 0, 0, 12, 12 };

#define HOUSE_X 7
#define HOUSE_Y 6
#define DOOR_FRONT_Y 4

static uint8_t reverse(uint8_t dir)
{
    return (uint8_t)((dir + 2) & 3);
}

uint8_t aligned(const Actor *a)
{
    return (a->x & 15) == 0 && (a->y & 15) == 0;
}

static int8_t cell_x(const Actor *a)
{
    return (int8_t)(a->x >> 4);
}

static int8_t cell_y(const Actor *a)
{
    return (int8_t)(a->y >> 4);
}

/* Whether an actor on a cell may go one cell in dir; the door and the house only for cats that
 * leave the house or go back to it. */
static uint8_t can_go(const Actor *a, uint8_t dir, uint8_t through_door)
{
    uint8_t c = maze_cell((int8_t)(cell_x(a) + DX[dir]), (int8_t)(cell_y(a) + DY[dir]));
    if (c == CELL_WALL) {
        return 0;
    }
    if (c == CELL_DOOR || c == CELL_HOUSE) {
        return through_door;
    }
    return 1;
}

static void step(Actor *a)
{
    a->x = (int16_t)(a->x + DX[a->dir]);
    a->y = (int16_t)(a->y + DY[a->dir]);
    if (a->x < -16) {
        a->x = (int16_t)(a->x + 272);
    } else if (a->x > 256) {
        a->x = (int16_t)(a->x - 272);
    }
}

void actors_reset(void)
{
    uint8_t i;

    mouse.x = 7 * 16 + 8;
    mouse.y = 8 * 16;
    mouse.dir = DIR_LEFT;
    mouse.sub = 0;
    want = DIR_LEFT;
    for (i = 0; i < CATS; i++) {
        cats[i].x = (int16_t)(START_X[i] * 16);
        cats[i].y = (int16_t)(START_Y[i] * 16);
        cats[i].dir = i == 0 ? DIR_LEFT : DIR_UP;
        cats[i].sub = 0;
        cats[i].mode = i == 0 ? CAT_CHASE : CAT_HOME;
        cats[i].wait = START_WAIT[i];
    }
    scared_left = 0;
    phase = 0;
    was_scatter = 1;
}

void actors_scare(void)
{
    uint8_t i;
    uint16_t frames = (uint16_t)(level < 8 ? 420 - level * 40 : 120);

    scared_left = frames;
    for (i = 0; i < CATS; i++) {
        if (cats[i].mode == CAT_CHASE) {
            cats[i].mode = CAT_SCARED;
            cats[i].dir = reverse(cats[i].dir);
        }
    }
}

/* --- the mouse ------------------------------------------------------------------------------- */
void mouse_move(uint16_t pad)
{
    uint8_t speed = (uint8_t)(level < 8 ? 15 + level : 23);

    if (pad & KEY_UP) {
        want = DIR_UP;
    } else if (pad & KEY_DOWN) {
        want = DIR_DOWN;
    } else if (pad & KEY_LEFT) {
        want = DIR_LEFT;
    } else if (pad & KEY_RIGHT) {
        want = DIR_RIGHT;
    }
    if (want != DIR_NONE && want == reverse(mouse.dir)) {
        mouse.dir = want;                    /* turning back works anywhere */
    }
    mouse.sub = (uint8_t)(mouse.sub + speed);
    while (mouse.sub >= 16) {
        mouse.sub = (uint8_t)(mouse.sub - 16);
        if (aligned(&mouse)) {
            if (cell_x(&mouse) >= 0 && cell_x(&mouse) < MAZE_W) {
                eat((uint8_t)cell_x(&mouse), (uint8_t)cell_y(&mouse));
            }
            if (want != DIR_NONE && can_go(&mouse, want, 0)) {
                mouse.dir = want;
            }
            if (!can_go(&mouse, mouse.dir, 0)) {
                mouse.sub = 0;               /* against a wall: wait there */
                return;
            }
        }
        step(&mouse);
    }
}

/* --- the cats -------------------------------------------------------------------------------- */
/* The open direction (never back) whose next cell is closest to (tx, ty). */
static uint8_t towards(const Actor *a, int8_t tx, int8_t ty, uint8_t through_door)
{
    uint8_t best = reverse(a->dir);
    uint16_t best_d = 0xFFFF;
    uint8_t d;

    for (d = 0; d < 4; d++) {
        int16_t dx, dy;
        uint16_t dist;
        if (d == reverse(a->dir) || !can_go(a, d, through_door)) {
            continue;
        }
        dx = (int16_t)(cell_x(a) + DX[d] - tx);
        dy = (int16_t)(cell_y(a) + DY[d] - ty);
        dist = (uint16_t)(dx * dx + dy * dy);
        if (dist < best_d) {
            best_d = dist;
            best = d;
        }
    }
    return best;
}

/* An open direction at random (never back). */
static uint8_t wander(const Actor *a)
{
    uint8_t d = (uint8_t)(random16() & 3);
    uint8_t n;

    for (n = 0; n < 4; n++) {
        if (d != reverse(a->dir) && can_go(a, d, 0)) {
            return d;
        }
        d = (uint8_t)((d + 1) & 3);
    }
    return reverse(a->dir);
}

/* Where a chasing cat is going: each has its own way. */
static uint8_t chase(uint8_t i, uint8_t scatter)
{
    Actor *a = &cats[i];
    int8_t mx = cell_x(&mouse);
    int8_t my = cell_y(&mouse);

    if (scatter) {
        return towards(a, (int8_t)CORNER_X[i], (int8_t)CORNER_Y[i], 0);
    }
    switch (i) {
    case 0:                                  /* the ginger one goes for the mouse itself */
        return towards(a, mx, my, 0);
    case 1:                                  /* the black one for three cells ahead of it */
        return towards(a, (int8_t)(mx + 3 * DX[mouse.dir]), (int8_t)(my + 3 * DY[mouse.dir]), 0);
    case 2:                                  /* the white one wanders */
        return wander(a);
    default: {                               /* the brown one hunts from afar, and is shy close by */
        int16_t dx = (int16_t)(cell_x(a) - mx);
        int16_t dy = (int16_t)(cell_y(a) - my);
        if (dx * dx + dy * dy > 25) {
            return towards(a, mx, my, 0);
        }
        return towards(a, (int8_t)CORNER_X[i], (int8_t)CORNER_Y[i], 0);
    }
    }
}

static uint8_t cat_speed(const Actor *a)
{
    if (a->mode == CAT_EYES) {
        return 32;
    }
    if (a->mode == CAT_SCARED || a->mode == CAT_LEAVING || (cell_y(a) == TUNNEL_ROW && (a->x < 16 || a->x > 224))) {
        return 8;
    }
    return (uint8_t)(level < 8 ? 14 + level : 22);
}

/* What a cat on a cell does next. */
static void decide(uint8_t i, uint8_t scatter)
{
    Actor *a = &cats[i];

    switch (a->mode) {
    case CAT_LEAVING:                        /* up through the door, then out into the maze */
        if (cell_y(a) > DOOR_FRONT_Y) {
            a->dir = DIR_UP;                 /* the door is above both cells of the house */
        } else {
            a->mode = scared_left > 0 ? CAT_SCARED : CAT_CHASE;
            a->dir = (random16() & 1) ? DIR_LEFT : DIR_RIGHT;
        }
        break;
    case CAT_EYES:                           /* back to the house, then out again */
        if (cell_y(a) == HOUSE_Y && cell_x(a) == HOUSE_X) {
            a->mode = CAT_LEAVING;
            a->dir = DIR_UP;
        } else if (cell_x(a) == HOUSE_X && cell_y(a) >= DOOR_FRONT_Y) {
            a->dir = DIR_DOWN;
        } else {
            a->dir = towards(a, HOUSE_X, DOOR_FRONT_Y, 0);
        }
        break;
    case CAT_SCARED:
        a->dir = wander(a);
        break;
    default:
        a->dir = chase(i, scatter);
        break;
    }
}

void cats_move(void)
{
    uint8_t i;
    uint8_t scatter;

    phase++;
    scatter = (uint8_t)((phase % 1620) < 420);
    if (scared_left > 0) {
        scared_left--;
        if (scared_left == 0) {
            for (i = 0; i < CATS; i++) {
                if (cats[i].mode == CAT_SCARED) {
                    cats[i].mode = CAT_CHASE;
                }
            }
        }
    }
    for (i = 0; i < CATS; i++) {
        Actor *a = &cats[i];
        if (a->mode == CAT_HOME) {
            if (a->wait > 0) {
                a->wait--;
                continue;
            }
            a->mode = CAT_LEAVING;
        }
        if (scatter != was_scatter && a->mode == CAT_CHASE) {
            a->dir = reverse(a->dir);        /* a change of mind shows: they turn round */
        }
        a->sub = (uint8_t)(a->sub + cat_speed(a));
        while (a->sub >= 16) {
            a->sub = (uint8_t)(a->sub - 16);
            if (aligned(a)) {
                decide(i, scatter);
            }
            step(a);
        }
    }
    was_scatter = scatter;
}

/* --- drawing --------------------------------------------------------------------------------- */
#define SPRITE_MOUSE  0                      /* tiles of the sprite sheet (assets/sprites.png) */
#define SPRITE_CAT    32
#define SPRITE_SCARED 64
#define SPRITE_EYES   68
#define PAL_MOUSE  0                         /* sprite palettes, as loaded in game.c */
#define PAL_CAT    1
#define PAL_SCARED 5
#define PAL_HOT    6
#define PAL_WHITE  3

static void put(uint16_t id, const Actor *a, uint16_t tile, uint8_t palette)
{
    oamSet(id, (uint16_t)(a->x & 0x1FF), (uint16_t)(a->y + MAZE_TOP - 1), 3, 0, 0, tile, palette);
    oamSetEx(id, OBJ_SMALL, OBJ_SHOW);
}

void actors_draw(uint8_t frame)
{
    uint8_t i;
    uint8_t step2 = (uint8_t)((frame >> 3) & 1);

    put(0, &mouse, (uint16_t)(SPRITE_MOUSE + (mouse.dir * 2 + step2) * 2), scared_left > 0 ? PAL_HOT : PAL_MOUSE);
    for (i = 0; i < CATS; i++) {
        const Actor *a = &cats[i];
        uint16_t id = (uint16_t)(4 + i * 4);
        if (a->mode == CAT_EYES) {
            put(id, a, (uint16_t)(SPRITE_EYES + a->dir * 2), PAL_SCARED);
        } else if (a->mode == CAT_SCARED) {
            uint8_t flash = (uint8_t)(scared_left < 120 && ((frame >> 3) & 1));
            put(id, a, (uint16_t)(SPRITE_SCARED + step2 * 2), flash ? PAL_WHITE : PAL_SCARED);
        } else {
            uint8_t look = a->mode == CAT_CHASE ? a->dir : DIR_DOWN;
            put(id, a, (uint16_t)(SPRITE_CAT + (look * 2 + step2) * 2), (uint8_t)(PAL_CAT + i));
        }
    }
}

void actors_hide(void)
{
    uint8_t i;

    for (i = 0; i <= CATS; i++) {
        oamSetVisible((uint16_t)(i * 4), OBJ_HIDE);
    }
}
