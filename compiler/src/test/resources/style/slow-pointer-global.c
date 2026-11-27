/* rule: slow-pointer-global
 * claim: show_by_pointer slower show_by_offset
 * Where a global says which row of one table is current, a pointer into the table costs more
 * than an offset into it: a function that reads through a pointer global copies its 4 bytes
 * into its frame first and reads with LDA [dp],Y; the offset indexes the table with X. */
#include <stdint.h>

#define ROW 64

static const uint8_t frames[4][ROW] = {
    { 1, 2, 3, 4, 5, 6, 7, 8 }, { 9, 10, 11, 12 }, { 13, 14 }, { 15, 16, 17 }
};

static const uint8_t *frame_row;     /* the current row, as a pointer */
static uint16_t frame_off;           /* the same row, as its offset into frames */
uint8_t shown[16];

void select_row(uint8_t sheet)
{
    frame_row = frames[sheet & 3];
    frame_off = (uint16_t)((sheet & 3) * ROW);
}

void show_by_pointer(uint8_t slot, uint8_t tile)
{
    shown[slot & 15] = frame_row[tile & (ROW - 1)];
}

void show_by_offset(uint8_t slot, uint8_t tile)
{
    shown[slot & 15] = (&frames[0][0])[frame_off + (tile & (ROW - 1))];
}

uint16_t sum;

void test_main(void)
{
    for (uint8_t s = 0; s < 4; s++) {
        select_row(s);
        for (uint8_t t = 0; t < 70; t = (uint8_t)(t + 3)) {
            show_by_pointer(t, t);
            sum = (uint16_t)(sum * 3u + shown[t & 15]);
            show_by_offset(t, (uint8_t)(t + 1));
            sum = (uint16_t)(sum * 3u + shown[t & 15]);
        }
    }
}
