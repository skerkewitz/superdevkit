/* An array element whose address is used twice: for loads and stores through X (indexed
 * addressing, which reads the index from its place) and as a pointer kept for later. The index
 * is computed right before the address, so the code generator could be tempted to hand it over
 * in A without storing it (a bug once: the indexed accesses then read a stale index). */
#include <stdint.h>

uint8_t arr[48];
uint8_t first;
uint8_t last;
uint8_t via_pointer;
uint16_t sum;
uint8_t *keep;

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 12; i++) {
        uint8_t *p = &arr[(uint16_t)(i * 3u + 1u)];
        *p = (uint8_t)(i + 10u);
        sum = (uint16_t)(sum + *p);
        keep = p;
    }
    first = arr[1];
    last = arr[34];
    via_pointer = *keep;
}
