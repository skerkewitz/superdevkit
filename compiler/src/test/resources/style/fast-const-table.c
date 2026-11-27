/* rule: fast-const-table
 * claim: classify_by_table faster classify_by_chain
 * A choice among constants by a small value, as a const table (one indexed load from ROM),
 * against the same choice as tests that return constants, one after the other. */
#include <stdint.h>

uint8_t out;

static uint8_t class_by_chain(uint8_t kind, uint8_t pal)
{
    if (kind == 0) {
        if (pal == 3) {
            return 1;
        }
        if (pal == 7) {
            return 5;
        }
        if (pal == 4 || pal == 6) {
            return 6;
        }
        return 0;
    }
    if (kind == 4) {
        if (pal == 1) {
            return 3;
        }
        if (pal == 4 || pal == 7) {
            return 6;
        }
        return 2;
    }
    return kind;
}

static const uint8_t first_class[8] = { 0, 0, 0, 1, 6, 0, 6, 5 };
static const uint8_t second_class[8] = { 2, 3, 2, 2, 6, 2, 2, 6 };

static uint8_t class_by_table(uint8_t kind, uint8_t pal)
{
    if (kind == 0) {
        return first_class[pal];
    }
    if (kind == 4) {
        return second_class[pal];
    }
    return kind;
}

void classify_by_chain(uint8_t kind, uint8_t attr)
{
    out = class_by_chain(kind, (uint8_t)((attr >> 1) & 7));
}

void classify_by_table(uint8_t kind, uint8_t attr)
{
    out = class_by_table(kind, (uint8_t)((attr >> 1) & 7));
}

uint16_t sum;

void test_main(void)
{
    for (uint8_t kind = 0; kind < 6; kind++) {
        for (uint8_t attr = 0; attr < 16; attr++) {
            classify_by_chain(kind, attr);
            sum = (uint16_t)(sum * 3u + out);
            classify_by_table(kind, attr);
            sum = (uint16_t)(sum * 3u + out);
        }
    }
}
