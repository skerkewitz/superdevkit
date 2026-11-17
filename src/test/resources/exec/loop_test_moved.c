/*
 * An 8-bit loop counter that only counts gives its test to the offset of the array accesses
 * (i < N becomes offset < N * size) and goes. From a start known only at run time, tests with
 * < and <=, element sizes at the limit (256 and 257: the offset still fits 16 bits) and beyond
 * it (258, and 1000 where the offset passes 65,536), and loops whose test must stay: a counter also used for something else, a test with
 * != that does not bound the counter, a loop counting down.
 */
#include <stdint.h>

struct rec {
    uint8_t a;
    uint16_t b;
    uint8_t pad[20];
};

struct big {
    uint8_t v;
    uint8_t pad[255];
};

struct bigger {
    uint8_t v;
    uint8_t pad[256];
};

struct biggest {
    uint8_t v;
    uint8_t pad[257];
};

struct rec recs[16];
struct big bigs[3];
struct bigger biggers[3];
struct biggest biggests[3];
uint8_t start_at = 3;
uint16_t up_sum;
uint16_t down_sum;
uint16_t from_sum;
uint16_t le_sum;
uint16_t ne_sum;
uint16_t big_sum;
uint16_t used_sum;
uint16_t wrap_sum;

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 16; i++) {
        recs[i].a = (uint8_t)(i * 13u + 1u);
        recs[i].b = (uint16_t)(i * 4099u);
    }
    for (i = 0; i < 3; i++) {
        bigs[i].v = (uint8_t)(i + 40u);
        biggers[i].v = (uint8_t)(i + 50u);
        biggests[i].v = (uint8_t)(i + 60u);
    }
    for (i = 0; i < 16; i++) {
        up_sum = (uint16_t)(up_sum * 3u + recs[i].a + recs[i].b);
    }
    for (i = 15; i >= 2; i--) {
        down_sum = (uint16_t)(down_sum * 5u + recs[i].a);
    }
    for (i = start_at; i < 14; i++) {
        from_sum = (uint16_t)(from_sum * 7u + recs[i].b);
    }
    for (i = 1; i <= 12; i += 2) {
        le_sum = (uint16_t)(le_sum * 3u + recs[i].a);
    }
    for (i = 0; i != 10; i++) {
        ne_sum = (uint16_t)(ne_sum * 3u + recs[i].a);
    }
    for (i = 0; i < 3; i++) {
        big_sum = (uint16_t)(big_sum * 3u + bigs[i].v);
    }
    for (i = 0; i < 3; i++) {
        big_sum = (uint16_t)(big_sum * 3u + biggers[i].v);
    }
    for (i = 0; i < 3; i++) {
        big_sum = (uint16_t)(big_sum * 3u + biggests[i].v);
    }
    for (i = 0; i < 16; i++) {
        used_sum = (uint16_t)(used_sum * 3u + recs[i].a + i);
    }
    /* i * 1000 passes 65,536 inside the loop (at i = 66): the test must stay on i */
    for (i = 60; i < 70; i++) {
        wrap_sum = (uint16_t)(wrap_sum * 3u + (uint16_t)(i * 1000u));
    }
}
