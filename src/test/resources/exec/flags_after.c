/*
 * A test of a value for 0 or its sign right after other code: the code generator leaves out
 * the CMP #0 when the instruction that put the value in A set N and Z for it, and must not
 * when something else (an index load for a store, a call, a comparison) set them since.
 */
#include <stdint.h>

uint8_t table[16] = {0, 3, 0, 7, 255, 0, 128, 1, 0, 9, 0, 0, 64, 2, 0, 5};
uint8_t out[16];
int16_t signs[16];
uint16_t zeros, nonzeros, negatives;

static uint8_t twice(uint8_t v)
{
    return (uint8_t)(v + v);
}

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 16; i++) {
        uint8_t t = (uint8_t)(table[i] & 0x0F);
        out[(uint8_t)(15 - i)] = t;              /* an index load between the AND and the test */
        if (t) {
            nonzeros++;
        } else {
            zeros++;
        }
        int16_t d = (int16_t)((int16_t)table[i] - 100);
        signs[i] = d;                            /* a store between the subtraction and the test */
        if (d < 0) {
            negatives++;
        }
        uint8_t u = twice(table[(uint8_t)(i ^ 3)]);  /* a call between */
        if (u == 0) {
            zeros += 100;
        }
        if (table[i] > 5 && t == 0) {            /* a comparison between */
            nonzeros += 1000;
        }
    }
}
