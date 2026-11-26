/*
 * A switch as a jump table (dampf.backend.SwitchSelection): dense and sparse switches, on 8- and
 * 16-bit values, on signed ones, with and without default, cases that fall through into the next,
 * values at both ends of the table's range check, a first case 0 tested before the table, a case
 * that starts a loop whose counter lives in X, switches in loops, and a large function in a ROM
 * bank of its own (a 28,000-byte table fills most of bank $80), whose jump table JMP (abs,X)
 * reads from that bank. Every result must be the PC's; SwitchTableTest checks that the tables are
 * there.
 */
#include <stdint.h>

uint8_t bytes[18] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 127, 128, 254, 255};
uint16_t words[16] = {0, 1, 98, 99, 100, 101, 104, 107, 108, 109, 110, 255, 256, 32768, 65534, 65535};
int16_t signeds[16] = {-32768, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 32767};
int8_t sbytes[12] = {-128, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 127};

uint16_t r_dense8;
uint16_t r_dense16;
uint16_t r_sparse;
uint16_t r_chain;
uint16_t r_signed;
uint16_t r_signed8;
uint16_t r_fall;
uint16_t r_masked;
uint16_t r_kinds;
uint16_t r_loop_head;
uint16_t r_in_loop;
uint16_t r_big;
uint16_t r_no_default = 1000;

/* dense from 0 on a byte, with default: case 0 first, then the table with its upper check */
static uint16_t dense8(uint8_t k)
{
    switch (k) {
    case 0: return 3;
    case 1: return 5;
    case 2: return 7;
    case 3: return 11;
    case 4: return 13;
    case 5: return 17;
    case 6: return 19;
    case 7: return 23;
    default: return 1;
    }
}

/* dense from 100 on a 16-bit value: checks at both ends (99 and 109 just outside) */
static uint16_t dense16(uint16_t k)
{
    switch (k) {
    case 100: return 29;
    case 101: return 31;
    case 102: return 37;
    case 103: return 41;
    case 104: return 43;
    case 105: return 47;
    case 106: return 53;
    case 107: return 59;
    case 108: return 61;
    default: return 2;
    }
}

/* sparse, out of order, with holes that go to default */
static uint16_t sparse(uint16_t k)
{
    switch (k) {
    case 13: return 67;
    case 1: return 71;
    case 4: return 73;
    case 9: return 79;
    case 3: return 83;
    case 12: return 89;
    case 7: return 97;
    default: return 4;
    }
}

/* too sparse for a table: stays a chain of compares */
static uint16_t chain(uint16_t k)
{
    switch (k) {
    case 0: return 101;
    case 100: return 103;
    case 256: return 107;
    case 32768: return 109;
    case 65535: return 113;
    default: return 5;
    }
}

/* signed, around 0, both ends checked after subtracting the lowest case */
static uint16_t signed16(int16_t k)
{
    switch (k) {
    case -5: return 127;
    case -4: return 131;
    case -3: return 137;
    case -2: return 139;
    case -1: return 149;
    case 0: return 151;
    case 1: return 157;
    case 2: return 163;
    case 3: return 167;
    case 4: return 173;
    default: return 6;
    }
}

static uint16_t signed8(int8_t k)
{
    switch (k) {
    case 0: return 179;
    case -3: return 181;
    case -2: return 191;
    case -1: return 193;
    case 1: return 197;
    case 2: return 199;
    case 3: return 211;
    case 4: return 223;
    default: return 7;
    }
}

/* cases that fall through: the sum of every case from the one taken to the next break */
static uint16_t fall(uint8_t k)
{
    uint16_t acc = 0;
    switch (k) {
    case 1: acc += 1;
    case 2: acc += 20;
    case 3: acc += 300;
        break;
    case 4: acc += 4000;
    case 5:
    case 6: acc += 5;
    case 7: acc += 60;
        break;
    case 8: acc += 700;
    default: acc += 8;
    }
    return acc;
}

/* the value's range is the table's: no check at all */
static uint16_t masked(uint16_t k)
{
    switch (k & 7) {
    case 0: return 227;
    case 1: return 229;
    case 2: return 233;
    case 3: return 239;
    case 4: return 241;
    case 5: return 251;
    case 6: return 257;
    case 7: return 263;
    }
    return 9;
}

/* no default: a value that is no case leaves the result as it was */
static void no_default(uint8_t k)
{
    switch (k) {
    case 0: r_no_default += 1; break;
    case 1: r_no_default += 3; break;
    case 2: r_no_default *= 3u; break;
    case 3: r_no_default -= 7; break;
    case 4: r_no_default ^= 0x55; break;
    case 5: r_no_default = (uint16_t)(r_no_default << 1); break;
    case 6: r_no_default = (uint16_t)(r_no_default >> 1); break;
    }
}

