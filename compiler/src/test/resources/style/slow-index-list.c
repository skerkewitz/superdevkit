/* rule: slow-index-list
 * claim: pairs_by_list slower pairs_by_counter
 * A pair loop over the slots in use: walking all slots with a counter and skipping the free ones
 * costs less than walking a list of the used slots' indexes, where most slots are in use. */
#include <dampf.h>
#include <stdint.h>

typedef struct {
    uint8_t  state;
    uint8_t  kind;
    int16_t  vx, vy;
    uint16_t x, y;
} Ball;

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

void pairs_by_list(void)
{
    uint8_t list[BALLS], n = 0, li, lj, i;
    for (i = 0; i < BALLS; i++) {
        if (used(&balls[i])) {
            list[n++] = i;
        }
    }
    DAMPF_RANGE(n, 0, BALLS);
    for (li = 0; li < n; li++) {
        Ball *a = &balls[list[li]];
        for (lj = (uint8_t)(li + 1); lj < n; lj++) {
            Ball *b = &balls[list[lj]];
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
    pairs_by_list();
}
