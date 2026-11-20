/* rule: slow-u16-counter-words
 * claim: fill_u16_counter slower fill_u8_counter
 * In a loop over an array of uint16_t, an 8-bit counter costs less than a 16-bit one: dampf then
 * drops the counter and steps the index register by 2, where it keeps a 16-bit counter in memory
 * beside it. Over bytes both widths cost the same (the rule slow-u8-counter went when byte counters
 * got unsigned ranges, 2026-11-20); over words they should too. */
#include <stdint.h>

uint16_t a[64], b[64];

void fill_u8_counter(void)
{
    uint8_t i;
    for (i = 0; i < 64; i++) {
        a[i] = (uint16_t)(1 | (3 << 10));
    }
}

void fill_u16_counter(void)
{
    uint16_t i;
    for (i = 0; i < 64; i++) {
        b[i] = (uint16_t)(1 | (3 << 10));
    }
}

void test_main(void)
{
    fill_u8_counter();
    fill_u16_counter();
}
