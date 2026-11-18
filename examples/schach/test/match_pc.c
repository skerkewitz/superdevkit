/*
 * Games between two versions of the console's player on the PC, built by match.sh: A and B are
 * two search.c files compiled with their public names prefixed (a_, b_), on one board.c. Each
 * opening (six random half moves from the start, from a seed) is played twice, A with white and
 * A with black. A game ends by the rules or is a draw after 300 half moves.
 *
 *   examples/schach/test/match.sh A/search.c B/search.c [nodes_a nodes_b openings]
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include "chess.h"

#define DECLARE(p) \
    void p##search_clear(void); \
    void p##search_start(uint16_t node_limit, uint8_t max_depth); \
    uint8_t p##search_run(uint16_t steps); \
    extern uint8_t p##best_from, p##best_to, p##best_flag;
DECLARE(a_)
DECLARE(b_)

static uint32_t rnd = 1;

static unsigned next_rnd(unsigned n)
{
    rnd = rnd * 1103515245u + 12345u;
    return (unsigned)((rnd >> 16) % n);
}

/* 1 when A wins, 0 when B wins, 2 for a draw */
static int game(unsigned opening, int a_white, unsigned nodes_a, unsigned nodes_b)
{
    unsigned plies;
    uint8_t end = END_NONE;

    set_start();
    rnd = opening * 2654435761u + 1;
    for (plies = 0; plies < 6; plies++) {
        unsigned k;
        list_legal();
        k = next_rnd(legal_n);
        play_move(legal_from[k], legal_to[k], legal_flag[k]);
    }
    a_search_clear();
    b_search_clear();
    for (plies = 0; plies < 300; plies++) {
        int a_moves = (side == WHITE) == (a_white != 0);
        uint8_t from, to, flag;
        list_legal();
        end = game_end();
        if (end != END_NONE) {
            break;
        }
        if (a_moves) {
            a_search_start((uint16_t)nodes_a, 30);
            while (!a_search_run(100)) {
            }
            from = a_best_from, to = a_best_to, flag = a_best_flag;
        } else {
            b_search_start((uint16_t)nodes_b, 30);
            while (!b_search_run(100)) {
            }
            from = b_best_from, to = b_best_to, flag = b_best_flag;
        }
        play_move(from, to, flag);
    }
    if (end != END_MATE) {
        return 2;
    }
    /* mate: the side to move lost */
    return ((side == WHITE) == (a_white != 0)) ? 0 : 1;
}

int main(int argc, char **argv)
{
    unsigned nodes_a = argc > 1 ? (unsigned)atoi(argv[1]) : 1500;
    unsigned nodes_b = argc > 2 ? (unsigned)atoi(argv[2]) : 1500;
    unsigned openings = argc > 3 ? (unsigned)atoi(argv[3]) : 50;
    unsigned o, wins = 0, losses = 0, draws = 0;
    double score, elo;

    chess_init();
    for (o = 0; o < openings; o++) {
        int colour;
        for (colour = 0; colour < 2; colour++) {
            int r = game(o, colour == 0, nodes_a, nodes_b);
            if (r == 1) {
                wins++;
            } else if (r == 0) {
                losses++;
            } else {
                draws++;
            }
        }
    }
    score = (wins + 0.5 * draws) / (wins + losses + draws);
    elo = score <= 0.0 ? -999 : score >= 1.0 ? 999 : -400.0 * log10(1.0 / score - 1.0);
    printf("A %u nodes against B %u nodes: +%u -%u =%u, %.1f %%, %+.0f Elo\n", nodes_a, nodes_b, wins, losses, draws,
           100.0 * score, elo);
    return 0;
}
