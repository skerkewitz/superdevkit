/*
 * Positions with one right move for the console (or one move it must not play), for both
 * colours: the console plays black in the game, the tests also look at white.
 */
#define SEARCH_POSITIONS 10

static const char *const SEARCH_NAME[SEARCH_POSITIONS] = {
    "mate in one (white)", "mate in one (black)", "mate in one, back rank", "mate in two (white)",
    "mate in two (black)", "win the hanging queen", "win the hanging rook", "do not take the pawn",
    "do not take the knight", "promote",
};

static const char *const SEARCH_FEN[SEARCH_POSITIONS] = {
    "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
    "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2",
    "r5k1/5ppp/8/8/8/8/5PPP/6K1 b - - 0 1",
    "kbK5/pp6/1P6/8/8/8/8/R7 w - - 0 1",
    "r7/8/8/8/8/1p6/PP6/KBk5 b - - 0 1",
    "4k3/8/5n2/8/4Q3/8/8/4K3 b - - 0 1",
    "4k3/8/8/8/8/2n5/8/3R3K b - - 0 1",
    "3qk3/8/8/8/3P4/2P5/8/4K3 b - - 0 1",
    "4k3/3p4/4p3/3n4/8/8/8/3QK3 w - - 0 1",
    "8/1P6/8/8/8/8/5k2/7K w - - 0 1",
};

/* The move, from and to square (and the piece of a promotion). */
static const char *const SEARCH_MOVE[SEARCH_POSITIONS] = {
    "h5f7", "d8h4", "a8a1", "a1a6", "a8a3", "f6e4", "c3d1", "d8d4", "d1d5", "b7b8q",
};

/* 1: the move must not be the one named */
static const uint8_t SEARCH_AVOID[SEARCH_POSITIONS] = { 0, 0, 0, 0, 0, 0, 0, 1, 1, 0 };
