/* A value in X or Y for part of a loop only (RegisterChoice, parts): the common way of the loop
 * keeps its offset or counter in a register; a rare way (a switch, a call, another index) uses
 * the register for something else and finds the value in its place, stored where the common way
 * leaves for it and loaded where it comes back. */
#include <stdint.h>

typedef struct {
    int16_t x, y;
    uint8_t kind, flags, anim, hp;
} Obj;

Obj objs[12];
uint8_t cells[8][24];
uint16_t hits;
uint16_t sums[4];
uint8_t table[16] = { 3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3 };

static void bump(Obj *o, uint8_t by)
{
    o->x = (int16_t)(o->x + table[by & 15]);
    hits++;
}

static void update(void)
{
    uint8_t i;
    for (i = 0; i < 12; i++) {
        Obj *o = &objs[i];
        if (o->kind == 0) {
            continue;                      /* the common way: nothing else */
        }
        if (o->flags & 1) {
            o->flags--;
        }
        switch (o->kind) {
        case 1: bump(o, o->anim); break;
        case 2: o->y = (int16_t)(o->y - table[o->anim & 15]); break;
        case 3: o->anim = (uint8_t)(o->anim + 3); bump(o, i); break;
        case 4: o->hp = table[(uint8_t)(o->hp + i) & 15]; break;
        case 5: o->x = (int16_t)(o->x + o->y); break;
        default: o->kind = 0; break;
        }
    }
}

static void scan(uint8_t from, uint8_t to)
{
    uint8_t x, y;
    for (y = from; y < to && y < 7; y++) {
        for (x = 1; x < 23; x++) {
            uint8_t c = cells[y][x];
            uint8_t k = (uint8_t)(c & 15);
            if (k <= 3 || (c & 128) != 0) {
                continue;
            }
            switch (k) {
            case 4: case 5:
                if (cells[y + 1][x] == 0) {
                    cells[y + 1][x] = (uint8_t)(c | 128);
                    cells[y][x] = 0;
                }
                break;
            case 8: sums[x & 3] = (uint16_t)(sums[x & 3] + table[k] + y); break;
            case 10: case 11: cells[y][x] = (uint8_t)(k + 16 * ((c >> 4) & 3) + 1); break;
            default: cells[y][x] = (uint8_t)(c | 128); break;
            }
        }
    }
}

void test_main(void)
{
    uint8_t t, i, j;
    for (i = 0; i < 12; i++) {
        objs[i].kind = (uint8_t)(i % 7 == 0 ? 0 : i % 7);
        objs[i].x = (int16_t)(i * 13 - 40);
        objs[i].y = (int16_t)(100 - i * 7);
        objs[i].flags = (uint8_t)(i & 3);
        objs[i].anim = (uint8_t)(i * 5);
        objs[i].hp = (uint8_t)(i + 2);
    }
    for (i = 0; i < 8; i++) {
        for (j = 0; j < 24; j++) {
            cells[i][j] = (uint8_t)((i * 7 + j * 3) % 13);
        }
    }
    for (t = 0; t < 20; t++) {
        update();
        scan((uint8_t)(t & 3), (uint8_t)((t & 3) + 4));
        if ((t & 7) == 0) {
            for (i = 0; i < 8; i++) {
                for (j = 0; j < 24; j++) {
                    cells[i][j] = (uint8_t)(cells[i][j] & 127);
                }
            }
        }
    }
}
