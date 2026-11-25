/*
 * A global indexed by a value plus a constant, a[i + 1]: the constant goes into the operand
 * (sta a+1,x) and X or Y holds i, so the sum is not computed (FrameLayout.offsetIndex). Bytes,
 * words and 32-bit values, loads and stores, a loop counter stepped by four that stays in X (the
 * sprite table's four bytes a sprite), a counter read after its step, and indexes whose sum
 * wraps past $FFFF in 16 bits, which must not be folded (the folded address would leave the
 * object).
 */
#include <stdint.h>

uint8_t table[544];
uint8_t xs[128];
uint8_t ys[128];
uint16_t tiles[128];
uint16_t words[40];
uint32_t longs[10];
uint8_t small[8];
uint16_t near_end = 0xFFFE;    /* read from memory: its range is unknown */
uint16_t wrap_sum;
uint16_t table_sum;
uint16_t word_sum;
uint32_t long_sum;
uint16_t pairs_sum;

static void fill(void)
{
    uint8_t i;
    for (i = 0; i < 128; i++) {
        xs[i] = (uint8_t)(i * 37u + 5u);
        ys[i] = (uint8_t)(i * 11u + 200u);
        tiles[i] = (uint16_t)(i * 517u + 3u);
    }
}

/* four bytes per entry at o, o + 1, o + 2, o + 3, the counter stepped by four */
static void pack(uint8_t n)
{
    uint8_t i;
    uint16_t o = 0;
    for (i = 0; i < n; i++) {
        table[o] = xs[i];
        table[o + 1] = ys[i];
        table[o + 2] = (uint8_t)tiles[i];
        table[o + 3] = (uint8_t)(0x30 | (tiles[i] >> 8 & 1));
        o += 4;
    }
}

/* the sum of entry bytes, read back the same way; o is read after its step too */
static uint16_t sum_table(uint8_t n)
{
    uint16_t s = 0;
    uint16_t o;
    for (o = 0; o < (uint16_t)n * 4u; o += 4) {
        s = (uint16_t)(s * 7u + table[o] + table[o + 1] * 3u + table[o + 2] + table[o + 3]);
    }
    return s;
}

/* neighbours in a word array: a[i + 1] - a[i], and a store at i + 2 */
static uint16_t words_step(void)
{
    uint8_t i;
    uint16_t s = 0;
    for (i = 0; i < 38; i++) {
        words[i + 2] = (uint16_t)(words[i] + words[i + 1] * 3u);
        s = (uint16_t)(s + words[i + 1] - words[i]);
    }
    return s;
}

static uint32_t longs_step(void)
{
    uint8_t i;
    uint32_t s = 0;
    for (i = 0; i < 9; i++) {
        longs[i + 1] = longs[i + 1] + longs[i] * 5u + 0x10001u;
        s += longs[i + 1] ^ (uint32_t)i;
    }
    return s;
}

/* an index outside a loop, from a narrow range: folded */
static uint16_t pick(uint8_t j)
{
    uint16_t i = (uint16_t)(j & 3u);
    return (uint16_t)(small[i + 1] + small[i + 4] * 256u);
}

/* CatchMe's actors: id = 4 + i * 4 is computed (table[id] reads it), id + 1 .. id + 3 are folded
 * onto it; i * 4, handed on in A into id, must not become the index of table[id] */
typedef struct {
    int16_t x, y;
    uint8_t mode, dir;
} Actor;
Actor actors[4];
uint8_t frame_no;

static void put(uint16_t id, const Actor *a, uint16_t tile, uint8_t pal)
{
    table[id] = (uint8_t)a->x;
    table[id + 1] = (uint8_t)a->y;
    table[id + 2] = (uint8_t)tile;
    table[id + 3] = (uint8_t)(0x30 | (pal << 1) | (tile >> 8));
}

static void draw_actors(void)
{
    uint8_t i;
    for (i = 0; i < 4; i++) {
        const Actor *a = &actors[i];
        uint16_t id = (uint16_t)(4 + i * 4);
        if (a->mode == 1) {
            put(id, a, (uint16_t)(10 + a->dir * 2), 2);
        } else if (a->mode == 2) {
            put(id, a, (uint16_t)(20 + frame_no * 2), frame_no ? 3 : 4);
        } else {
            put(id, a, (uint16_t)(300 + a->dir * 2), (uint8_t)(5 + i));
        }
    }
}

/* (uint16_t)(near_end + 3) wraps to 1: the sum is the index, the fold is not allowed */
static uint16_t wrapped(uint16_t base)
{
    uint16_t a = (uint16_t)(base + 3u);
    uint16_t b = (uint16_t)(base + 2u);
    return (uint16_t)(small[a] * 256u + small[b]);
}

void test_main(void)
{
    uint8_t i;
    fill();
    for (i = 0; i < 8; i++) {
        small[i] = (uint8_t)(i * 31u + 7u);
    }
    for (i = 0; i < 40; i++) {
        words[i] = (uint16_t)(i * 1013u + 9u);
    }
    for (i = 0; i < 10; i++) {
        longs[i] = (uint32_t)i * 0x01010101u + 0x7FFF0000u;
    }
    pack(128);
    table_sum = sum_table(128);
    pack(5);
    table_sum = (uint16_t)(table_sum + sum_table(9));
    word_sum = words_step();
    long_sum = longs_step();
    pairs_sum = (uint16_t)(pick(1) + pick(3) + pick(6));
    for (i = 0; i < 4; i++) {
        actors[i].x = (int16_t)(i * 40 - 20);
        actors[i].y = (int16_t)(i * 30 + 7);
        actors[i].mode = (uint8_t)(i % 3);
        actors[i].dir = (uint8_t)(3 - i);
    }
    frame_no = 1;
    draw_actors();
    table_sum = (uint16_t)(table_sum + sum_table(6));
    wrap_sum = wrapped(near_end);
    wrap_sum = (uint16_t)(wrap_sum + wrapped(4) + wrapped(0));
}
