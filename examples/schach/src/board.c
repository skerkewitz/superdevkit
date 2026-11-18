/*
 * Schach: the board and the rules. See chess.h for the representation.
 *
 * make_move keeps the hash, the material with the piece squares (psq) and the phase up to date
 * and saves what it changes in the undo arrays; unmake_move takes it back from there. A move is
 * legal when the own king is not attacked after it (left_in_check), which covers pins, checks,
 * double check and the en passant capture that uncovers the king. Castling is checked when it
 * is generated: not out of check, not through an attacked square.
 */
#include "chess.h"

uint8_t board[128];
uint8_t plist[32];
uint8_t pidx[128];
uint8_t side, castle, ep, half;
uint16_t fullmove;
uint32_t hash;
int16_t psq;
uint8_t phase;

uint8_t mv_from[MOVES_MAX], mv_to[MOVES_MAX], mv_flag[MOVES_MAX];
int16_t mv_score[MOVES_MAX];
uint16_t mv_count;

uint8_t sp;
static uint8_t u_cap[UNDO_MAX], u_capidx[UNDO_MAX], u_castle[UNDO_MAX], u_ep[UNDO_MAX], u_half[UNDO_MAX];
static uint8_t u_from[UNDO_MAX], u_to[UNDO_MAX], u_flag[UNDO_MAX], u_phase[UNDO_MAX];
static int16_t u_psq[UNDO_MAX];

uint32_t hist[HIST_MAX];
uint8_t hist_len;

int16_t psq_val[16][128];
static uint32_t zob[16][128];
static uint32_t zob_castle[16];
static uint32_t zob_ep[8];
static uint32_t zob_side;

/* --- tables ---------------------------------------------------------------------------------- */
static const int8_t KNIGHT_STEPS[8] = { 33, 31, 18, 14, -14, -18, -31, -33 };
static const int8_t KING_STEPS[8] = { 17, 16, 15, 1, -1, -15, -16, -17 };
/* bishop: the first four, rook: the last four, queen and king: all eight */
static const int8_t DIRS[8] = { 17, 15, -15, -17, 16, 1, -1, -16 };

/* The castling rights a move from or to a square keeps. */
static uint8_t castle_keep[128];

/* RAY[a - b + 119]: the step of the line from b to a when the two squares share a rank, file or
 * diagonal, else 0. */
static int8_t RAY[239];

static const int16_t VALUE[8] = { 0, 100, 320, 330, 500, 900, 0, 0 };
const uint8_t PHASE_OF[8] = { 0, 0, 1, 1, 2, 4, 0, 0 };

/* Piece squares from white's view, written as the board is seen: a8 first, h1 last (the
 * simplified evaluation function of Tomasz Michniewski). */
static const int8_t PST[6][64] = {
    {  0,  0,  0,  0,  0,  0,  0,  0,
      50, 50, 50, 50, 50, 50, 50, 50,
      10, 10, 20, 30, 30, 20, 10, 10,
       5,  5, 10, 25, 25, 10,  5,  5,
       0,  0,  0, 20, 20,  0,  0,  0,
       5, -5,-10,  0,  0,-10, -5,  5,
       5, 10, 10,-20,-20, 10, 10,  5,
       0,  0,  0,  0,  0,  0,  0,  0 },
    {-50,-40,-30,-30,-30,-30,-40,-50,
     -40,-20,  0,  0,  0,  0,-20,-40,
     -30,  0, 10, 15, 15, 10,  0,-30,
     -30,  5, 15, 20, 20, 15,  5,-30,
     -30,  0, 15, 20, 20, 15,  0,-30,
     -30,  5, 10, 15, 15, 10,  5,-30,
     -40,-20,  0,  5,  5,  0,-20,-40,
     -50,-40,-30,-30,-30,-30,-40,-50 },
    {-20,-10,-10,-10,-10,-10,-10,-20,
     -10,  0,  0,  0,  0,  0,  0,-10,
     -10,  0,  5, 10, 10,  5,  0,-10,
     -10,  5,  5, 10, 10,  5,  5,-10,
     -10,  0, 10, 10, 10, 10,  0,-10,
     -10, 10, 10, 10, 10, 10, 10,-10,
     -10,  5,  0,  0,  0,  0,  5,-10,
     -20,-10,-10,-10,-10,-10,-10,-20 },
    {  0,  0,  0,  0,  0,  0,  0,  0,
       5, 10, 10, 10, 10, 10, 10,  5,
      -5,  0,  0,  0,  0,  0,  0, -5,
      -5,  0,  0,  0,  0,  0,  0, -5,
      -5,  0,  0,  0,  0,  0,  0, -5,
      -5,  0,  0,  0,  0,  0,  0, -5,
      -5,  0,  0,  0,  0,  0,  0, -5,
       0,  0,  0,  5,  5,  0,  0,  0 },
    {-20,-10,-10, -5, -5,-10,-10,-20,
     -10,  0,  0,  0,  0,  0,  0,-10,
     -10,  0,  5,  5,  5,  5,  0,-10,
      -5,  0,  5,  5,  5,  5,  0, -5,
       0,  0,  5,  5,  5,  5,  0, -5,
     -10,  5,  5,  5,  5,  5,  0,-10,
     -10,  0,  5,  0,  0,  0,  0,-10,
     -20,-10,-10, -5, -5,-10,-10,-20 },
    {  0 }                              /* the king: KING_MG and KING_EG, in evaluate() */
};

