/* Function pointers in constant tables (docs/subset.md, "Function pointers"): calls through
 * tables of handlers with and without arguments and results, in loops, from tables of structs and
 * of two dimensions, and to functions in other ROM banks than the caller. */
#include <stdint.h>

#define V10(b) b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9
#define V100(b) V10(b), V10(b + 10), V10(b + 20), V10(b + 30), V10(b + 40), \
                V10(b + 50), V10(b + 60), V10(b + 70), V10(b + 80), V10(b + 90)
#define V1000(b) V100(b), V100(b + 100), V100(b + 200), V100(b + 300), V100(b + 400), \
                 V100(b + 500), V100(b + 600), V100(b + 700), V100(b + 800), V100(b + 900)
#define V5000(b) V1000(b), V1000(b + 1000), V1000(b + 2000), V1000(b + 3000), V1000(b + 4000)

/* Three tables of almost a bank each: the functions below go where there is room left, so a
 * table's functions lie in different banks. */
static const uint16_t fill_a[15000] = { V5000(0), V5000(5000), V5000(10000) };
static const uint16_t fill_b[15000] = { V5000(3), V5000(20003), V5000(40003) };
static const uint16_t fill_c[15000] = { V5000(9), V5000(30009), V5000(50009) };

/* Long enough that only a few fit into the room a full bank has left. */
#define STEP(k) x = (uint16_t)((x ^ (uint16_t)(x >> 3)) + (k) + fill_a[(x + (k)) % 15000u]);
#define STEPS10(k) STEP(k) STEP(k + 1) STEP(k + 2) STEP(k + 3) STEP(k + 4) \
                   STEP(k + 5) STEP(k + 6) STEP(k + 7) STEP(k + 8) STEP(k + 9)
#define STEPS50(k) STEPS10(k) STEPS10(k + 10) STEPS10(k + 20) STEPS10(k + 30) STEPS10(k + 40)

uint16_t ticks[4];
uint16_t state_trace;

static void title_tick(void) { ticks[0]++; state_trace = (uint16_t)(state_trace * 5u + 1u); }
static void play_tick(void) { ticks[1]++; state_trace = (uint16_t)(state_trace * 5u + 2u); }
static void pause_tick(void) { ticks[2]++; state_trace = (uint16_t)(state_trace * 5u + 3u); }
static void over_tick(void)
{
    uint16_t x = state_trace;
    STEPS50(0)
    ticks[3]++;
    state_trace = x;
}

static void (*const handlers[])(void) = { title_tick, play_tick, pause_tick, over_tick };

/* With arguments and a result; each long enough to fill what a bank has left. */
static uint16_t op_add(uint16_t a, uint8_t b) { uint16_t x = a; STEPS50(100) return (uint16_t)(x + b); }
static uint16_t op_sub(uint16_t a, uint8_t b) { uint16_t x = a; STEPS50(200) return (uint16_t)(x - b); }
static uint16_t op_mix(uint16_t a, uint8_t b) { uint16_t x = a; STEPS50(300) return (uint16_t)(x ^ (uint16_t)(b << 4)); }
static uint16_t op_keep(uint16_t a, uint8_t b) { return (uint16_t)(a + (b & 3u)); }

typedef uint16_t (*Op)(uint16_t, uint8_t);
static const Op ops[4] = { op_add, &op_sub, op_mix, op_keep };

uint16_t op_results[16];

/* 32-bit values and pointers as arguments and results */
static int32_t scale(int32_t v, const uint8_t *p) { return v * p[0] - p[1]; }
static int32_t shift(int32_t v, const uint8_t *p) { return (v >> 2) + p[1]; }
static int32_t (*const widen[2])(int32_t, const uint8_t *) = { scale, shift };
static const uint8_t factors[2] = { 7, 3 };
int32_t wide[4];

/* a table of structs, called through a field */
typedef struct {
    uint8_t cost;
    int8_t (*react)(int8_t level);
} Rule;

static int8_t calm(int8_t level) { return (int8_t)(level / 2); }
static int8_t angry(int8_t level) { return (int8_t)(level * 2 - 1); }

static const Rule rules[3] = { { 1, calm }, { 4, angry }, { 2, calm } };
int8_t levels[6];
uint8_t costs;

/* two dimensions, and a call inside another call's arguments */
static uint8_t twice(uint8_t v) { return (uint8_t)(v * 2u); }
static uint8_t plus3(uint8_t v) { return (uint8_t)(v + 3u); }
static uint8_t (*const grid[2][2])(uint8_t) = { { twice, plus3 }, { plus3, twice } };
uint8_t grid_out[4];

/* a static table inside a function, a single constant pointer, and (*f)() */
static int16_t neg(int16_t v) { return (int16_t)-v; }
static int16_t same(int16_t v) { return v; }
static int16_t (*const flip)(int16_t) = neg;
int16_t local_out[3];

uint16_t total;

void test_main(void)
{
    uint8_t i;
    static int16_t (*const pick[2])(int16_t) = { same, neg };

    for (i = 0; i < 12; i++) {
        handlers[(i * 7u) % 4u]();
    }
    for (i = 0; i < 16; i++) {
        op_results[i] = ops[i & 3u]((uint16_t)(i * 1000u + 7u), (uint8_t)(i * 17u));
        total = (uint16_t)(total + op_results[i]);
    }
    for (i = 0; i < 4; i++) {
        wide[i] = widen[i & 1u]((int32_t)i * -70000 + 5, factors);
    }
    for (i = 0; i < 6; i++) {
        const uint8_t r = (uint8_t)(i % 3u);
        levels[i] = rules[r].react((int8_t)(i * 20 - 50));
        costs = (uint8_t)(costs + rules[r].cost);
    }
    for (i = 0; i < 4; i++) {
        grid_out[i] = grid[i >> 1][i & 1u](grid[1][0]((uint8_t)(i + 40u)));
    }
    local_out[0] = pick[0](-300);
    local_out[1] = pick[1](-300);
    local_out[2] = (*flip)(1234);
}
