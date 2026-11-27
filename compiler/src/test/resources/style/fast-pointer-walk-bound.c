/* rule: fast-pointer-walk-bound
 * claim: nearest_by_pointer faster nearest_by_index
 * A loop over the first n elements of a global array: walked with a pointer up to &items[n],
 * the timing report bounds it by the array's 32 elements (a pointer stays within its array);
 * counted with i < n, by the counter's type, 255 rounds for a uint8_t. */
#include <stdint.h>

typedef struct {
    int16_t x, y;
    uint8_t reach;
} Item;

#define ITEMS 32
Item items[ITEMS];
uint8_t item_count;
int16_t nearest;

void nearest_by_index(int16_t px)
{
    int16_t best = 0x7FFF;
    for (uint8_t i = 0; i < item_count; i++) {
        int16_t d = (int16_t)(items[i].x - px);
        if (d < best) {
            best = d;
        }
    }
    nearest = best;
}

void nearest_by_pointer(int16_t px)
{
    int16_t best = 0x7FFF;
    const Item *end = &items[item_count];
    for (const Item *p = items; p != end; p++) {
        int16_t d = (int16_t)(p->x - px);
        if (d < best) {
            best = d;
        }
    }
    nearest = best;
}

void test_main(void)
{
    int16_t t = 0;
    for (uint8_t i = 0; i < ITEMS; i++) {
        items[i].x = (int16_t)(i * 37 - 500);
    }
    for (uint8_t n = 0; n <= ITEMS; n = (uint8_t)(n + 8)) {
        item_count = n;
        nearest_by_index(100);
        t = (int16_t)(t + nearest);
        nearest_by_pointer(100);
        t = (int16_t)(t + nearest);
    }
    nearest = t;
}
