/* rule: slow-word-offsets
 * claim: pairs_by_word_offsets slower pairs_by_byte_offsets
 * A list of byte offsets into an array of structs (same-list-of-offsets) as uint16_t costs more
 * than as uint8_t: dampf then reaches the element through a 24-bit pointer (LDA [dp],Y) instead
 * of indexing the array with X. */
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

void pairs_by_byte_offsets(void)
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

void pairs_by_word_offsets(void)
{
    uint16_t list[BALLS];
    uint8_t n = 0, li, lj, i;
    for (i = 0; i < BALLS; i++) {
        if (used(&balls[i])) {
            list[n++] = (uint16_t)((const uint8_t *)&balls[i] - (const uint8_t *)balls);
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
    pairs_by_byte_offsets();
    pairs_by_word_offsets();
}
