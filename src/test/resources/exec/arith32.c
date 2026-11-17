/* Multiplication, division, remainder and shifts in 16 and 32 bits, signed and unsigned. */
#include <stdint.h>

int16_t a = -7;
int16_t b = 2;
int32_t big = -100000;
uint32_t ub = 4000000000u;
uint16_t x = 50000;
int16_t s = -100;
uint8_t n = 5;

int16_t div_trunc;          /* -7 / 2 = -3 (towards zero) */
int16_t mod_sign;           /* -7 % 2 = -1 (sign of the dividend) */
int32_t big_div;
int32_t big_mod;
uint32_t ub_div;
uint32_t ub_mod;
uint16_t x_div;
uint16_t x_mod;
uint16_t low_product;       /* 300 * 300 = 90000, low 16 bits */
int32_t wide_product;
uint32_t wrapped_product;
int16_t shr_negative;       /* -100 >> 3 = -13 (arithmetic shift) */
uint16_t shr_unsigned;
int32_t shl_wide;
int32_t shr_all_ones;
int32_t minus_one = -1;
int32_t shr_negative32;     /* -100000 >> 5 = -3125: the full 32-bit result is kept */
int32_t shr_minus_one;      /* -1 >> 31 = -1, computed at run time */
uint32_t shr_variable;
uint16_t shl_variable;
int16_t radius;
uint8_t span;
int32_t sum32;
uint32_t returned32;

static int32_t add32(int32_t p, int32_t q)
{
    return p + q;
}

static uint32_t mix(uint32_t v, uint16_t k)
{
    return (v ^ k) + (v >> 16);
}

void test_main(void)
{
    int16_t w = 12;
    uint16_t scale = 200;
    int16_t dy = 3;
    int16_t ry = 7;

    div_trunc = a / b;
    mod_sign = a % b;
    big_div = big / 7;
    big_mod = big % 7;
    ub_div = ub / 3;
    ub_mod = ub % 3;
    x_div = x / 7;
    x_mod = x % 7;
    low_product = (uint16_t)(x / 50000 * 300 * 300);
    wide_product = (int32_t)a * 100000;
    wrapped_product = ub * 3u;
    shr_negative = s >> 3;
    shr_unsigned = (uint16_t)(0x8001u >> 15);
    shl_wide = (int32_t)1 << 20;
    shr_all_ones = (int32_t)-1 >> 31;
    shr_variable = ub >> n;
    shr_negative32 = big >> n;
    shr_minus_one = minus_one >> 31;
    shl_variable = (uint16_t)(x << n);
    /* vacuum.c shape(): a 32-bit intermediate, then back to 16 bits */
    radius = (int16_t)(((int32_t)(24 * 16 - w) * scale + 2048) >> 12);
    span = (uint8_t)((dy * 24 + ry / 2) / ry);
    sum32 = add32(big, 123456);
    returned32 = mix(ub, 0x1234);
}