const int16_t KING_MG[64] = {
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -20,-30,-30,-40,-40,-30,-30,-20,
    -10,-20,-20,-20,-20,-20,-20,-10,
     20, 20,  0,  0,  0,  0, 20, 20,
     20, 30, 10,  0,  0, 10, 30, 20
};
const int16_t KING_EG[64] = {
    -50,-40,-30,-20,-20,-30,-40,-50,
    -30,-20,-10,  0,  0,-10,-20,-30,
    -30,-10, 20, 30, 30, 20,-10,-30,
    -30,-10, 30, 40, 40, 30,-10,-30,
    -30,-10, 30, 40, 40, 30,-10,-30,
    -30,-10, 20, 30, 30, 20,-10,-30,
    -30,-30,  0,  0,  0,  0,-30,-30,
    -50,-30,-30,-30,-30,-30,-30,-50
};

static uint32_t rnd_state;

static uint32_t rnd32(void)
{
    uint32_t x = rnd_state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    rnd_state = x;
    return x;
}

void chess_init(void)
{
    uint8_t p, sq, i;

    rnd_state = 0x9E3779B9u;
    for (p = 0; p < 16; p++) {
        for (sq = 0; sq < 128; sq++) {
            zob[p][sq] = 0;
            psq_val[p][sq] = 0;
            if (!OFF(sq) && TYPE(p) != EMPTY && TYPE(p) != 7) {
                uint8_t f = FILE_OF(sq), r = RANK_OF(sq);
                zob[p][sq] = rnd32();
                if (TYPE(p) != KING) {
                    if (COLOR(p) == WHITE) {
                        psq_val[p][sq] = (int16_t)(VALUE[TYPE(p)] + PST[TYPE(p) - 1][(7 - r) * 8 + f]);
                    } else {
                        psq_val[p][sq] = (int16_t)-(VALUE[TYPE(p)] + PST[TYPE(p) - 1][r * 8 + f]);
                    }
                }
            }
        }
    }
    for (i = 0; i < 16; i++) {
        zob_castle[i] = i == 0 ? 0 : rnd32();
    }
    for (i = 0; i < 8; i++) {
        zob_ep[i] = rnd32();
    }
    zob_side = rnd32();
    for (sq = 0; sq < 128; sq++) {
        castle_keep[sq] = 15;
    }
    for (i = 0; i < 8; i++) {
        int8_t d = DIRS[i];
        int16_t delta = d;
        uint8_t k;
        for (k = 0; k < 7; k++) {
            RAY[delta + 119] = d;
            delta = (int16_t)(delta + d);
        }
    }
    castle_keep[SQ(4, 0)] = (uint8_t)(15 & ~(CASTLE_WK | CASTLE_WQ));
    castle_keep[SQ(7, 0)] = (uint8_t)(15 & ~CASTLE_WK);
    castle_keep[SQ(0, 0)] = (uint8_t)(15 & ~CASTLE_WQ);
    castle_keep[SQ(4, 7)] = (uint8_t)(15 & ~(CASTLE_BK | CASTLE_BQ));
    castle_keep[SQ(7, 7)] = (uint8_t)(15 & ~CASTLE_BK);
    castle_keep[SQ(0, 7)] = (uint8_t)(15 & ~CASTLE_BQ);
}

