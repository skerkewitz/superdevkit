/* family: copy
 * tolerance: 10
 * One byte array copied into another, by index with every counter type, up and down, and by
 * pointer.
 * gap: copy_ptr_end C6 the pointers are compared as 32-bit values every round
 */
#include <stdint.h>

uint8_t src[100], dst[100];

void setup(void)
{
    uint16_t i;
    for (i = 0; i < 100; i++) {
        src[i] = (uint8_t)(i * 13 + 5);
        dst[i] = 0;
    }
}

uint16_t check(void)
{
    uint16_t i, s = 0;
    for (i = 0; i < 100; i++) {
        s = (uint16_t)(s + dst[i] * (i + 1));
    }
    return s;
}

void copy_u16(void) { uint16_t i; for (i = 0; i < 100; i++) dst[i] = src[i]; }
void copy_u8(void) { uint8_t i; for (i = 0; i < 100; i++) dst[i] = src[i]; }
void copy_i16(void) { int16_t i; for (i = 0; i < 100; i++) dst[i] = src[i]; }
void copy_down(void) { uint16_t i; for (i = 100; i > 0; i--) dst[i - 1] = src[i - 1]; }
void copy_ptr(void) { const uint8_t *s = src; uint8_t *d = dst; uint16_t n; for (n = 0; n < 100; n++) *d++ = *s++; }
void copy_ptr_end(void) { const uint8_t *s = src; uint8_t *d; for (d = dst; d < dst + 100; d++) *d = *s++; }
void copy_while_n(void) { const uint8_t *s = src; uint8_t *d = dst; uint16_t n = 100; while (n--) *d++ = *s++; }
