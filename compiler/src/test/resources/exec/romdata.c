/* Constant data in ROM: tables, strings, structs with pointers into other tables. */
#include <stdint.h>

typedef struct {
    uint8_t x;
    uint8_t y;
    uint8_t type;
} Enemy;

typedef struct {
    const uint8_t *cells;
    const Enemy *enemies;
    uint8_t enemy_count;
    uint16_t time;
} Level;

static const uint8_t level1_cells[6] = { 1, 0, 0, 0x80, 0x80, 1 };
static const uint8_t level2_cells[] = { 2, 2, 0x81 };
static const Enemy level1_enemies[] = { { 3, 4, 1 }, { 10, 12, 2 } };
static const Enemy level2_enemies[] = { { 7, 7, 3 } };

const Level levels[2] = {
    { level1_cells, level1_enemies, 2, 2400 },
    { level2_cells, level2_enemies, 1, 1800 },
};

static const char *const names[] = { "WALKER", "BOUNCER", "THROWER" };
static const uint16_t powers[] = { 1, 10, 100, 1000, 10000 };

uint16_t cell_sum;
uint16_t enemy_sum;
uint16_t time_sum;
uint8_t name_length;
uint8_t third_letter;
uint8_t table_size;
char hello[8];
uint16_t digits;

static uint8_t length(const char *s)
{
    uint8_t n = 0;
    while (*s) {
        s++;
        n++;
    }
    return n;
}

void test_main(void)
{
    uint8_t l;
    uint8_t i;
    const char *greeting = "HELLO";
    uint16_t v = 4711;

    for (l = 0; l < 2; l++) {
        const Level *lv = &levels[l];
        for (i = 0; i < lv->enemy_count; i++) {
            enemy_sum = (uint16_t)(enemy_sum + lv->enemies[i].x * lv->enemies[i].type);
        }
        cell_sum = (uint16_t)(cell_sum + lv->cells[0] + lv->cells[2]);
        time_sum = (uint16_t)(time_sum + lv->time);
    }
    name_length = length(names[1]);
    third_letter = (uint8_t)names[2][2];
    table_size = (uint8_t)(sizeof powers / sizeof powers[0]);
    for (i = 0; greeting[i] != 0; i++) {
        hello[i] = greeting[i];
    }
    for (i = 4; i < 5; i--) {   /* counts down, ends when i wraps to 255 */
        digits = (uint16_t)(digits * 10 + v / powers[i] % 10);
    }
}