/* --- setting up ------------------------------------------------------------------------------ */
static uint8_t piece_of_char(char c)
{
    switch (c) {
    case 'P': return WHITE | PAWN;
    case 'N': return WHITE | KNIGHT;
    case 'B': return WHITE | BISHOP;
    case 'R': return WHITE | ROOK;
    case 'Q': return WHITE | QUEEN;
    case 'K': return WHITE | KING;
    case 'p': return BLACK | PAWN;
    case 'n': return BLACK | KNIGHT;
    case 'b': return BLACK | BISHOP;
    case 'r': return BLACK | ROOK;
    case 'q': return BLACK | QUEEN;
    case 'k': return BLACK | KING;
    default: return EMPTY;
    }
}

/* The ep square only when a pawn of the side to move could take there: the hash of a position
 * then does not depend on a double step nobody can answer. */
static uint8_t ep_possible(uint8_t sq, uint8_t by)
{
    uint8_t pawn = (uint8_t)(by | PAWN);
    uint8_t a, b;

    if (by == WHITE) {
        a = (uint8_t)(sq - 15);
        b = (uint8_t)(sq - 17);
    } else {
        a = (uint8_t)(sq + 15);
        b = (uint8_t)(sq + 17);
    }
    return (uint8_t)((!OFF(a) && board[a] == pawn) || (!OFF(b) && board[b] == pawn));
}

/* Hash, psq, phase and the piece lists from the board. 0 when the position is not one (a king
 * missing, more than 16 pieces of one side). */
static uint8_t setup_from_board(void)
{
    uint8_t sq, n[2], c;

    for (sq = 0; sq < 32; sq++) {
        plist[sq] = NO_SQ;
    }
    n[0] = 1;
    n[1] = 1;
    hash = 0;
    psq = 0;
    phase = 0;
    for (sq = 0; sq < 128; sq++) {
        uint8_t p;
        pidx[sq] = 0;
        if (OFF(sq)) {
            continue;
        }
        p = board[sq];
        if (p == EMPTY) {
            continue;
        }
        c = (uint8_t)(COLOR(p) >> 3);
        if (TYPE(p) == KING) {
            if (plist[c * 16] != NO_SQ) {
                return 0;
            }
            plist[c * 16] = sq;
            pidx[sq] = (uint8_t)(c * 16);
        } else {
            if (n[c] == 16) {
                return 0;
            }
            plist[c * 16 + n[c]] = sq;
            pidx[sq] = (uint8_t)(c * 16 + n[c]);
            n[c]++;
        }
        hash ^= zob[p][sq];
        psq = (int16_t)(psq + psq_val[p][sq]);
        phase = (uint8_t)(phase + PHASE_OF[TYPE(p)]);
    }
    if (plist[0] == NO_SQ || plist[16] == NO_SQ) {
        return 0;
    }
    if (ep != NO_SQ && !ep_possible(ep, side)) {
        ep = NO_SQ;
    }
    hash ^= zob_castle[castle];
    if (ep != NO_SQ) {
        hash ^= zob_ep[FILE_OF(ep)];
    }
    if (side == BLACK) {
        hash ^= zob_side;
    }
    sp = 0;
    hist_len = 0;
    mv_count = 0;
    return 1;
}

uint8_t set_fen(const char *fen)
{
    uint8_t file = 0, rank = 7, sq;

    for (sq = 0; sq < 128; sq++) {
        board[sq] = EMPTY;
    }
    while (*fen != 0 && *fen != ' ') {
        char c = *fen++;
        if (c == '/') {
            if (rank == 0) {
                return 0;
            }
            rank--;
            file = 0;
        } else if (c >= '1' && c <= '8') {
            file = (uint8_t)(file + (uint8_t)(c - '0'));
        } else {
            uint8_t p = piece_of_char(c);
            if (p == EMPTY || file > 7) {
                return 0;
            }
            board[SQ(file, rank)] = p;
            file++;
        }
    }
    while (*fen == ' ') {
        fen++;
    }
    side = *fen == 'b' ? BLACK : WHITE;
    if (*fen != 0) {
        fen++;
    }
    while (*fen == ' ') {
        fen++;
    }
    castle = 0;
    while (*fen != 0 && *fen != ' ') {
        switch (*fen) {
        case 'K': castle |= CASTLE_WK; break;
        case 'Q': castle |= CASTLE_WQ; break;
        case 'k': castle |= CASTLE_BK; break;
        case 'q': castle |= CASTLE_BQ; break;
        default: break;
        }
        fen++;
    }
    while (*fen == ' ') {
        fen++;
    }
    ep = NO_SQ;
    if (*fen >= 'a' && *fen <= 'h' && fen[1] >= '1' && fen[1] <= '8') {
        ep = SQ((uint8_t)(*fen - 'a'), (uint8_t)(fen[1] - '1'));
        fen += 2;
    } else if (*fen != 0) {
        fen++;
    }
    while (*fen == ' ') {
        fen++;
    }
    half = 0;
    while (*fen >= '0' && *fen <= '9') {
        half = (uint8_t)(half * 10 + (uint8_t)(*fen - '0'));
        fen++;
    }
    while (*fen == ' ') {
        fen++;
    }
    fullmove = 0;
    while (*fen >= '0' && *fen <= '9') {
        fullmove = (uint16_t)(fullmove * 10 + (uint16_t)(*fen - '0'));
        fen++;
    }
    if (fullmove == 0) {
        fullmove = 1;
    }
    return setup_from_board();
}

