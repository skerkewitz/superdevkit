/* family: sum-bytes
 * tolerance: 10
 * The sum of data[0] to data[99] into a uint16_t, by index and by pointer, with every counter
 * type, direction, test and loop form. The appendix of docs/current-compiler-flaws.md.
 * gap: d_u8_ne100 L2 != gives the range analysis no bound, so the byte counter "might wrap"
 * gap: g_u16_down L2,L3 i != 0 gives the range analysis no bound, so i - 1 is no index (24-bit pointer); i - 1 is also the step, and the X pattern wants a step with one user
 * gap: h_u8_down L2,C5 i != 0 gives the range analysis no bound; the byte counter's index is computed in 16 bits beside it
 * gap: i_u16_dowhile L3 the test reads the stepped value, the X pattern wants the phi
 * gap: j_u16_var_bound C7 the global bound is loaded every round; index not proved below 32 KB
 * gap: k_u8_var_bound C7 the global bound is loaded every round
 * gap: l_u16_local_bound R3 index not proved below 32 KB, so a 24-bit pointer
 * gap: m_u16_from1 L4 the index is i - 1, the X pattern wants i itself
 * gap: n_u16_ne100 L2 != gives the range analysis no bound
 * gap: x_ptr_walk L5 two counters that always hold the same value, both kept
 * gap: y_ptr_end C6 the pointers are compared as 32-bit values every round
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
