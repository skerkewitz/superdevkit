/* rule: same-declaration-place
 * claim: declared_at_top same declared_where_used
 * Where a local is declared makes no difference: declare it where it is first used. */
#include <stdint.h>

uint8_t data[200];
uint16_t r1, r2;

uint16_t declared_at_top(void)
{
    uint16_t s;
    uint16_t i;
    uint8_t v;
    s = 0;
    for (i = 0; i < 200; i++) {
        v = data[i];
        s = (uint16_t)(s + v);
    }
    return s;
}

uint16_t declared_where_used(void)
{
    uint16_t s = 0;
    for (uint16_t i = 0; i < 200; i++) {
        uint8_t v = data[i];
        s = (uint16_t)(s + v);
    }
    return s;
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 200; i++) {
        data[i] = (uint8_t)(i * 5);
    }
    r1 = declared_at_top();
    r2 = declared_where_used();
}
