/*
 * The end of a game on the PC: every position of end_positions.h gives the end it must.
 *
 *   cc -std=c99 -O2 -Wall -Wextra -fsanitize=undefined,address -idirafter src/main/resources/dampf/include \
 *       -I examples/schach/src examples/schach/test/end_pc.c examples/schach/src/board.c -o build/end
 */
#include <stdio.h>
#include "chess.h"
#include "end_positions.h"

int main(void)
{
    uint8_t i;
    int failed = 0;

    chess_init();
    for (i = 0; i < END_POSITIONS; i++) {
        uint8_t got = end_of(i);
        int ok = got == END_EXPECTED[i];
        printf("%-32s end %u, expected %u %s\n", END_NAME[i], got, END_EXPECTED[i], ok ? "ok" : "WRONG");
        failed |= !ok;
    }
    return failed;
}
