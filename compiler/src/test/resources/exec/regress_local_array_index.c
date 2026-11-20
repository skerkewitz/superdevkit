/* Regression (2026-11-20): the code generator's record of what A holds took an indexed element
 * of a local array (lda z:dampf_frame+0,x) for a fixed place. A second load with the same text and
 * another X was left out, so la[i] ^= la[k] read la[k] twice. The selection and the LIR's
 * RegContents had the same rule, and by it a store through an index forgot only its own text, not
 * the elements it may write; the lines after out_xor check that side (it did not fail here before
 * the fix). Found by the random loop programs (RandomLoopProgram, seed 105). */
#include <stdint.h>

uint16_t i = 26;
uint8_t k = 1;
uint16_t j = 3;
uint8_t out_xor, out_fixed;
uint16_t out_word, out_sum;

void test_main(void)
{
    uint8_t la[32];
    uint16_t lw[8];
    uint16_t t;
    la[1] = 7;
    la[26] = 3;
    la[i & 31] ^= la[k];          /* 3 ^ 7: two indexed loads with different X */
    out_xor = la[26];
    lw[3] = 1000;
    t = lw[3];
    lw[j & 7] = 0;                /* writes lw[3] through the index */
    out_word = lw[3];
    out_sum = (uint16_t)(t + lw[3]);
    la[5] = 40;
    la[(uint16_t)(i - 21) & 31] = 0;
    out_fixed = la[5];
}
