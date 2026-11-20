/* family: fill-words
 * tolerance: 10
 * A constant stored into every element of a uint16_t array, by index with every counter type,
 * up and down, and by pointer. The style guide's slow-u16-counter-words.
 * gap: fill_u16 L4 the i16 counter is kept beside the derived offset (slow-u16-counter-words)
 * gap: fill_i16 R1,L4 widening throws away the lower bound; the counter is kept beside the offset
 * gap: fill_u16_le L4 as fill_u16
 * gap: fill_u16_ne L2,L4 != gives the range analysis no bound; the counter is kept beside the offset
 * gap: fill_u16_down L2,L3 i != 0 gives the range analysis no bound, so i - 1 is no index (24-bit pointer); i - 1 is also the step, and the X pattern wants a step with one user
 * gap: fill_u8_down L2,C5 as h_u8_down of sum-bytes
 * gap: fill_while L4 as fill_u16
 * gap: fill_do L3,L4 as fill_u16, and the test reads the stepped value
 * gap: fill_ptr_walk L5 the pointer's offset and the counter have one start and step
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
