/* One block that enters two loops, one on each edge of its branch, both with a counter in X:
 * the block's code set X once, for the second loop's start, so the first loop started from the
 * wrong value (a random loop program, seed 15599). The first loop's index starts at 2. */
#include <stdint.h>

uint8_t a[64];
uint8_t c;
uint16_t s;
uint16_t t;

void test_main(void)
{
    uint16_t i;
    uint16_t k;
    uint16_t j;
    const uint8_t *p;
    for (i = 0; i < 64; i++) {
        a[i] = (uint8_t)(i * 3u + 1u);
    }
    c = a[5];
    if (c != 0) {
        p = a + 2;
        for (k = 0; k < 57; k++) {
            s = (uint16_t)(s + *p++);
        }
    }
    for (j = 0; j < 30; j++) {
        t = (uint16_t)(t + a[j]);
    }
}
