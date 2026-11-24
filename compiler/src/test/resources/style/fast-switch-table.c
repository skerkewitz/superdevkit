/* rule: fast-switch-table
 * claim: dispatch_switch faster dispatch_if_chain
 * A switch over many cases close together is a jump table: every case costs the same, about 15
 * cycles. The same tests as an if/else chain go one value after the other, and the last case and
 * the values that are none pay for all before them. */
#include <stdint.h>

uint16_t r;

void dispatch_switch(uint8_t k)
{
    switch (k) {
    case 0: r = 3; break;
    case 1: r = 5; break;
    case 2: r = 7; break;
    case 3: r = 11; break;
    case 4: r = 13; break;
    case 5: r = 17; break;
    case 6: r = 19; break;
    case 7: r = 23; break;
    case 8: r = 29; break;
    case 9: r = 31; break;
    case 10: r = 37; break;
    case 11: r = 41; break;
    default: r = 1; break;
    }
}

void dispatch_if_chain(uint8_t k)
{
    if (k == 0) {
        r = 3;
    } else if (k == 1) {
        r = 5;
    } else if (k == 2) {
        r = 7;
    } else if (k == 3) {
        r = 11;
    } else if (k == 4) {
        r = 13;
    } else if (k == 5) {
        r = 17;
    } else if (k == 6) {
        r = 19;
    } else if (k == 7) {
        r = 23;
    } else if (k == 8) {
        r = 29;
    } else if (k == 9) {
        r = 31;
    } else if (k == 10) {
        r = 37;
    } else if (k == 11) {
        r = 41;
    } else {
        r = 1;
    }
}

uint16_t sum;

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 14; i++) {
        dispatch_switch(i);
        sum = (uint16_t)(sum * 3u + r);
        dispatch_if_chain(i);
        sum = (uint16_t)(sum * 3u + r);
    }
}
