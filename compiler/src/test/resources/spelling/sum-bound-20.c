/* family: sum-bound-20
 * tolerance: 10
 * The loop of sum-bytes with the bound 20, for each counter type that holds it, up and down:
 * the value of a constant bound must not change the code.
 * gap: u16_down L3 i - 1 is the step, and the X pattern wants a step with one user
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

uint16_t u16_up(void) { uint16_t s = 0; uint16_t i; for (i = 0; i < 20; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t i16_up(void) { uint16_t s = 0; int16_t i; for (i = 0; i < 20; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t u8_up(void) { uint16_t s = 0; uint8_t i; for (i = 0; i < 20; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t u16_down(void) { uint16_t s = 0; uint16_t i; for (i = 20; i > 0; i--) s = (uint16_t)(s + data[i - 1]); return s; }
uint16_t i16_down(void) { uint16_t s = 0; int16_t i; for (i = 20 - 1; i >= 0; i--) s = (uint16_t)(s + data[i]); return s; }
