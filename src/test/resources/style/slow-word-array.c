/* rule: slow-word-array
 * claim: sum_words slower sum_bytes
 * An element of uint16_t costs more to reach than one of uint8_t: the index is doubled first. Use
 * the narrowest type the values fit, for arrays (not for loop counters, slow-u8-counter). */
#include <stdint.h>

uint8_t bytes[200];
uint16_t words[200];
uint16_t r1, r2;

uint16_t sum_bytes(void)
{
    uint16_t s = 0;
    uint16_t i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + bytes[i]);
    }
    return s;
}

uint16_t sum_words(void)
{
    uint16_t s = 0;
    uint16_t i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + words[i]);
    }
    return s;
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 200; i++) {
        bytes[i] = (uint8_t)(i * 3);
        words[i] = (uint16_t)(i * 3 % 256);
    }
    r1 = sum_bytes();
    r2 = sum_words();
}
