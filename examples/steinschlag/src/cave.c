/*
 * The caves and their physics.
 *
 * A cave is made from a seed: dirt everywhere, boulders and gems at random with the cave's own
 * shares, then walls of brick where the cave says, steel around it, the exit, the beetles and a
 * space for the miner to start in. The same seed always gives the same cave.
 *
 * The physics runs in ticks, a few frames apart (game.c). One tick looks at every cell once, top
 * to bottom: a boulder or a gem falls into the space below it, rolls off another boulder, a gem
 * or a wall when there is room beside and below, and crushes what it lands on after a fall; a
 * beetle follows the wall on its left; a blast grows, then leaves space (or gems). Something that
 * moved down is not looked at again in the same tick: every cell that was handled carries the
 * tick's parity in its DONE bit. The scan of one tick is spread over several frames
 * (cave_scan_rows), so that no frame takes too long.
 */
#include "steinschlag.h"

typedef struct {
    uint16_t seed;
    uint8_t boulders, gems, spaces;  /* shares out of 256 of the cells inside */
    uint8_t needed;                  /* gems to open the exit */
    uint8_t seconds;
    uint8_t start_x, start_y, exit_x, exit_y;
    const uint8_t *walls;            /* x, y, length, 1 vertical or 0 horizontal; ends with 0 */
    const uint8_t *beetles;          /* x, y pairs; ends with 0 */
} CaveDef;

static const uint8_t WALLS_1[] = { 8, 6, 14, 0, 18, 12, 14, 0, 30, 3, 8, 1, 0 };
static const uint8_t BEETLES_1[] = { 34, 16, 0 };
static const uint8_t WALLS_2[] = { 5, 5, 30, 0, 5, 11, 30, 0, 5, 17, 30, 0, 20, 1, 4, 1, 0 };
static const uint8_t BEETLES_2[] = { 10, 8, 28, 14, 0 };
static const uint8_t WALLS_3[] = { 10, 1, 9, 1, 20, 12, 9, 1, 30, 1, 9, 1, 2, 10, 6, 0, 32, 10, 6, 0, 0 };
static const uint8_t BEETLES_3[] = { 15, 5, 25, 16, 35, 5, 0 };

static const CaveDef CAVE_DEFS[CAVES] = {
    { 0x1A2B, 40, 20, 14, 10, 150, 3, 2, 37, 19, WALLS_1, BEETLES_1 },
    { 0x7C31, 56, 22, 10, 14, 150, 2, 2, 37, 2, WALLS_2, BEETLES_2 },
    { 0x4E95, 64, 24, 8, 18, 180, 20, 20, 20, 2, WALLS_3, BEETLES_3 },
};

uint8_t cave[CAVE_H][CAVE_W];
uint8_t gems_needed, gems_got;
uint16_t time_left;
uint8_t miner_x, miner_y;
uint8_t miner_dead, cave_won;

static uint8_t parity;               /* DONE or 0: what the DONE bit of a cell handled in this tick holds */
static uint8_t exit_x, exit_y;
static uint8_t push_wait;            /* ticks the miner has pushed against a boulder */
static uint16_t rng = 0x1234;

static const int8_t DX[4] = { 1, 0, -1, 0 };
static const int8_t DY[4] = { 0, -1, 0, 1 };

uint16_t random16(void)
{
    uint16_t bit = (uint16_t)(((rng >> 0) ^ (rng >> 2) ^ (rng >> 3) ^ (rng >> 5)) & 1);
    rng = (uint16_t)((rng >> 1) | (bit << 15));
    return rng;
}

static void set(uint8_t x, uint8_t y, uint8_t value)
{
    cave[y][x] = (uint8_t)(value | parity);
    cell_changed(x, y);
}

