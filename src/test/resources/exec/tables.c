/* Object tables as in the game: an array of structs, walked with a pointer. */
#include <stdint.h>

#define MAX_BUBBLES 6

typedef struct {
    uint16_t x;         /* 8.8 fixed point */
    uint16_t y;
    int16_t vx;
    uint8_t state;
    uint8_t age;
} Bubble;

Bubble bubbles[MAX_BUBBLES];
uint16_t active;
uint16_t sum_x;
uint8_t oldest;
Bubble copy;
int16_t grid[3][4];
uint8_t counts[8];

static void spawn(uint8_t i, uint16_t x, int16_t vx)
{
    Bubble *b = &bubbles[i];
    b->x = x;
    b->y = (uint16_t)(200 << 8);
    b->vx = vx;
    b->state = 1;
    b->age = 0;
}

static void update(void)
{
    uint8_t i;
    for (i = 0; i < MAX_BUBBLES; i++) {
        Bubble *b = &bubbles[i];
        if (b->state == 0) {
            continue;
        }
        b->x = (uint16_t)(b->x + b->vx);
        b->age++;
        if (b->age > 3 && b->vx < 0) {
            b->state = 0;
        }
    }
}

void test_main(void)
{
    uint8_t i;
    uint8_t r;
    uint8_t c;

    spawn(0, 0x1000, 0x0180);
    spawn(2, 0x8000, -0x0100);
    spawn(5, 0xF000, 0x0040);
    for (i = 0; i < 5; i++) {
        update();
    }
    for (i = 0; i < MAX_BUBBLES; i++) {
        if (bubbles[i].state) {
            active++;
            sum_x = (uint16_t)(sum_x + (bubbles[i].x >> 8));
        }
        if (bubbles[i].age > oldest) {
            oldest = bubbles[i].age;
        }
    }
    copy = bubbles[5];          /* struct assignment */
    copy.age += 100;
    for (r = 0; r < 3; r++) {
        for (c = 0; c < 4; c++) {
            grid[r][c] = (int16_t)(r * 10 - c);
        }
    }
    grid[2][3] += grid[1][1];
    for (i = 0; i < 20; i++) {
        counts[(i * 3) & 7]++;
    }
}
