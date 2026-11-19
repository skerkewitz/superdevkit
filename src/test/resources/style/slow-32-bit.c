/* rule: slow-32-bit
 * claim: xor_32 slower xor_16_halves
 * A 32-bit value costs more than twice a 16-bit one: each operation goes through memory. A value
 * that is only ever combined bit by bit (a hash) can be kept as two 16-bit halves. */
#include <stdint.h>

uint32_t hash32 = 0x12345678u, key32 = 0x9ABCDEF0u;
uint16_t hash_lo = 0x5678, hash_hi = 0x1234, key_lo = 0xDEF0, key_hi = 0x9ABC;

void xor_32(void)
{
    hash32 ^= key32;
}

void xor_16_halves(void)
{
    hash_lo ^= key_lo;
    hash_hi ^= key_hi;
}

void test_main(void)
{
    xor_32();
    xor_16_halves();
}
