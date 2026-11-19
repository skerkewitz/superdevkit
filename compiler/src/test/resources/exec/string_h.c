/* <string.h>: memset and memcpy, on bytes, on a struct and on an array of structs, with sizeof.
 * The struct has no padding on the PC either (its sizeof is the same on both, ADR record 11). */
#include <stdint.h>
#include <string.h>

typedef struct {
    int16_t x, y;
    uint8_t kind, flags;
} Thing;

uint8_t buf[40];
uint8_t copy[40];
Thing things[5];
Thing saved[5];
uint16_t sum;

void test_main(void)
{
    uint8_t i;

    memset(buf, 0xA5, sizeof buf);
    memset(buf + 10, 0, 7);
    for (i = 0; i < 40; i++) {
        copy[i] = (uint8_t)(i * 3);
    }
    memcpy(copy + 5, buf + 8, 12);
    for (i = 0; i < 5; i++) {
        things[i].x = (int16_t)(i * 100 - 250);
        things[i].y = (int16_t)(-i);
        things[i].kind = (uint8_t)(i + 1);
    }
    memcpy(saved, things, sizeof things);
    memset(&things[2], 0xFF, sizeof things[2]);
    sum = 0;
    for (i = 0; i < 40; i++) {
        sum = (uint16_t)(sum + copy[i] + buf[i]);
    }
}
