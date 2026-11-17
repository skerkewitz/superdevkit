/* Bitwise operators on promoted values, unary minus and plus, character constants. */
#include <stdint.h>

uint8_t a = 200;
uint8_t b = 0x0F;
int8_t c = -1;
uint16_t not_a;
uint8_t not_a_low;
uint16_t mixed;
int16_t neg_b;
uint16_t xor_hi;
uint8_t letter;
int16_t sign_bits;
uint16_t plus_c;

void test_main(void)
{
    not_a = ~a;                 /* ~(int)200 = -201 -> 65335 */
    not_a_low = ~a;             /* 55 */
    mixed = a & b | 0x300;      /* 8 | 0x300 = 776 */
    neg_b = -b;                 /* -15 */
    xor_hi = 0x1234;
    xor_hi ^= 0xFF00;           /* 0xED34 */
    letter = 'A' + 2;           /* 'C' = 67 */
    sign_bits = c & 0x7F00;     /* (int)-1 & 0x7F00 = 0x7F00 */
    plus_c = +c;                /* -1 -> 65535 */
}
