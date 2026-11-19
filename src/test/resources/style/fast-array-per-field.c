/* rule: fast-array-per-field
 * claim: move_array_per_field faster move_array_of_structs
 * One array per field costs less than an array of structs: the element of a field array is
 * reached without the struct's size in the index. Small gain; split a struct only in hot code. */
#include <stdint.h>

typedef struct {
    int16_t x;
    int16_t y;
} Obj;

Obj objs[32];
int16_t px[32], py[32];

void move_array_of_structs(void)
{
    uint16_t i;
    for (i = 0; i < 32; i++) {
        objs[i].x = (int16_t)(objs[i].x + 2);
        objs[i].y = (int16_t)(objs[i].y - 1);
    }
}

void move_array_per_field(void)
{
    uint16_t i;
    for (i = 0; i < 32; i++) {
        px[i] = (int16_t)(px[i] + 2);
        py[i] = (int16_t)(py[i] - 1);
    }
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 32; i++) {
        objs[i].x = (int16_t)(i * 3);
        objs[i].y = (int16_t)(i * 5);
        px[i] = (int16_t)(i * 3);
        py[i] = (int16_t)(i * 5);
    }
    move_array_of_structs();
    move_array_per_field();
}
