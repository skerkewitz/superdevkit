/* Regression (2026-11-19): Blubber Blasen's water stream, which lost its trail from tick 2,623
 * on. A branch whose two sides both start with a loop writes the start value of both counters
 * before it branches (their φs are "direct"). The frame layout let the two counters share a
 * place, so the second write (0) replaced the first (9) and the shifting loop never ran. */
#include <stdint.h>

typedef struct {
    uint16_t timer;
    uint8_t  trail_x[10];
    uint8_t  trail_y[10];
    uint8_t  trail_len;
    uint8_t  carrying;
} Stream;

Stream streams[2];
uint8_t riders[2] = { 1, 0 };

static void carry(Stream *s)
{
    uint8_t i;
    for (i = 0; i < 2; i++) {
        if (riders[i]) {
            s->carrying = (uint8_t)(s->carrying | (1u << i));
        }
    }
}

static void step(Stream *s, uint8_t x, uint8_t y)
{
    uint8_t i;
    s->timer++;
    if ((s->timer & 3) == 0) {
        for (i = 9; i > 0; i--) {
            s->trail_x[i] = s->trail_x[i - 1];
            s->trail_y[i] = s->trail_y[i - 1];
        }
        s->trail_x[0] = x;
        s->trail_y[0] = y;
        if (s->trail_len < 10) {
            s->trail_len++;
        }
    }
    carry(s);
}

void test_main(void)
{
    uint8_t t;
    for (t = 0; t < 30; t++) {
        step(&streams[t & 1], (uint8_t)(t * 3u), (uint8_t)(t + 7u));
    }
}
