/*
 * DAMPF_ASM: functions written in assembly (asm/asm_functions.s) in place of their C bodies, with
 * parameters and results of 8, 16 and 32 bits and a pointer, a loop with a bound, called from a
 * loop. The PC compiles the bodies; dampf links the assembly, in both code generators, and in
 * the build from MIR runs each one against its body (docs/hints.md).
 */
#include <stdint.h>
#include <dampf.h>

uint16_t counter;
uint8_t mixed[8];
int16_t negated[8];
uint16_t bits[8];
uint32_t sums32[4];
uint16_t word_sums[4];
uint16_t ram_words[8] = {1, 2, 3, 4, 50000, 60000, 7, 8};
const uint16_t rom_words[8] = {100, 200, 300, 400, 500, 600, 700, 800};

/* 8 bits in and out: the assembly works with an 8-bit accumulator and leaves A's high byte alone */
uint8_t mix8(uint8_t a, uint8_t b) DAMPF_ASM("asm/asm_functions.s")
{
    return (uint8_t)((a ^ b) + 1);
}

/* a signed byte: the caller extends the low byte of A */
int8_t neg8(int8_t a) DAMPF_ASM("asm/asm_functions.s")
{
    return (int8_t)-a;
}

/* a loop of 16 rounds in the assembly, with its own locals */
uint16_t bits16(uint16_t x) DAMPF_ASM("asm/asm_functions.s")
{
    uint16_t n = 0;
    for (uint8_t i = 0; i < 16; i++) {
        n = (uint16_t)(n + (x & 1));
        x = (uint16_t)(x >> 1);
    }
    return n;
}

/* 32 bits in and out: the result's low word in A, its high word in X */
uint32_t add32(uint32_t a, uint32_t b) DAMPF_ASM("asm/asm_functions.s")
{
    return a + b;
}

/* a pointer (to RAM and to ROM) and a count the hint bounds, for the check too */
uint16_t sum_words(const uint16_t *p, uint8_t n) DAMPF_ASM("asm/asm_functions.s")
{
    DAMPF_RANGE(n, 0, 8);
    uint16_t s = 0;
    for (uint8_t i = 0; i < n; i++) {
        s = (uint16_t)(s + p[i]);
    }
    return s;
}

/* no result: a global changes */
void bump(uint16_t by) DAMPF_ASM("asm/asm_functions.s")
{
    counter = (uint16_t)(counter + by);
}

void test_main(void)
{
    for (uint8_t i = 0; i < 8; i++) {
        mixed[i] = mix8((uint8_t)(i * 37), (uint8_t)(250 - i));
        negated[i] = (int16_t)(neg8((int8_t)(i * 40 - 128)) * 3);
        bits[i] = bits16((uint16_t)(i * 9001u));
        bump(i);
    }
    for (uint8_t i = 0; i < 4; i++) {
        sums32[i] = add32(0xFFFF0000u + i * 0x9000u, 0x00012345u * i);
        word_sums[i] = (uint16_t)(sum_words(ram_words, (uint8_t)(i * 2 + 1)) + sum_words(rom_words + i, (uint8_t)(i + 2)));
    }
}
