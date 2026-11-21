/* Loops over bytes that run in 8 bits from entry to exit (ByteLoops): copies, fills, a sum of
 * bytes with a carry, and the loops that must not (a byte tested by a branch, a byte used as an
 * index, a loop whose function returns A). */
#include <stdint.h>

uint8_t src[40], dst[40], hist[8];
uint8_t small[5] = { 3, 0, 250, 7, 0 };
uint16_t zeros;
uint8_t last;
uint16_t sum16;

static void copy(void) { uint16_t i; for (i = 0; i < 40; i++) dst[i] = src[i]; }
static void copy_down(void) { uint16_t i; for (i = 40; i > 0; i--) dst[i - 1] = (uint8_t)(src[i - 1] + 3); }
static void fill(uint8_t c) { uint16_t i; for (i = 0; i < 40; i++) dst[i] = c; }
static void clear(void) { uint16_t i; for (i = 0; i < 8; i++) hist[i] = 0; }
static void count_zeros(void) { uint16_t i; zeros = 0; for (i = 0; i < 5; i++) { if (small[i] == 0) zeros++; } }
static void histogram(void) { uint16_t i; for (i = 0; i < 40; i++) hist[src[i] & 7]++; }
static uint8_t copy_last(void) { uint16_t i; for (i = 0; i < 5; i++) dst[i] = small[i]; return dst[4 - 1]; }
static uint16_t copy_sum(void) { uint16_t i; uint16_t s = 0x1234; for (i = 0; i < 5; i++) dst[i] = small[i]; return s; }
static void mix(void)
{
    uint16_t i;
    for (i = 0; i < 39; i++) dst[i] = (uint8_t)(src[i] ^ src[i + 1] | 0x81);
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 40; i++) {
        src[i] = (uint8_t)(i * 37 + 11);
    }
    copy();
    sum16 = dst[0] + dst[39];
    copy_down();
    sum16 += dst[0] + dst[39] + dst[20];
    fill(0xA5);
    sum16 += dst[7];
    clear();
    histogram();
    count_zeros();
    last = copy_last();
    sum16 += copy_sum();
    mix();
    sum16 += dst[0] + dst[38];
}
