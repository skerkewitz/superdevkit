/* rule: same-pointer-walk
 * claim: move_by_pointer same move_by_index
 * Walking an array with a pointer beside a counter (o++, o->x) costs what indexing it costs
 * (objs[i].x): the pointer's offset and the counter are one variable in X. */
#include <stdint.h>

typedef struct {
    int16_t x;
    int16_t y;
} Obj;

Obj a[32], b[32];

void move_by_index(void)
{
    uint16_t i;
    for (i = 0; i < 32; i++) {
        a[i].x = (int16_t)(a[i].x + 2);
        a[i].y = (int16_t)(a[i].y - 1);
    }
}

void move_by_pointer(void)
{
    Obj *o = b;
    uint16_t i;
    for (i = 0; i < 32; i++) {
        o->x = (int16_t)(o->x + 2);
        o->y = (int16_t)(o->y - 1);
        o++;
    }
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 32; i++) {
        a[i].x = b[i].x = (int16_t)(i * 3);
        a[i].y = b[i].y = (int16_t)(i * 5);
    }
    move_by_index();
    move_by_pointer();
}
