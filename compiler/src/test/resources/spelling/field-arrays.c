/* family: field-arrays
 * tolerance: 10
 * Two fields of every element moved, kept as one array per field (px[i], py[i]), with a 16-bit and
 * an 8-bit counter. The same as an array of structs in struct-fields.c, about 12 % cheaper (the C
 * guide's fast-array-per-field).
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

void fields_u16(void) { uint16_t i; for (i = 0; i < 32; i++) { px[i] = (int16_t)(px[i] + 2); py[i] = (int16_t)(py[i] - 1); } }
void fields_u8(void) { uint8_t i; for (i = 0; i < 32; i++) { px[i] = (int16_t)(px[i] + 2); py[i] = (int16_t)(py[i] - 1); } }
