/*
 * Functions that return from several places, inlined into functions that return their value:
 * the shared return block is copied into every path (a jump, a branch, a switch case), and each
 * path must return the value it brought. `ground` has two call sites and is too large to be
 * inlined, so its own returns run; `kind` is inlined into the loop.
 */
#include <stdint.h>

uint8_t cells[64];
int16_t xs[8] = {-5, 0, 7, 31, 32, 100, 255, 12};
int16_t ys[8] = {3, 40, 0, 63, 9, 1, 25, 70};
uint16_t solid_sum;
uint16_t kind_sum;

static uint8_t solid_tile(int16_t tx, int16_t ty)
{
    if (tx < 0 || tx >= 8) {
        return 1;
    }
    if (ty < 0 || ty >= 8) {
        return 0;
    }
    return (uint8_t)((cells[((uint16_t)ty << 3) + (uint16_t)tx] & 0x80) != 0);
}

static uint8_t solid_px(int16_t sx, int16_t sy)
{
    if (sy < 8) {
        return 0;
    }
    return solid_tile((int16_t)(sx >> 2), (int16_t)((sy - 8) >> 2));
}

static uint8_t ground(int16_t sx, int16_t sy)
{
    int16_t tx = (int16_t)(sx >> 2);
    if (sy < 12 && tx >= 1 && tx < 6) {
        return 2;
    }
    return solid_px(sx, sy);
}

static uint16_t kind(uint8_t k, uint16_t v)
{
    switch (k) {
    case 0: return 11;
    case 1: return v;
    case 2: return (uint16_t)(v + 7u);
    case 5: return 11;
    case 6: return (uint16_t)(v ^ 0x5A5Au);
    case 7: return (uint16_t)((v << 3) | 5u);
    case 9: return (uint16_t)(v - 300u);
    case 12: return (uint16_t)(v >> 1);
    default: break;
    }
    if (v == 0) {
        return 99;
    }
    if (v > 50u && k > 9) {
        return (uint16_t)(v + k);
    }
    return (uint16_t)(v * 3u);
}

void test_main(void)
{
    uint8_t i;
    uint8_t j;
    for (i = 0; i < 64; i++) {
        cells[i] = (uint8_t)((i * 37u) & 0xC0u);
    }
    for (i = 0; i < 8; i++) {
        for (j = 0; j < 8; j++) {
            solid_sum = (uint16_t)(solid_sum * 3u + ground(xs[i], ys[j]));
        }
    }
    for (i = 0; i < 14; i++) {
        kind_sum = (uint16_t)(kind_sum * 5u + kind(i, (uint16_t)ys[i & 7]));
        kind_sum = (uint16_t)(kind_sum * 5u + kind(i, 0));
    }
    solid_sum = (uint16_t)(solid_sum + ground(9, 20));
}
