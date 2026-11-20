/* Loops whose test moves from the counter to a variable derived from it (Induction, L4 of
 * docs/current-compiler-flaws.md): i - 1 as the index from 1 up, (i - 1) * 2 and a stride of 5
 * counting down, r * 16 + c in a table, a byte counter to a byte bound that is 0 in one call,
 * a signed counter down to a variable bound, a do loop over words, and a counter the loop also
 * reads (which stays). Each sum is kept. */
#include <stdint.h>

typedef struct {
    int16_t x;
    int16_t y;
    uint8_t f;
} Obj;

uint8_t bytes[128];
uint16_t words[64];
uint8_t table[8][16];
Obj objs[24];
uint16_t out[16];

static uint16_t from1(uint16_t n)
{
    uint16_t s = 0;
    uint16_t i;
    for (i = 1; i < n; i++) {
        s = (uint16_t)(s + bytes[i - 1u]);
    }
    return s;
}

static uint16_t words_down(uint16_t n)
{
    uint16_t s = 0;
    uint16_t i;
    for (i = n; i > 0; i--) {
        s = (uint16_t)(s + words[i - 1u]);
    }
    return s;
}

static void objs_down(void)
{
    uint16_t i;
    for (i = 24; i > 0; i--) {
        objs[i - 1u].x = (int16_t)(objs[i - 1u].x + 3);
        objs[i - 1u].f = (uint8_t)(objs[i - 1u].f + 1u);
    }
}

static uint16_t row(uint16_t r)
{
    uint16_t s = 0;
    uint16_t c;
    for (c = 0; c < 16; c++) {
        s = (uint16_t)(s + table[r][c]);
    }
    return s;
}

static uint16_t bytes_to(uint8_t n)
{
    uint16_t s = 0;
    uint8_t i;
    for (i = 0; i < n; i++) {
        s = (uint16_t)(s + bytes[i]);
    }
    return s;
}

static uint16_t signed_down(int16_t lo)
{
    uint16_t s = 0;
    int16_t i;
    for (i = 63; i > lo; i--) {
        s = (uint16_t)(s + words[i]);
    }
    return s;
}

static uint16_t words_do(void)
{
    uint16_t s = 0;
    uint16_t i = 0;
    do {
        s = (uint16_t)(s + words[i]);
        i++;
    } while (i < 64);
    return s;
}

static uint16_t keeps(uint16_t n)
{
    uint16_t s = 0;
    uint16_t i;
    for (i = 0; i < n; i++) {
        s = (uint16_t)(s + objs[i].x + i);
    }
    return s;
}

void test_main(void)
{
    uint16_t i;
    uint16_t r;
    for (i = 0; i < 128; i++) {
        bytes[i] = (uint8_t)(i * 7u + 1u);
    }
    for (i = 0; i < 64; i++) {
        words[i] = (uint16_t)(i * 300u + 5u);
    }
    for (r = 0; r < 8; r++) {
        for (i = 0; i < 16; i++) {
            table[r][i] = (uint8_t)(r * 16u + i * 3u);
        }
    }
    for (i = 0; i < 24; i++) {
        objs[i].x = (int16_t)(i * 11u);
        objs[i].f = (uint8_t)i;
    }
    out[0] = from1(100);
    out[1] = from1(1);
    out[2] = words_down(64);
    out[3] = words_down(0);
    objs_down();
    out[4] = (uint16_t)objs[0].x;
    out[5] = objs[23].f;
    out[6] = row(3);
    out[7] = row(7);
    out[8] = bytes_to(0);
    out[9] = bytes_to(128);
    out[10] = signed_down(-1);
    out[11] = signed_down(40);
    out[12] = words_do();
    out[13] = keeps(24);
    out[14] = keeps(0);
}
