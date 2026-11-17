/*
 * DAMPF_RANGE: the hint bounds loops the timing analysis cannot bound on its own (a string's
 * length, a count from memory) and gives the optimizer a range; on the PC it is an assertion,
 * and the MIR interpreter fails if it does not hold. DAMPF_BUDGET: only a bounded function can
 * have a budget, so text_len's needs the hint (and on the PC the budget is nothing).
 */
#include <stdint.h>
#include <dampf.h>

const char names[4][12] = {"ALPHA", "BETA", "GAMMA", ""};
uint8_t counts[3] = {3, 0, 7};
uint8_t lens[4];
uint16_t total;
int16_t signed_sum;

DAMPF_BUDGET(2000) static uint8_t text_len(const char *s)
{
    uint8_t n = 0;
    while (s[n]) {
        DAMPF_RANGE(n, 0, 10);
        n++;
    }
    return n;
}

static uint16_t add_up(uint8_t which)
{
    uint8_t k = counts[which];
    uint16_t s = 0;
    DAMPF_RANGE(k, 0, 7);
    while (k) {
        s = (uint16_t)(s + k * 3u);
        k--;
    }
    return s;
}

static int16_t scaled(int16_t v)
{
    DAMPF_RANGE(v, -100, 100);
    return (int16_t)(v * 3 + 1);
}

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 4; i++) {
        lens[i] = text_len(names[i]);
    }
    total = (uint16_t)(add_up(0) + add_up(1) + add_up(2));
    signed_sum = (int16_t)(scaled(-100) + scaled(100) + scaled(7));
}
