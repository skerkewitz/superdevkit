/* A loop that ends in a branch on its counter's value from before the step (if (c) break;
 * after an inner loop with a call): the code generator wrote the head's φs, the counter's
 * included, before the branch, which then read the stepped value (a random loop program,
 * seed 16474). */
#include <stdint.h>

int16_t a[8];
uint16_t n = 4;
uint16_t calls;

static void count(void)
{
    calls++;
}

void test_main(void)
{
    uint16_t c;
    uint16_t k;
    for (c = 0; c < 8; c++) {
        a[c] = (int16_t)(c * 57 - 252);
    }
    for (c = 0; c <= 5; c++) {
        a[c] ^= 77;
        for (k = (uint16_t)(c + 1); k < n; k++) {
            count();
        }
        if (c) {
            break;
        }
    }
}
