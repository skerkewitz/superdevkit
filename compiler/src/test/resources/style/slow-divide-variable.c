/* rule: slow-divide-variable
 * claim: divide_variables slower divide_constant
 * A division by a variable calls the runtime; an unsigned division by a power of two is a shift. */
#include <stdint.h>

uint16_t va = 12345, vb = 8;
uint16_t r1, r2;

uint16_t divide_variables(void)
{
    return (uint16_t)(va / vb);
}

uint16_t divide_constant(void)
{
    return (uint16_t)(va / 8);
}

void test_main(void)
{
    r1 = divide_variables();
    r2 = divide_constant();
}
