/* rule: slow-signed-divide
 * claim: divide_signed slower divide_unsigned
 * A signed division by a power of two needs a correction for negative values (C rounds towards
 * zero); dividing the value as unsigned, where it cannot be negative, is a shift. A DAMPF_RANGE
 * that proves the value is not negative does not remove the correction today. */
#include <stdint.h>
#include <dampf.h>

int16_t sv = 1000;
int16_t r1, r2;

int16_t divide_signed(void)
{
    int16_t v = sv;
    DAMPF_RANGE(v, 0, 1000);
    return (int16_t)(v / 8);
}

int16_t divide_unsigned(void)
{
    int16_t v = sv;
    DAMPF_RANGE(v, 0, 1000);
    return (int16_t)((uint16_t)v / 8);
}

void test_main(void)
{
    r1 = divide_signed();
    r2 = divide_unsigned();
}