void cave_start(uint8_t number)
{
    const CaveDef *d = &CAVE_DEFS[number];
    const uint8_t *w;
    uint8_t x, y, i, n;

    rng = d->seed;
    for (y = 0; y < CAVE_H; y++) {
        for (x = 0; x < CAVE_W; x++) {
            uint8_t r = (uint8_t)random16();
            uint8_t k = K_DIRT;
            if (x == 0 || y == 0 || x == CAVE_W - 1 || y == CAVE_H - 1) {
                k = K_STEEL;
            } else if (r < d->boulders) {
                k = K_BOULDER;
            } else if (r < d->boulders + d->gems) {
                k = K_GEM;
            } else if (r < d->boulders + d->gems + d->spaces) {
                k = K_SPACE;
            }
            cave[y][x] = k;
        }
    }
    for (w = d->walls; w[0] != 0; w += 4) {
        for (i = 0; i < w[2]; i++) {
            x = (uint8_t)(w[0] + (w[3] ? 0 : i));
            y = (uint8_t)(w[1] + (w[3] ? i : 0));
            if (x < CAVE_W - 1 && y < CAVE_H - 1) {
                cave[y][x] = K_BRICK;
            }
        }
    }
    for (w = d->beetles; w[0] != 0; w += 2) {           /* a beetle in a small room of its own */
        for (y = (uint8_t)(w[1] - 1); y <= w[1] + 1; y++) {
            for (x = (uint8_t)(w[0] - 1); x <= w[0] + 1; x++) {
                cave[y][x] = K_SPACE;
            }
        }
        cave[w[1]][w[0]] = K_BEETLE;
    }
    for (y = (uint8_t)(d->start_y - 1); y <= d->start_y + 1; y++) {   /* nothing falls on the start */
        for (x = (uint8_t)(d->start_x - 1); x <= d->start_x + 1; x++) {
            if (KIND(cave[y][x]) != K_STEEL) {
                cave[y][x] = K_DIRT;
            }
        }
    }
    miner_x = d->start_x;
    miner_y = d->start_y;
    cave[miner_y][miner_x] = K_MINER;
    exit_x = d->exit_x;
    exit_y = d->exit_y;
    cave[exit_y][exit_x] = K_EXIT;
    n = 0;
    for (y = 0; y < CAVE_H; y++) {
        for (x = 0; x < CAVE_W; x++) {
            if (KIND(cave[y][x]) == K_GEM) {
                n++;
            }
        }
    }
    gems_needed = d->needed < n ? d->needed : n;
    gems_got = 0;
    time_left = d->seconds;
    miner_dead = 0;
    cave_won = 0;
    push_wait = 0;
    parity = 0;
    rng = (uint16_t)(rng ^ 0x5A5A);
}

void cave_tick_begin(void)
{
    parity = (uint8_t)(parity ^ DONE);
}

/* --- blasts ---------------------------------------------------------------------------------- */
/* Everything in the 3 x 3 cells around (x, y) but steel becomes a blast; one with gems leaves gems. */
static void blast(uint8_t x, uint8_t y, uint8_t gems)
{
    uint8_t bx, by;

    for (by = (uint8_t)(y - 1); by <= y + 1; by++) {
        for (bx = (uint8_t)(x - 1); bx <= x + 1; bx++) {
            uint8_t k = KIND(cave[by][bx]);
            if (k == K_STEEL || k == K_EXIT || k == K_EXIT_OPEN) {
                continue;
            }
            if (k == K_MINER && !miner_dead) {
                miner_dead = 1;
                sdevkit_sfx_play(SFX_CRUSHED);
            }
            set(bx, by, gems ? K_BLAST_GEM : K_BLAST);
        }
    }
}

/* --- falling and rolling --------------------------------------------------------------------- */
static uint8_t round_top(uint8_t k)
{
    return k == K_BOULDER || k == K_GEM || k == K_BRICK;
}

static void fall(uint8_t x, uint8_t y, uint8_t c)
{
    uint8_t k = KIND(c);
    uint8_t below = KIND(cave[y + 1][x]);

    if (below == K_SPACE) {
        set(x, y, K_SPACE);
        set(x, (uint8_t)(y + 1), (uint8_t)(k | FALLING));
        return;
    }
    if (c & FALLING) {
        if (below == K_MINER) {
            blast(x, (uint8_t)(y + 1), 0);
            return;
        }
        if (below == K_BEETLE) {
            sdevkit_sfx_play(SFX_BEETLE);
            blast(x, (uint8_t)(y + 1), 1);
            return;
        }
    }
    if (round_top(below)) {
        if (KIND(cave[y][x - 1]) == K_SPACE && KIND(cave[y + 1][x - 1]) == K_SPACE) {
            set(x, y, K_SPACE);
            set((uint8_t)(x - 1), y, (uint8_t)(k | FALLING));
            return;
        }
        if (KIND(cave[y][x + 1]) == K_SPACE && KIND(cave[y + 1][x + 1]) == K_SPACE) {
            set(x, y, K_SPACE);
            set((uint8_t)(x + 1), y, (uint8_t)(k | FALLING));
            return;
        }
    }
    cave[y][x] = (uint8_t)(k | parity);                /* at rest */
    if (c & FALLING) {
        cell_changed(x, y);
        if (k == K_BOULDER) {
            sdevkit_sfx_play(SFX_LAND);
        }
    }
}

