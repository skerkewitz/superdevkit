/* rule: slow-shift-above-8
 * claim: shift_by_9 slower shift_by_8_then_1
 * A shift by a constant above 8 is a loop of single shifts today; a shift by 8 is a byte move.
 * Where a table row is 512 bytes (index << 9), splitting the shift into << 8 and << 1 is cheaper. */
#include <stdint.h>

uint16_t v = 0x0035;
uint16_t r1, r2;

uint16_t shift_by_9(void)
{
    return (uint16_t)(v << 9);
}

uint16_t shift_by_8_then_1(void)
{
    return (uint16_t)((uint16_t)(v << 8) << 1);
}

void test_main(void)
{
    r1 = shift_by_9();
    r2 = shift_by_8_then_1();
}
