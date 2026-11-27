/* Functions inlined at every call have no code of their own in the ROM (ADR-6FNWEJAA as
 * amended): clamp and step below are copied into their callers, and step's call of clamp is
 * copied with it. big is too large to inline at two calls and keeps its code. The report still
 * times clamp and step; InlinedWithoutCodeTest checks the labels and the report. */
#include <stdint.h>

uint8_t values[8];
uint16_t total;
uint8_t clamped;
uint16_t mixed;

static uint8_t clamp(uint8_t v, uint8_t hi)
{
    return v > hi ? hi : v;
}

static uint16_t step(uint16_t sum, uint8_t v)
{
    return (uint16_t)(sum + clamp(v, 40));
}

uint16_t big(uint16_t a, uint16_t b)
{
    uint16_t r = 0;
    uint8_t i;
    for (i = 0; i < 8; i++) {
        r = (uint16_t)(r * 3u + values[i]);
        if (r > a) {
            r = (uint16_t)(r - b);
        } else if ((r & 1u) != 0) {
            r = (uint16_t)(r ^ a);
        } else {
            r = (uint16_t)(r + (b >> 2));
        }
        switch (r & 3u) {
        case 0: r = (uint16_t)(r + 11u); break;
        case 1: r = (uint16_t)(r ^ 0x5Au); break;
        case 2: r = (uint16_t)(r - 7u); break;
        default: r = (uint16_t)(r << 1); break;
        }
        switch ((r >> 4) & 3u) {
        case 0: values[i] = (uint8_t)(values[i] + (uint8_t)r); break;
        case 1: values[i] = (uint8_t)(values[i] ^ (uint8_t)(r >> 8)); break;
        case 2: values[i] = (uint8_t)(values[(i + 1u) & 7u] - (uint8_t)r); break;
        default: values[i] = (uint8_t)(values[i] | 0x81u); break;
        }
        if (values[i] > 200u) {
            r = (uint16_t)(r + values[i] * 5u);
        }
    }
    return r;
}

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 8; i++) {
        values[i] = (uint8_t)(i * 13u);
    }
    for (i = 0; i < 8; i++) {
        total = step(total, values[i]);
    }
    clamped = clamp(values[7], 50);
    mixed = big(total, 300);
    mixed = (uint16_t)(mixed + big(mixed, 17));
}