void set_start(void)
{
    set_fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
}

/* --- attacks --------------------------------------------------------------------------------- */
uint8_t attacked(uint8_t sq, uint8_t by)
{
    uint8_t i, t, p;
    uint8_t knight = (uint8_t)(by | KNIGHT), king = (uint8_t)(by | KING);
    uint8_t bishop = (uint8_t)(by | BISHOP), rook = (uint8_t)(by | ROOK), queen = (uint8_t)(by | QUEEN);

    /* pawns: a white pawn on s attacks s + 15 and s + 17 */
    if (by == WHITE) {
        t = (uint8_t)(sq - 15);
        if (!OFF(t) && board[t] == (WHITE | PAWN)) {
            return 1;
        }
        t = (uint8_t)(sq - 17);
        if (!OFF(t) && board[t] == (WHITE | PAWN)) {
            return 1;
        }
    } else {
        t = (uint8_t)(sq + 15);
        if (!OFF(t) && board[t] == (BLACK | PAWN)) {
            return 1;
        }
        t = (uint8_t)(sq + 17);
        if (!OFF(t) && board[t] == (BLACK | PAWN)) {
            return 1;
        }
    }
    for (i = 0; i < 8; i++) {
        t = (uint8_t)(sq + KNIGHT_STEPS[i]);
        if (!OFF(t) && board[t] == knight) {
            return 1;
        }
        t = (uint8_t)(sq + KING_STEPS[i]);
        if (!OFF(t) && board[t] == king) {
            return 1;
        }
    }
    for (i = 0; i < 8; i++) {
        int8_t d = DIRS[i];
        uint8_t slider = i < 4 ? bishop : rook;
        t = (uint8_t)(sq + d);
        while (!OFF(t)) {
            p = board[t];
            if (p != EMPTY) {
                if (p == slider || p == queen) {
                    return 1;
                }
                break;
            }
            t = (uint8_t)(t + d);
        }
    }
    return 0;
}

uint8_t in_check(void)
{
    return attacked(KING_SQ(side), (uint8_t)(side ^ BLACK));
}

uint8_t left_in_check(void)
{
    return attacked(KING_SQ(side ^ BLACK), side);
}

/* After make_move(from, ...): whether the side that moved left its king attacked, cheaper than
 * left_in_check() when the side was not in check before. Then only a king move, an en passant
 * capture (two pieces leave a line) and a piece that left a line through its own king can
 * expose the king: the line from the king through the from square is looked at, nothing else. */
uint8_t illegal_after(uint8_t from, uint8_t was_in_check)
{
    uint8_t mover = (uint8_t)(side ^ BLACK);
    uint8_t k = KING_SQ(mover);
    uint8_t s = (uint8_t)(sp - 1);
    int8_t d;
    uint8_t t, p;

    if (was_in_check || k == u_to[s] || u_flag[s] == MF_EP) {
        return attacked(k, side);
    }
    d = RAY[(int16_t)from - (int16_t)k + 119];
    if (d == 0) {
        return 0;
    }
    t = (uint8_t)(k + d);
    while (!OFF(t)) {
        p = board[t];
        if (p != EMPTY) {
            if (COLOR(p) == mover) {
                return 0;
            }
            if (TYPE(p) == QUEEN) {
                return 1;
            }
            if (d == 1 || d == -1 || d == 16 || d == -16) {
                return (uint8_t)(TYPE(p) == ROOK);
            }
            return (uint8_t)(TYPE(p) == BISHOP);
        }
        t = (uint8_t)(t + d);
    }
    return 0;
}

