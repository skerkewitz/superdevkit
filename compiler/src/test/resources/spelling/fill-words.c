/* family: fill-words
 * tolerance: 10
 * A constant stored into every element of a uint16_t array, by index with every counter type,
 * up and down, and by pointer. Was the style guide's slow-u16-counter-words.
 * gap: fill_ptr_walk rotate the loop head computes the pointer (no code: the store adds the offset), so the loop is not rotated: a BCS out and a BRA back each round
 * gap: fill_ptr_end C6 the pointers are compared as 32-bit values every round
 */
#include <stdint.h>

uint16_t w[64];

void setup(void)
{
    uint16_t i;
    for (i = 0; i < 64; i++) {
        w[i] = 0;
    }
}

uint16_t check(void)
{
    uint16_t i, s = 0;
    for (i = 0; i < 64; i++) {
        s = (uint16_t)(s + w[i] * (i + 1));
    }
    return s;
}

void fill_u16(void) { uint16_t i; for (i = 0; i < 64; i++) w[i] = 0x0C01; }
void fill_u8(void) { uint8_t i; for (i = 0; i < 64; i++) w[i] = 0x0C01; }
void fill_i16(void) { int16_t i; for (i = 0; i < 64; i++) w[i] = 0x0C01; }
void fill_u16_le(void) { uint16_t i; for (i = 0; i <= 63; i++) w[i] = 0x0C01; }
void fill_u16_ne(void) { uint16_t i; for (i = 0; i != 64; i++) w[i] = 0x0C01; }
void fill_u16_down(void) { uint16_t i; for (i = 64; i > 0; i--) w[i - 1] = 0x0C01; }
void fill_u8_down(void) { uint8_t i; for (i = 64; i > 0; i--) w[i - 1] = 0x0C01; }
void fill_while(void) { uint16_t i = 0; while (i < 64) { w[i] = 0x0C01; i++; } }
void fill_do(void) { uint16_t i = 0; do { w[i] = 0x0C01; i++; } while (i < 64); }
void fill_ptr_walk(void) { uint16_t *p = w; uint16_t i; for (i = 0; i < 64; i++) *p++ = 0x0C01; }
void fill_ptr_end(void) { uint16_t *p; for (p = w; p < w + 64; p++) *p = 0x0C01; }
