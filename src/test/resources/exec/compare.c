/* Comparisons: C compares after the usual arithmetic conversions, in 32-bit int or unsigned int. */
#include <stdint.h>

uint8_t u8 = 200;
int8_t i8 = -1;
uint16_t u16 = 65535;
int16_t i16 = -32768;
int16_t i16big = 30000;
int16_t i16neg = -30000;
uint32_t u32 = 4000000000u;
int32_t i32 = -5;
int32_t i32big = 2000000000;
int32_t i32neg = -2000000000;

uint8_t mixed_signs;        /* 200 > -1 in int: true */
uint8_t wide_unsigned;      /* 65535 > -32768 in int: true */
uint8_t unsigned_wins;      /* -5 becomes 4294967291 in unsigned int: true */
uint8_t wrapped_is_zero;    /* (uint16_t)(65535 + 1) == 0: true */
uint8_t int_is_not_zero;    /* 65535 + 1 == 0 in int: false */
uint8_t below_min;          /* -32768 - 1 < -32768 in int: true */
uint8_t product_big;        /* 200 * 200 > 30000 in int: true */
uint8_t overflow16;         /* 30000 > -30000: the 16-bit subtraction overflows */
uint8_t overflow32;         /* 2000000000 > -2000000000: the 32-bit subtraction overflows */
uint8_t less_equal;
uint8_t greater_equal;
uint8_t not_equal32;
int16_t as_value;           /* a comparison used as a number */

void test_main(void)
{
    mixed_signs = u8 > i8;
    wide_unsigned = u16 > i16;
    unsigned_wins = u32 < (uint32_t)i32;
    wrapped_is_zero = (uint16_t)(u16 + 1) == 0;
    int_is_not_zero = u16 + 1 == 0;
    below_min = i16 - 1 < i16;
    product_big = u8 * u8 > 30000;
    overflow16 = i16big > i16neg;
    overflow32 = i32big > i32neg;
    less_equal = i8 <= -1;
    greater_equal = u32 >= 4000000001u;
    not_equal32 = i32 != -5;
    as_value = (u8 > 100) + (i8 < 0) + (u16 == 65535) - (i16 > 0);
}
