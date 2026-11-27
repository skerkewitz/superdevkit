/* More variables than bank $7E holds (ADR-6FVM9QWS): the two largest objects, items (31,200
   bytes, zero at start) and table (29,500 bytes, initialised), go to bank $7F, which the code
   reaches with long addresses; near stays in $7E. The arrays are static locals, which the test
   does not compare element by element; the globals below say what the program computed. Every
   kind of access to a place in $7F: loads and stores of 8, 16 and 32 bits at a constant and at a
   variable index, a zero stored, steps (++, --, +=), bit operations and shifts in place, a place
   in $7F as a loop counter, as an index, as a shift count, as a switch value and as an argument,
   and the initial values copied to $7F at start-up. */
#include <stdint.h>

struct item {
    uint16_t a;
    uint32_t b;
    uint8_t c;
    int16_t d;
    struct item *next;
};

uint16_t sum_a;
uint16_t sum_c;
uint32_t sum_b;
uint16_t first_values;
uint16_t last_value;
uint16_t counted;
uint16_t indexed;
uint16_t shifted;
uint16_t switched;
uint16_t argument;
int16_t negative;
uint8_t flags;
uint16_t stepped;
uint32_t wide;
uint16_t near_check;
uint16_t copied;

static uint16_t twice(uint16_t x)
{
    return (uint16_t)(x * 2u);
}

static uint8_t kind(uint8_t c)
{
    switch (c) {
    case 0: return 10;
    case 1: return 11;
    case 2: return 12;
    case 3: return 13;
    default: return 99;
    }
}

void test_main(void)
{
    static struct item items[2400];
    static uint8_t table[29500] = {7, 8, 9, 10, 11};
    static uint8_t near[28000];
    uint16_t i;

    /* stores and loads at a variable index */
    for (i = 0; i < 2400; i++) {
        items[i].a = (uint16_t)(i * 3u);
        items[i].b = (uint32_t)i * 100000u;
        items[i].c = (uint8_t)i;
        items[i].d = (int16_t)(500 - (int16_t)i);
    }
    for (i = 0; i < 2400; i += 7) {
        sum_a = (uint16_t)(sum_a + items[i].a);
        sum_b += items[i].b;
        sum_c = (uint16_t)(sum_c + items[i].c);
    }

    /* the initial values, copied to bank $7F at start-up, and the zeros after them */
    first_values = (uint16_t)(table[0] + table[1] + table[2] + table[3] + table[4] + table[5] + table[29499]);
    table[29499] = 200;
    last_value = table[29499];

    /* places at a constant index, with values the loop above wrote (the compiler does not know
       them): steps, bit operations, shifts, and zeros the loop below reads back */
    items[50].a = 0;
    items[50].b = 0;
    items[50].c = 0;
    items[50].d = 0;
    items[10].a++;
    items[10].a++;
    items[11].a--;
    items[12].a += 1000;
    items[12].b += 70000;
    items[13].b -= 1;
    items[14].c |= 0x81;
    items[15].c &= 0x0F;
    items[16].c ^= 0xFF;
    items[17].a <<= 3;
    items[18].a >>= 2;
    items[19].d = (int16_t)-items[19].d;
    items[20].d >>= 1;
    items[21].c++;
    items[22].c--;
    stepped = (uint16_t)(items[10].a + items[11].a + items[12].a + items[17].a + items[18].a + items[21].c + items[22].c);
    wide = items[12].b + items[13].b + items[10].b;
    flags = (uint8_t)(items[14].c + items[15].c + items[16].c);
    negative = (int16_t)(items[19].d + items[20].d);

    /* a place in $7F as a loop counter, an index, a shift count, a switch value, an argument */
    for (items[30].a = 40; items[30].a < 60; items[30].a++) {
        counted = (uint16_t)(counted + items[items[30].a].c + items[items[30].a].a + (uint16_t)items[items[30].a].b);
    }
    near[items[31].a] = 77;                       /* 93 */
    indexed = (uint16_t)(near[93] + items[items[31].a].a + items[items[3].c].d);
    shifted = (uint16_t)(1u << (items[5].c & 7));
    switched = (uint16_t)(kind(items[2].c) + kind(items[7].c));
    argument = twice(items[12].a);

    /* $7E stays usable */
    near[27999] = 5;
    near[0] = 6;
    near_check = (uint16_t)(near[27999] + near[0] + near[1]);

    /* a struct copied within bank $7F, and a long one into a frame */
    items[40] = items[12];
    {
        struct item local;
        local = items[40];
        copied = (uint16_t)(local.a + items[40].c);
    }
}
