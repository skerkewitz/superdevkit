/* family: sum-bytes
 * tolerance: 10
 * gap: i_u16_dowhile reverse as copy_u16 in copy.c, against g_u16_down; the other loops counting up are within 10 %
 * gap: n_u16_ne100 reverse as i_u16_dowhile
 * The sum of data[0] to data[99] into a uint16_t, by index and by pointer, with every counter
 * type, direction, test and loop form. The appendix of docs/archive/compiler-flaws.md.
 */
#include <stdint.h>

uint8_t data[200];
uint8_t n8 = 100;
uint16_t n16 = 100;

void setup(void)
{
    uint16_t i;
    for (i = 0; i < 200; i++) {
        data[i] = (uint8_t)(i * 7);
    }
}

uint16_t a_u16_lt100(void) { uint16_t s = 0; uint16_t i; for (i = 0; i < 100; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t b_u8_lt100(void) { uint16_t s = 0; uint8_t i; for (i = 0; i < 100; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t c_i16_lt100(void) { uint16_t s = 0; int16_t i; for (i = 0; i < 100; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t d_u8_ne100(void) { uint16_t s = 0; uint8_t i; for (i = 0; i != 100; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t e_u8_le99(void) { uint16_t s = 0; uint8_t i; for (i = 0; i <= 99; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t f_u16_le99(void) { uint16_t s = 0; uint16_t i; for (i = 0; i <= 99; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t g_u16_down(void) { uint16_t s = 0; uint16_t i; for (i = 100; i > 0; i--) s = (uint16_t)(s + data[i - 1]); return s; }
uint16_t h_u8_down(void) { uint16_t s = 0; uint8_t i; for (i = 100; i > 0; i--) s = (uint16_t)(s + data[i - 1]); return s; }
uint16_t i_u16_dowhile(void) { uint16_t s = 0; uint16_t i = 0; do { s = (uint16_t)(s + data[i]); i++; } while (i < 100); return s; }
uint16_t j_u16_var_bound(void) { uint16_t s = 0; uint16_t i; for (i = 0; i < n16; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t k_u8_var_bound(void) { uint16_t s = 0; uint8_t i; for (i = 0; i < n8; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t l_u16_local_bound(void) { uint16_t s = 0; uint16_t i; uint16_t n = n16; for (i = 0; i < n; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t m_u16_from1(void) { uint16_t s = 0; uint16_t i; for (i = 1; i < 101; i++) s = (uint16_t)(s + data[i - 1]); return s; }
uint16_t n_u16_ne100(void) { uint16_t s = 0; uint16_t i; for (i = 0; i != 100; i++) s = (uint16_t)(s + data[i]); return s; }
uint16_t o_u16_while(void) { uint16_t s = 0; uint16_t i = 0; while (i < 100) { s = (uint16_t)(s + data[i]); i++; } return s; }
uint16_t x_ptr_walk(void) { uint16_t s = 0; const uint8_t *p = data; uint16_t i; for (i = 0; i < 100; i++) s = (uint16_t)(s + *p++); return s; }
uint16_t y_ptr_end(void) { uint16_t s = 0; const uint8_t *p; for (p = data; p < data + 100; p++) s = (uint16_t)(s + *p); return s; }
