/* Regression (2026-11-20): a byte counter stepped down from 0 by a 16-bit DEC became $FFFF instead
 * of $00FF. Since C1, c-- on a byte is c + -1, which ArithSelection.staysByte finds clean for
 * c = 0 (ADC #$00FF gives 255), so it got no mask; incDec still wrote the 16-bit DEC. The signed
 * test c > -1 then read $FFFF and the loop did not end. Found by the random loop programs
 * (RandomLoopProgram, seed 5052). */
#include <stdint.h>

uint16_t a[8] = {3, 1, 4, 1, 5, 9, 2, 6};
uint16_t sum;
uint16_t rounds;

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 20; i++) {
        int8_t c;
        for (c = 0; c > -1; c--) {
            sum = (uint16_t)(sum + a[(uint16_t)c & 7]);
            rounds++;
        }
    }
}