/* --- move generation ------------------------------------------------------------------------- */
static void add(uint8_t from, uint8_t to, uint8_t flag)
{
    uint16_t n = mv_count;
    mv_from[n] = from;
    mv_to[n] = to;
    mv_flag[n] = flag;
    mv_count = (uint16_t)(n + 1);
}

static void add_pawn(uint8_t from, uint8_t to, uint8_t last_rank)
{
    if (RANK_OF(to) == last_rank) {
        add(from, to, MF_PROMO_Q);
        add(from, to, MF_PROMO_N);
        add(from, to, MF_PROMO_R);
        add(from, to, MF_PROMO_B);
    } else {
        add(from, to, MF_NORMAL);
    }
}

void gen_moves(uint8_t captures_only)
{
    uint8_t base = (uint8_t)(side << 1);
    uint8_t enemy = (uint8_t)(side ^ BLACK);
    uint8_t i, j, from, to, p, q, type;
    int8_t fwd = side == WHITE ? 16 : -16;
    uint8_t start_rank = side == WHITE ? 1 : 6;
    uint8_t last_rank = side == WHITE ? 7 : 0;

    for (i = 0; i < 16; i++) {
        from = plist[base + i];
        if (OFF(from)) {
            continue;
        }
        p = board[from];
        type = TYPE(p);
        if (type == PAWN) {
            to = (uint8_t)(from + fwd);
            if (board[to] == EMPTY && (!captures_only || RANK_OF(to) == last_rank)) {
                add_pawn(from, to, last_rank);
                if (RANK_OF(from) == start_rank && !captures_only) {
                    to = (uint8_t)(to + fwd);
                    if (board[to] == EMPTY) {
                        add(from, to, MF_DOUBLE);
                    }
                }
            }
            for (j = 0; j < 2; j++) {
                to = (uint8_t)(from + fwd + (j == 0 ? -1 : 1));
                if (OFF(to)) {
                    continue;
                }
                q = board[to];
                if (q != EMPTY && COLOR(q) == enemy) {
                    add_pawn(from, to, last_rank);
                } else if (to == ep) {
                    add(from, to, MF_EP);
                }
            }
        } else if (type == KNIGHT || type == KING) {
            const int8_t *steps = type == KNIGHT ? KNIGHT_STEPS : KING_STEPS;
            for (j = 0; j < 8; j++) {
                to = (uint8_t)(from + steps[j]);
                if (OFF(to)) {
                    continue;
                }
                q = board[to];
                if (q == EMPTY) {
                    if (!captures_only) {
                        add(from, to, MF_NORMAL);
                    }
                } else if (COLOR(q) == enemy) {
                    add(from, to, MF_NORMAL);
                }
            }
        } else {
            uint8_t first = type == ROOK ? 4 : 0;
            uint8_t last = type == BISHOP ? 4 : 8;
            for (j = first; j < last; j++) {
                int8_t d = DIRS[j];
                to = (uint8_t)(from + d);
                while (!OFF(to)) {
                    q = board[to];
                    if (q == EMPTY) {
                        if (!captures_only) {
                            add(from, to, MF_NORMAL);
                        }
                    } else {
                        if (COLOR(q) == enemy) {
                            add(from, to, MF_NORMAL);
                        }
                        break;
                    }
                    to = (uint8_t)(to + d);
                }
            }
        }
    }
    /* castling: the rights, the squares between empty, the king not in check and not passing an
     * attacked square; whether it lands in check is left to left_in_check() */
    if (!captures_only && (castle & (side == WHITE ? CASTLE_WK | CASTLE_WQ : CASTLE_BK | CASTLE_BQ)) != 0) {
        uint8_t k = side == WHITE ? SQ(4, 0) : SQ(4, 7);
        uint8_t kside = side == WHITE ? CASTLE_WK : CASTLE_BK;
        uint8_t qside = side == WHITE ? CASTLE_WQ : CASTLE_BQ;
        if (!attacked(k, enemy)) {
            if ((castle & kside) && board[k + 1] == EMPTY && board[k + 2] == EMPTY && !attacked((uint8_t)(k + 1), enemy)) {
                add(k, (uint8_t)(k + 2), MF_CASTLE);
            }
            if ((castle & qside) && board[k - 1] == EMPTY && board[k - 2] == EMPTY && board[k - 3] == EMPTY
                && !attacked((uint8_t)(k - 1), enemy)) {
                add(k, (uint8_t)(k - 2), MF_CASTLE);
            }
        }
    }
}

