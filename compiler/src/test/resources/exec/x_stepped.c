/* Loop counters in X whose stepped value is read too: X holds the counter until its step and the
 * stepped value after it. A do loop tested on the stepped value (INX, CPX), loops counting down
 * that index with i - 1 (the step itself), a signed test against 0 (CPX #0, BPL), a round that
 * reads the counter before its step and the stepped value after it, a byte counter, and a loop
 * left in the middle of a round, before its step. */
#include <stdint.h>

uint8_t src[40];
uint8_t dst[40];
uint8_t odd[41];
uint16_t w[30];
uint16_t sum_do;
uint16_t sum_byte;
uint16_t sum_signed;
uint8_t found;
uint16_t found_at;

static void fill(void)
{
    uint16_t i = 0;
    do {
        src[i] = (uint8_t)(i * 7u + 3u);
        i++;
    } while (i < 40);
}

static uint16_t sum_down(void)
{
    uint16_t s = 0;
    uint16_t i;
    for (i = 40; i > 0; i--) {
        s = (uint16_t)(s + src[i - 1u]);
    }
    return s;
}

static void copy_down(void)
{
    uint16_t i;
    for (i = 40; i > 0; i--) {
        dst[i - 1u] = src[i - 1u];
    }
}

static uint16_t signed_down(void)
{
    uint16_t s = 0;
    int16_t i;
    for (i = 29; i >= 0; i--) {
        w[i] = (uint16_t)(i * 3);
        s = (uint16_t)(s + (uint16_t)i);
    }
    for (i = 39; i >= 0; i--) {               /* the counter only indexes: in X, CPX #0, BPL */
        dst[i] = (uint8_t)(dst[i] + 1u);
    }
    return s;
}

static void before_and_after(void)
{
    uint16_t i = 0;
    while (i < 40) {
        odd[i] = (uint8_t)(src[i] + 1u);   /* the counter, before its step */
        i++;
        odd[i] = (uint8_t)(odd[i] ^ 0x55u); /* the stepped value, after it */
    }
}

static uint16_t byte_do(void)
{
    uint16_t s = 0;
    uint8_t i = 0;
    do {
        s = (uint16_t)(s + dst[i]);
        i++;
    } while (i < 40);
    return s;
}

static void search(uint8_t v)
{
    uint16_t i = 0;
    found = 0;
    do {
        if (src[i] == v) {
            found = 1;
            break;                         /* leaves before the step */
        }
        i++;
    } while (i != 40);
    found_at = found ? 1u : 0u;
}

void test_main(void)
{
    fill();
    sum_do = sum_down();
    copy_down();
    sum_signed = signed_down();
    before_and_after();
    sum_byte = byte_do();
    search(src[17]);
}
