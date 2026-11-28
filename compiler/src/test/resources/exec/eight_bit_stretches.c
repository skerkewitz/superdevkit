/* Byte work in 8 bits across blocks (Widths): byte stores on both ways of a branch, loops whose
 * entry also goes elsewhere, a byte value read in 16 bits after the branch that stored it, calls
 * and returns between byte code (the runtime's multiplication takes an operand in A), an index
 * from a byte, signed bytes, carries of 16-bit sums next to byte stores, and a switch on a byte. */
#include <stdint.h>

uint8_t buf[64];
uint8_t n, flags, out1, out2, mode;
int8_t sb[8] = { -3, 7, -128, 127, 0, -1, 64, -65 };
uint8_t tab[16] = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 250, 254, 255 };
uint16_t wide[8];
uint16_t total;
int16_t stotal;
uint8_t hist[8];

static void put(uint8_t x, uint8_t y, uint8_t t)
{
    uint8_t i = n;
    buf[i] = x;
    if (t > 3) {
        buf[i + 1] = y;
        flags |= 1;
    } else {
        buf[i + 1] = (uint8_t)(y + 1);
        flags |= 2;
    }
    buf[i + 2] = t;
    n = (uint8_t)(i + 3);
}

/* the high byte of A after the branch: x is read as a 16-bit value */
static uint16_t after_branch(uint8_t x, uint8_t y)
{
    uint8_t v;
    if (x & 1) {
        v = (uint8_t)(x + y);
        out1 = v;
    } else {
        v = (uint8_t)(x - y);
        out2 = v;
    }
    return (uint16_t)(v + 300);
}

static void rows(uint8_t first, uint8_t count)
{
    uint16_t k;
    uint8_t c = first;
    if (count == 0) {
        mode = 9;
        return;
    }
    for (k = 0; k < count; k++) {
        buf[k] = c;
        buf[k + 32] = (uint8_t)(c ^ 0x55);
        c = (uint8_t)(c + 7);
    }
    n = c;
}

static uint8_t pick(uint8_t i)
{
    uint8_t v = tab[i & 15];
    if (v > 200) {
        v = (uint8_t)(v - 200);
    } else if (v == 5) {
        v = 77;
    }
    return v;
}

static int16_t signed_mix(uint8_t i)
{
    int8_t s = sb[i & 7];
    int8_t t;
    if (s < 0) {
        t = (int8_t)(s + 1);
    } else {
        t = (int8_t)(s - 1);
    }
    sb[(i + 1) & 7] = t;
    return (int16_t)(t * 3 + s);
}

static void sums(uint8_t a, uint8_t b)
{
    uint16_t w = (uint16_t)(a * 200u);
    buf[40] = a;
    w = (uint16_t)(w + b);
    if (w > 1000) {
        buf[41] = b;
        w = (uint16_t)(w + 0x1F0);
    }
    buf[42] = (uint8_t)w;
    total = (uint16_t)(total + w);
}

static void mul_between(uint8_t a, uint16_t b)
{
    buf[44] = a;
    wide[6] = (uint16_t)(wide[0] * wide[1] + b);
    buf[45] = (uint8_t)(a + 1);
    if (a > 100) {
        wide[7] = (uint16_t)(wide[6] * wide[2] + a);
    }
    buf[46] = (uint8_t)(a ^ (uint8_t)wide[7]);
}

static void by_mode(uint8_t m, uint8_t v)
{
    switch (m) {
    case 0: buf[50] = v; break;
    case 1: buf[51] = (uint8_t)(v + 1); out1 = v; break;
    case 2: buf[52] = (uint8_t)(v << 1); break;
    case 3: hist[v & 7]++; break;
    default: buf[53] = m; break;
    }
    buf[54] = (uint8_t)(m + v);
}

static uint8_t count_small(void)
{
    uint8_t i, c = 0;
    for (i = 0; i < 16; i++) {
        if (tab[i] < 10) {
            c++;
        } else {
            buf[60 + (i & 3)] = tab[i];
        }
    }
    return c;
}

void test_main(void)
{
    uint8_t i;
    put(1, 2, 3);
    put(4, 5, 6);
    put(250, 255, 0);
    put(7, 8, 200);
    for (i = 0; i < 6; i++) {
        wide[i] = after_branch((uint8_t)(i * 51), (uint8_t)(i * 13 + 200));
    }
    rows(3, 0);
    rows(250, 20);
    for (i = 0; i < 18; i++) {
        uint8_t p = pick(i);
        total = (uint16_t)(total + p);
        hist[p & 7] = (uint8_t)(hist[p & 7] + p);
    }
    for (i = 0; i < 10; i++) {
        stotal = (int16_t)(stotal + signed_mix(i));
    }
    sums(3, 250);
    sums(7, 1);
    sums(255, 255);
    for (i = 0; i < 7; i++) {
        by_mode(i, (uint8_t)(i * 37 + 5));
    }
    n = (uint8_t)(n + count_small());
    mul_between(7, 1000);
    mul_between(200, 3);
}
