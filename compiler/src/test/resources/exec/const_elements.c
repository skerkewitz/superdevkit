/*
 * Elements of constant arrays and fields of constant structs at constant indexes, which dampf
 * reads at compile time: every element width, signed and unsigned, bytes no initializer gives
 * (zero), 2-D arrays, a string, a struct's fields at their offsets, an index that becomes
 * constant only after inlining, and a constant array that is also read at a variable index.
 * A variable array with the same initializer is read the same way and must not be folded: the
 * test changes it first.
 */
#include <stdint.h>

static const uint8_t bytes[6] = {0, 1, 127, 128, 200, 255};
static const int8_t sbytes[5] = {-128, -1, 0, 1, 127};
static const uint16_t words[5] = {0, 1, 0x7FFF, 0x8000, 0xFFFF};
static const int16_t swords[6] = {INT16_MIN, -300, -1, 0, 300, INT16_MAX};
static const uint32_t longs[4] = {0, 0x12345678u, 0x80000000u, 0xFFFFFFFFu};
static const int32_t slongs[3] = {INT32_MIN, -70000, 70000};
static const uint8_t partly[8] = {9, 8};              /* the rest is 0 */
static const int16_t grid[3][4] = {{1, 2, 3, 4}, {-5, -6, -7, -8}, {100, 200, 300, 400}};
static const char text[] = "dampf";
struct thing { uint8_t kind; int16_t x; uint8_t flags[3]; int32_t far; };
static const struct thing things[2] = {{1, -2, {3, 4, 5}, -100000}, {200, 30000, {255}, 99}};
static const struct thing one = {7, -7, {0, 0, 7}, 7};
uint8_t changed[6] = {0, 1, 127, 128, 200, 255};

uint16_t r16[24];
int32_t r32[8];
uint8_t r8[8];
uint16_t sum;

static int16_t row_sum(uint8_t r)
{
    return (int16_t)(grid[r][0] + grid[r][1] + grid[r][2] + grid[r][3]);
}

static uint8_t kind_of(uint8_t i)
{
    return things[i].kind;
}

void test_main(void)
{
    uint8_t i;
    changed[3] = 42;
    r16[0] = bytes[3];
    r16[1] = (uint16_t)(int16_t)sbytes[0];
    r16[2] = (uint16_t)(sbytes[1] + sbytes[4]);
    r16[3] = words[3];
    r16[4] = (uint16_t)(words[4] + words[1]);
    r16[5] = (uint16_t)swords[0];
    r16[6] = (uint16_t)(swords[1] * 3);
    r16[7] = partly[1];
    r16[8] = partly[5];
    r16[9] = (uint16_t)grid[1][2];
    r16[10] = (uint16_t)row_sum(2);
    r16[11] = (uint16_t)(uint8_t)text[1];
    r16[12] = (uint16_t)(uint8_t)text[5];
    r16[13] = (uint16_t)things[0].x;
    r16[14] = things[1].flags[0];
    r16[15] = things[1].flags[2];
    r16[16] = kind_of(1);
    r16[17] = (uint16_t)one.x;
    r16[18] = changed[3];
    r16[19] = (uint16_t)(bytes[5] * 256);
    r16[20] = sbytes[0] < 0 ? 1 : 2;
    r16[21] = words[2] > 1000 ? 3 : 4;
    r32[0] = (int32_t)longs[1];
    r32[1] = (int32_t)longs[2];
    r32[2] = (int32_t)(longs[3] >> 4);
    r32[3] = slongs[0];
    r32[4] = slongs[1] + slongs[2];
    r32[5] = things[0].far;
    r32[6] = one.far;
    r8[0] = (uint8_t)(bytes[4] + bytes[5]);
    r8[1] = (uint8_t)sbytes[1];
    r8[2] = one.flags[2];
    for (i = 0; i < 6; i++) {
        sum += (uint16_t)(bytes[i] + changed[i]);       /* the same arrays at a variable index */
        sum += (uint16_t)swords[i];
    }
    for (i = 0; i < 2; i++) {
        sum += (uint16_t)(kind_of(i) * 3);
    }
}
