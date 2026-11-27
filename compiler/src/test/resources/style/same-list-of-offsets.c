/* rule: same-list-of-offsets
 * claim: pairs_by_offsets same pairs_by_counter
 * A list of the used slots costs what a counter over all slots costs if it holds their byte
 * offsets: an index from a list is multiplied by the element's size on every use
 * (slow-index-list), an offset is not. The struct is 11 bytes on the SNES and 12 on the PC, so
 * sizeof(Ball) is rejected: the offsets come from a pointer difference, which is right on both.
 * They must fit a byte (slow-word-offsets). */
#include <dampf.h>
#include <stdint.h>

typedef struct {
    uint8_t  state;
    uint8_t  kind;
    int16_t  vx, vy;
    uint16_t x, y;
    uint8_t  r;
} Ball;   /* 11 bytes on the SNES, 12 on the PC (dampf does not pad) */

#define BALLS 12
Ball balls[BALLS];
uint16_t near_pairs;

static uint8_t used(const Ball *b)
{
    return b->state == 2;
}

void pairs_by_counter(void)
{
    uint8_t i, j;
    for (i = 0; i < BALLS; i++) {
        Ball *a = &balls[i];
        if (!used(a)) {
            continue;
        }
        for (j = (uint8_t)(i + 1); j < BALLS; j++) {
            Ball *b = &balls[j];
            if (!used(b)) {
                continue;
            }
            if ((uint16_t)(a->x - b->x + 0x1000u) < 0x2000u && (uint16_t)(a->y - b->y + 0x1000u) < 0x2000u) {
                near_pairs++;
            }
        }
    }
}

void pairs_by_offsets(void)
{
    uint8_t list[BALLS], n = 0, li, lj, i;
    for (i = 0; i < BALLS; i++) {
        if (used(&balls[i])) {
            list[n++] = (uint8_t)((const uint8_t *)&balls[i] - (const uint8_t *)balls);
        }
    }
    DAMPF_RANGE(n, 0, BALLS);
    for (li = 0; li < n; li++) {
        Ball *a = (Ball *)((uint8_t *)balls + list[li]);
        for (lj = (uint8_t)(li + 1); lj < n; lj++) {
            Ball *b = (Ball *)((uint8_t *)balls + list[lj]);
            if ((uint16_t)(a->x - b->x + 0x1000u) < 0x2000u && (uint16_t)(a->y - b->y + 0x1000u) < 0x2000u) {
                near_pairs++;
            }
        }
    }
}

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < BALLS; i++) {
        balls[i].state = (uint8_t)(i % 5 == 4 ? 0 : 2);
        balls[i].x = (uint16_t)(i * 0x0700u);
        balls[i].y = (uint16_t)(i * 0x0300u);
    }
    pairs_by_counter();
    pairs_by_offsets();
}
