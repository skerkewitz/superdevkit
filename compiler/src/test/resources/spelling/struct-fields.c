/* family: struct-fields
 * tolerance: 10
 * Two fields of every element of an array of structs moved, by index and by pointer. One array per
 * field (field-arrays.c) is about 12 % cheaper: a stride of 5 bytes takes TXA ADC TAX where a field
 * array takes INX INX. That is a choice of data layout, not of spelling, and the C guide says so
 * (fast-array-per-field); splitting the array is roadmap M9 row 4.
 */
#include <stdint.h>

typedef struct {
    int16_t x;
    int16_t y;
    uint8_t flags;
} Obj;

Obj objs[32];
int16_t px[32], py[32];

void setup(void)
{
    uint16_t i;
    for (i = 0; i < 32; i++) {
        objs[i].x = (int16_t)(i * 3);
        objs[i].y = (int16_t)(i * 5 - 40);
        objs[i].flags = (uint8_t)i;
        px[i] = (int16_t)(i * 3);
        py[i] = (int16_t)(i * 5 - 40);
    }
}

/* the same for every member: what moved, wherever it is kept, weighted by its place */
uint16_t check(void)
{
    uint16_t i, s = 0;
    for (i = 0; i < 32; i++) {
        s = (uint16_t)(s + (uint16_t)(objs[i].x + px[i]) * (i + 1) + (uint16_t)(objs[i].y + py[i]) * (i + 7));
    }
    return s;
}

void struct_u16(void) { uint16_t i; for (i = 0; i < 32; i++) { objs[i].x = (int16_t)(objs[i].x + 2); objs[i].y = (int16_t)(objs[i].y - 1); } }
void struct_u8(void) { uint8_t i; for (i = 0; i < 32; i++) { objs[i].x = (int16_t)(objs[i].x + 2); objs[i].y = (int16_t)(objs[i].y - 1); } }
void struct_ptr(void) { Obj *o; for (o = objs; o < objs + 32; o++) { o->x = (int16_t)(o->x + 2); o->y = (int16_t)(o->y - 1); } }
void struct_ptr_count(void) { Obj *o = objs; uint16_t i; for (i = 0; i < 32; i++, o++) { o->x = (int16_t)(o->x + 2); o->y = (int16_t)(o->y - 1); } }
void struct_down(void) { uint16_t i; for (i = 32; i > 0; i--) { objs[i - 1].x = (int16_t)(objs[i - 1].x + 2); objs[i - 1].y = (int16_t)(objs[i - 1].y - 1); } }
