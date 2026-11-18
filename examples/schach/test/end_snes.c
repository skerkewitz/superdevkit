/*
 * The end of a game in dampf's emulator: the ends of end_positions.h in globals, which SchachTest
 * compares with the same program built by gcc (and with the expected ends).
 */
#include "../src/board.c"
#include "end_positions.h"

uint8_t ends[END_POSITIONS];
uint8_t end_wrong;                      /* how many positions give another end than expected */

void test_main(void)
{
    uint8_t i;

    chess_init();
    for (i = 0; i < END_POSITIONS; i++) {
        ends[i] = end_of(i);
        if (ends[i] != END_EXPECTED[i]) {
            end_wrong++;
        }
    }
}
