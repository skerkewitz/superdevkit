/* Enums: constants are int; an enum type is unsigned int unless an enumerator is negative (gcc). */
#include <stdint.h>

typedef enum { GS_TITLE, GS_PLAY, GS_OVER = 10, GS_NEXT } GameState;
enum Dir { DIR_LEFT = -1, DIR_NONE, DIR_RIGHT };

GameState state;
enum Dir dir = DIR_LEFT;
uint8_t unsigned_wrap;      /* state - 1 > 0 with state 0: unsigned arithmetic, true */
uint8_t signed_enum;        /* dir - 1 < 0: int arithmetic, true */
uint8_t next_value;
uint16_t switched;
int16_t const_sum;

static uint16_t score(GameState s)
{
    switch (s) {
    case GS_TITLE: return 1;
    case GS_PLAY: return 20;
    case GS_OVER: return 300;
    default: return 4000;
    }
}

void test_main(void)
{
    unsigned_wrap = state - 1 > 0;
    signed_enum = dir - 1 < 0;
    state = GS_NEXT;
    next_value = (uint8_t)state;
    switched = (uint16_t)(score(GS_TITLE) + score(GS_PLAY) + score(GS_OVER) + score(state));
    const_sum = (int16_t)(DIR_LEFT + DIR_RIGHT * 10 + GS_NEXT * 100);
}
