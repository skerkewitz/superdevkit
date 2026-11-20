/* family: sum-bound-128
 * tolerance: 10
 * The loop of sum-bytes with the bound 128, for each counter type that holds it, up and down:
 * the value of a constant bound must not change the code.
 * gap: u16_down L2,L3 i != 0 gives the range analysis no bound, so i - 1 is no index (24-bit pointer); i - 1 is also the step, and the X pattern wants a step with one user
 * gap: i16_down L3 the test i >= 0 is signed, and the X pattern takes eq, ne, ult and uge only
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
