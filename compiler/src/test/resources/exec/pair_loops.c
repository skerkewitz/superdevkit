/* The kit's benchmark for pair loops (dampf.exec.PairLoopsBench, the roadmap's M9): 16 discs that
 * push each other apart when they overlap and drift together when they are near, the kind of
 * loop every game with many moving things has, in four variants:
 *
 *                 struct array (d[i].x, 23 bytes)   one array per field (x[i])
 *   counter       pairs_counter_struct (A)          pairs_counter_fields (C)
 *   sorted list   pairs_list_struct (B)             pairs_list_fields (D)
 *
 * The counter tries every pair; the list keeps the floating discs sorted by x and stops at the
 * first one too far to the right, which in a clump leaves a part of the pairs. A pair only adds
 * to the two discs' moves, which are applied after all pairs, so the order of the pairs does not
 * matter and all four variants give the same positions (all_agree). 32 frames of positions,
 * made by a fixed random sequence around a clump.
 *
 * Without VARIANT, test_main runs all four on every frame (an execution test: gcc and dampf give
 * the same checksums). With -DVARIANT=0 it only makes the frames, with 1 to 4 also runs A, B, C
 * or D, for the benchmark, which subtracts the first from the others. */
#include <stdint.h>

#define DISCS    16
#define FRAMES   32             /* the benchmark's frames; the execution test plays the first 8 */
#ifdef VARIANT
#define RUN      FRAMES
#else
#define RUN      8              /* all four variants and the naive code generator's ROM within its limit */
#endif
#define ST_GONE  0
#define ST_FLOAT 2
#define ST_HELD  3
#define PUSH     12             /* closer than this on both axes: pushed apart */
#define REACH    22             /* closer than this: drift together */
#define DRIFT    48             /* 3/16 px a frame (8.8) */
#define SHOVE    160            /* 5/8 px a frame (8.8) */
#define MIN_X    16
#define MAX_X    232
#define MIN_Y    20
#define MAX_Y    200
#define PX(v)    ((int16_t)((v) >> 8))

typedef struct {               /* 23 bytes, as a game's actor might have */
    uint8_t  state;
    uint16_t x, y;             /* the top left, 8.8 */
    int16_t  mx, my;           /* this frame's move, 8.8 */
    uint8_t  colour, timer, combo;
    uint16_t score;
    uint8_t  owner, frame, flags;
    uint16_t spin;
    uint8_t  spare[4];
} Disc;

/* --- the frames: a fixed random sequence (a 16-bit LCG) ------------------------------------- */
static uint16_t seed;

static uint16_t next(void)
{
    seed = (uint16_t)(seed * 25173u + 13849u);
    return seed;
}

/* --- the struct array (A and B) --------------------------------------------------------------- */
Disc d[DISCS];

static int16_t abs16(int16_t v)
{
    return v < 0 ? (int16_t)-v : v;
}

static uint8_t afloat_s(uint8_t i)
{
    return d[i].state == ST_FLOAT || d[i].state == ST_HELD;
}

static void pair_s(uint8_t i, uint8_t j)
{
    int16_t dx = (int16_t)(PX(d[j].x) - PX(d[i].x));
    int16_t dy = (int16_t)(PX(d[j].y) - PX(d[i].y));
    int16_t ax = abs16(dx), ay = abs16(dy);

    if (ax >= REACH || ay >= REACH) {
        return;
    }
    if (ax < PUSH && ay < PUSH) {
        /* on the same column or row, the lower number goes first: either order of the pair agrees */
        int16_t sx = dx < 0 || (dx == 0 && i > j) ? (int16_t)-SHOVE : SHOVE;
        int16_t sy = dy < 0 || (dy == 0 && i > j) ? (int16_t)-SHOVE : SHOVE;
        d[i].mx = (int16_t)(d[i].mx - sx);
        d[j].mx = (int16_t)(d[j].mx + sx);
        d[i].my = (int16_t)(d[i].my - sy);
        d[j].my = (int16_t)(d[j].my + sy);
    } else if (d[i].state == ST_FLOAT && d[j].state == ST_FLOAT) {
        int16_t sx = dx < 0 ? (int16_t)-DRIFT : dx > 0 ? DRIFT : 0;
        d[i].mx = (int16_t)(d[i].mx + sx);
        d[j].mx = (int16_t)(d[j].mx - sx);
    }
}

