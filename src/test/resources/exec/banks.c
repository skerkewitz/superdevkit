/* More than one ROM bank: two 30,000-byte tables and 200 functions that call each other. */
#include <stdint.h>

#define V10(b) b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9
#define V100(b) V10(b), V10(b + 10), V10(b + 20), V10(b + 30), V10(b + 40), \
                V10(b + 50), V10(b + 60), V10(b + 70), V10(b + 80), V10(b + 90)
#define V1000(b) V100(b), V100(b + 100), V100(b + 200), V100(b + 300), V100(b + 400), \
                 V100(b + 500), V100(b + 600), V100(b + 700), V100(b + 800), V100(b + 900)
#define V5000(b) V1000(b), V1000(b + 1000), V1000(b + 2000), V1000(b + 3000), V1000(b + 4000)

/* 15,000 entries of 2 bytes each: each table needs most of a 32 KB bank */
static const uint16_t table_a[15000] = { V5000(0), V5000(5000), V5000(10000) };
static const uint16_t table_b[15000] = { V5000(7), V5000(20007), V5000(40007) };

#define F(n) \
    static uint16_t f##n(uint16_t x) \
    { \
        uint16_t y = (uint16_t)(x * 3u + n); \
        if (y & 1) { \
            y = (uint16_t)(y ^ table_a[(x + n) % 15000u]); \
        } else { \
            y = (uint16_t)(y + table_b[(y + n) % 15000u]); \
        } \
        return y; \
    }
#define F10(p) F(p##0) F(p##1) F(p##2) F(p##3) F(p##4) F(p##5) F(p##6) F(p##7) F(p##8) F(p##9)
#define F100(p) F10(p##0) F10(p##1) F10(p##2) F10(p##3) F10(p##4) \
                F10(p##5) F10(p##6) F10(p##7) F10(p##8) F10(p##9)
F100(1)
F100(2)

#define C(n) acc = f##n(acc);
#define C10(p) C(p##0) C(p##1) C(p##2) C(p##3) C(p##4) C(p##5) C(p##6) C(p##7) C(p##8) C(p##9)
#define C100(p) C10(p##0) C10(p##1) C10(p##2) C10(p##3) C10(p##4) \
                C10(p##5) C10(p##6) C10(p##7) C10(p##8) C10(p##9)

uint16_t acc = 1;
uint16_t last_a;
uint16_t last_b;
uint16_t via_pointer;

void test_main(void)
{
    const uint16_t *p = &table_b[14999];

    C100(1)
    C100(2)
    last_a = table_a[14999];
    last_b = table_b[14998];
    via_pointer = *p;
}
