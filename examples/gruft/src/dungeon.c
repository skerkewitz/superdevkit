/*
 * Gruft: the dungeon, made from a seed, and what happens in a turn.
 *
 * A dungeon is 48 rooms of random sizes at random places, each joined to the one before by a
 * corridor that goes across, then down or up. Rooms may overlap; they then make larger halls. The
 * hero starts in the first room, the stairs down are in the last. Most rooms get a creature, many
 * a thing to pick up.
 *
 * The maps live in RAM, one cell a byte: what the cell is (tiles), whether the hero has seen it
 * (seen), who or what is on it (occupant). Together with the distance map they take about 100 KB,
 * more than the SNES's first bank of RAM holds, so dampf puts the largest of them into the second
 * bank (ADR-6FVM9QWS); the code is the same C either way. The program reaches them both ways: by
 * index (tiles[y][x]) and through pointers to their rows (carve, dist_row, look, and the
 * screen's drawing in game.c).
 *
 * The creatures find the hero by a distance map: after each of the hero's steps, a breadth-first
 * search writes the number of steps to the hero into every cell it reaches, up to REACH steps
 * away; a creature steps to the neighbour with the smallest number. A cell's entry has the
 * search's number in its high byte, so a new search needs no clearing of the map: an entry from
 * an older search does not count.
 */
#include <string.h>
#include "gruft.h"

#define ROOMS 48
#define REACH 10                       /* the creatures notice the hero this many steps away */
#define QUEUE 224                      /* the cells within REACH steps, at most: 2 * 10 * 11 + 1 */
#define FAR 0xFF

uint8_t tiles[MAP_H][MAP_W];
uint8_t seen[MAP_H][MAP_W];
uint8_t occupant[MAP_H][MAP_W];
static uint16_t dist_north[MAP_H / 2][MAP_W];
static uint16_t dist_south[MAP_H / 2][MAP_W];
struct thing things[THINGS];

uint8_t hero_x, hero_y;
uint8_t hero_hp;
uint16_t gold;
uint8_t depth;
const char *message;

static uint16_t rng = 1;
static uint8_t search;                 /* the number of the last breadth-first search */
static uint8_t turn;
static uint8_t queue_x[QUEUE], queue_y[QUEUE];
static uint16_t tail;                  /* the end of the search's queue */

static const uint8_t HP[6] = { 0, 3, 2, 6, 0, 0 };
static const uint8_t DAMAGE[6] = { 0, 1, 2, 3, 0, 0 };
static const char *const HIT[6] = { "", "YOU HIT THE RAT", "YOU HIT THE BAT", "YOU HIT THE GHOUL", "", "" };
static const char *const DIES[6] = { "", "THE RAT DIES", "THE BAT DIES", "THE GHOUL DIES", "", "" };
static const char *const BITES[6] = { "", "THE RAT BITES", "THE BAT BITES", "THE GHOUL HITS YOU", "", "" };
static const int8_t STEP_X[4] = { 1, 0, -1, 0 };
static const int8_t STEP_Y[4] = { 0, -1, 0, 1 };

static uint16_t random16(void)
{
    uint16_t bit = (uint16_t)(((rng >> 0) ^ (rng >> 2) ^ (rng >> 3) ^ (rng >> 5)) & 1);
    rng = (uint16_t)((rng >> 1) | (bit << 15));
    return rng;
}

static void look(void);
static void distances(void);

/* --- making a dungeon ------------------------------------------------------------------------ */
/* Rock becomes wall, from x0 to x1 of a row. */
static void wall(uint8_t *row, uint8_t x0, uint8_t x1)
{
    uint8_t x;

    for (x = x0; x <= x1; x++) {
        if (row[x] == T_ROCK) {
            row[x] = T_WALL;
        }
    }
}

/* A room or a piece of corridor: floor inside, and the rock around it becomes wall, which the
 * screen shows (the rest of the rock stays dark). A row at a time, through a pointer to it. */
static void carve(uint8_t x0, uint8_t y0, uint8_t x1, uint8_t y1)
{
    uint8_t x, y;

    wall(tiles[y0 - 1], (uint8_t)(x0 - 1), (uint8_t)(x1 + 1));
    for (y = y0; y <= y1; y++) {
        uint8_t *row = tiles[y];
        wall(row, (uint8_t)(x0 - 1), (uint8_t)(x0 - 1));
        for (x = x0; x <= x1; x++) {
            row[x] = T_FLOOR;
        }
        wall(row, (uint8_t)(x1 + 1), (uint8_t)(x1 + 1));
    }
    wall(tiles[y1 + 1], (uint8_t)(x0 - 1), (uint8_t)(x1 + 1));
}

