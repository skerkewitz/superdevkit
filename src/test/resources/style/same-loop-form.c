/* rule: same-loop-form
 * claim: loop_for same loop_while
 * A for loop and the same loop written with while cost the same. */
#include <stdint.h>

uint8_t data[200];
uint16_t r1, r2;

uint16_t loop_for(void)
{
    uint16_t s = 0, i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + data[i]);
    }
    return s;
}

uint16_t loop_while(void)
{
    uint16_t s = 0, i = 0;
    while (i < 200) {
        s = (uint16_t)(s + data[i]);
        i++;
    }
    return s;
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 200; i++) {
        data[i] = (uint8_t)(i * 9);
    }
    r1 = loop_for();
    r2 = loop_while();
}