/* --- making and taking back ------------------------------------------------------------------ */
static void move_piece(uint8_t p, uint8_t from, uint8_t to)
{
    uint8_t idx = pidx[from];
    board[to] = p;
    board[from] = EMPTY;
    pidx[to] = idx;
    plist[idx] = to;
    hash ^= zob[p][from] ^ zob[p][to];
    psq = (int16_t)(psq + psq_val[p][to] - psq_val[p][from]);
}

void make_move(uint8_t from, uint8_t to, uint8_t flag)
{
    uint8_t s = sp;
    uint8_t p = board[from];
    uint8_t cap_sq = to, cap;

    u_from[s] = from;
    u_to[s] = to;
    u_flag[s] = flag;
    u_castle[s] = castle;
    u_ep[s] = ep;
    u_half[s] = half;
    u_psq[s] = psq;
    u_phase[s] = phase;
    hist[hist_len] = hash;
    hist_len++;

    if (flag == MF_EP) {
        cap_sq = (uint8_t)((from & 0xF0) | (to & 0x0F));
    }
    cap = board[cap_sq];
    u_cap[s] = cap;
    half++;
    if (cap != EMPTY) {
        uint8_t idx = pidx[cap_sq];
        u_capidx[s] = idx;
        plist[idx] = NO_SQ;
        board[cap_sq] = EMPTY;
        hash ^= zob[cap][cap_sq];
        psq = (int16_t)(psq - psq_val[cap][cap_sq]);
        phase = (uint8_t)(phase - PHASE_OF[TYPE(cap)]);
        half = 0;
    }
    move_piece(p, from, to);
    if (TYPE(p) == PAWN) {
        half = 0;
        if (flag >= MF_PROMO_N) {
            uint8_t np = (uint8_t)(side | PROMO_TYPE(flag));
            board[to] = np;
            hash ^= zob[p][to] ^ zob[np][to];
            psq = (int16_t)(psq + psq_val[np][to] - psq_val[p][to]);
            phase = (uint8_t)(phase + PHASE_OF[TYPE(np)]);
        }
    } else if (flag == MF_CASTLE) {
        uint8_t rook = (uint8_t)(side | ROOK);
        if (to > from) {
            move_piece(rook, (uint8_t)(from + 3), (uint8_t)(from + 1));
        } else {
            move_piece(rook, (uint8_t)(from - 4), (uint8_t)(from - 1));
        }
    }
    {
        uint8_t keep = (uint8_t)(castle & castle_keep[from] & castle_keep[to]);
        if (keep != castle) {
            hash ^= zob_castle[castle] ^ zob_castle[keep];
            castle = keep;
        }
    }
    if (ep != NO_SQ) {
        hash ^= zob_ep[FILE_OF(ep)];
        ep = NO_SQ;
    }
    side ^= BLACK;
    hash ^= zob_side;
    if (flag == MF_DOUBLE) {
        uint8_t e = (uint8_t)((from + to) >> 1);
        if (ep_possible(e, side)) {
            ep = e;
            hash ^= zob_ep[FILE_OF(e)];
        }
    }
    sp = (uint8_t)(s + 1);
}

void unmake_move(void)
{
    uint8_t s = (uint8_t)(sp - 1);
    uint8_t from = u_from[s], to = u_to[s], flag = u_flag[s];
    uint8_t p, idx, cap;

    sp = s;
    hist_len--;
    hash = hist[hist_len];
    side ^= BLACK;
    p = board[to];
    if (flag >= MF_PROMO_N) {
        p = (uint8_t)(side | PAWN);
    }
    idx = pidx[to];
    board[from] = p;
    board[to] = EMPTY;
    pidx[from] = idx;
    plist[idx] = from;
    if (flag == MF_CASTLE) {
        uint8_t rf, rt;
        if (to > from) {
            rf = (uint8_t)(from + 3);
            rt = (uint8_t)(from + 1);
        } else {
            rf = (uint8_t)(from - 4);
            rt = (uint8_t)(from - 1);
        }
        idx = pidx[rt];
        board[rf] = board[rt];
        board[rt] = EMPTY;
        pidx[rf] = idx;
        plist[idx] = rf;
    }
    cap = u_cap[s];
    if (cap != EMPTY) {
        uint8_t cap_sq = to;
        if (flag == MF_EP) {
            cap_sq = (uint8_t)((from & 0xF0) | (to & 0x0F));
        }
        idx = u_capidx[s];
        board[cap_sq] = cap;
        pidx[cap_sq] = idx;
        plist[idx] = cap_sq;
    }
    castle = u_castle[s];
    ep = u_ep[s];
    half = u_half[s];
    psq = u_psq[s];
    phase = u_phase[s];
}

