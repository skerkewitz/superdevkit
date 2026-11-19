/* rule: same-named-temporary
 * claim: with_temporary same inline_expression
 * Naming a value used once costs nothing: it stays in the accumulator either way. Name it when that
 * makes the code clearer. */
#include <stdint.h>

uint16_t x = 100, y = 30, z = 7;
uint16_t r1, r2;

uint16_t with_temporary(void)
{
    uint16_t sum = (uint16_t)(x + y);
    uint16_t scaled = (uint16_t)(sum << 2);
    return (uint16_t)(scaled - z);
}

uint16_t inline_expression(void)
{
    return (uint16_t)((uint16_t)((uint16_t)(x + y) << 2) - z);
}

void test_main(void)
{
    r1 = with_temporary();
    r2 = inline_expression();
}
