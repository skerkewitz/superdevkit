/*
 * Loops whose bounds the static timing finds, each run to its limit: the emulator checks every
 * call against the computed best and worst case (8-bit unsigned counters, a limit that is not a
 * constant, a counter stepped down to an ordered test or to zero, a test further down the loop,
 * a long loop past the range analysis's widening limit).
 */
#include <stdint.h>

uint8_t table[256];
uint16_t wide[600];
uint8_t limit;
uint8_t which;
uint16_t sum;
uint8_t found;

static void up_to(uint8_t n)
{
    uint8_t i;
    for (i = 0; i < n; i++) {
        sum = (uint16_t)(sum + table[i]);
    }
}

static void down_to(uint8_t rank)
{
    uint8_t i;
    for (i = 200; i > rank; i--) {
        table[i] = table[(uint8_t)(i - 1)];
    }
}

static void count_down(uint8_t k)
{
    while (k) {
        sum++;
        k--;
    }
}

static void by_hundreds(uint8_t value)
{
    while (value >= 100) {
        value = (uint8_t)(value - 100);
        found++;
    }
}

static void either(uint8_t layer)
{
    uint8_t r;
    for (r = 0; r < (layer == 0 ? 24 : 32); r++) {
        table[r] = (uint8_t)(table[r] + 1);
    }
}

static int8_t find_down(uint8_t start)
{
    int8_t i = (int8_t)(start & 7);
    while (table[(uint8_t)i] > 9) {
        if (i == 0) {
            return -1;
        }
        i--;
    }
    return i;
}

static void long_loop(void)
{
    uint16_t i;
    for (i = 0; i < 600; i++) {
        wide[i] = (uint16_t)(wide[i] + i);
    }
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 256; i++) {
        table[i] = (uint8_t)(i * 7);
    }
    limit = 255;
    up_to(limit);
    up_to(0);
    down_to(0);
    down_to(199);
    count_down(255);
    count_down(0);
    by_hundreds(255);
    by_hundreds(99);
    which = 1;
    either(which);
    either(0);
    found = (uint8_t)(found + (uint8_t)find_down(7));
    table[3] = 1;
    found = (uint8_t)(found + (uint8_t)find_down(7));
    long_loop();
}
