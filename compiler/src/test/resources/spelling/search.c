/* family: search
 * tolerance: 10
 * The index of the first element equal to a key (it is at index 90): with break, with return,
 * with the test in the loop's condition, by index with every counter type and by pointer.
 * gap: find_return_i16 R1 widening throws away the lower bound: signed compare, 24-bit pointer
 * gap: find_ptr C6 the pointers are compared as 32-bit values every round
 */
#include <stdint.h>

uint8_t data[100] = {
    11, 48, 85, 122, 159, 196, 233, 19, 56, 93, 130, 167, 204, 241, 27, 64,
    101, 138, 175, 212, 249, 35, 72, 109, 146, 183, 220, 6, 43, 80, 117, 154,
    191, 228, 14, 51, 88, 125, 162, 199, 236, 22, 59, 96, 133, 170, 207, 244,
    30, 67, 104, 141, 178, 215, 1, 38, 75, 112, 149, 186, 223, 9, 46, 83,
    120, 157, 194, 231, 17, 54, 91, 128, 165, 202, 239, 25, 62, 99, 136, 173,
    210, 247, 33, 70, 107, 144, 181, 218, 4, 41, 255, 115, 152, 189, 226, 12,
    49, 86, 123, 160
};
uint8_t key = 255;

uint16_t find_break_u16(void) { uint16_t i; for (i = 0; i < 100; i++) { if (data[i] == key) break; } return i; }
uint16_t find_break_u8(void) { uint8_t i; for (i = 0; i < 100; i++) { if (data[i] == key) break; } return i; }
uint16_t find_return_u16(void) { uint16_t i; for (i = 0; i < 100; i++) { if (data[i] == key) return i; } return 100; }
uint16_t find_return_u8(void) { uint8_t i; for (i = 0; i < 100; i++) { if (data[i] == key) return i; } return 100; }
uint16_t find_return_i16(void) { int16_t i; for (i = 0; i < 100; i++) { if (data[i] == key) return (uint16_t)i; } return 100; }
uint16_t find_cond_u16(void) { uint16_t i = 0; while (i < 100 && data[i] != key) i++; return i; }
uint16_t find_ptr(void) { const uint8_t *p; for (p = data; p < data + 100; p++) { if (*p == key) break; } return (uint16_t)(p - data); }