/* a case that starts a loop: its counter lives in X, set on the way from the table */
static uint16_t loop_head(uint8_t k)
{
    uint16_t acc = 0;
    uint8_t i;
    switch (k) {
    case 0: acc = 7; break;
    case 1: acc = 11; break;
    case 2:
        for (i = 1; i < 14; i++) {
            acc = (uint16_t)(acc + words[i]);
        }
        break;
    case 3: acc = 13; break;
    case 4: acc = 17; break;
    case 5: acc = 19; break;
    case 6: acc = 23; break;
    }
    return acc;
}

/* object kinds in an array, the empty one first, as games keep them */
typedef struct {
    uint8_t kind;
    uint8_t timer;
    uint16_t x;
} Obj;

Obj objs[10] = {
    {0, 1, 10}, {1, 2, 20}, {0, 3, 30}, {2, 4, 40}, {3, 5, 50},
    {4, 6, 60}, {5, 7, 70}, {0, 8, 80}, {6, 9, 90}, {9, 10, 100},
};

static void kinds(void)
{
    Obj *o;
    for (o = objs; o < objs + 10; o++) {
        switch (o->kind) {
        case 0:
            break;
        case 1:
            o->x = (uint16_t)(o->x + 1);
            break;
        case 2:
            o->x = (uint16_t)(o->x - o->timer);
            break;
        case 3:
            o->timer = (uint8_t)(o->timer * 2u);
            break;
        case 4:
            o->kind = 0;
            break;
        case 5:
            o->x = (uint16_t)(o->x ^ 0x1234);
            break;
        case 6:
            o->timer = (uint8_t)(o->timer + 100);
            break;
        default:
            o->x = 0;
            break;
        }
        r_kinds = (uint16_t)(r_kinds * 31u + o->x + o->timer + o->kind);
    }
}

/* most of bank $80, so the large function below goes into a bank of its own */
#define V10(b) b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7, b + 8, b + 9
#define V100(b) V10(b), V10(b + 10), V10(b + 20), V10(b + 30), V10(b + 40), \
                V10(b + 50), V10(b + 60), V10(b + 70), V10(b + 80), V10(b + 90)
#define V1000(b) V100(b), V100(b + 100), V100(b + 200), V100(b + 300), V100(b + 400), \
                 V100(b + 500), V100(b + 600), V100(b + 700), V100(b + 800), V100(b + 900)
static const uint16_t filler[14000] = {
    V1000(0), V1000(1000), V1000(2000), V1000(3000), V1000(4000), V1000(5000), V1000(6000),
    V1000(7000), V1000(8000), V1000(9000), V1000(10000), V1000(11000), V1000(12000), V1000(13000)
};

/* a large function: 96 cases of some code each */
#define BIG(n) \
    case n: \
        acc = (uint16_t)(acc * 5u + filler[(acc + n * 37u) & 8191u]); \
        acc = (uint16_t)(acc ^ (n * 41u)); \
        acc = (uint16_t)((acc << 3) | (acc >> 13)); \
        acc = (uint16_t)(acc + filler[(acc ^ n) & 4095u] * 3u); \
        break;
#define BIG8(p) BIG(p##0) BIG(p##1) BIG(p##2) BIG(p##3) BIG(p##4) BIG(p##5) BIG(p##6) BIG(p##7)

static uint16_t big(uint8_t k, uint16_t acc)
{
    switch (k) {
    BIG8(1) BIG8(2) BIG8(3) BIG8(4) BIG8(5) BIG8(6) BIG8(7) BIG8(8) BIG8(9)
    BIG8(10) BIG8(11) BIG8(12)
    default:
        acc = (uint16_t)(acc + 1);
        break;
    }
    return acc;
}

void test_main(void)
{
    uint8_t i;
    uint16_t j;
    for (i = 0; i < 18; i++) {
        r_dense8 = (uint16_t)(r_dense8 * 7u + dense8(bytes[i]));
        r_fall = (uint16_t)(r_fall * 7u + fall(bytes[i]));
        no_default(bytes[i]);
        r_loop_head = (uint16_t)(r_loop_head * 7u + loop_head(bytes[i]));
    }
    for (i = 0; i < 16; i++) {
        r_dense16 = (uint16_t)(r_dense16 * 7u + dense16(words[i]));
        r_sparse = (uint16_t)(r_sparse * 7u + sparse(words[i]) + sparse(bytes[i]));
        r_chain = (uint16_t)(r_chain * 7u + chain(words[i]));
        r_signed = (uint16_t)(r_signed * 7u + signed16(signeds[i]));
        r_masked = (uint16_t)(r_masked * 7u + masked(words[i]) + masked(i));
    }
    for (i = 0; i < 12; i++) {
        r_signed8 = (uint16_t)(r_signed8 * 7u + signed8(sbytes[i]));
    }
    for (j = 0; j < 3; j++) {
        kinds();
    }
    /* in a loop whose counter is no loop counter in X: it calls a function */
    j = 0;
    while (j < 300) {
        r_in_loop = (uint16_t)(r_in_loop * 3u + dense8((uint8_t)j) + big((uint8_t)(j * 7u), j));
        j += 13;
    }
    for (i = 0; i < 18; i++) {
        r_big = big(bytes[i], r_big);
        r_big = big((uint8_t)(bytes[i] + 90u), r_big);
    }
}
