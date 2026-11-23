/* 32-bit values only loaded, combined bit by bit and stored become two 16-bit values (SplitWords):
 * with constants, zero extensions, a value chosen by a branch, truncations and the high half; and
 * tests for equality on the halves; and values that must stay whole, since an addition or an ordered
 * comparison reads them too. */
#include <stdint.h>

uint32_t a = 0x12345678u, b = 0x9ABCDEF0u, c = 0xFFFF0000u, r[12];
uint16_t w = 0xBEEF, low, high, tests[5];
uint8_t byte, pick = 1;
uint32_t table[4] = { 0x01020304u, 0xA0B0C0D0u, 0xFFFFFFFFu, 0x80000001u };
int32_t s = -2;

void test_main(void)
{
    uint8_t i;
    r[0] = a ^ b;
    r[1] = a & b;
    r[2] = a | c;
    r[3] = (a ^ 0x0000FFFFu) & 0xF0F0F0F0u;
    r[4] = b ^ (uint32_t)w;
    r[5] = (pick ? a : b) ^ c;
    low = (uint16_t)(a ^ b);
    high = (uint16_t)((a ^ b) >> 16);
    byte = (uint8_t)(a | b);
    for (i = 0; i < 4; i++) {
        r[6] ^= table[i];
    }
    r[7] = (a ^ b) + 1;              /* whole: an addition reads it */
    r[8] = (a & c) > 0x12340000u;    /* whole: an ordered comparison reads it */
    r[9] = (uint32_t)s ^ a;
    for (i = 0; i < 4; i++) {
        if (table[i] & c) { tests[0]++; }
        if (!(table[i] & 0x0000FFFFu)) { tests[1]++; }
        if ((table[i] ^ a) == 0x8BB8CEFCu) { tests[2]++; }
        if ((table[i] | c) != table[(uint8_t)(i + 1) & 3]) { tests[3]++; }
        if ((uint32_t)w == (table[i] & 0xFFFFu)) { tests[4]++; }
    }
    a ^= b;
    b ^= a;
    a ^= b;
    r[10] = a;
    r[11] = b;
}
