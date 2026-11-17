/*
 * A pointer plus an index that is never negative is [dp],y with the index in Y; the sum is not
 * computed. Bytes, words and 32-bit values (Y steps by two for the second word), loads and
 * stores, a loop counter as the index, a pointer read from memory. A signed index that may be
 * negative must not be used this way (Y would carry into the bank), and an index plus a field's
 * offset still goes through the computed pointer.
 */
#include <stdint.h>

uint8_t bytes[40];
uint16_t words[12];
uint32_t longs[6];
uint8_t *cursor;
/* pointers read from memory: after inlining, the accesses still go through a pointer */
uint16_t *wp;
uint32_t *lp;
uint8_t *mp;
struct pair *pp;
uint32_t *lps[2];
int8_t steps[6] = {-3, -1, 0, 2, 3, -2};
uint8_t order[7] = {5, 0, 3, 3, 1, 4, 2};

struct pair {
    uint8_t a;
    uint16_t b;
};
struct pair pairs[5];

uint16_t byte_sum;
uint16_t word_sum;
uint32_t long_sum;
uint16_t signed_sum;
uint16_t pair_sum;
uint8_t rows[4][32];
uint8_t row_pick = 2;
uint16_t row_sum;

static uint16_t sum_bytes(const uint8_t *p, uint8_t n)
{
    uint16_t s = 0;
    uint8_t i;
    for (i = 0; i < n; i++) {
        s = (uint16_t)(s * 3u + p[i]);
    }
    return s;
}

static void fill_words(uint16_t *w, uint8_t n, uint16_t seed)
{
    uint8_t i;
    for (i = 0; i < n; i++) {
        w[i] = (uint16_t)(seed + i * 257u);
    }
}

static uint32_t sum_longs(const uint32_t *l, uint8_t n)
{
    uint32_t s = 0;
    uint8_t i;
    for (i = 0; i < n; i++) {
        s += l[i] ^ (uint32_t)i;
    }
    return s;
}

static uint32_t pick_longs(const uint32_t *l, const uint8_t *at, uint8_t n)
{
    uint32_t s = 0;
    uint8_t i;
    for (i = 0; i < n; i++) {
        s = s * 3u + l[at[i]];
    }
    return s;
}

static uint16_t around(const uint8_t *mid)
{
    uint16_t s = 0;
    uint8_t i;
    for (i = 0; i < 6; i++) {
        s = (uint16_t)(s * 5u + mid[steps[i]]);
    }
    return s;
}

/* a row of a 2D array: the pointer is itself a sum (the array plus row * 32) */
static uint16_t sum_row(uint8_t r)
{
    const uint8_t *row = rows[r];
    uint16_t s = 0;
    uint8_t i;
    for (i = 0; i < 12; i++) {
        s = (uint16_t)(s * 3u + row[order[i % 7] * 4u]);
    }
    return s;
}

static uint16_t sum_pairs(const struct pair *p, uint8_t n)
{
    uint16_t s = 0;
    uint8_t i;
    for (i = 0; i < n; i++) {
        s = (uint16_t)(s + p[i].a + p[i].b);
    }
    return s;
}

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 40; i++) {
        bytes[i] = (uint8_t)(i * 29u + 3u);
    }
    cursor = &bytes[4];
    for (i = 0; i < 6; i++) {
        longs[i] = (uint32_t)0x01020304u * (uint32_t)(i + 1u) + (uint32_t)0xFFFF0000u;
        pairs[i < 5 ? i : 0].a = (uint8_t)(i * 7u);
        pairs[i < 5 ? i : 0].b = (uint16_t)(i * 1001u);
    }
    for (i = 0; i < 9; i++) {
        cursor[(uint16_t)(i * 3u)] = (uint8_t)(cursor[(uint16_t)(i * 3u)] + i);
    }
    byte_sum = sum_bytes(bytes, 40);
    byte_sum = (uint16_t)(byte_sum + sum_bytes(cursor, 7));
    wp = words;
    lp = longs;
    mp = &bytes[20];
    pp = pairs;
    fill_words(wp, 12, 40000u);
    wp = &words[2];
    fill_words(wp, 5, 7u);
    word_sum = sum_bytes((const uint8_t *)words, 24);
    long_sum = sum_longs(lp, 6);
    lp = &longs[1];
    long_sum += sum_longs(lp, 4);
    /* read from an array at an index known only at run time: nothing can forward the address */
    lps[0] = longs;
    lps[1] = &longs[1];
    lp = lps[order[6] & 1u];
    long_sum += pick_longs(lp, order, 7);
    signed_sum = around(mp);
    pair_sum = sum_pairs(pp, 5);
    for (i = 0; i < 128; i++) {
        rows[i >> 5][i & 31] = (uint8_t)(i * 7u + 1u);
    }
    row_sum = sum_row(row_pick);
    row_sum = (uint16_t)(row_sum + sum_row((uint8_t)(row_pick + 1u)));
}
