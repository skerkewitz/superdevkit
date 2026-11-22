/* rule: slow-signed-divide
 * claim: divide_signed slower divide_unsigned
 * A signed division by a power of two needs a correction for negative values (C rounds towards
 * zero); dividing the value as unsigned, where it cannot be negative, is a shift. Where dampf
 * knows the value is not negative (a comparison before it, a DAMPF_RANGE), it does that itself. */
#include <stdint.h>

int16_t sv = 1000;
int16_t r1, r2;

int16_t divide_signed(void)
{
    int16_t v = sv;
    return (int16_t)(v / 8);
}

int16_t divide_unsigned(void)
{
    int16_t v = sv;
    return (int16_t)((uint16_t)v / 8);
}

void test_main(void)
{
    r1 = divide_signed();
    r2 = divide_unsigned();
}
