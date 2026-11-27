/* rule: slow-field-array-offset
 * claim: sum_two_fields slower sum_one_field
 * An array field of a struct indexed by a variable (objs[i].y[k]) is reached with LDA objs,X
 * only at offset 0 in the struct; at any other offset dampf builds a 24-bit pointer to the
 * element for each access. Two such arrays as one, the first field (xy[k], xy[S + k]), keep
 * both at offset 0. */
#include <stdint.h>

#define N 32
#define S 5

typedef struct {
    int16_t x[S];
    int16_t y[S];
    uint8_t reach;
} Two;

typedef struct {
    int16_t xy[2 * S];      /* x of each sample, then y */
    uint8_t reach;
} One;

Two two[N];
One one[N];
uint16_t acc;

void sum_two_fields(uint8_t k)
{
    uint16_t s = 0;
    for (uint8_t i = 0; i < N; i++) {
        s = (uint16_t)(s + (uint16_t)two[i].x[k] + (uint16_t)two[i].y[k]);
    }
    acc = s;
}

void sum_one_field(uint8_t k)
{
    uint16_t s = 0;
    for (uint8_t i = 0; i < N; i++) {
        s = (uint16_t)(s + (uint16_t)one[i].xy[k] + (uint16_t)one[i].xy[S + k]);
    }
    acc = s;
}

void test_main(void)
{
    uint16_t t = 0;
    for (uint8_t i = 0; i < N; i++) {
        for (uint8_t k = 0; k < S; k++) {
            two[i].x[k] = one[i].xy[k] = (int16_t)(i * 7 + k);
            two[i].y[k] = one[i].xy[S + k] = (int16_t)(i * 3 - k);
        }
    }
    for (uint8_t k = 0; k < S; k++) {
        sum_two_fields(k);
        t = (uint16_t)(t + acc);
        sum_one_field(k);
        t = (uint16_t)(t + acc);
    }
    acc = t;
}
