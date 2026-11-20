/* family: struct-fields
 * tolerance: 10
 * Two fields of every element moved: of an array of structs by index and by pointer, and of one
 * array per field. The style guide's fast-array-per-field.
 * gap: struct_u16 L4 the counter is kept beside the derived offset; then the stride (split)
 * gap: struct_u8 split a stride of 5 bytes takes TXA ADC TAX where the field arrays take INX INX: only splitting the array (roadmap M9 row 4) closes it
 * gap: struct_ptr C6 the pointers are compared as 32-bit values every round
 * gap: struct_ptr_count L5 the pointer's offset and the counter have one start and step
 * gap: struct_down L3 i - 1 is the step, and the X pattern wants a step with one user; the offsets of objs[i - 1] then come from i - 1 (L4)
 * gap: fields_u16 L4 the counter is kept beside the derived offset
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
void fields_u16(void) { uint16_t i; for (i = 0; i < 32; i++) { px[i] = (int16_t)(px[i] + 2); py[i] = (int16_t)(py[i] - 1); } }
void fields_u8(void) { uint8_t i; for (i = 0; i < 32; i++) { px[i] = (int16_t)(px[i] + 2); py[i] = (int16_t)(py[i] - 1); } }
