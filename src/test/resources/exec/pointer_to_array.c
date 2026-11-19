/* Pointers to arrays: T (*p)[N], declared, stepped, indexed twice, passed to a function, into
 * ROM and into RAM, with sizeof. A table of rows picked at run time is the use (Er-Tüp's walls). */
#include <stdint.h>

static const uint8_t ROWS_A[3][4] = { { 1, 2, 3, 4 }, { 5, 6, 7, 8 }, { 9, 10, 11, 12 } };
static const uint8_t ROWS_B[3][4] = { { 21, 22, 23, 24 }, { 25, 26, 27, 28 }, { 29, 30, 31, 32 } };
static int16_t grid[2][3];

uint8_t pick_a, pick_b, second_row, size_row, size_ptr, after_step;
uint16_t sum;
int16_t g00, g12;

static uint16_t sum_rows(const uint8_t (*rows)[4], uint8_t n)
{
    uint16_t s = 0;
    uint8_t r, c;
    for (r = 0; r < n; r++) {
        for (c = 0; c < 4; c++) {
            s = (uint16_t)(s + rows[r][c]);
        }
    }
    return s;
}

static void fill(int16_t (*g)[3])
{
    g[0][0] = -7;
    g[1][2] = 1234;
}

void test_main(void)
{
    const uint8_t (*t)[4] = ROWS_A;
    uint8_t which;

    pick_a = t[2][1];
    for (which = 0; which < 2; which++) {
        t = which ? ROWS_B : ROWS_A;
    }
    pick_b = t[1][3];
    t++;
    after_step = (*t)[0];
    second_row = t[1][2];
    size_row = (uint8_t)sizeof *t;
    size_ptr = (uint8_t)sizeof t[0][0];
    sum = (uint16_t)(sum_rows(ROWS_A, 3) + sum_rows(ROWS_B, 2));
    fill(grid);
    g00 = grid[0][0];
    g12 = grid[1][2];
    return;
}
