/* A comparison with the sum of an int16_t and a constant, which C does in 32 bits: at the values
 * where the sum no longer fits 16 bits, with every predicate and the sum on either side
 * (CompareSum: a test that it fits, then a 16-bit comparison). */
#include <stdint.h>

#define N 12
int16_t v[N] = { -32768, -32767, -32736, -32735, -33, -1, 0, 1, 32, 32735, 32736, 32767 };
uint16_t above[6], below[6], left[6];

#define COUNT(slot, cond) if (cond) { slot++; }

void test_main(void)
{
    uint8_t i, j;
    for (i = 0; i < N; i++) {
        for (j = 0; j < N; j++) {
            int16_t a = v[i], b = v[j];
            COUNT(above[0], a < b + 32)
            COUNT(above[1], a <= b + 32)
            COUNT(above[2], a > b + 32)
            COUNT(above[3], a >= b + 32)
            COUNT(above[4], a == b + 32)
            COUNT(above[5], a != b + 32)
            COUNT(below[0], a < b - 32)
            COUNT(below[1], a <= b - 32)
            COUNT(below[2], a > b - 32)
            COUNT(below[3], a >= b - 32)
            COUNT(below[4], a == b - 32)
            COUNT(below[5], a != b - 32)
            COUNT(left[0], b + 1 < a)
            COUNT(left[1], b + 1 <= a)
            COUNT(left[2], b - 1 > a)
            COUNT(left[3], b - 1 >= a)
            COUNT(left[4], b + 32767 > a)
            COUNT(left[5], b - 32767 < a)
        }
    }
}
