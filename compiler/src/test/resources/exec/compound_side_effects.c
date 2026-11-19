/* A compound assignment whose left side has side effects, as a statement: the left side is
 * evaluated once (C 6.5.16.2). A function call and an increment in the index, a member through a
 * pointer from a call, and every operator. (Er-Tüp: bucket_shots[bucket(x)] |= bit.) And ++ and --
 * in front of such an lvalue, anywhere: its value is the new one, in the lvalue's type. */
#include <stdint.h>

static uint16_t table[8];
static uint8_t calls;
static int16_t pair[2];

typedef struct {
    uint8_t a;
    int16_t b;
} Thing;

static Thing things[3];
static uint8_t small[4];
static const int16_t WORDS[4] = { 10, 20, 30, 40 };
static const int16_t *walk[2] = { WORDS, WORDS + 3 };

static uint8_t pick(uint8_t i)
{
    calls++;
    return (uint8_t)(i & 7);
}

static Thing *thing(uint8_t i)
{
    calls++;
    return &things[i];
}

uint16_t t0, t3, t5, t7;
uint8_t calls_seen, k_after, a1, wrapped, down, small2;
int16_t b2, p0, p1, w_up, w_down;

void test_main(void)
{
    uint8_t k = 0;

    table[pick(3)] |= 0x0F0F;
    table[pick(3)] += 0x1000;
    table[pick(5)] = 100;
    table[pick(5)] -= 1;
    table[pick(5)] *= 3;
    table[pick(5)] /= 7;
    table[pick(7)] = 0xFFFF;
    table[pick(7)] &= 0x5A5A;
    table[pick(7)] ^= 0x00FF;
    table[pick(7)] >>= 2;
    table[pick(0)] = 1;
    table[pick(0)] <<= 9;
    table[pick(0)] %= 300;
    pair[k++] += -5;
    pair[k++] -= 7;
    thing(1)->a += 200;
    thing(1)->a += 100;                     /* wraps in 8 bits */
    thing(2)->b -= 30000;
    thing(2)->b -= 10000;                   /* converted back to int16_t */
    small[pick(2)] = 255;
    wrapped = ++small[pick(2)];             /* 0, in uint8_t */
    down = --small[pick(2)];                /* 255 again */
    ++small[pick(2)];
    small2 = small[2];
    w_up = *++walk[pick(0)];                /* the pointer one element on: 20 */
    w_down = *--walk[pick(1)];              /* one back: 30 */
    t0 = table[0];
    t3 = table[3];
    t5 = table[5];
    t7 = table[7];
    calls_seen = calls;
    k_after = k;
    a1 = things[1].a;
    b2 = things[2].b;
    p0 = pair[0];
    p1 = pair[1];
}
