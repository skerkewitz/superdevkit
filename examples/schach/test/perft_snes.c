/*
 * Perft in dampf's emulator: test_main runs the positions of perft_positions.h to small depths
 * and leaves the counts in globals, which SchachTest compares with the same program built by gcc
 * (and with the known counts). The depths are what the emulator does in seconds; the PC goes
 * deeper (perft_pc.c).
 *
 * One file, so that the test can hand it to gcc and dampf alike: it includes board.c.
 */
#include "../src/board.c"
#include "perft_positions.h"

static const uint8_t SNES_DEPTH[PERFT_POSITIONS] = { 3, 2, 3, 2, 2 };

uint32_t counts[PERFT_POSITIONS];
uint8_t wrong;                          /* the positions whose count is not the known one, a bit each */
uint32_t end_hash[PERFT_POSITIONS];     /* the hash after perft: the board was given back */

void test_main(void)
{
    uint8_t i;

    chess_init();
    for (i = 0; i < PERFT_POSITIONS; i++) {
        set_fen(PERFT_FEN[i]);
        counts[i] = perft(SNES_DEPTH[i]);
        end_hash[i] = hash;
        if (counts[i] != PERFT_COUNT[i][SNES_DEPTH[i] - 1]) {
            wrong |= (uint8_t)(1 << i);
        }
    }
}