uint8_t repeated(void)
{
    uint8_t i, stop;

    if (half < 4 || hist_len < 4) {
        return 0;
    }
    stop = hist_len > half ? (uint8_t)(hist_len - half) : 0;
    i = (uint8_t)(hist_len - 4);
    for (;;) {
        if (hist[i] == hash) {
            return 1;
        }
        if (i < stop + 2) {
            return 0;
        }
        i = (uint8_t)(i - 2);
    }
}

/* --- perft ----------------------------------------------------------------------------------- */
uint32_t divide[DIVIDE_MAX];
uint8_t divide_from[DIVIDE_MAX], divide_to[DIVIDE_MAX], divide_flag[DIVIDE_MAX], divide_n;

#define PERFT_PLY 8
static uint16_t pf_first[PERFT_PLY], pf_cur[PERFT_PLY];
static uint8_t pf_check[PERFT_PLY];

/* A loop over an explicit stack: one entry per ply, where its moves start and which is next. */
uint32_t perft(uint8_t depth)
{
    uint32_t count = 0, root_before = 0;
    uint8_t ply = 0;
    uint16_t m;

    divide_n = 0;
    if (depth == 0) {
        return 1;
    }
    pf_first[0] = mv_count;
    pf_check[0] = in_check();
    gen_moves(0);
    pf_cur[0] = pf_first[0];
    for (;;) {
        m = pf_cur[ply];
        if (m == mv_count) {             /* this ply's moves are done */
            if (ply == 0) {
                break;
            }
            mv_count = pf_first[ply];
            ply--;
            unmake_move();
            if (ply == 0 && divide_n < DIVIDE_MAX) {
                divide[divide_n] = count - root_before;
                divide_n++;
            }
            continue;
        }
        pf_cur[ply] = (uint16_t)(m + 1);
        make_move(mv_from[m], mv_to[m], mv_flag[m]);
        if (illegal_after(mv_from[m], pf_check[ply])) {
            unmake_move();
            continue;
        }
        if (ply == 0 && divide_n < DIVIDE_MAX) {
            divide_from[divide_n] = mv_from[m];
            divide_to[divide_n] = mv_to[m];
            divide_flag[divide_n] = mv_flag[m];
            root_before = count;
        }
        if (ply + 1 == depth) {
            count++;
            unmake_move();
            if (ply == 0 && divide_n < DIVIDE_MAX) {
                divide[divide_n] = 1;
                divide_n++;
            }
            continue;
        }
        ply++;
        pf_first[ply] = mv_count;
        pf_check[ply] = in_check();
        gen_moves(0);
        pf_cur[ply] = pf_first[ply];
    }
    mv_count = pf_first[0];
    return count;
}

/* --- the game -------------------------------------------------------------------------------- */
uint8_t legal_from[LEGAL_MAX], legal_to[LEGAL_MAX], legal_flag[LEGAL_MAX];
uint8_t legal_n;

void list_legal(void)
{
    uint16_t first = mv_count, m;

    legal_n = 0;
    gen_moves(0);
    for (m = first; m < mv_count; m++) {
        make_move(mv_from[m], mv_to[m], mv_flag[m]);
        if (!left_in_check()) {
            legal_from[legal_n] = mv_from[m];
            legal_to[legal_n] = mv_to[m];
            legal_flag[legal_n] = mv_flag[m];
            legal_n++;
        }
        unmake_move();
    }
    mv_count = first;
}

/* Neither side can mate: king against king, king and one minor piece against king, kings and
 * bishops all on squares of one colour. King and two knights against king is not a draw by the
 * rules (a mate exists, the weaker side has to help), so the game goes on. */
