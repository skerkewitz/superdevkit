/*
 * The perft positions and their counts, from the Chessprogramming wiki ("Perft Results"):
 * the start, "Kiwipete" (castling, en passant, promotions, pins), position 3 (en passant that
 * uncovers a check on the rank, rook endgame), position 4 (promotions with captures, castling
 * rights lost by captures; black to move in its mirror), position 5 (a promotion that gives
 * check, castling).
 */
#define PERFT_POSITIONS 5
#define PERFT_DEPTHS 5

static const char *const PERFT_NAME[PERFT_POSITIONS] = { "start", "kiwipete", "pos3", "pos4", "pos5" };

static const char *const PERFT_FEN[PERFT_POSITIONS] = {
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
    "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
    "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
};

static const uint32_t PERFT_COUNT[PERFT_POSITIONS][PERFT_DEPTHS] = {
    { 20, 400, 8902, 197281, 4865609 },
    { 48, 2039, 97862, 4085603, 193690690 },
    { 14, 191, 2812, 43238, 674624 },
    { 6, 264, 9467, 422333, 15833292 },
    { 44, 1486, 62379, 2103487, 89941194 },
};
