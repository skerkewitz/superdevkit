/*
 * The console's player on the PC: positions with one right move (mates, a piece to win, a piece
 * not to take), then games of the engine against itself to the end, every move checked to be
 * legal and the board given back after each search.
 *
 *   cc -std=c99 -O2 -Wall -Wextra -fsanitize=undefined,address -I examples/schach/src \
 *       examples/schach/test/search_pc.c examples/schach/src/board.c examples/schach/src/search.c \
 *       -o build/search && build/search [nodes]
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "search.h"
#include "search_positions.h"

static uint16_t limit = 4000;

/* The engine's move for the position, the search run in pieces of 50 steps as the game does. */
static void think(void)
{
    search_start(limit, 30);
    while (!search_run(50)) {
    }
}

static void name(uint8_t from, uint8_t to, uint8_t flag, char *out)
{
    out[0] = (char)('a' + FILE_OF(from));
    out[1] = (char)('1' + RANK_OF(from));
    out[2] = (char)('a' + FILE_OF(to));
    out[3] = (char)('1' + RANK_OF(to));
    out[4] = flag >= MF_PROMO_N ? "nbrq"[flag - MF_PROMO_N] : 0;
    out[5] = 0;
}

static int positions(void)
{
    int failed = 0;
    unsigned i;

    for (i = 0; i < SEARCH_POSITIONS; i++) {
        char got[6];
        int ok;
        set_fen(SEARCH_FEN[i]);
        search_clear();
        think();
        name(best_from, best_to, best_flag, got);
        ok = SEARCH_AVOID[i] ? strcmp(got, SEARCH_MOVE[i]) != 0 : strcmp(got, SEARCH_MOVE[i]) == 0;
        printf("%-28s %s%-5s got %-5s score %6d depth %2u nodes %5u %s\n", SEARCH_NAME[i], SEARCH_AVOID[i] ? "not " : "",
               SEARCH_MOVE[i], got, best_score, best_depth, nodes, ok ? "ok" : "WRONG");
        if (!ok || sp != 0 || mv_count != 0) {
            failed = 1;
        }
    }
    return failed;
}

/* Engine against engine from the start; the first moves are taken from the seed so that the
 * games differ. */
static int selfplay(uint32_t seed, int verbose)
{
    unsigned plies = 0;
    uint8_t end;
    uint32_t total = 0;

    set_start();
    search_clear();
    for (;;) {
        uint8_t k, found = 0;
        list_legal();
        end = game_end();
        if (end != END_NONE || plies >= 400) {
            break;
        }
        if (plies < 2) {
            seed = seed * 1103515245u + 12345u;
            k = (uint8_t)((seed >> 16) % legal_n);
            best_from = legal_from[k];
            best_to = legal_to[k];
            best_flag = legal_flag[k];
        } else {
            uint32_t before = chess_hash();
            think();
            total += nodes;
            if (chess_hash() != before || sp != 0 || mv_count != 0) {
                printf("the board was not given back after a search\n");
                return 1;
            }
            list_legal();
        }
        for (k = 0; k < legal_n; k++) {
            if (legal_from[k] == best_from && legal_to[k] == best_to && legal_flag[k] == best_flag) {
                found = 1;
            }
        }
        if (!found) {
            printf("an illegal move after %u plies\n", plies);
            return 1;
        }
        if (verbose) {
            char t[10];
            move_text(best_from, best_to, best_flag, t);
            printf("%s%s ", side == WHITE ? "" : "", t);
        }
        play_move(best_from, best_to, best_flag);
        plies++;
    }
    printf("%sgame %u: %u plies, end %u, %u nodes per move\n", verbose ? "\n" : "", (unsigned)seed % 1000, plies, end,
           plies > 2 ? (unsigned)(total / (plies - 2)) : 0);
    return 0;
}

int main(int argc, char **argv)
{
    int failed, g, games = 4;

    if (argc >= 2) {
        limit = (uint16_t)atoi(argv[1]);
    }
    if (argc >= 3) {
        games = atoi(argv[2]);
    }
    chess_init();
    failed = positions();
    for (g = 0; g < games && !failed; g++) {
        failed = selfplay((uint32_t)(g * 7919 + 1), g == 0);
    }
    return failed;
}
