/* rule: slow-multiply-variable
 * claim: multiply_variables slower multiply_constant
 * A multiplication of two variables calls the runtime; by a constant it becomes shifts and adds.
 * Multiply by a variable only where the factor really varies. */
#include <stdint.h>

uint16_t va = 123, vb = 40;
uint16_t r1, r2;

uint16_t multiply_variables(void)
{
    return (uint16_t)(va * vb);
}

uint16_t multiply_constant(void)
{
    return (uint16_t)(va * 40);
}

void test_main(void)
{
    r1 = multiply_variables();
    r2 = multiply_constant();
}
