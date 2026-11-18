/*
 * Plays a game on the PC as the SNES plays it, for the check of a whole game (check.txt): black
 * is the console (search.c, book.c, the settings of schach.h, one search_run(STEPS) a tick, at
 * least MIN_THINK ticks), white another copy of the player (search.c with its names prefixed by
 * w_, its own table) at a node limit of its own. Prints one line per half move:
 *   white e2e4               black e7e5 183     (the ticks the console's move took)
 * and at the end "end N" (chess.h: END_MATE ...). Built and run by make_check.py.
 *
 *   game_pc white_nodes seed max_moves
 */
#include <stdio.h>
#include <stdlib.h>
#include "search.h"
#include "book.h"
#include "schach.h"

void w_search_clear(void);
void w_search_start(uint16_t node_limit, uint8_t max_depth);
uint8_t w_search_run(uint16_t steps);
extern uint8_t w_best_from, w_best_to, w_best_flag;

static void uci(uint8_t from, uint8_t to, uint8_t flag)
{
    printf("%c%c%c%c%s", 'a' + FILE_OF(from), '1' + RANK_OF(from), 'a' + FILE_OF(to), '1' + RANK_OF(to),
           flag >= MF_PROMO_N ? (flag == MF_PROMO_N ? "n" : flag == MF_PROMO_B ? "b" : flag == MF_PROMO_R ? "r" : "q") : "");
}

int main(int argc, char **argv)
{
    unsigned white_nodes = argc > 1 ? (unsigned)atoi(argv[1]) : 300;
    uint16_t seed = (uint16_t)(argc > 2 ? atoi(argv[2]) : 0);
    unsigned max_moves = argc > 3 ? (unsigned)atoi(argv[3]) : 200, moves = 0;
    uint8_t end;

    chess_init();
    book_init();
    set_start();
    search_clear();
    w_search_clear();
    book_seed(seed);
    for (;;) {
        list_legal();
        end = game_end();
        if (end != END_NONE || moves >= max_moves) {
            break;
        }
        if (side == WHITE) {
            w_search_start((uint16_t)white_nodes, 30);
            while (!w_search_run(100)) {
            }
            printf("white ");
            uci(w_best_from, w_best_to, w_best_flag);
            printf("\n");
            play_move(w_best_from, w_best_to, w_best_flag);
        } else {
            unsigned ticks = 0;
            uint8_t from, to, flag;
            if (book_find()) {
                from = book_from, to = book_to, flag = book_flag;
                ticks = MIN_THINK;
            } else {
                search_start(NODE_LIMIT, MAX_DEPTH);
                do {
                    ticks++;
                } while (!search_run(STEPS));
                if (ticks < MIN_THINK) {
                    ticks = MIN_THINK;
                }
                from = best_from, to = best_to, flag = best_flag;
            }
            printf("black ");
            uci(from, to, flag);
            printf(" %u\n", ticks);
            play_move(from, to, flag);
            moves++;
        }
    }
    printf("end %u\n", end);
    return 0;
}
