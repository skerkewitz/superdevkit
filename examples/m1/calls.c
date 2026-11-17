/* M1 example: open build/examples/m1/calls.sfc in Mesen and step through test_main() in the C source. */
#include <stdint.h>

uint16_t result;
uint16_t nested;
uint8_t small_result;
int8_t signed_result;

static uint16_t add3(uint16_t a, uint16_t b, uint16_t c)
{
    uint16_t s = a + b;
    s = s + c;
    return s;
}

static uint8_t inc8(int8_t x)
{
    return x + 1;               /* int arithmetic, then truncated to uint8_t */
}

static int8_t neg8(uint8_t x)
{
    return -x;                  /* -(int)x, then converted to int8_t */
}

void test_main(void)
{
    result = add3(1000, 2000, 3);
    nested = add3(add3(1, 2, 3), add3(10, 20, 30), add3(100, 200, 300));
    small_result = inc8(-1);
    signed_result = neg8(inc8(126));
}
