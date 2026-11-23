/* rule: same-32-bit-bits
 * claim: xor_32 same xor_16_halves
 * A value only combined bit by bit (a hash) costs the same as one uint32_t or as two uint16_t
 * halves: no bit carries from one half to the other, and dampf splits it (SplitWords). */
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
