/* rule: slow-u8-counter
 * claim: sum_u8_counter slower sum_u16_counter
 * A loop counter of uint8_t costs more than one of uint16_t: each i++ switches the accumulator
 * to 8 bits and back, and each byte load is masked. */
#include <stdint.h>

uint8_t data[200];
uint16_t r1, r2;

uint16_t sum_u8_counter(void)
{
    uint16_t s = 0;
    uint8_t i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + data[i]);
    }
    return s;
}

uint16_t sum_u16_counter(void)
{
    uint16_t s = 0;
    uint16_t i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + data[i]);
    }
    return s;
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 200; i++) {
        data[i] = (uint8_t)(i * 7);
    }
    r1 = sum_u8_counter();
    r2 = sum_u16_counter();
}
