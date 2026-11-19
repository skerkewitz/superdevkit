/* rule: same-multiply-width
 * claim: multiply_bytes same multiply_words
 * A multiplication of two bytes costs as much as one of two 16-bit values: both call the runtime
 * (the SNES's 8 x 8 multiplier is not used). Narrowing the factors buys nothing. */
#include <stdint.h>

uint8_t ua = 12, ub = 40;
uint16_t va = 12, vb = 40;
uint16_t r1, r2;

uint16_t multiply_bytes(void)
{
    return (uint16_t)(ua * ub);
}

uint16_t multiply_words(void)
{
    return (uint16_t)(va * vb);
}

void test_main(void)
{
    r1 = multiply_bytes();
    r2 = multiply_words();
}
