/* Shifts by every constant below the width, 16 and 32 bits, left, right and right of a signed
 * value: written out, not looped (ArithSelection.unrolledShift). From 16 places on a 32-bit shift
 * moves one word to the other, from 3 places it builds each word from both words of x; the
 * xorshift steps shift a value into its own place. */
#include <stdint.h>

uint32_t ul[31], ur[31];
int32_t sr[31];
uint16_t wl[15], wr[15];
int16_t vr[15];
uint32_t rng[4];
uint16_t hash_hi, hash_lo;

#define S32(n) ul[n - 1] = x << n; ur[n - 1] = x >> n; sr[n - 1] = s >> n;
#define S16(n) wl[n - 1] = (uint16_t)(w << n); wr[n - 1] = w >> n; vr[n - 1] = (int16_t)(v >> n);

static void shifts32(uint32_t x, int32_t s)
{
    S32(1) S32(2) S32(3) S32(4) S32(5) S32(6) S32(7) S32(8) S32(9) S32(10) S32(11) S32(12) S32(13)
    S32(14) S32(15) S32(16) S32(17) S32(18) S32(19) S32(20) S32(21) S32(22) S32(23) S32(24)
    S32(25) S32(26) S32(27) S32(28) S32(29) S32(30) S32(31)
}

static void shifts16(uint16_t w, int16_t v)
{
    S16(1) S16(2) S16(3) S16(4) S16(5) S16(6) S16(7) S16(8) S16(9) S16(10) S16(11) S16(12)
    S16(13) S16(14) S16(15)
}

static uint32_t xorshift(uint32_t x)
{
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    return x;
}

static uint32_t join(void) { return ((uint32_t)hash_hi << 16) | hash_lo; }

void test_main(void)
{
    uint16_t i;
    uint32_t x = 0xC3A51E87u;
    shifts32(0xC3A51E87u, (int32_t)0xC3A51E87u);
    shifts16(0xB5C3u, (int16_t)0xB5C3u);
    for (i = 0; i < 4; i++) {
        x = xorshift(x);
        rng[i] = x;
    }
    hash_hi = (uint16_t)(x >> 16);
    hash_lo = (uint16_t)x;
    rng[0] ^= join() >> 24;
    rng[1] ^= (uint32_t)((int32_t)join() >> 20);
}
