/* Two loops over the rows of a table that lie one after another (Collapse), and the nests that
 * must stay two loops: a row index read in the body, work per row, a break, rows that do not
 * fill the table, a start at or past the bound, signed and byte counters. */
#include <stdint.h>

uint8_t t[8][16];
uint8_t w[6][10];
int16_t sw[4][8];
uint16_t r_sum, r_u8, r_i16, r_ptr, r_part, r_none, r_start, r_index, r_per_row, r_break, r_fill, r_words;
uint8_t rows_seen;
uint16_t last_r, last_c;

static uint16_t sum_rows(void)
{
    uint16_t r, c, s = 0;
    for (r = 0; r < 8; r++)
        for (c = 0; c < 16; c++)
            s = (uint16_t)(s + t[r][c]);
    return s;
}

static uint16_t sum_rows_u8(void)
{
    uint8_t r, c;
    uint16_t s = 0;
    for (r = 0; r < 8; r++)
        for (c = 0; c < 16; c++)
            s = (uint16_t)(s + t[r][c]);
    return s;
}

static uint16_t sum_rows_i16(void)
{
    int16_t r, c;
    uint16_t s = 0;
    for (r = 0; r < 4; r++)
        for (c = 0; c < 8; c++)
            s = (uint16_t)(s + (uint16_t)sw[r][c]);
    return s;
}

static uint16_t sum_rows_ptr(void)
{
    uint16_t r, c, s = 0;
    for (r = 0; r < 8; r++) {
        const uint8_t *row = t[r];
        for (c = 0; c < 16; c++)
            s = (uint16_t)(s + row[c]);
    }
    return s;
}

/* rows 2 to 4 of 6 of a table with rows of 10 */
static uint16_t part_rows(void)
{
    uint16_t r, c, s = 0;
    for (r = 2; r < 5; r++)
        for (c = 0; c < 10; c++)
            s = (uint16_t)(s + w[r][c] * 3);
    return s;
}

static uint16_t no_rows(uint16_t from)
{
    uint16_t r, c, s = 7;
    for (r = from; r < 6; r++)
        for (c = 0; c < 10; c++)
            s = (uint16_t)(s + w[r][c]);
    return s;
}

static uint16_t start_past(void)
{
    uint16_t r, c, s = 5;
    for (r = 9; r < 8; r++)
        for (c = 0; c < 16; c++)
            s = (uint16_t)(s + t[r - 9][c]);
    return s;
}

/* the row index in the body: not one loop */
static uint16_t row_index(void)
{
    uint16_t r, c, s = 0;
    for (r = 0; r < 8; r++)
        for (c = 0; c < 16; c++)
            s = (uint16_t)(s + t[r][c] * (r + 1));
    return s;
}

/* work per row */
static uint16_t per_row(void)
{
    uint16_t r, c, s = 0;
    rows_seen = 0;
    for (r = 0; r < 8; r++) {
        for (c = 0; c < 16; c++)
            s = (uint16_t)(s + t[r][c]);
        rows_seen++;
    }
    return s;
}

/* a break out of the inner loop */
static uint16_t with_break(void)
{
    uint16_t r, c, s = 0;
    for (r = 0; r < 8; r++)
        for (c = 0; c < 16; c++) {
            if (t[r][c] == 200)
                break;
            s = (uint16_t)(s + t[r][c]);
        }
    return s;
}

/* the counters read after the loops */
static uint16_t counters_after(void)
{
    uint16_t r, c, s = 0;
    for (r = 0; r < 6; r++)
        for (c = 0; c < 10; c++)
            s = (uint16_t)(s + w[r][c]);
    last_r = r;
    last_c = c;
    return s;
}

static void fill_rows(uint8_t v)
{
    uint16_t r, c;
    for (r = 0; r < 6; r++)
        for (c = 0; c < 10; c++)
            w[r][c] = v;
}

void test_main(void)
{
    uint16_t r, c;
    for (r = 0; r < 8; r++)
        for (c = 0; c < 16; c++)
            t[r][c] = (uint8_t)(r * 37 + c * 11 + 3);
    t[5][9] = 200;
    for (r = 0; r < 6; r++)
        for (c = 0; c < 10; c++)
            w[r][c] = (uint8_t)(r * 13 + c * 5);
    for (r = 0; r < 4; r++)
        for (c = 0; c < 8; c++)
            sw[r][c] = (int16_t)(r * 1000 - c * 777);
    r_sum = sum_rows();
    r_u8 = sum_rows_u8();
    r_i16 = sum_rows_i16();
    r_ptr = sum_rows_ptr();
    r_part = part_rows();
    r_none = no_rows(6) + no_rows(4);
    r_start = start_past();
    r_index = row_index();
    r_per_row = per_row();
    r_break = with_break();
    r_words = counters_after();
    fill_rows(9);
    r_fill = counters_after();
}
