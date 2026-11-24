/* rule: fast-table-call-many
 * claim: by_table faster by_switch
 * Sixteen handlers: a switch tests the cases one after the other, a call through a table
 * of functions costs the same for every entry. */
#include <stdint.h>

uint8_t state;
uint16_t counts[16];
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
static void tick4(void) { counts[4]++; WORK(8) WORK(9) }
static void tick5(void) { counts[5]++; WORK(10) WORK(11) }
static void tick6(void) { counts[6]++; WORK(12) WORK(13) }
static void tick7(void) { counts[7]++; WORK(14) WORK(15) }
static void tick8(void) { counts[8]++; WORK(16) WORK(17) }
static void tick9(void) { counts[9]++; WORK(18) WORK(19) }
static void tick10(void) { counts[10]++; WORK(20) WORK(21) }
static void tick11(void) { counts[11]++; WORK(22) WORK(23) }
static void tick12(void) { counts[12]++; WORK(24) WORK(25) }
static void tick13(void) { counts[13]++; WORK(26) WORK(27) }
static void tick14(void) { counts[14]++; WORK(28) WORK(29) }
static void tick15(void) { counts[15]++; WORK(30) WORK(31) }

static void (*const ticks[16])(void) = {
    tick0, tick1, tick2, tick3, tick4, tick5, tick6, tick7,
    tick8, tick9, tick10, tick11, tick12, tick13, tick14, tick15
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
    case 4: tick4(); break;
    case 5: tick5(); break;
    case 6: tick6(); break;
    case 7: tick7(); break;
    case 8: tick8(); break;
    case 9: tick9(); break;
    case 10: tick10(); break;
    case 11: tick11(); break;
    case 12: tick12(); break;
    case 13: tick13(); break;
    case 14: tick14(); break;
    case 15: tick15(); break;
    }
}

void test_main(void)
{
    for (state = 0; state < 16; state++) {
        by_table();
        by_switch();
    }
}