static void put(uint8_t kind, uint8_t x, uint8_t y)
{
    uint8_t n;

    if (tiles[y][x] != T_FLOOR || occupant[y][x] != 0 || (x == hero_x && y == hero_y)) {
        return;
    }
    for (n = 0; n < THINGS; n++) {
        if (things[n].kind == K_NONE) {
            things[n].kind = kind;
            things[n].x = x;
            things[n].y = y;
            things[n].hp = (uint8_t)(HP[kind] + (kind == K_GHOUL ? depth : 0));
            occupant[y][x] = (uint8_t)(n + 1);
            return;
        }
    }
}

/* Puts a thing on a random cell of a room (one random number after the other: the order of a
 * call's arguments is open in C). */
static void place(uint8_t x, uint8_t y, uint8_t w, uint8_t h, uint8_t kind)
{
    uint8_t at_x = (uint8_t)(x + random16() % w);
    uint8_t at_y = (uint8_t)(y + random16() % h);
    put(kind, at_x, at_y);
}

void dungeon_make(uint16_t seed)
{
    uint8_t r, w, h, x, y;
    uint8_t cx = 0, cy = 0, px = 0, py = 0;

    rng = (uint16_t)(seed | 1);
    memset(tiles, T_ROCK, sizeof tiles);
    memset(seen, 0, sizeof seen);
    memset(occupant, 0, sizeof occupant);
    memset(things, 0, sizeof things);
    for (r = 0; r < ROOMS; r++) {
        /* a room, at least SIGHT cells from the edge, so the hero's sight never leaves the map */
        w = (uint8_t)(4 + random16() % 9);
        h = (uint8_t)(3 + random16() % 6);
        x = (uint8_t)(SIGHT + random16() % (MAP_W - 2 * SIGHT - w));
        y = (uint8_t)(SIGHT + random16() % (MAP_H - 2 * SIGHT - h));
        carve(x, y, (uint8_t)(x + w - 1), (uint8_t)(y + h - 1));
        cx = (uint8_t)(x + w / 2);
        cy = (uint8_t)(y + h / 2);
        if (r == 0) {
            hero_x = cx;
            hero_y = cy;
        } else {
            carve(px < cx ? px : cx, py, px < cx ? cx : px, py);    /* across, then down or up */
            carve(cx, py < cy ? py : cy, cx, py < cy ? cy : py);
            if ((random16() & 3) != 0) {
                place(x, y, w, h, (uint8_t)(K_RAT + random16() % 3));
            }
            if ((random16() & 1) != 0) {
                place(x, y, w, h, (random16() & 3) == 0 ? K_POTION : K_GOLD);
            }
        }
        px = cx;
        py = cy;
    }
    tiles[cy][cx] = T_STAIRS;
    if (occupant[cy][cx] != 0) {
        things[occupant[cy][cx] - 1].kind = K_NONE;
        occupant[cy][cx] = 0;
    }
    look();
    distances();
}

/* --- a turn ---------------------------------------------------------------------------------- */
/* The row of the distance map: in the north half or the south one. */
static uint16_t *dist_row(uint8_t y)
{
    return y < MAP_H / 2 ? dist_north[y] : dist_south[y - MAP_H / 2];
}

static uint8_t dist_at(uint8_t x, uint8_t y)
{
    uint16_t v = dist_row(y)[x];
    return (uint8_t)(v >> 8) == search ? (uint8_t)v : FAR;
}

/* What the hero sees: the cells around it, marked as seen for good. */
static void look(void)
{
    uint8_t x, y;

    for (y = (uint8_t)(hero_y - SIGHT); y <= (uint8_t)(hero_y + SIGHT); y++) {
        uint8_t *row = seen[y];
        for (x = (uint8_t)(hero_x - SIGHT); x <= (uint8_t)(hero_x + SIGHT); x++) {
            row[x] = 1;
        }
    }
}

/* A neighbour of a cell in the search: if it is no wall and not reached yet, it gets the
 * distance and joins the queue. row and cells: its rows of the distance map and of the tiles. */
static void reach(uint8_t x, uint8_t y, uint16_t *row, const uint8_t *cells, uint16_t entry)
{
    if (cells[x] >= T_FLOOR && (uint8_t)(row[x] >> 8) != search) {
        row[x] = entry;
        queue_x[tail] = x;
        queue_y[tail] = y;
        tail++;
    }
}

