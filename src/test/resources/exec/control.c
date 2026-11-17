/* Statements: if/else, the three loops, break and continue, switch with fall-through, ?:, && and ||. */
#include <stdint.h>

uint16_t for_sum;
uint16_t while_count;
uint16_t do_count;
uint16_t odd_sum;
uint16_t nested;
uint8_t calls;
uint8_t short_and;
uint8_t short_or;
uint16_t switch_result;
int16_t switch32_result;
uint8_t ternary;
uint8_t post_value;
uint8_t post_after;
uint8_t counter_value;
int8_t sign_of;

static uint8_t touch(uint8_t v)
{
    calls++;
    return v;
}

static uint16_t classify(uint8_t k)
{
    uint16_t r = 0;
    switch (k) {
    case 0:
        r = 10;
        break;
    case 1:
    case 2:
        r = 20;             /* 1 and 2 */
    case 3:
        r += 5;             /* falls through from 1 and 2 */
        break;
    case 200:
        return 999;
    default:
        r = 1;
    }
    return r;
}

static int16_t big_switch(int32_t v)
{
    switch (v) {
    case -100000: return -1;
    case 100000: return 1;
    case 65536: return 2;
    }
    return 0;
}

static uint8_t count_calls(void)
{
    static uint8_t n = 40;
    n++;
    return n;
}

static int8_t sign(int16_t v)
{
    if (v < 0) {
        return -1;
    } else if (v > 0) {
        return 1;
    }
    return 0;
}

void test_main(void)
{
    uint8_t i;
    uint8_t j;

    for (i = 1; i <= 100; i++) {
        for_sum += i;
    }
    while (1) {
        while_count++;
        if (while_count == 17) {
            break;
        }
    }
    do {
        do_count += 3;
    } while (do_count < 10);
    for (i = 0; i < 20; i++) {
        if ((i & 1) == 0) {
            continue;
        }
        odd_sum += i;
    }
    for (i = 0; i < 5; i++) {
        for (j = 0; j < 4; j++) {
            if (j == 2) {
                break;
            }
            nested += (uint16_t)(i * 10 + j);
        }
    }
    short_and = touch(0) && touch(1);       /* touch(1) is not called */
    short_or = touch(1) || touch(0);        /* touch(0) is not called */
    switch_result = (uint16_t)(classify(0) + classify(1) * 100u + classify(3) * 1000u + classify(9) + classify(200));
    switch32_result = (int16_t)(big_switch(-100000) * 100 + big_switch(100000) * 10 + big_switch(65536) + big_switch(7));
    ternary = for_sum > 5000 ? (uint8_t)1 : (uint8_t)2;
    i = 250;
    post_value = i++;
    post_after = i;
    count_calls();
    counter_value = count_calls();
    sign_of = (int8_t)(sign(-300) * 4 + sign(0) * 2 + sign(5));
}
