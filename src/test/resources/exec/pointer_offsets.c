/* Pointers that dampf turns into offsets into one array (PointerOffsets, PointerParams): a
 * pointer walking an array in a loop up to just past its end, a function always called with
 * pointers into the same array (compressed), one called with pointers into two different
 * arrays (stays a pointer), and a pointer handed on from one function to the next. */
#include <stdint.h>

typedef struct {
    int16_t x;
    uint8_t kind;
    uint8_t hp;
} Thing;

Thing things[10];
Thing others[3];
uint8_t bytes[40];
uint16_t walked;
uint16_t sum_hp;
int16_t mixed;
uint8_t last_kind;
uint16_t filled;

static void heal(Thing *t, uint8_t by)
{
    t->hp = (uint8_t)(t->hp + by);
}

static int16_t pos(const Thing *t)
{
    return t->x;
}

static void heal_twice(Thing *t)
{
    heal(t, 1);
    heal(t + 0, 2);
}

static uint8_t sum_kinds(const Thing *from, const Thing *to)
{
    uint8_t s = 0;
    while (from != to) {
        s = (uint8_t)(s + from->kind);
        from++;
    }
    return s;
}

void test_main(void)
{
    uint8_t i;
    uint8_t *p;
    Thing *t;

    for (i = 0; i < 10; i++) {
        things[i].x = (int16_t)(i * 7 - 20);
        things[i].kind = (uint8_t)(i % 3);
        things[i].hp = i;
    }
    for (i = 0; i < 3; i++) {
        others[i].x = (int16_t)(-100 - i);
    }

    for (p = bytes; p < bytes + 40; p += 4) {
        *p = (uint8_t)(p - bytes);
        filled++;
    }
    for (p = bytes; p != bytes + 39; p++) {
        walked = (uint16_t)(walked + *p);
    }

    for (t = things; t < things + 10; t++) {
        heal(t, 5);
        heal_twice(t);
    }
    for (i = 0; i < 10; i++) {
        sum_hp = (uint16_t)(sum_hp + things[i].hp);
    }
    mixed = (int16_t)(pos(&things[3]) + pos(&others[2]) + pos(things + 9));
    last_kind = sum_kinds(things + 2, things + 10);
}
