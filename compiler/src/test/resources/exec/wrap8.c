/* 8- and 16-bit wrap-around and the conversions between signed and unsigned types. */
#include <stdint.h>

uint8_t u8a = 200;
uint8_t u8b = 100;
uint8_t u8sum;
uint8_t u8diff;
int8_t i8a = -100;
int8_t i8b = 100;
int8_t i8diff;
int8_t i8neg;
uint16_t u16max = 65535;
uint16_t u16wrap;
uint16_t from_negative;
int16_t from_unsigned;
int16_t widened;
uint16_t big_sum;

void test_main(void)
{
    u8sum = u8a + u8b;                  /* 300 -> 44 */
    u8diff = u8b - u8a;                 /* -100 -> 156 */
    i8diff = (int8_t)(i8a - i8b);       /* -200 -> 56 */
    i8neg = -i8a;                       /* 100 */
    u16wrap = u16max + 2;               /* 65537 -> 1 */
    from_negative = i8a;                /* -100 -> 65436 */
    from_unsigned = u16max;             /* 65535 -> -1 */
    widened = u8a;                      /* 200, not -56 */
    big_sum = u8a + u8b + u16max;       /* 65835 -> 299 */
}