static void apply_s(void)
{
    for (uint8_t i = 0; i < DISCS; i++) {
        int16_t x, y;
        if (!afloat_s(i)) {
            continue;
        }
        x = (int16_t)(PX(d[i].x) + PX(d[i].mx));
        y = (int16_t)(PX(d[i].y) + PX(d[i].my));
        if (x < MIN_X) {
            x = MIN_X;
        } else if (x > MAX_X) {
            x = MAX_X;
        }
        if (y < MIN_Y) {
            y = MIN_Y;
        } else if (y > MAX_Y) {
            y = MAX_Y;
        }
        d[i].x = (uint16_t)((uint16_t)x << 8 | (d[i].x & 0xFF));
        d[i].y = (uint16_t)((uint16_t)y << 8 | (d[i].y & 0xFF));
        d[i].mx = 0;
        d[i].my = 0;
    }
}

void pairs_counter_struct(void)
{
    for (uint8_t i = 0; i < DISCS; i++) {
        if (!afloat_s(i)) {
            continue;
        }
        for (uint8_t j = (uint8_t)(i + 1); j < DISCS; j++) {
            if (afloat_s(j)) {
                pair_s(i, j);
            }
        }
    }
    apply_s();
}

static uint8_t list_s[DISCS], list_sx[DISCS];

void pairs_list_struct(void)
{
    uint8_t n = 0;
    for (uint8_t i = 0; i < DISCS; i++) {           /* the floating discs, sorted by x */
        if (afloat_s(i)) {
            uint8_t x = (uint8_t)PX(d[i].x);
            uint8_t m = n;
            while (m > 0 && list_sx[m - 1] > x) {
                list_s[m] = list_s[m - 1];
                list_sx[m] = list_sx[m - 1];
                m--;
            }
            list_s[m] = i;
            list_sx[m] = x;
            n++;
        }
    }
    for (uint8_t k = 0; k < n; k++) {
        for (uint8_t l = (uint8_t)(k + 1); l < n; l++) {
            if ((uint8_t)(list_sx[l] - list_sx[k]) >= REACH) {
                break;
            }
            pair_s(list_s[k], list_s[l]);
        }
    }
    apply_s();
}

/* --- one array per field (C and D) ------------------------------------------------------------ */
uint8_t  f_state[DISCS];
uint16_t f_x[DISCS], f_y[DISCS];
int16_t  f_mx[DISCS], f_my[DISCS];

static uint8_t afloat_f(uint8_t i)
{
    return f_state[i] == ST_FLOAT || f_state[i] == ST_HELD;
}

static void pair_f(uint8_t i, uint8_t j)
{
    int16_t dx = (int16_t)(PX(f_x[j]) - PX(f_x[i]));
    int16_t dy = (int16_t)(PX(f_y[j]) - PX(f_y[i]));
    int16_t ax = abs16(dx), ay = abs16(dy);

    if (ax >= REACH || ay >= REACH) {
        return;
    }
    if (ax < PUSH && ay < PUSH) {
        /* on the same column or row, the lower number goes first: either order of the pair agrees */
        int16_t sx = dx < 0 || (dx == 0 && i > j) ? (int16_t)-SHOVE : SHOVE;
        int16_t sy = dy < 0 || (dy == 0 && i > j) ? (int16_t)-SHOVE : SHOVE;
        f_mx[i] = (int16_t)(f_mx[i] - sx);
        f_mx[j] = (int16_t)(f_mx[j] + sx);
        f_my[i] = (int16_t)(f_my[i] - sy);
        f_my[j] = (int16_t)(f_my[j] + sy);
    } else if (f_state[i] == ST_FLOAT && f_state[j] == ST_FLOAT) {
        int16_t sx = dx < 0 ? (int16_t)-DRIFT : dx > 0 ? DRIFT : 0;
        f_mx[i] = (int16_t)(f_mx[i] + sx);
        f_mx[j] = (int16_t)(f_mx[j] - sx);
    }
}

static void apply_f(void)
{
    for (uint8_t i = 0; i < DISCS; i++) {
        int16_t x, y;
        if (!afloat_f(i)) {
            continue;
        }
        x = (int16_t)(PX(f_x[i]) + PX(f_mx[i]));
        y = (int16_t)(PX(f_y[i]) + PX(f_my[i]));
        if (x < MIN_X) {
            x = MIN_X;
        } else if (x > MAX_X) {
            x = MAX_X;
        }
        if (y < MIN_Y) {
            y = MIN_Y;
        } else if (y > MAX_Y) {
            y = MAX_Y;
        }
        f_x[i] = (uint16_t)((uint16_t)x << 8 | (f_x[i] & 0xFF));
        f_y[i] = (uint16_t)((uint16_t)y << 8 | (f_y[i] & 0xFF));
        f_mx[i] = 0;
        f_my[i] = 0;
    }
}

