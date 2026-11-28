/*
 * Tests for 0 and for the sign where N and Z may describe the value already: after a mask and a
 * load of a loop counter or an index (which the code generator moves above the mask), where
 * every way into a join set them, after a transfer to X or Y, and where they do not (one way
 * sets them from another value, a call, a byte tested after a word). The values are the edges:
 * 0, 1, -1, the least and greatest.
 */
#include <stdint.h>

uint8_t bytes[12] = {0, 1, 255, 128, 0, 7, 0, 64, 2, 0, 200, 16};
int16_t words[12] = {0, -1, 1, INT16_MIN, INT16_MAX, -300, 300, 0, 2, -2, 256, 1000};
uint16_t loops, zeros, negatives, joins, shifted, counted;
uint8_t marks[12];
int16_t lows[12];

static int16_t pick(uint8_t i)
{
    return (i & 1) ? words[i] : (int16_t)(words[i] + 1);
}

struct player { int16_t x; uint8_t cells[8]; uint8_t on; };
struct player players[3] = {{0, {1, 2, 3, 4, 5, 6, 7, 8}, 1}, {-3, {0}, 0}, {255, {9, 0, 0x40, 0, 0, 0, 0, 255}, 2}};
uint8_t grid[8];

/* a byte field tested, then a loop whose counter starts at 0 */
static void add_cells(struct player *p)
{
    uint8_t j;
    if (p->on != 0) {
        for (j = 0; j < 8; j++) {
            grid[j] = (uint8_t)(grid[j] + p->cells[j]);
        }
    }
    if ((p->x & 3) != 0) {
        counted += 5;
    }
}

static uint8_t low_nibble(uint8_t v)
{
    return (uint8_t)(v & 0x0F);
}

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 12; i++) {
        uint8_t j;
        /* a mask, the inner loop's counter set to 0, then the test of the mask */
        if (bytes[i] & 0x0F) {
            for (j = 0; j < 3; j++) {
                loops += (uint16_t)(j + bytes[i]);
            }
        }
        /* a mask, an index set for the store, then the test */
        uint8_t m = (uint8_t)(bytes[i] & 0xF0);
        marks[(uint8_t)(11 - i)] = m;
        if (m != 0) {
            counted++;
        }
        /* a value from two ways, tested after the join */
        int16_t v = pick(i);
        if (v < 0) {
            negatives++;
        }
        if (v == 0) {
            zeros++;
        }
        /* one way computes another value last */
        int16_t w = words[i];
        if (bytes[i] > 100) {
            w = (int16_t)(w - 1);
            lows[i] = (int16_t)bytes[i];
        }
        if (w >= 0) {
            joins++;
        }
        /* a shift, then the test of its result; a byte after a word */
        uint16_t s = (uint16_t)words[i] >> 8;
        if (s) {
            shifted += s;
        }
        if (low_nibble(bytes[i]) == 0 && (uint8_t)words[i] == 0) {
            shifted += 1000;
        }
        if (i < 3) {
            add_cells(&players[i]);
            if (players[i].cells[2] & 0x40) {
                for (j = 0; j < 4; j++) {
                    counted += j;
                }
            }
        }
        /* a counter in X tested for 0 */
        uint8_t k = bytes[i];
        while (k != 0) {
            k = (uint8_t)(k >> 1);
            counted += 3;
        }
    }
}
