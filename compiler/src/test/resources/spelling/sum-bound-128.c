/* family: sum-bound-128
 * tolerance: 10
 * The loop of sum-bytes with the bound 128, for each counter type that holds it, up and down:
 * the value of a constant bound must not change the code.
 * gap: i16_up R1 more than 24 rounds: widening throws away the lower bound, signed compare, 24-bit pointer
 * gap: u16_down C1 the step is a sub, every loop pattern wants an add; 24-bit pointer
 * gap: i16_down C1 the step is a sub, and the test i >= 0 is signed: the counter stays in memory
 */
#include <stdint.h>

uint8_t data[300];

void setup(void)
{
    uint16_t i;
    for (i = 0; i < 300; i++) {
        data[i] = (uint8_t)(i * 7);
    }
}

uint16_t u16_up(void) { uint16_t s = 0; uint16_t i; for (i = 0; i < 128; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t i16_up(void) { uint16_t s = 0; int16_t i; for (i = 0; i < 128; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t u8_up(void) { uint16_t s = 0; uint8_t i; for (i = 0; i < 128; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t u16_down(void) { uint16_t s = 0; uint16_t i; for (i = 128; i > 0; i--) s = (uint16_t)(s + data[i - 1]); return s; }
uint16_t i16_down(void) { uint16_t s = 0; int16_t i; for (i = 128 - 1; i >= 0; i--) s = (uint16_t)(s + data[i]); return s; }
