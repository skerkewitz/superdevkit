/*
 * Multiplications by constants with eight or more shifts in a row, which the code generator
 * writes as XBA and a mask instead of eight ASL: x * 256, * 512, * 32768, * 257, * 0x0300,
 * * -256 and * 0xFF00, of bytes and of 16-bit values, signed and unsigned, on the edge values.
 */
#include <stdint.h>

#define N 10

uint16_t u[N] = {0, 1, 2, 0x7F, 0x80, 0xFF, 0x100, 0x1234, 0x7FFF, 0xFFFF};
int16_t s[N] = {0, 1, -1, 127, -128, 255, -256, 0x1234, INT16_MAX, INT16_MIN};
uint8_t b[N] = {0, 1, 2, 3, 0x7F, 0x80, 0x81, 0xC3, 0xFE, 0xFF};
uint16_t ru[N * 8];
int16_t rs[N * 8];
uint16_t rb[N * 4];
uint32_t wide[N];
uint16_t hash;

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < N; i++) {
        uint16_t x = u[i];
        int16_t y = s[i];
        uint8_t c = b[i];
        uint8_t k = (uint8_t)(i * 8);
        ru[k] = (uint16_t)(x * 256u);
        ru[k + 1] = (uint16_t)(x * 512u);
        ru[k + 2] = (uint16_t)(x * 32768u);
        ru[k + 3] = (uint16_t)(x * 257u);
        ru[k + 4] = (uint16_t)(x * 0x0300u);
        ru[k + 5] = (uint16_t)(x * 0xFF00u);
        ru[k + 6] = (uint16_t)(x * 0x1100u);
        ru[k + 7] = (uint16_t)(x * 0x8100u);
        rs[k] = (int16_t)(y * 256);
        rs[k + 1] = (int16_t)(y * 1024);
        rs[k + 2] = (int16_t)(y * -256);
        rs[k + 3] = (int16_t)(y * -768);
        rs[k + 4] = (int16_t)(y * 0x4000);
        rs[k + 5] = (int16_t)(y * 255);
        rs[k + 6] = (int16_t)((y * 256) >> 4);
        rs[k + 7] = (int16_t)(y * 3 * 256);
        uint8_t m = (uint8_t)(i * 4);
        rb[m] = (uint16_t)(c * 256);
        rb[m + 1] = (uint16_t)(c * 256 + c);
        rb[m + 2] = (uint16_t)(c * 2048);
        rb[m + 3] = (uint16_t)((c * 256) | (uint8_t)(c + 1));
        wide[i] = (uint32_t)x * 256u;
        if ((uint16_t)(x * 256u) == 0) {
            hash += i;
        }
        if ((int16_t)(y * 256) < 0) {
            hash += (uint16_t)(i * 7);
        }
    }
}
