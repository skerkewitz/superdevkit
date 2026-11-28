/* Signed comparisons of two variables, decided by the sign of their difference (N xor V after
 * one SBC), and the absolute difference a > b ? a - b : b - a as one subtraction and a negation:
 * every relation as a branch, as a value and as a loop's condition, on the edge values of 8 and
 * 16 bits (the least and greatest, 0, -1, 1, and pairs whose difference overflows). */
#include <stdint.h>

#define N16 13
#define N8  11

int16_t v16[N16] = { INT16_MIN, INT16_MIN + 1, -30000, -1000, -2, -1, 0, 1, 2, 1000, 30000, INT16_MAX - 1, INT16_MAX };
int8_t v8[N8] = { INT8_MIN, INT8_MIN + 1, -100, -2, -1, 0, 1, 2, 100, INT8_MAX - 1, INT8_MAX };

uint8_t branch16[N16 * N16];    /* bit k: relation k taken as a branch */
uint8_t value16[N16 * N16];     /* the relations as values, summed with weights */
uint8_t branch8[N8 * N8];
uint8_t value8[N8 * N8];
uint16_t dist_hash;             /* the absolute differences in their four spellings and abs(a - b) */
uint16_t dist_count;            /* how often the four spellings agree */
uint16_t loops;                 /* loops whose condition is a signed comparison of two variables */
uint16_t narrow;                /* comparisons the value ranges decide without overflow */
int16_t lo16 = INT16_MAX - 5, hi16 = INT16_MAX, least16 = INT16_MIN, low16 = INT16_MIN + 4;
int16_t best = INT16_MAX, worst = INT16_MIN;

static uint8_t relations16(int16_t a, int16_t b)
{
    uint8_t m = 0;
    if (a < b) m |= 1;
    if (a <= b) m |= 2;
    if (a > b) m |= 4;
    if (a >= b) m |= 8;
    if (!(a < b)) m |= 16;
    if (!(a > b)) m |= 32;
    return m;
}

static uint8_t values16(int16_t a, int16_t b)
{
    return (uint8_t)((a < b) + 2 * (a <= b) + 4 * (a > b) + 8 * (a >= b));
}

static uint8_t relations8(int8_t a, int8_t b)
{
    uint8_t m = 0;
    if (a < b) m |= 1;
    if (a <= b) m |= 2;
    if (a > b) m |= 4;
    if (a >= b) m |= 8;
    return m;
}

static uint8_t values8(int8_t a, int8_t b)
{
    return (uint8_t)((a < b) + 2 * (a <= b) + 4 * (a > b) + 8 * (a >= b));
}

static int16_t dist_gt(int16_t a, int16_t b) { return (int16_t)(a > b ? a - b : b - a); }
static int16_t dist_ge(int16_t a, int16_t b) { return (int16_t)(a >= b ? a - b : b - a); }
static int16_t dist_lt(int16_t a, int16_t b) { return (int16_t)(a < b ? b - a : a - b); }
static int16_t dist_le(int16_t a, int16_t b) { return (int16_t)(b >= a ? b - a : a - b); }
static int16_t abs16(int16_t v) { return v < 0 ? (int16_t)-v : v; }

static void mix(int16_t d)
{
    dist_hash = (uint16_t)(dist_hash * 31u + (uint16_t)d);
}

void test_main(void)
{
    uint8_t i, j;
    for (i = 0; i < N16; i++) {
        for (j = 0; j < N16; j++) {
            int16_t a = v16[i], b = v16[j];
            int16_t d1 = dist_gt(a, b), d2 = dist_ge(a, b), d3 = dist_lt(a, b), d4 = dist_le(a, b);
            branch16[i * N16 + j] = relations16(a, b);
            value16[i * N16 + j] = values16(a, b);
            mix(d1);
            mix(d2);
            mix(d3);
            mix(d4);
            mix(abs16((int16_t)(a - b)));
            if (d1 == d2 && d2 == d3 && d3 == d4) {
                dist_count++;
            }
            /* the minimum and maximum, a signed comparison of two variables in a loop */
            if (a < best) best = a;
            if (b > worst) worst = b;
        }
    }
    for (i = 0; i < N8; i++) {
        for (j = 0; j < N8; j++) {
            branch8[i * N8 + j] = relations8(v8[i], v8[j]);
            value8[i * N8 + j] = values8(v8[i], v8[j]);
            /* bytes widened: their difference cannot overflow 16 bits */
            int16_t a = v8[i], b = v8[j];
            if (a < b) narrow++;
            if (a <= b) narrow += 2;
            if (a < 5) narrow += 4;
            if (b >= -7) narrow += 8;
            mix((int16_t)(a > b ? a - b : b - a));
        }
    }
    /* loops counted by a signed comparison of two variables, at both ends of the range */
    for (int16_t k = lo16; k < hi16; k++) {
        loops++;
    }
    for (int16_t k = least16; k <= low16; k++) {
        loops += 16;
    }
    int16_t k = hi16;
    while (k > lo16) {
        k--;
        loops += 256;
    }
    for (i = 0; i < N16 && v16[i] < lo16; i++) {
        loops += 4096;
    }
}