/* --- beetles --------------------------------------------------------------------------------- */
static uint8_t next_to_miner(uint8_t x, uint8_t y)
{
    uint8_t d;

    for (d = 0; d < 4; d++) {
        if (KIND(cave[y + DY[d]][x + DX[d]]) == K_MINER) {
            return 1;
        }
    }
    return 0;
}

/* A beetle keeps the wall on its left: it turns left where it can, else goes on, else turns right. */
static void crawl(uint8_t x, uint8_t y, uint8_t c)
{
    uint8_t dir = EXTRA(c);
    uint8_t left = (uint8_t)((dir + 1) & 3);

    if (next_to_miner(x, y)) {
        blast(x, y, 0);
        return;
    }
    if (KIND(cave[y + DY[left]][x + DX[left]]) == K_SPACE) {
        set(x, y, K_SPACE);
        set((uint8_t)(x + DX[left]), (uint8_t)(y + DY[left]), (uint8_t)(K_BEETLE | (left << 4)));
    } else if (KIND(cave[y + DY[dir]][x + DX[dir]]) == K_SPACE) {
        set(x, y, K_SPACE);
        set((uint8_t)(x + DX[dir]), (uint8_t)(y + DY[dir]), (uint8_t)(K_BEETLE | (dir << 4)));
    } else {
        cave[y][x] = (uint8_t)(K_BEETLE | (((dir + 3) & 3) << 4) | parity);
    }
}

void cave_scan_rows(uint8_t from, uint8_t to)
{
    uint8_t x, y;

    for (y = from; y < to && y < CAVE_H - 1; y++) {
        for (x = 1; x < CAVE_W - 1; x++) {
            uint8_t c = cave[y][x];
            uint8_t k = KIND(c);
            if (k <= K_STEEL || (c & DONE) == parity) {
                continue;                              /* nothing moves, or done in this tick */
            }
            switch (k) {
            case K_BOULDER:
            case K_GEM:
                fall(x, y, c);
                break;
            case K_BEETLE:
                crawl(x, y, c);
                break;
            case K_BLAST:
            case K_BLAST_GEM: {
                uint8_t stage = EXTRA(c);
                if (stage < 2) {
                    set(x, y, (uint8_t)(k | ((stage + 1) << 4)));
                } else {
                    set(x, y, k == K_BLAST_GEM ? K_GEM : K_SPACE);
                }
                break;
            }
            case K_EXIT:
                if (gems_got >= gems_needed) {
                    set(x, y, K_EXIT_OPEN);
                    sdevkit_sfx_play(SFX_EXIT);
                }
                break;
            default:
                cave[y][x] = (uint8_t)((c & (uint8_t)~DONE) | parity);   /* the miner, the open exit */
                break;
            }
        }
    }
}

/* --- the miner ------------------------------------------------------------------------------- */
uint8_t miner_step(uint8_t dir, uint8_t grab)
{
    uint8_t tx = (uint8_t)(miner_x + DX[dir]);
    uint8_t ty = (uint8_t)(miner_y + DY[dir]);
    uint8_t k = KIND(cave[ty][tx]);

    if (k == K_GEM) {
        sdevkit_sfx_play(SFX_GEM);
        gems_got++;
        add_score(gems_got > gems_needed ? 15 : 10);
    }
    if (k == K_DIRT) {
        sdevkit_sfx_play(SFX_DIG);
    }
    if (k == K_DIRT || k == K_GEM) {
        if (grab) {                                    /* dig or take it from where you stand */
            set(tx, ty, K_SPACE);
            return 0;
        }
    } else if (k == K_EXIT_OPEN) {
        cave_won = 1;
    } else if (k == K_BOULDER && DY[dir] == 0 && !grab
               && KIND(cave[ty][tx + DX[dir]]) == K_SPACE && !(cave[ty][tx] & FALLING)) {
        if (++push_wait < 2) {
            return 0;                                  /* a boulder moves on the second push */
        }
        push_wait = 0;
        set((uint8_t)(tx + DX[dir]), ty, K_BOULDER);
    } else if (k != K_SPACE) {
        return 0;
    }
    set(miner_x, miner_y, K_SPACE);
    miner_x = tx;
    miner_y = ty;
    set(miner_x, miner_y, K_MINER);
    return 1;
}
