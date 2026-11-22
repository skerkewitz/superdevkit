/* The runtime's multiplications on the CPU's 8 x 8 multiplier: every pair of values with bytes
 * that exercise each partial product and each carry, for 16 and 32 bits, both operand orders. */
#include <stdint.h>

#define N16 14
#define N32 16

uint16_t w[N16] = { 0, 1, 2, 3, 0x00FF, 0x0100, 0x0101, 0x7FFF, 0x8000, 0x8001, 0xFF00, 0xFFFE, 0xFFFF, 0xA5C3 };
uint32_t d[N32] = { 0, 1, 2, 0xFF, 0x100, 0xFFFF, 0x10000, 0x10001, 0xFF00FF, 0x1000000, 0x7FFFFFFF, 0x80000000,
                    0x80000001, 0xFFFFFF00, 0xFFFFFFFF, 0xC3A51E87 };
uint16_t sum16, xor16;
uint32_t sum32, xor32;
int16_t ssum16;
int32_t ssum32;
uint8_t ub[12] = { 0, 1, 2, 3, 15, 16, 127, 128, 129, 200, 254, 255 };
int8_t sb[9] = { 0, 1, -1, 2, -2, 127, -127, -128, 77 };
uint16_t sum_bytes, xor_bytes;
uint8_t sum_low;
int16_t sum_signed;
int8_t sum_low_signed;

void test_main(void)
{
    uint8_t i, j;
    for (i = 0; i < N16; i++) {
        for (j = 0; j < N16; j++) {
            uint16_t p = (uint16_t)((uint32_t)w[i] * w[j]);
            sum16 = (uint16_t)(sum16 + p);
            xor16 = (uint16_t)((uint16_t)(xor16 << 1 | xor16 >> 15) ^ p);
            ssum16 = (int16_t)(ssum16 + (int16_t)((uint16_t)w[i] * (uint32_t)(uint16_t)(w[j] ^ 0x5A5A)));
        }
    }
    for (i = 0; i < N32; i++) {
        for (j = 0; j < N32; j++) {
            uint32_t p = d[i] * d[j];
            sum32 += p;
            xor32 = (xor32 << 1 | xor32 >> 31) ^ p;
            ssum32 = (int32_t)((uint32_t)ssum32 + (d[i] ^ 0x5A5A5A5A) * (d[j] + 7));
        }
    }
    /* two bytes: the CPU's multiplier without a call, also for a byte result and signed bytes */
    for (i = 0; i < 12; i++) {
        for (j = 0; j < 12; j++) {
            uint16_t p = (uint16_t)(ub[i] * ub[j]);
            sum_bytes = (uint16_t)(sum_bytes + p);
            xor_bytes = (uint16_t)((uint16_t)(xor_bytes << 3 | xor_bytes >> 13) ^ p);
            sum_low = (uint8_t)(sum_low + (uint8_t)(ub[i] * ub[j]));
        }
    }
    for (i = 0; i < 9; i++) {
        for (j = 0; j < 9; j++) {
            sum_signed = (int16_t)(sum_signed + sb[i] * sb[j]);
            sum_low_signed = (int8_t)(sum_low_signed + (int8_t)(sb[i] * sb[j]));
        }
    }
}
