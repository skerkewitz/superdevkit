/* Regression (2026-11-20): the lowering's SSA construction returned a φ it had just removed.
 * Reading l after the if made a φ in the do loop's first block and one where the ?: joins; the
 * second was trivial and removed, which made the first trivial too, but the first was returned
 * as l's value and written into the blocks the read went through: "uses an instruction that is
 * not in the function". Found by the random loop programs (RandomLoopProgram, seed 12). */
#include <stdint.h>

uint8_t g = 1;
uint8_t h;
uint16_t a[8] = {1, 2, 3, 4, 5, 6, 7, 8};
int8_t out_taken, out_skipped;
uint16_t sum;

static int8_t run(uint8_t on)
{
    int8_t l = 5;
    if (on) {
        uint16_t c = 0;
        do {
            a[c & 7] = (uint16_t)(g ? a[(c + 1) & 7] : a[2]);
            sum = (uint16_t)(sum + a[c & 7]);
            c++;
        } while (c != 10);
    }
    return l;
}

void test_main(void)
{
    out_taken = run(1);
    out_skipped = run(h);
}
