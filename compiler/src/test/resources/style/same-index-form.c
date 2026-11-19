/* rule: same-index-form
 * claim: read_indexed same read_pointer_arithmetic
 * a[i] and *(a + i) cost the same. */
#include <stdint.h>

uint8_t data[200];
uint16_t r1, r2;

uint16_t read_indexed(void)
{
    uint16_t s = 0, i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + data[i]);
    }
    return s;
}

uint16_t read_pointer_arithmetic(void)
{
    uint16_t s = 0, i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + *(data + i));
    }
    return s;
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 200; i++) {
        data[i] = (uint8_t)(i ^ 0x55);
    }
    r1 = read_indexed();
    r2 = read_pointer_arithmetic();
}
