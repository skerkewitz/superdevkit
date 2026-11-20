/* Loops that do nothing go (dampf.mir.DeadLoops), others stay: loops left empty when the calls in
 * them are inlined away (as the debug view's calls on the SNES), loops whose values nobody reads,
 * and next to them loops that look alike but must stay: their values are used after them, they
 * write memory, or dampf cannot prove that they end (they do end here). */
#include <stdint.h>

int16_t xs[6] = { 3, -7, 120, 0, 55, -300 };
uint16_t out[8];

static void nothing(int16_t x, int16_t y, uint8_t k)
{
    (void)x;
    (void)y;
    (void)k;
}

static uint16_t square(uint16_t v)
{
    return (uint16_t)(v * v);
}

void test_main(void)
{
    uint8_t i;
    int16_t s;
    uint16_t u, sum = 0;

    for (i = 0; i < 6; i++) {                       /* goes: the calls are empty */
        nothing(xs[i], (int16_t)(xs[i] + 8), i);
    }
    for (u = 0; u < 1000; u++) {                    /* goes: nobody reads the squares */
        (void)square(u);
    }
    for (s = 100; s > -100; s -= 3) {               /* goes: signed, counting down */
    }
    for (u = 1; u != 50; u += 7) {                  /* goes: an odd step meets 50 at last */
    }
    out[0] = sum;

    for (i = 0; i < 6; i++) {                       /* stays: it writes memory */
        out[1] = (uint16_t)(out[1] + (uint16_t)xs[i]);
    }
    for (u = 0; u < 37; u += 2) {                   /* stays: u is read after it */
    }
    out[2] = u;
    for (u = 0; u != 40; u += 2) {                  /* goes: 0, 2, ..., 40 meets 40 */
    }
    out[3] = 7;
    for (i = 250; i < 255; i++) {                   /* stays: i is read after it */
    }
    out[4] = i;
    out[5] = square((uint16_t)xs[2]);
}
