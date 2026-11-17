/*
 * A choice between one bit and 0 on a single bit, (x & M) ? K : 0 and (x & M) ? 0 : K, which
 * dampf turns into the masked bit shifted to K's place: to a higher bit, a lower bit, the same
 * bit, into a 16-bit result, and inverted. Every input from 0 to 255.
 */
#include <stdint.h>

uint16_t sum8;
uint16_t sum16;
uint8_t last;

static uint8_t attr_byte(uint8_t attr)
{
    return (uint8_t)(((attr & 0x80) ? 0x80u : 0u) | ((attr & 0x40) ? 0x40u : 0u)
                     | ((attr & 4) ? 0x20u : 0u) | ((attr & 0x10) ? 1u : 0u) | ((attr & 1) ? 0u : 2u));
}

static uint16_t wide(uint8_t attr)
{
    return (uint16_t)(((attr & 2) ? 0x100u : 0u) | ((attr & 0x20) ? 0u : 0x8000u));
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 256; i++) {
        uint8_t a = (uint8_t)i;
        last = attr_byte(a);
        sum8 = (uint16_t)(sum8 * 3u + last);
        sum16 = (uint16_t)(sum16 * 5u + wide(a));
    }
}
