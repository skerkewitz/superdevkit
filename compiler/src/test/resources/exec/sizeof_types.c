/* sizeof of typedef names in parentheses: the grammar reads sizeof (name) as a parenthesised
 * expression first, so dampf took the typedef name for an undeclared variable (a bug once). */
#include <stdint.h>

typedef struct {
    uint8_t a;
    uint8_t b;
    int16_t c;
} Quad;

typedef uint16_t Word;

uint32_t s_u8;
uint32_t s_word;
uint32_t s_quad;
uint32_t s_quads;
uint32_t s_expr;
Quad q[3];

void test_main(void)
{
    s_u8 = sizeof(uint8_t);
    s_word = sizeof(Word);
    s_quad = sizeof(Quad);
    s_quads = sizeof(q) / sizeof(Quad);
    s_expr = sizeof (q[1].c) + sizeof(int16_t);
}
