/* Signed division, remainder and shift of values whose sign the ranges know: not negative, they
 * become the unsigned operations (RangeNarrow); a value that can be negative keeps C's rounding
 * towards zero. */
#include <stdint.h>
#include <dampf.h>

int16_t v[10] = { 0, 1, 7, 8, 9, 1000, 32767, -1, -8, -32768 };
int16_t div_known[7], rem_known[7], shift_known[7];
int16_t div_any[10], rem_any[10], shift_any[10];
int16_t after_test[10];

static int16_t known_div(int16_t x) { DAMPF_RANGE(x, 0, 32767); return (int16_t)(x / 8); }
static int16_t known_rem(int16_t x) { DAMPF_RANGE(x, 0, 32767); return (int16_t)(x % 8); }
static int16_t known_shift(int16_t x) { DAMPF_RANGE(x, 0, 32767); return (int16_t)(x >> 3); }

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 7; i++) {
        div_known[i] = known_div(v[i]);
        rem_known[i] = known_rem(v[i]);
        shift_known[i] = known_shift(v[i]);
    }
    for (i = 0; i < 10; i++) {
        int16_t x = v[i];
        div_any[i] = (int16_t)(x / 8);
        rem_any[i] = (int16_t)(x % 8);
        shift_any[i] = (int16_t)(x >> 3);
        after_test[i] = x >= 0 ? (int16_t)(x / 16) : (int16_t)(x / 4);
    }
}