/* The distance map from the hero, up to REACH steps, by a breadth-first search. */
static void distances(void)
{
    uint16_t head = 0;

    if (++search == 0) {
        /* every 256th search: the old numbers would count again, so the map is cleared once */
        memset(dist_north, 0, sizeof dist_north);
        memset(dist_south, 0, sizeof dist_south);
        search = 1;
    }
    dist_row(hero_y)[hero_x] = (uint16_t)(search << 8);
    queue_x[0] = hero_x;
    queue_y[0] = hero_y;
    tail = 1;
    while (head < tail) {
        uint8_t x = queue_x[head];
        uint8_t y = queue_y[head];
        uint16_t *here = dist_row(y);
        uint8_t d = (uint8_t)here[x];
        uint16_t entry = (uint16_t)((search << 8) | (d + 1));
        head++;
        if (d == REACH) {
            continue;
        }
        reach((uint8_t)(x + 1), y, here, tiles[y], entry);
        reach((uint8_t)(x - 1), y, here, tiles[y], entry);
        reach(x, (uint8_t)(y - 1), dist_row((uint8_t)(y - 1)), tiles[y - 1], entry);
        reach(x, (uint8_t)(y + 1), dist_row((uint8_t)(y + 1)), tiles[y + 1], entry);
    }
}

/* Every creature within reach steps towards the hero, or bites when next to it. Ghouls are slow:
 * they move every other turn. */
static void creatures(void)
{
    uint8_t i, k;

    for (i = 0; i < THINGS; i++) {
        struct thing *t = &things[i];
        uint8_t d, best, bx, by;
        if (t->kind == K_NONE || t->kind > K_GHOUL) {
            continue;
        }
        d = dist_at(t->x, t->y);
        if (d == FAR) {
            continue;                  /* asleep, far away */
        }
        if (d == 1) {
            hero_hp = hero_hp > DAMAGE[t->kind] ? (uint8_t)(hero_hp - DAMAGE[t->kind]) : 0;
            message = BITES[t->kind];
            continue;
        }
        if (t->kind == K_GHOUL && (turn & 1) != 0) {
            continue;
        }
        best = d;
        bx = t->x;
        by = t->y;
        for (k = 0; k < 4; k++) {
            uint8_t nx = (uint8_t)(t->x + STEP_X[k]);
            uint8_t ny = (uint8_t)(t->y + STEP_Y[k]);
            uint8_t nd = dist_at(nx, ny);
            if (nd < best && occupant[ny][nx] == 0) {
                best = nd;
                bx = nx;
                by = ny;
            }
        }
        if (best < d) {
            occupant[t->y][t->x] = 0;
            occupant[by][bx] = (uint8_t)(i + 1);
            t->x = bx;
            t->y = by;
        }
    }
}

uint8_t hero_turn(uint8_t dir)
{
    uint8_t nx = (uint8_t)(hero_x + STEP_X[dir]);
    uint8_t ny = (uint8_t)(hero_y + STEP_Y[dir]);
    uint8_t o;

    if (tiles[ny][nx] < T_FLOOR) {
        return 0;
    }
    message = 0;
    o = occupant[ny][nx];
    if (o != 0) {
        struct thing *t = &things[o - 1];
        if (t->kind <= K_GHOUL) {
            uint16_t r = random16();
            uint8_t damage = (uint8_t)(1 + (r & 1) + ((r >> 1) & 1));
            if (t->hp <= damage) {
                message = DIES[t->kind];
                t->kind = K_NONE;
                occupant[ny][nx] = 0;
            } else {
                t->hp = (uint8_t)(t->hp - damage);
                message = HIT[t->kind];
            }
            nx = hero_x;                       /* a fight: the hero stays */
            ny = hero_y;
        } else {
            if (t->kind == K_GOLD) {
                gold = (uint16_t)(gold + 10 + depth * 5u);
                message = "GOLD!";
            } else {
                hero_hp = hero_hp + 8 > HP_MAX ? HP_MAX : (uint8_t)(hero_hp + 8);
                message = "A POTION. YOU FEEL BETTER";
            }
            t->kind = K_NONE;
            occupant[ny][nx] = 0;
        }
    }
    hero_x = nx;
    hero_y = ny;
    turn++;
    if (tiles[ny][nx] == T_STAIRS) {
        depth++;
        dungeon_make(random16());
        message = "DOWN THE STAIRS";
        return 1;
    }
    look();
    distances();
    creatures();
    return 1;
}
