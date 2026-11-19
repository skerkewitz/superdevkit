/* A typedef with the same name as its struct tag (typedef struct S {...} S;). */
#include <stdint.h>

typedef struct Bubble {
    int16_t x;
    uint8_t size;
} Bubble;

typedef struct Pair Pair;
struct Pair {
    Bubble a;
    struct Bubble b;
};

Pair pair;
int16_t sum;
uint8_t sizes;

static int16_t span(const Pair *p) { return (int16_t)(p->b.x - p->a.x); }

void test_main(void) {
    pair.a.x = -40;
    pair.a.size = 3;
    pair.b = pair.a;
    pair.b.x = 1000;
    pair.b.size = 200;
    sum = span(&pair);
    sizes = (uint8_t)(pair.a.size + pair.b.size);
}
