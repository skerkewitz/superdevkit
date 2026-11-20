/* family: pairs
 * tolerance: 10
 * The nested loop over all pairs (i, j) with j > i, as a game's collision test: the pairs closer
 * than 8 in x. By index with every counter type, by pointer, and with the inner loop counting down.
 * gap: pairs_ptr C6 the pointers are compared as 32-bit values: b is computed every inner round, and the indexes into xs come on top
 * gap: pairs_down L4 the counter moved to its offset is compared with a bound in a place, and the X pattern takes constants only (o >= 2 * (i + 1))
 */
#include <stdint.h>

int16_t xs[24] = {
    3, 90, 11, 140, 17, 200, 25, 31, 88, 95, 150, 152, 7, 60, 64, 66, 120, 127, 180, 185, 230, 233, 1, 250
};

uint16_t pairs_u16(void)
{
    uint16_t i, j, n = 0;
    for (i = 0; i < 24; i++) {
        for (j = i + 1; j < 24; j++) {
            int16_t d = (int16_t)(xs[i] - xs[j]);
            if (d < 8 && d > -8) {
                n++;
            }
        }
    }
    return n;
}

uint16_t pairs_u8(void)
{
    uint8_t i, j;
    uint16_t n = 0;
    for (i = 0; i < 24; i++) {
        for (j = (uint8_t)(i + 1); j < 24; j++) {
            int16_t d = (int16_t)(xs[i] - xs[j]);
            if (d < 8 && d > -8) {
                n++;
            }
        }
    }
    return n;
}

uint16_t pairs_i16(void)
{
    int16_t i, j;
    uint16_t n = 0;
    for (i = 0; i < 24; i++) {
        for (j = (int16_t)(i + 1); j < 24; j++) {
            int16_t d = (int16_t)(xs[i] - xs[j]);
            if (d < 8 && d > -8) {
                n++;
            }
        }
    }
    return n;
}

uint16_t pairs_hoisted(void)
{
    uint16_t i, j, n = 0;
    for (i = 0; i < 24; i++) {
        int16_t xi = xs[i];
        for (j = i + 1; j < 24; j++) {
            int16_t d = (int16_t)(xi - xs[j]);
            if (d < 8 && d > -8) {
                n++;
            }
        }
    }
    return n;
}

uint16_t pairs_ptr(void)
{
    const int16_t *a, *b;
    uint16_t n = 0;
    for (a = xs; a < xs + 24; a++) {
        for (b = a + 1; b < xs + 24; b++) {
            int16_t d = (int16_t)(*a - *b);
            if (d < 8 && d > -8) {
                n++;
            }
        }
    }
    return n;
}

uint16_t pairs_down(void)
{
    uint16_t i, j, n = 0;
    for (i = 0; i < 24; i++) {
        for (j = 23; j > i; j--) {
            int16_t d = (int16_t)(xs[i] - xs[j]);
            if (d < 8 && d > -8) {
                n++;
            }
        }
    }
    return n;
}
