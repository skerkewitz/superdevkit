/*
 * Values the code generator does not store: every use takes them from A. Each case is one where
 * a read of the place is easy to miss: a φ that shares the value's place (the loop's sum), a value
 * one branch uses and the other does not (the state), a value read again after A changed.
 */
#include <stdint.h>

uint8_t states[6] = {2, 3, 1, 2, 0, 3};
uint8_t steps[3] = {3, 0, 7};
uint16_t sums[3];
uint16_t picked;
uint16_t mixed;

static uint16_t sum_down(uint8_t k)
{
    uint16_t s = 0;
    while (k) {
        s = (uint16_t)(s + k * 3u);
        k--;
    }
    return s;
}

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 3; i++) {
        sums[i] = sum_down(steps[i]);
    }
    for (i = 0; i < 6; i++) {
        uint8_t st = states[i];
        if (st == 2 || st == 3) {
            picked = (uint16_t)(picked + (uint16_t)(i * 5u));
        }
    }
    for (i = 0; i < 6; i++) {
        uint16_t a = (uint16_t)(states[i] + 7u);
        uint16_t b = (uint16_t)(steps[i % 3] * 2u);
        mixed = (uint16_t)(mixed * 3u + (uint16_t)(a ^ b) + a);
    }
}
