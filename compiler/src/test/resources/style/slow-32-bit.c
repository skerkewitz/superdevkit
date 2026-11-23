/* rule: slow-32-bit
 * claim: add_32 slower add_16
 * A 32-bit addition costs more than twice a 16-bit one: its words go through memory and the carry
 * goes from one to the other. Bit operations are the exception (same-32-bit-bits). */
#include <stdint.h>

uint32_t sum32 = 0x12345678u, step32 = 0x00010002u;
uint16_t sum16 = 0x5678, step16 = 0x0002;

void add_32(void)
{
    sum32 += step32;
}

void add_16(void)
{
    sum16 = (uint16_t)(sum16 + step16);
}

void test_main(void)
{
    add_32();
    add_16();
}
