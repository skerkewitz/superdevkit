/* rule: same-enum-object
 * claim: step_enum same step_uint8
 * An object of an enum type is stored in one byte like a uint8_t when its enumerators fit, so a
 * state kept in it costs the same. */
#include <stdint.h>

typedef enum { ST_IDLE, ST_WALK, ST_JUMP, ST_FALL, ST_DEAD } State;

State states[32];
uint8_t codes[32];
uint8_t timer[32];
uint16_t r1, r2;

uint16_t step_enum(void)
{
    uint16_t moving = 0;
    for (uint8_t i = 0; i < 32; i++) {
        switch (states[i]) {
        case ST_IDLE: states[i] = timer[i] ? ST_IDLE : ST_WALK; break;
        case ST_WALK: states[i] = ST_JUMP; moving++; break;
        case ST_JUMP: states[i] = ST_FALL; moving++; break;
        case ST_FALL: states[i] = timer[i] > 3 ? ST_DEAD : ST_IDLE; break;
        default: break;
        }
    }
    return moving;
}

uint16_t step_uint8(void)
{
    uint16_t moving = 0;
    for (uint8_t i = 0; i < 32; i++) {
        switch (codes[i]) {
        case ST_IDLE: codes[i] = timer[i] ? ST_IDLE : ST_WALK; break;
        case ST_WALK: codes[i] = ST_JUMP; moving++; break;
        case ST_JUMP: codes[i] = ST_FALL; moving++; break;
        case ST_FALL: codes[i] = timer[i] > 3 ? ST_DEAD : ST_IDLE; break;
        default: break;
        }
    }
    return moving;
}

void test_main(void)
{
    for (uint8_t i = 0; i < 32; i++) {
        timer[i] = (uint8_t)(i & 7);
        states[i] = (i & 3) == 0 ? ST_IDLE : (i & 3) == 1 ? ST_WALK : (i & 3) == 2 ? ST_JUMP : ST_FALL;
        codes[i] = (uint8_t)(i & 3);
    }
    r1 = step_enum();
    r2 = step_uint8();
}
