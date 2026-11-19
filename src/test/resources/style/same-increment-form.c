/* rule: same-increment-form
 * claim: count_postfix same count_prefix
 * i++ and ++i cost the same. */
#include <stdint.h>

uint8_t data[200];
uint16_t r1, r2;

uint16_t count_postfix(void)
{
    uint16_t s = 0, i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + data[i]);
    }
    return s;
}

uint16_t count_prefix(void)
{
    uint16_t s = 0, i;
    for (i = 0; i < 200; ++i) {
        s = (uint16_t)(s + data[i]);
    }
    return s;
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 200; i++) {
        data[i] = (uint8_t)i;
    }
    r1 = count_postfix();
    r2 = count_prefix();
}
