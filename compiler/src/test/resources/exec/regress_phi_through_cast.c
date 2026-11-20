/* Regression (2026-11-20): two φs of a loop's head, the counter c and l = c of the last round. With
 * a byte counter, l's operand is zext c, which has no place of its own and reads c's. The code
 * generator wrote both φs straight at the end of the step block ("direct"), c first, so l got the
 * new c: 253 here instead of 249. FrameLayout.directPhis checked that no φ reads another, but not
 * through such a cast. Found by the random loop programs (RandomLoopProgram, seed 1438). */
#include <stdint.h>

uint8_t go = 1;
int16_t out;
uint16_t out_wide;

void test_main(void)
{
    int16_t l = -1;
    uint16_t w = 0;
    if (go) {
        uint8_t c;
        for (c = 1; c != 253; c += 4) {
            l = (int16_t)(c);
        }
        {
            uint16_t k;
            for (k = 3; k < 300; k += 7) {
                w = k;
            }
        }
    }
    out = l;
    out_wide = w;
}
