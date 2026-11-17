/* Pointers: output parameters, pointer stepping, differences, comparisons, local arrays. */
#include <stdint.h>

typedef struct {
    uint8_t x;
    uint8_t y;
    uint8_t tile;
    uint8_t attr;
} Sprite;

Sprite oam[8];
Sprite *oam_next = oam;
uint8_t oam_count;
uint16_t px;
uint16_t py;
int16_t distance;
uint8_t found_index;
uint8_t is_null;
uint8_t local_sum;
int32_t wide[3];

static void oam_add(uint8_t x, uint8_t y, uint8_t tile)
{
    if (oam_count < 8) {
        Sprite *s = oam_next++;
        oam_count++;
        s->x = x;
        s->y = y;
        s->tile = tile;
        s->attr = (uint8_t)(tile ^ 0x30);
    }
}

static void position(uint16_t *x, uint16_t *y)
{
    *x = 0x1234;
    *y += 5;
}

static Sprite *find(uint8_t tile)
{
    Sprite *s;
    for (s = oam; s < oam_next; s++) {
        if (s->tile == tile) {
            return s;
        }
    }
    return 0;
}

void test_main(void)
{
    uint8_t i;
    uint8_t table[5] = { 3, 1, 4, 1, 5 };
    int32_t *w = wide;

    for (i = 0; i < 5; i++) {
        oam_add((uint8_t)(i * 20), (uint8_t)(100 - i), (uint8_t)(i + 7));
    }
    py = 10;
    position(&px, &py);
    distance = (int16_t)(oam_next - oam);
    found_index = (uint8_t)(find(9) - oam);
    is_null = find(99) == 0;
    for (i = 0; i < 5; i++) {
        local_sum = (uint8_t)(local_sum + table[i]);
    }
    *w++ = -100000;
    *w = *(w - 1) * 2;
    w[1] = w[-1] + w[0];
}
