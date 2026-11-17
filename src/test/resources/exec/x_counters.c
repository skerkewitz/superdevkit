/* Loop counters that dampf keeps in X (counted with INX, compared with CPX, used as the index).
 * Two loops in a row: the first loop's test block enters the second, which loads its start
 * value into X; that must not happen while the first loop still needs X (a bug once: the
 * benchmark hung). Also a loop counting down, one with a step of 3, and an 8-bit counter. */
#include <stdint.h>

uint8_t a[20];
uint8_t b[20];
uint16_t w[12];
uint16_t sum;
uint8_t last;

void test_main(void)
{
    uint8_t i, j;
    uint16_t k;
    a[3] = 9;
    for (i = 0; i < 20; i++) {
        a[i] = (uint8_t)(a[i] + 5u);
    }
    for (j = 0; j < 20; j++) {
        b[j] = (uint8_t)(a[j] + 1u);
    }
    for (k = 12; k != 0; k--) {
        w[k - 1u] = (uint16_t)(k * 100u);
    }
    for (k = 0; k < 18; k += 3) {
        sum = (uint16_t)(sum + b[k]);
    }
    last = b[19];
}