static uint8_t no_mating_material(void)
{
    uint8_t i, sq, t, minors = 0, bishop_colors = 0, knights = 0;

    for (i = 0; i < 32; i++) {
        sq = plist[i];
        if (OFF(sq)) {
            continue;
        }
        t = TYPE(board[sq]);
        if (t == PAWN || t == ROOK || t == QUEEN) {
            return 0;
        }
        if (t == KNIGHT) {
            knights++;
            minors++;
        } else if (t == BISHOP) {
            minors++;
            bishop_colors |= (uint8_t)(((FILE_OF(sq) + RANK_OF(sq)) & 1) + 1);
        }
    }
    if (minors <= 1) {
        return 1;
    }
    return (uint8_t)(knights == 0 && bishop_colors != 3);
}

static uint8_t repetitions(void)
{
    uint8_t i, n = 1;

    for (i = 0; i < hist_len; i++) {
        if (hist[i] == hash && ((hist_len - i) & 1) == 0) {
            n++;
        }
    }
    return n;
}

uint8_t game_end(void)
{
    if (legal_n == 0) {
        return in_check() ? END_MATE : END_STALEMATE;
    }
    if (half >= 100) {
        return END_FIFTY;
    }
    if (repetitions() >= 3) {
        return END_REPETITION;
    }
    if (no_mating_material()) {
        return END_MATERIAL;
    }
    return END_NONE;
}

void play_move(uint8_t from, uint8_t to, uint8_t flag)
{
    make_move(from, to, flag);
    sp = 0;
    if (half == 0) {
        hist_len = 0;                    /* no position before a capture or pawn move comes back */
    }
    if (side == WHITE) {
        fullmove++;
    }
}

static const char PIECE_CHAR[8] = { ' ', 'P', 'N', 'B', 'R', 'Q', 'K', ' ' };

/* Standard algebraic notation (Nf3, exd5, O-O, e8=Q+, Qxf7#), written before the move is
 * played; list_legal() must hold the position's legal moves. At most 8 characters and the 0. */
void move_text(uint8_t from, uint8_t to, uint8_t flag, char *out)
{
    uint8_t n = 0, i;
    uint8_t p = board[from];
    uint8_t type = TYPE(p);
    uint8_t capture = (uint8_t)(board[to] != EMPTY || flag == MF_EP);

    if (flag == MF_CASTLE) {
        out[n++] = 'O';
        out[n++] = '-';
        out[n++] = 'O';
        if (to < from) {
            out[n++] = '-';
            out[n++] = 'O';
        }
    } else {
        if (type == PAWN) {
            if (capture) {
                out[n++] = (char)('a' + FILE_OF(from));
            }
        } else {
            uint8_t same_file = 0, same_rank = 0, other = 0;
            out[n++] = PIECE_CHAR[type];
            for (i = 0; i < legal_n; i++) {
                uint8_t f = legal_from[i];
                if (legal_to[i] == to && f != from && board[f] == p) {
                    other = 1;
                    if (FILE_OF(f) == FILE_OF(from)) {
                        same_file = 1;
                    }
                    if (RANK_OF(f) == RANK_OF(from)) {
                        same_rank = 1;
                    }
                }
            }
            if (other) {
                if (!same_file) {
                    out[n++] = (char)('a' + FILE_OF(from));
                } else if (!same_rank) {
                    out[n++] = (char)('1' + RANK_OF(from));
                } else {
                    out[n++] = (char)('a' + FILE_OF(from));
                    out[n++] = (char)('1' + RANK_OF(from));
                }
            }
        }
        if (capture) {
            out[n++] = 'x';
        }
        out[n++] = (char)('a' + FILE_OF(to));
        out[n++] = (char)('1' + RANK_OF(to));
        if (flag >= MF_PROMO_N) {
            out[n++] = '=';
            out[n++] = PIECE_CHAR[PROMO_TYPE(flag)];
        }
    }
    /* check or mate: make the move and look at the answers */
    make_move(from, to, flag);
    if (in_check()) {
        uint16_t first = mv_count, m;
        uint8_t mate = 1;
        gen_moves(0);
        for (m = first; m < mv_count && mate; m++) {
            make_move(mv_from[m], mv_to[m], mv_flag[m]);
            if (!left_in_check()) {
                mate = 0;
            }
            unmake_move();
        }
        mv_count = first;
        out[n++] = mate ? '#' : '+';
    }
    unmake_move();
    out[n] = 0;
}
