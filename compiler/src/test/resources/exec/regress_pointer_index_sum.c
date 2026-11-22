/* A pointer plus an index whose sum is computed, since a field after the first one is read
 * through it as well (p->row is p + 2), while p->col reads at the sum itself. That read used
 * [w],y with the index in Y, from a place that was never written: the index had been handed on in
 * A into the sum. Now an access splits the sum into [pointer],y only where the sum is not
 * computed. */
#include <stdint.h>

struct wave { uint8_t col, kind, row, n, gap, t; };
struct wave waves[4] = { {1, 2, 3, 4, 5, 6}, {7, 8, 9, 10, 11, 12}, {13, 14, 15, 16, 17, 18}, {19, 20, 21, 22, 23, 24} };
struct wave *wp = waves;
uint8_t pick = 2;
uint16_t out, kind;

void test_main(void)
{
    struct wave *w = wp;
    struct wave *p = &w[pick];
    out = (uint16_t)(p->col * 16 + p->row);
    kind = w->kind;
}
