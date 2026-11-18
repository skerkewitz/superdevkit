/*
 * The console against Stockfish at a limited strength (UCI_LimitStrength, UCI_Elo), on the PC:
 * the console as in the game (the book with a seed per game, then the search with the node limit of
 * the default level, schach.h, or the one given), Stockfish through its UCI protocol over a pipe, the rules by board.c. The colours
 * alternate. Stockfish's limited levels are not human Elo; the result is a rough figure.
 *
 *   vs_stockfish path/to/stockfish elo games [movetime_ms [nodes]]
 * Built and run by vs_stockfish.sh. POSIX only (fork, pipes).
 */
#define _POSIX_C_SOURCE 200809L
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "search.h"
#include "book.h"
#include "schach.h"

static FILE *to_sf, *from_sf;

static void send(const char *s)
{
    fprintf(to_sf, "%s\n", s);
    fflush(to_sf);
}

static void wait_for(const char *prefix, char *line, size_t size)
{
    while (fgets(line, (int)size, from_sf) != NULL) {
        if (strncmp(line, prefix, strlen(prefix)) == 0) {
            return;
        }
    }
    fprintf(stderr, "vs_stockfish: Stockfish stopped\n");
    exit(1);
}

static void start(const char *path)
{
    int in[2], out[2];
    pid_t pid;

    if (pipe(in) != 0 || pipe(out) != 0) {
        exit(1);
    }
    pid = fork();
    if (pid == 0) {
        dup2(in[0], 0);
        dup2(out[1], 1);
        close(in[1]);
        close(out[0]);
        execl(path, path, (char *)NULL);
        _exit(127);
    }
    close(in[0]);
    close(out[1]);
    to_sf = fdopen(in[1], "w");
    from_sf = fdopen(out[0], "r");
}

static void uci(uint8_t from, uint8_t to, uint8_t flag, char *out)
{
    out[0] = (char)('a' + FILE_OF(from));
    out[1] = (char)('1' + RANK_OF(from));
    out[2] = (char)('a' + FILE_OF(to));
    out[3] = (char)('1' + RANK_OF(to));
    out[4] = flag >= MF_PROMO_N ? "nbrq"[flag - MF_PROMO_N] : 0;
    out[5] = 0;
}

/* 1 the console won, 0 lost, 2 draw */
static int game(unsigned g, int console_white, unsigned movetime, unsigned nodes_limit)
{
    static char moves[8192];
    char line[4096], cmd[8400];
    unsigned plies = 0;
    uint8_t end = END_NONE;

    moves[0] = 0;
    set_start();
    search_clear();
    book_seed((uint16_t)(g * 7919 + 1));
    send("ucinewgame");
    for (;;) {
        uint8_t from = 0, to = 0, flag = 0, k, found = 0;
        char m[6];
        list_legal();
        end = game_end();
        if (end != END_NONE || plies >= 400) {
            break;
        }
        if ((side == WHITE) == (console_white != 0)) {
            if (book_find()) {
                from = book_from, to = book_to, flag = book_flag;
            } else {
                search_start((uint16_t)nodes_limit, MAX_DEPTH);
                while (!search_run(STEPS)) {
                }
                from = best_from, to = best_to, flag = best_flag;
            }
            list_legal();
        } else {
            snprintf(cmd, sizeof cmd, "position startpos%s%s", moves[0] ? " moves" : "", moves);
            send(cmd);
            snprintf(cmd, sizeof cmd, "go movetime %u", movetime);
            send(cmd);
            wait_for("bestmove", line, sizeof line);
            for (k = 0; k < legal_n; k++) {
                uci(legal_from[k], legal_to[k], legal_flag[k], m);
                if (strncmp(line + 9, m, strlen(m)) == 0 && (line[9 + strlen(m)] == ' ' || line[9 + strlen(m)] == '\n')) {
                    from = legal_from[k], to = legal_to[k], flag = legal_flag[k];
                    found = 1;
                }
            }
            if (!found) {
                fprintf(stderr, "vs_stockfish: Stockfish's move %s is not legal here\n", line);
                exit(1);
            }
        }
        uci(from, to, flag, m);
        strcat(moves, " ");
        strcat(moves, m);
        play_move(from, to, flag);
        plies++;
    }
    if (end != END_MATE) {
        return 2;
    }
    return ((side == WHITE) == (console_white != 0)) ? 0 : 1;   /* the side to move is mated */
}

int main(int argc, char **argv)
{
    char line[4096], cmd[256];
    unsigned elo, games, movetime, nodes_limit, g, won = 0, lost = 0, drawn = 0;
    double score, diff;

    if (argc < 4) {
        fprintf(stderr, "usage: vs_stockfish stockfish elo games [movetime_ms [nodes]]\n");
        return 2;
    }
    elo = (unsigned)atoi(argv[2]);
    games = (unsigned)atoi(argv[3]);
    movetime = argc > 4 ? (unsigned)atoi(argv[4]) : 100;
    nodes_limit = argc > 5 ? (unsigned)atoi(argv[5]) : NODES_NORMAL;
    chess_init();
    book_init();
    start(argv[1]);
    send("uci");
    wait_for("uciok", line, sizeof line);
    send("setoption name Threads value 1");
    send("setoption name UCI_LimitStrength value true");
    snprintf(cmd, sizeof cmd, "setoption name UCI_Elo value %u", elo);
    send(cmd);
    send("isready");
    wait_for("readyok", line, sizeof line);
    for (g = 0; g < games; g++) {
        int r = game(g, (g & 1) == 0, movetime, nodes_limit);
        if (r == 1) {
            won++;
        } else if (r == 0) {
            lost++;
        } else {
            drawn++;
        }
        fprintf(stderr, "\rgame %u: +%u -%u =%u", g + 1, won, lost, drawn);
    }
    fprintf(stderr, "\n");
    send("quit");
    score = (won + 0.5 * drawn) / games;
    diff = score <= 0.0 ? -800 : score >= 1.0 ? 800 : -400.0 * log10(1.0 / score - 1.0);
    printf("console (%u nodes) against Stockfish UCI_Elo %u (movetime %u ms): +%u -%u =%u, %.1f %%, "
           "about %.0f Elo\n", nodes_limit, elo, movetime, won, lost, drawn, 100.0 * score, elo + diff);
    return 0;
}
