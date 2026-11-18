/*
 * The console's player in dampf's emulator: searches with a node limit from a few positions
 * (the start, a middle game, the tactical positions of search_positions.h) and leaves the moves,
 * scores and node counts in globals, which SchachTest compares with the same program built by
 * gcc. Its cycles per node are the measure of how far the console can see on the SNES.
 *
 * One file, so that the test can hand it to gcc and dampf alike.
 */
#include "../src/board.c"
#include "../src/search.c"
#include "search_positions.h"

#define RUNS 4
static const char *const RUN_FEN[RUNS] = {
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 5 4",
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "4k3/8/5n2/8/4Q3/8/8/4K3 b - - 0 1",
};

uint8_t move_from[RUNS], move_to[RUNS], move_flag[RUNS];
int16_t scores[RUNS];
uint8_t depths[RUNS];
uint16_t node_counts[RUNS];
uint32_t total_nodes;
uint8_t given_back;                     /* 1 when every search left the board as it found it */

void test_main(void)
{
    uint8_t i;

    chess_init();
    given_back = 1;
    for (i = 0; i < RUNS; i++) {
        uint32_t before;
        set_fen(RUN_FEN[i]);
        before = hash;
        search_clear();
        search_start(600, 30);
        while (!search_run(40)) {
        }
        move_from[i] = best_from;
        move_to[i] = best_to;
        move_flag[i] = best_flag;
        scores[i] = best_score;
        depths[i] = best_depth;
        node_counts[i] = nodes;
        total_nodes += nodes;
        if (hash != before || sp != 0 || mv_count != 0) {
            given_back = 0;
        }
    }
}
