/*
 * The end of a game: positions (and moves played from them, from-to) with the end they must give
 * (chess.h: END_NONE, END_MATE ...), for each way a game ends and for cases that are not an end.
 */
#define END_POSITIONS 18

static const char *const END_NAME[END_POSITIONS] = {
    "mate", "stalemate", "check, not mate", "50 moves", "49 and a half moves", "mate on the 100th half move",
    "threefold repetition", "twice is not enough", "king against king", "king and knight against king",
    "king and bishop against king", "bishops all on one colour", "bishops on both colours",
    "two knights can mate", "bishop against knight", "a pawn is enough", "a rook is enough",
    "castling rights differ",
};

static const char *const END_FEN[END_POSITIONS] = {
    "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3",
    "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1",
    "4k3/8/8/8/8/8/8/R3K3 b - - 0 1",
    "8/8/8/4k3/8/8/4K3/4R3 w - - 100 80",
    "8/8/8/4k3/8/8/4K3/4R3 w - - 99 80",
    "R5k1/5ppp/8/8/8/8/8/6K1 b - - 100 80",
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "8/8/8/4k3/8/8/4K3/8 w - - 0 1",
    "8/8/8/4k3/8/8/4K3/4N3 w - - 0 1",
    "8/8/8/4k3/8/8/4K3/4B3 b - - 0 1",
    "5b2/8/8/4k3/8/8/4K3/2B1B3 w - - 0 1",
    "2b5/8/8/4k3/8/8/4K3/2B5 w - - 0 1",
    "8/8/8/4k3/8/8/4K3/3NN3 w - - 0 1",
    "8/8/8/4k3/2n5/8/4K3/2B5 w - - 0 1",
    "8/8/8/4k3/8/8/4KP2/8 w - - 0 1",
    "8/8/8/4k3/8/8/4K3/7R w - - 0 1",
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
};

/* Moves played from the position, from and to squares, separated by spaces. */
static const char *const END_MOVES[END_POSITIONS] = {
    "", "", "", "", "", "",
    "g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 f3g1 f6g8",
    "g1f3 g8f6 f3g1 f6g8",
    "", "", "", "", "", "", "", "", "",
    "g1f3 g8f6 h1g1 h8g8 g1h1 g8h8 f3g1 f6g8 g1f3 g8f6 f3g1 f6g8",
};

static const uint8_t END_EXPECTED[END_POSITIONS] = {
    END_MATE, END_STALEMATE, END_NONE, END_FIFTY, END_NONE, END_MATE,
    END_REPETITION, END_NONE, END_MATERIAL, END_MATERIAL,
    END_MATERIAL, END_MATERIAL, END_NONE,
    END_NONE, END_NONE, END_NONE, END_NONE,
    END_NONE,
};

/* Plays END_MOVES[i] from END_FEN[i] and returns the end; 0xFF when a move is not legal. */
static uint8_t end_of(uint8_t i)
{
    const char *m = END_MOVES[i];

    set_fen(END_FEN[i]);
    while (*m != 0) {
        uint8_t from = SQ((uint8_t)(m[0] - 'a'), (uint8_t)(m[1] - '1'));
        uint8_t to = SQ((uint8_t)(m[2] - 'a'), (uint8_t)(m[3] - '1'));
        uint8_t k, found = 0xFF;
        list_legal();
        for (k = 0; k < legal_n; k++) {
            if (legal_from[k] == from && legal_to[k] == to) {
                found = k;
            }
        }
        if (found == 0xFF) {
            return 0xFF;
        }
        play_move(from, to, legal_flag[found]);
        m += 4;
        while (*m == ' ') {
            m++;
        }
    }
    list_legal();
    return game_end();
}
