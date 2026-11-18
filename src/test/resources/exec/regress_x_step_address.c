/* Regression (2026-11-18): an offset kept in X for the whole loop (a loop counter in X) was
 * stepped before a later store whose address was formed from the old offset, so the store went
 * to the next element. Here: things[i].kind is stored after the offset of things[i] has been
 * advanced by the size of a Thing. Found with string_h.c. */
#include <stdint.h>

typedef struct {
    int16_t x, y;
    uint8_t kind, flags;
} Thing;

Thing things[5];

void test_main(void)
{
    uint8_t i;

    for (i = 0; i < 5; i++) {
        things[i].y = (int16_t)(-i);
        things[i].kind = (uint8_t)(i + 1);
    }
}
