/* rule: slow-hand-shifts
 * claim: times_40_by_shifts slower times_40
 * Writing a multiplication by a constant as shifts by hand costs more than writing * 40: dampf
 * makes the shifts itself. */
#include <stdint.h>

uint16_t va = 123;
uint16_t r1, r2;

uint16_t times_40(void)
{
    return (uint16_t)(va * 40);
}

uint16_t times_40_by_shifts(void)
{
    return (uint16_t)((va << 5) + (va << 3));
}

void test_main(void)
{
    r1 = times_40();
    r2 = times_40_by_shifts();
}
