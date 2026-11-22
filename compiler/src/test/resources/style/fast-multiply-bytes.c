/* rule: fast-multiply-bytes
 * claim: multiply_bytes faster multiply_words
 * Two bytes are multiplied on the SNES's 8 x 8 multiplier, without a call; two 16-bit values whose
 * range dampf does not know call the runtime. */
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
