/* Locals: initialisers, shadowing in inner blocks, ++/--, compound assignments. */
#include <stdint.h>

uint16_t outer_value;
uint16_t inner_value;
uint8_t counter = 254;
int16_t down = -32767;
uint16_t compound = 1000;

static uint16_t twice(uint16_t x)
{
    uint16_t y = x;
    y += x;
    return y;
}

void test_main(void)
{
    uint16_t v = 7;
    {
        uint16_t v = 100;       /* shadows the outer v */
        inner_value = twice(v);
    }
    outer_value = v;
    ++counter;
    ++counter;                  /* 254 -> 255 -> 0 */
    --down;
    --down;                     /* -32767 -> -32768 -> 32767 (as int16_t) */
    compound -= 1001;           /* 65535 */
    compound &= 0x0FF0;
    compound |= 3;
}
