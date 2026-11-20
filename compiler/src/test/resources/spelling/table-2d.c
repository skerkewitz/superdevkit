/* family: table-2d
 * tolerance: 10
 * The sum of a uint8_t t[8][16], row by row: two loops with every counter type, a row pointer,
 * one loop over all 128 elements, and one pointer over the whole table.
 * gap: rows_u16 collapse the rows lie one after another, so the two loops could be one of 128 rounds (flat_index); the outer loop's 8 rounds and entries are the rest (not in the plan)
 * gap: rows_u8 collapse as rows_u16
 * gap: rows_i16 collapse as rows_u16
 * gap: rows_ptr collapse as rows_u16
 * gap: flat_ptr C6 the pointers are compared as 32-bit values every round
 */
#include <stdint.h>

uint8_t t[8][16];

void setup(void)
{
    uint16_t r, c;
    for (r = 0; r < 8; r++) {
        for (c = 0; c < 16; c++) {
            t[r][c] = (uint8_t)(r * 31 + c * 7);
        }
    }
}

uint16_t rows_u16(void) { uint16_t r, c, s = 0; for (r = 0; r < 8; r++) for (c = 0; c < 16; c++) s = (uint16_t)(s + t[r][c]); return s; }
uint16_t rows_u8(void) { uint8_t r, c; uint16_t s = 0; for (r = 0; r < 8; r++) for (c = 0; c < 16; c++) s = (uint16_t)(s + t[r][c]); return s; }
uint16_t rows_i16(void) { int16_t r, c; uint16_t s = 0; for (r = 0; r < 8; r++) for (c = 0; c < 16; c++) s = (uint16_t)(s + t[r][c]); return s; }
uint16_t rows_ptr(void) { uint16_t r, c, s = 0; for (r = 0; r < 8; r++) { const uint8_t *row = t[r]; for (c = 0; c < 16; c++) s = (uint16_t)(s + row[c]); } return s; }
uint16_t flat_index(void) { uint16_t i, s = 0; for (i = 0; i < 128; i++) s = (uint16_t)(s + t[i >> 4][i & 15]); return s; }
uint16_t flat_ptr(void) { const uint8_t *p; uint16_t s = 0; for (p = (const uint8_t *)t; p < (const uint8_t *)t + 128; p++) s = (uint16_t)(s + *p); return s; }
