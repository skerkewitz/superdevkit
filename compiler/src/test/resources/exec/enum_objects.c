/* Enum objects in the smallest width that holds their enumerators (ADR-6FKZFDJ3): 1 byte for
 * most, 2 for one above 255, signed for one with a negative enumerator. On the PC they take 4
 * bytes; the checker allows only enumerators and values of the same enum to be stored, so the
 * values are the same. Each enum value is also copied into a 32-bit global, so a value cut off
 * by a too narrow object would show there. */
#include <stdint.h>

typedef enum { MODE_IDLE, MODE_WALK, MODE_JUMP, MODE_DEAD } Mode;     /* 1 byte, unsigned */
enum Big { BIG_LOW = 2, BIG_MID = 255, BIG_HIGH = 1000 };            /* 2 bytes, unsigned */
enum Turn { TURN_LEFT = -1, TURN_NONE, TURN_RIGHT };                 /* 1 byte, signed */
enum Depth { DEPTH_ROOF = -300, DEPTH_GROUND = 0, DEPTH_CAVE = 200 }; /* 2 bytes, signed */
enum Flag { FLAG_TOP = 255 };                                        /* 1 byte, unsigned */

typedef struct {
    uint8_t x;
    Mode mode;
    enum Turn turn;
    enum Big big;
    int16_t y;
    enum Depth depth;
} Actor;

static const Mode next_mode[4] = { MODE_WALK, MODE_JUMP, MODE_DEAD, MODE_IDLE };

Mode mode;
enum Big big = BIG_HIGH;
enum Turn turn = TURN_LEFT;
enum Depth depth = DEPTH_ROOF;
enum Flag flag = FLAG_TOP;
Actor actors[3];
Mode modes[5];
enum Turn turns[3] = { TURN_RIGHT, TURN_LEFT, TURN_NONE };

uint32_t w_mode, w_big, w_flag, w_actor_big;
int32_t w_turn, w_depth, w_actor_turn, w_actor_depth;
uint8_t below_zero;      /* mode - 1 > 0 with mode 0: unsigned arithmetic, as on the PC */
uint8_t turn_negative;   /* turn < 0: int arithmetic */
uint8_t big_compare;     /* big > 999 */
int32_t turn_sum;
uint16_t count_walk;
uint32_t elements;       /* sizeof modes / sizeof modes[0]: the same count on both */
int32_t local_result;

static Mode step(Mode m)
{
    return next_mode[m];
}

static enum Turn flip(enum Turn t)
{
    return t == TURN_LEFT ? TURN_RIGHT : t == TURN_RIGHT ? TURN_LEFT : TURN_NONE;
}

static void set_turn(enum Turn *p, enum Turn t)
{
    *p = t;
}

static int32_t locals(enum Depth d)
{
    enum Depth here = d;
    enum Big b = BIG_LOW;
    Mode m = MODE_IDLE;
    for (uint8_t i = 0; i < 3; i++) {
        m = step(m);
    }
    if (here == DEPTH_ROOF) {
        b = BIG_HIGH;
    }
    return (int32_t)here * 10 + (int32_t)b + (int32_t)m;
}

void test_main(void)
{
    below_zero = mode - 1 > 0;
    mode = step(MODE_JUMP);
    turn_negative = turn < 0;
    big_compare = big > 999;
    depth = depth == DEPTH_ROOF ? DEPTH_CAVE : DEPTH_GROUND;

    for (uint8_t i = 0; i < 3; i++) {
        Actor *a = &actors[i];
        a->x = (uint8_t)(i + 1);
        a->mode = i == 0 ? MODE_WALK : MODE_JUMP;
        a->turn = turns[i];
        a->big = i == 2 ? BIG_HIGH : BIG_MID;
        a->y = (int16_t)(-1 - i);
        a->depth = i == 1 ? DEPTH_ROOF : DEPTH_CAVE;
        set_turn(&a->turn, flip(a->turn));
    }
    actors[0] = actors[2];
    actors[0].x = 9;

    Mode m = MODE_IDLE;
    for (uint8_t i = 0; i < 5; i++) {
        modes[i] = m;
        m = step(m);
        if (modes[i] == MODE_WALK) {
            count_walk += 100;
        }
    }
    flag = FLAG_TOP;
    turn = (enum Turn)1;
    turn_sum = turn + turns[1] + actors[1].turn * 10;

    w_mode = mode;
    w_big = big;
    w_flag = flag;
    w_turn = turn;
    w_depth = depth;
    w_actor_big = actors[2].big;
    w_actor_turn = actors[1].turn;
    w_actor_depth = actors[1].depth;
    elements = sizeof modes / sizeof modes[0];
    local_result = locals(DEPTH_ROOF) + locals(DEPTH_CAVE);
}
