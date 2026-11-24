/* rule: slow-table-call-few
 * claim: by_table slower by_switch
 * Four handlers: a call through a table of functions costs more than a switch that calls
 * them, which tests the cases one after the other (and could inline a small handler). */
#include <stdint.h>

uint8_t state;
uint16_t counts[4];
uint16_t mix[8];

/* each too long to be inlined, as a game's state handlers are */
#define WORK(k) \
    mix[0] = (uint16_t)((mix[0] ^ (uint16_t)(mix[1] >> 3)) + (k)); \
    mix[1] = (uint16_t)((mix[1] ^ (uint16_t)(mix[2] >> 3)) + (k)); \
    mix[2] = (uint16_t)((mix[2] ^ (uint16_t)(mix[3] >> 3)) + (k)); \
    mix[3] = (uint16_t)((mix[3] ^ (uint16_t)(mix[4] >> 3)) + (k)); \
    mix[4] = (uint16_t)((mix[4] ^ (uint16_t)(mix[5] >> 3)) + (k)); \
    mix[5] = (uint16_t)((mix[5] ^ (uint16_t)(mix[6] >> 3)) + (k)); \
    mix[6] = (uint16_t)((mix[6] ^ (uint16_t)(mix[7] >> 3)) + (k)); \
    mix[7] = (uint16_t)((mix[7] ^ (uint16_t)(mix[0] >> 3)) + (k));

static void tick0(void) { counts[0]++; WORK(0) WORK(1) }
static void tick1(void) { counts[1]++; WORK(2) WORK(3) }
static void tick2(void) { counts[2]++; WORK(4) WORK(5) }
static void tick3(void) { counts[3]++; WORK(6) WORK(7) }

static void (*const ticks[4])(void) = {
    tick0, tick1, tick2, tick3
};

void by_table(void)
{
    ticks[state]();
}

void by_switch(void)
{
    switch (state) {
    case 0: tick0(); break;
    case 1: tick1(); break;
    case 2: tick2(); break;
    case 3: tick3(); break;
    }
}

void test_main(void)
{
    for (state = 0; state < 4; state++) {
        by_table();
        by_switch();
    }
}