void pairs_counter_fields(void)
{
    for (uint8_t i = 0; i < DISCS; i++) {
        if (!afloat_f(i)) {
            continue;
        }
        for (uint8_t j = (uint8_t)(i + 1); j < DISCS; j++) {
            if (afloat_f(j)) {
                pair_f(i, j);
            }
        }
    }
    apply_f();
}

static uint8_t list_f[DISCS], list_fx[DISCS];

void pairs_list_fields(void)
{
    uint8_t n = 0;
    for (uint8_t i = 0; i < DISCS; i++) {
        if (afloat_f(i)) {
            uint8_t x = (uint8_t)PX(f_x[i]);
            uint8_t m = n;
            while (m > 0 && list_fx[m - 1] > x) {
                list_f[m] = list_f[m - 1];
                list_fx[m] = list_fx[m - 1];
                m--;
            }
            list_f[m] = i;
            list_fx[m] = x;
            n++;
        }
    }
    for (uint8_t k = 0; k < n; k++) {
        for (uint8_t l = (uint8_t)(k + 1); l < n; l++) {
            if ((uint8_t)(list_fx[l] - list_fx[k]) >= REACH) {
                break;
            }
            pair_f(list_f[k], list_f[l]);
        }
    }
    apply_f();
}

/* --- the frames and the checksums --------------------------------------------------------------- */
uint32_t check_a, check_b, check_c, check_d;   /* FNV-1a of the positions after each frame */
uint8_t  all_agree;                             /* 1 when the four are the same */

/* Frame f's discs: most floating in a clump, some held, some gone. */
static void load_struct(uint8_t f)
{
    seed = (uint16_t)(f * 977u + 5u);
    for (uint8_t i = 0; i < DISCS; i++) {
        uint16_t r = next();
        d[i].state = (r & 15) < 2 ? ST_GONE : (r & 15) < 4 ? ST_HELD : ST_FLOAT;
        d[i].x = (uint16_t)((100u + (next() >> 10)) << 8 | (r & 0xFF));
        d[i].y = (uint16_t)((90u + (next() >> 10)) << 8 | (r >> 8));
        d[i].mx = 0;
        d[i].my = 0;
    }
}

static void load_fields(uint8_t f)
{
    seed = (uint16_t)(f * 977u + 5u);
    for (uint8_t i = 0; i < DISCS; i++) {
        uint16_t r = next();
        f_state[i] = (r & 15) < 2 ? ST_GONE : (r & 15) < 4 ? ST_HELD : ST_FLOAT;
        f_x[i] = (uint16_t)((100u + (next() >> 10)) << 8 | (r & 0xFF));
        f_y[i] = (uint16_t)((90u + (next() >> 10)) << 8 | (r >> 8));
        f_mx[i] = 0;
        f_my[i] = 0;
    }
}

static uint32_t fnv16(uint32_t h, uint16_t v)
{
    h = (h ^ (v & 0xFF)) * 16777619u;
    return (h ^ (v >> 8)) * 16777619u;
}

static uint32_t hash_struct(uint32_t h)
{
    for (uint8_t i = 0; i < DISCS; i++) {
        h = fnv16(fnv16(h, d[i].x), d[i].y);
    }
    return h;
}

static uint32_t hash_fields(uint32_t h)
{
    for (uint8_t i = 0; i < DISCS; i++) {
        h = fnv16(fnv16(h, f_x[i]), f_y[i]);
    }
    return h;
}

void test_main(void)
{
    check_a = check_b = check_c = check_d = 2166136261u;
    for (uint8_t f = 0; f < RUN; f++) {
#ifndef VARIANT
        load_struct(f);
        pairs_counter_struct();
        check_a = hash_struct(check_a);
        load_struct(f);
        pairs_list_struct();
        check_b = hash_struct(check_b);
        load_fields(f);
        pairs_counter_fields();
        check_c = hash_fields(check_c);
        load_fields(f);
        pairs_list_fields();
        check_d = hash_fields(check_d);
#elif VARIANT == 0
        load_struct(f);
        load_fields(f);
#elif VARIANT == 1
        load_struct(f);
        load_fields(f);
        pairs_counter_struct();
#elif VARIANT == 2
        load_struct(f);
        load_fields(f);
        pairs_list_struct();
#elif VARIANT == 3
        load_struct(f);
        load_fields(f);
        pairs_counter_fields();
#else
        load_struct(f);
        load_fields(f);
        pairs_list_fields();
#endif
    }
    all_agree = check_a == check_b && check_a == check_c && check_a == check_d;
}
