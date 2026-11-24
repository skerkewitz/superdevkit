/* Constants converted to a type that cannot hold them: ConversionWarningTest compiles this file
 * with dampf and with gcc (no warning options) and compares where each warns, and with what
 * values. gcc warns only if the value fits the type with neither signedness. A line that ends
 * in "gcc only" is one where gcc warns and dampf does not (yet): gcc folds more than C's
 * constant expressions there. */
#include <stdint.h>

#define BIG 420
#define SMALL 100
enum { E300 = 300, ENEG = -1, E255 = 255 };

/* initializers of globals */
uint8_t g1 = 420;
uint8_t g2 = -1;
int8_t g3 = 255;
uint16_t g4 = 65536;
uint8_t g5 = 0xFF;
uint8_t g6 = 0x1FF;
int8_t g7 = 0x80;
int8_t g8 = 128;
int8_t g9 = -129;
uint8_t g10 = BIG;
uint8_t g11 = E300;
uint8_t g12 = (uint8_t)420;
uint8_t g13[] = { 420, 1, 256, SMALL, -128, -129 };
uint16_t g14 = -1;
uint16_t g15 = -32769;
int16_t g16 = 0xFFFF;
int16_t g17 = 40000;
int16_t g18 = 65536;
uint32_t g19 = -1;
int32_t g20 = 0xFFFFFFFF;
int32_t g21 = 0x80000000;
char g22 = 200;
char g23 = 256;
uint8_t g24 = ENEG;
int8_t g25 = E255;
uint8_t g26 = 200 + 100;
uint8_t g27 = 1 ? 300 : 1;
uint8_t g28 = 0 ? 300 : 1;
uint8_t g29 = 256u;
uint8_t g30 = (int16_t)300;
uint8_t g31 = (uint16_t)-1;
int8_t g32 = (uint8_t)200;
uint8_t g33 = -1u;
uint8_t g34 = ~0u;
uint8_t g35 = 1 << 8;
uint8_t g36 = 'a' * 4;
uint8_t g37 = -1u + 301;
int8_t g38 = 'a' + 100;
uint8_t g39 = 300 & 0xFF;
uint8_t g40 = 0x100 >> 1;

struct s { uint8_t a; int16_t b; uint8_t c[2]; };
struct s g41 = { 300, 40000, { 1, 999 } };
struct s g42[2] = { { 1, 2, { 3, 4 } }, { 256, -32769, { -1, -200 } } };

/* arguments and return values */
uint8_t take(uint8_t x, int8_t y)
{
    return (uint8_t)(x + y);
}

uint8_t ret1(void)
{
    return 300;
}

int8_t ret2(void)
{
    return 255;
}

uint16_t ret3(void)
{
    return BIG * 1000;
}

/* assignments, local initializers, and what gcc folds */
uint8_t gl;
int8_t gs;
int16_t gt;

void conversions(uint8_t c, int32_t n)
{
    uint8_t l = 420;
    static uint8_t sl = 500;
    uint8_t arr[2] = { 256, -129 };
    const int32_t k = 300;
    uint8_t m = k;
    l = 300;
    l = BIG;
    l += 300;
    l = c ? 300 : 1;
    l = n + 300;
    l = take(300, 200);
    l = take(BIG, -200);
    l = (int32_t)300;
    l = (uint8_t)300;
    l = (300);
    l = 1 ? 300 : 1;
    l = 0 ? 1 : 300;
    l = 2147483647 + 0;
    l = -2147483647 - 1;
    l = 300 - 2147483647 - 1;
    l = (0 && n) + 300;
    l = (1 || n) + 299;
    l = !n + 300;
    l = (int8_t)200 + 300;
    l = 3 << 30;
    l = 300 % 256 + 256;
    arr[0] = 300;
    gl = 256;
    gs = -129;
    gt = 0x10000;
    l = n * 0 + 300; /* gcc only */
    l = n - n + 300; /* gcc only */
    l = (n & 0) | 300; /* gcc only */
    l = n ? 300 : 300; /* gcc only */
    (void)sl;
    (void)m;
    (void)arr;
    (void)l;
}

/* never evaluated, or the constant overflowed: gcc is silent about the conversion */
void silent(int32_t n)
{
    uint8_t l = 0;
    l = 0 && take(300, 0);
    l = 1 || take(300, 0);
    l = 0 ? take(300, 0) : 1;
    l = 1 ? 1 : take(300, 0);
    l = sizeof(take(300, 0));
    l = sizeof(gl = 300);
    l = (int16_t)(2147483647 + 1); /* gcc warns about the overflow only */
    l = -(-2147483647 - 1); /* gcc warns about the overflow only */
    l = (-2147483647 - 1) / -1; /* gcc warns about the overflow only */
    l = (-2147483647 - 1) % -1; /* gcc warns about the overflow only */
    l = 65536 * 65536; /* gcc warns about the overflow only */
    l = 0x7FFFFFFF * 2; /* gcc warns about the overflow only */
    l = 1 && take(300, 0);
    (void)l;
    (void)n;
}
