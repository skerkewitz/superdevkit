/*
 * Perft on the PC: the rules of board.c against the known counts of five standard positions
 * (the Chessprogramming wiki, "Perft Results"), which together hold every special case.
 *
 *   cc -std=c99 -O2 -Wall -Wextra -fsanitize=undefined,address -I examples/schach/src \
 *       examples/schach/test/perft_pc.c examples/schach/src/board.c -o build/perft && build/perft 4
 *   build/perft divide 3 "<fen>"     the count per first move, to find a wrong move
 *
 * Not part of the game: a main() with stdio, for gcc only. test/perft_snes.c runs the same
 * positions in dampf's emulator.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "chess.h"
#include "perft_positions.h"

static void square_name(uint8_t sq, char *out)
{
    out[0] = (char)('a' + FILE_OF(sq));
    out[1] = (char)('1' + RANK_OF(sq));
    out[2] = 0;
}

int main(int argc, char **argv)
{
    int depth_max = 4, failed = 0;
    unsigned i;

    chess_init();
    if (argc >= 4 && strcmp(argv[1], "divide") == 0) {
        uint32_t total;
        uint8_t k;
        if (!set_fen(argv[3])) {
            printf("bad FEN\n");
            return 2;
        }
        total = perft((uint8_t)atoi(argv[2]));
        for (k = 0; k < divide_n; k++) {
            char a[3], b[3];
            square_name(divide_from[k], a);
            square_name(divide_to[k], b);
            printf("%s%s%c %u\n", a, b, divide_flag[k] >= MF_PROMO_N ? "nbrq"[divide_flag[k] - MF_PROMO_N] : ' ', (unsigned)divide[k]);
        }
        printf("total %u\n", (unsigned)total);
        return 0;
    }
    if (argc >= 2) {
        depth_max = atoi(argv[1]);
    }
    for (i = 0; i < PERFT_POSITIONS; i++) {
        int d;
        if (!set_fen(PERFT_FEN[i])) {
            printf("%s: bad FEN\n", PERFT_NAME[i]);
            return 2;
        }
        for (d = 1; d <= depth_max && d <= PERFT_DEPTHS; d++) {
            uint32_t got = perft((uint8_t)d);
            int ok = got == PERFT_COUNT[i][d - 1];
            printf("%-9s depth %d: %9u %s\n", PERFT_NAME[i], d, (unsigned)got, ok ? "ok" : "WRONG");
            if (!ok) {
                printf("          expected %u\n", (unsigned)PERFT_COUNT[i][d - 1]);
                failed = 1;
            }
            if (sp != 0 || mv_count != 0 || hist_len != 0) {
                printf("          the board was not given back (sp %u, moves %u)\n", sp, mv_count);
                failed = 1;
            }
        }
    }
    return failed;
}
