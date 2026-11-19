/*
 * Schach: the console's player.
 *
 * Alpha-beta in negamax form with iterative deepening, a quiescence search over captures
 * (and promotions), a check extension, a transposition table and move ordering (the table's move
 * first, then captures by MVV-LVA, then killer moves, then quiet moves by their piece squares).
 *
 * There is no recursion. Each ply has an entry in the arrays s_*: its window, depth, where its
 * moves start in the shared list, which move is next, the best so far, and a stage:
 *   ST_ENTER  the node is new: draws, the table, stand pat, generate the moves;
 *   ST_NEXT   pick the best move not tried yet, make it, and enter the child at ply + 1;
 *   ST_CHILD  the child has left with its score in ret: take the move back, update alpha;
 *   ST_NULL   the null move's child has left: cut if it held beta, else generate the moves.
 * Pruning: the null move (the other side moves twice; if the position still holds beta with a
 * shallower search, the node is cut), and at depth 1 only the captures and the checks when the
 * evaluation is far below alpha (futility). A node leaves by dropping its moves and handing its score one ply up (leave()). One step of
 * search_run() is one of these stages, so the search stops after any step and goes on in the next
 * frame; a node limit (never time) ends it, and gives the same move on the SNES and on the PC.
 */
#include "search.h"

#define MAX_PLY 32
#define INF 30000
#define MATE 29000
#define MATE_BOUND 28000
#define QS_PLIES 6                      /* the quiescence search stops this deep below the horizon */
#define SORTED 8                        /* a node picks its best moves first, this many; then in order */
#define NULL_R 2                        /* the null move is searched this much shallower */
#define FUTILITY 300                    /* at depth 1, quiet moves cannot win more than this */

enum { ST_ENTER, ST_NEXT, ST_CHILD, ST_NULL };
enum { TT_NONE, TT_EXACT, TT_LOWER, TT_UPPER };

uint8_t best_from, best_to, best_flag;
int16_t best_score;
uint8_t best_depth;
uint16_t nodes;

static uint8_t ply;
static int16_t ret;                     /* the score the child left with */
static uint8_t root_depth, depth_limit, done;
static uint16_t node_limit;

static int8_t s_depth[MAX_PLY];
static int16_t s_alpha[MAX_PLY], s_beta[MAX_PLY], s_best[MAX_PLY], s_alpha0[MAX_PLY];
static uint16_t s_first[MAX_PLY], s_cur[MAX_PLY], s_made[MAX_PLY], s_batch[MAX_PLY];
static uint8_t s_stage[MAX_PLY], s_legal[MAX_PLY], s_check[MAX_PLY], s_quiet[MAX_PLY];
static uint8_t s_bfrom[MAX_PLY], s_bto[MAX_PLY], s_bflag[MAX_PLY];
static uint8_t s_null_ok[MAX_PLY], s_futile[MAX_PLY], s_hint_from[MAX_PLY], s_hint_to[MAX_PLY];
static int16_t s_static[MAX_PLY];       /* the evaluation of the node, where it was needed */
static uint8_t killer_from[MAX_PLY][2], killer_to[MAX_PLY][2];

/* The transposition table: 2048 entries, the low 11 bits of the hash pick one, the high 16 bits
 * check it. */
#define TT_SIZE 2048
static uint16_t tt_key[TT_SIZE];
static int16_t tt_score[TT_SIZE];
static int8_t tt_depth[TT_SIZE];
static uint8_t tt_flag[TT_SIZE], tt_from[TT_SIZE], tt_to[TT_SIZE];

/* --- evaluation ------------------------------------------------------------------------------ */
/* The square as KING_MG and KING_EG are written (a8 first), from white's view. */
#define WHITE_INDEX(sq) ((uint8_t)((7 - RANK_OF(sq)) * 8 + FILE_OF(sq)))
#define BLACK_INDEX(sq) ((uint8_t)(RANK_OF(sq) * 8 + FILE_OF(sq)))

int16_t evaluate(void)
{
    int16_t s = psq;
    uint8_t w = WHITE_INDEX(KING_SQ(WHITE)), b = BLACK_INDEX(KING_SQ(BLACK));

    /* the kings: hidden in the middle game, in the centre in the endgame */
    if (phase >= 12) {
        s = (int16_t)(s + KING_MG[w] - KING_MG[b]);
    } else if (phase <= 5) {
        s = (int16_t)(s + KING_EG[w] - KING_EG[b]);
    } else {
        s = (int16_t)(s + ((KING_MG[w] + KING_EG[w] - KING_MG[b] - KING_EG[b]) >> 1));
    }
    return side == WHITE ? s : (int16_t)-s;
}

/* --- the transposition table ----------------------------------------------------------------- */
/* Mate scores are stored as the distance from the node, not from the root. */
static int16_t to_tt(int16_t v, uint8_t p)
{
    if (v >= MATE_BOUND) {
        return (int16_t)(v + p);
    }
    if (v <= -MATE_BOUND) {
        return (int16_t)(v - p);
    }
    return v;
}

static int16_t from_tt(int16_t v, uint8_t p)
{
    if (v >= MATE_BOUND) {
        return (int16_t)(v - p);
    }
    if (v <= -MATE_BOUND) {
        return (int16_t)(v + p);
    }
    return v;
}

static void tt_store(uint8_t p, int16_t v, uint8_t flag)
{
    uint16_t i = (uint16_t)(hash_lo & (TT_SIZE - 1));
    uint16_t key = hash_hi;

    DAMPF_RANGE(i, 0, TT_SIZE - 1);
    if (tt_key[i] == key && tt_flag[i] != TT_NONE && tt_depth[i] > s_depth[p]) {
        return;                          /* keep the deeper result of the same position */
    }
    tt_key[i] = key;
    tt_score[i] = to_tt(v, p);
    tt_depth[i] = s_depth[p];
    tt_flag[i] = flag;
    tt_from[i] = s_bfrom[p];
    tt_to[i] = s_bto[p];
}

void search_clear(void)
{
    uint16_t i;

    for (i = 0; i < TT_SIZE; i++) {
        tt_flag[i] = TT_NONE;
        tt_key[i] = 0;
    }
}

/* --- move ordering --------------------------------------------------------------------------- */
/* The scores of the moves from first to the end of the list, for picking the best first. */
static void score_moves(uint8_t p, uint16_t first, uint8_t hint_from, uint8_t hint_to)
{
    uint16_t m;
    uint16_t end = mv_count;
    uint8_t k0f = killer_from[p][0], k0t = killer_to[p][0], k1f = killer_from[p][1], k1t = killer_to[p][1];

    for (m = first; m < end; m++) {
        uint8_t from, to, flag, piece, victim;
        int16_t sc;

        DAMPF_RANGE(m, 0, MOVES_MAX - 1);
        from = mv_from[m];
        to = mv_to[m];
        flag = mv_flag[m];
        piece = board[from];
        victim = TYPE(board[to]);

        if (flag == MF_EP) {
            victim = PAWN;
        }
        if (from == hint_from && to == hint_to && (flag < MF_PROMO_N || flag == MF_PROMO_Q)) {
            sc = 30000;
        } else if (victim != EMPTY) {
            sc = (int16_t)(10000 + victim * 8 - TYPE(piece));
            if (flag == MF_PROMO_Q) {
                sc = (int16_t)(sc + 800);
            }
        } else if (flag == MF_PROMO_Q) {
            sc = 9500;
        } else if (flag >= MF_PROMO_N) {
            sc = -1000;                  /* under-promotions last */
        } else if (from == k0f && to == k0t) {
            sc = 9000;
        } else if (from == k1f && to == k1t) {
            sc = 8990;
        } else {
            sc = (int16_t)(psq_val[piece][to] - psq_val[piece][from]);
            if (side == BLACK) {
                sc = (int16_t)-sc;
            }
        }
        mv_score[m] = sc;
    }
}

/* --- the search ------------------------------------------------------------------------------ */
static void start_iteration(void)
{
    ply = 0;
    s_alpha[0] = -INF;
    s_beta[0] = INF;
    s_depth[0] = (int8_t)root_depth;
    s_stage[0] = ST_ENTER;
    s_check[0] = in_check();
}

static void leave(int16_t v)
{
    uint8_t p = ply;

    mv_count = s_first[p];
    if (p != 0) {
        ply = (uint8_t)(p - 1);
        ret = v;
        return;
    }
    /* an iteration is finished */
    best_from = s_bfrom[0];
    best_to = s_bto[0];
    best_flag = s_bflag[0];
    best_score = v;
    best_depth = root_depth;
    if (v >= MATE_BOUND || v <= -MATE_BOUND || root_depth >= depth_limit || nodes >= node_limit) {
        done = 1;
        return;
    }
    root_depth++;
    start_iteration();
}

/* The node limit in the middle of an iteration: take every move back. The root's best move of
 * this iteration counts if it has one: the first move tried is the last iteration's best, so a
 * different one was found better by a deeper search. */
static void stop(void)
{
    while (ply > 0) {
        ply--;
        if (s_stage[ply] == ST_NULL) {
            unmake_null();
        } else {
            unmake_move();
        }
    }
    mv_count = s_first[0];
    if (s_bfrom[0] != NO_SQ) {
        best_from = s_bfrom[0];
        best_to = s_bto[0];
        best_flag = s_bflag[0];
        best_score = s_best[0];
    }
    done = 1;
}

/* The moves of a full node: the captures first, the quiet moves only if none of them cuts; all at
 * once in check, or when the table's move is a quiet one, so that it still comes first. */
static void generate(uint8_t p, uint8_t hint_from, uint8_t hint_to)
{
    if (s_check[p] || (hint_from != NO_SQ && board[hint_to] == EMPTY)) {
        s_quiet[p] = 1;
        gen_moves(GEN_ALL, s_check[p]);
    } else {
        s_quiet[p] = 0;
        gen_moves(GEN_CAPTURES, 0);
    }
    score_moves(p, s_first[p], hint_from, hint_to);
    s_cur[p] = s_first[p];
    s_batch[p] = s_first[p];
    s_stage[p] = ST_NEXT;
}

static void enter(void)
{
    uint8_t p = ply;
    int8_t depth = s_depth[p];
    uint8_t check;
    uint8_t hint_from = NO_SQ, hint_to = NO_SQ;

    s_first[p] = mv_count;
    if (p > 0) {
        if (nodes >= node_limit && root_depth > 1) {
            stop();
            return;
        }
        if (half >= 100 || repeated()) {
            leave(0);
            return;
        }
        if (p >= MAX_PLY - 1 || mv_count > MOVES_MAX - MOVES_ROOM) {
            leave(evaluate());
            return;
        }
    }
    nodes++;
    check = s_check[p];
    if (check && p < root_depth * 2 + 2) {
        depth = depth <= 0 ? 1 : (int8_t)(depth + 1);   /* a check is answered, never stood pat */
    }
    s_depth[p] = depth;
    s_alpha0[p] = s_alpha[p];
    s_legal[p] = 0;
    s_bfrom[p] = NO_SQ;
    s_bto[p] = NO_SQ;
    s_bflag[p] = 0;
    if (depth <= 0) {
        int16_t stand = evaluate();
        if (stand >= s_beta[p] || depth <= -QS_PLIES) {
            leave(stand);
            return;
        }
        if (stand > s_alpha[p]) {
            s_alpha[p] = stand;
        }
        s_best[p] = stand;
        s_quiet[p] = 1;
        gen_moves(GEN_CAPTURES, 0);
    } else {
        uint16_t i = (uint16_t)(hash_lo & (TT_SIZE - 1));
        DAMPF_RANGE(i, 0, TT_SIZE - 1);
        if (tt_flag[i] != TT_NONE && tt_key[i] == hash_hi) {
            hint_from = tt_from[i];
            hint_to = tt_to[i];
            if (p > 0 && tt_depth[i] >= depth) {
                int16_t v = from_tt(tt_score[i], p);
                uint8_t f = tt_flag[i];
                if (f == TT_EXACT || (f == TT_LOWER && v >= s_beta[p]) || (f == TT_UPPER && v <= s_alpha[p])) {
                    leave(v);
                    return;
                }
            }
        }
        if (p == 0 && root_depth > 1) {
            hint_from = best_from;       /* the last iteration's move first */
            hint_to = best_to;
        }
        s_best[p] = -INF;
        s_futile[p] = 0;
        if (p > 0 && !check && (depth == 1 || depth >= NULL_R + 1)) {
            s_static[p] = evaluate();
        }
        if (p > 0 && !check && depth >= NULL_R + 1 && s_null_ok[p] && s_beta[p] < MATE_BOUND
            && s_beta[p] > -MATE_BOUND && s_static[p] >= s_beta[p] && has_pieces()) {
            uint8_t c = (uint8_t)(p + 1);
            s_hint_from[p] = hint_from;
            s_hint_to[p] = hint_to;
            make_null();
            s_alpha[c] = (int16_t)-s_beta[p];
            s_beta[c] = (int16_t)(1 - s_beta[p]);
            s_depth[c] = (int8_t)(depth - 1 - NULL_R);
            s_check[c] = 0;
            s_null_ok[c] = 0;
            s_stage[c] = ST_ENTER;
            s_stage[p] = ST_NULL;
            ply = c;
            return;
        }
        if (p > 0 && !check && depth == 1 && s_static[p] + FUTILITY <= s_alpha[p]) {
            s_futile[p] = 1;
            s_best[p] = (int16_t)(s_static[p] + FUTILITY);
        }
        generate(p, hint_from, hint_to);
        return;
    }
    score_moves(p, s_first[p], hint_from, hint_to);
    s_cur[p] = s_first[p];
    s_batch[p] = s_first[p];
    s_stage[p] = ST_NEXT;
}

/* After the null move: a cut if the other side, moving twice, still could not get below beta. */
static void null_done(void)
{
    uint8_t p = ply;
    int16_t v = (int16_t)-ret;

    unmake_null();
    if (v >= s_beta[p]) {
        leave(v >= MATE_BOUND ? s_beta[p] : v);
        return;
    }
    generate(p, s_hint_from[p], s_hint_to[p]);
}

static void finish(void)
{
    uint8_t p = ply;

    if (s_depth[p] > 0) {
        if (s_legal[p] == 0) {
            leave(s_check[p] ? (int16_t)(p - MATE) : 0);
            return;
        }
        tt_store(p, s_best[p], s_best[p] > s_alpha0[p] ? TT_EXACT : TT_UPPER);
    }
    leave(s_best[p]);
}

/* In the quiescence search: captures not worth trying. One that cannot lift the score to alpha
 * even if the piece were free; one of a cheaper piece that a pawn defends; an under-promotion. */
static uint8_t skip_capture(uint8_t p, uint8_t from, uint8_t to, uint8_t flag)
{
    uint8_t victim = flag == MF_EP ? PAWN : TYPE(board[to]);
    uint8_t pawn;

    if (flag >= MF_PROMO_N) {
        return (uint8_t)(flag != MF_PROMO_Q);
    }
    if (s_best[p] + PIECE_VALUE[victim] + 200 <= s_alpha[p]) {
        return 1;
    }
    if (PIECE_VALUE[TYPE(board[from])] <= PIECE_VALUE[victim]) {
        return 0;
    }
    pawn = (uint8_t)((side ^ BLACK) | PAWN);
    if (side == WHITE) {
        return (uint8_t)((!OFF(to + 15) && board[to + 15] == pawn) || (!OFF(to + 17) && board[to + 17] == pawn));
    }
    return (uint8_t)((!OFF(to - 15) && board[to - 15] == pawn) || (!OFF(to - 17) && board[to - 17] == pawn));
}

static void next(void)
{
    uint8_t p = ply;
    uint16_t m = s_cur[p], end = mv_count, i, pick;
    int16_t top;
    uint8_t from, to, flag;

    if (m == end) {
        if (s_quiet[p]) {
            finish();
            return;
        }
        s_quiet[p] = 1;                  /* the captures did not cut: now the quiet moves */
        s_batch[p] = end;
        gen_moves(GEN_QUIETS, 0);
        score_moves(p, end, NO_SQ, NO_SQ);
        return;
    }
    DAMPF_RANGE(m, 0, MOVES_MAX - 1);
    pick = m;
    top = mv_score[m];
    if (m - s_batch[p] < SORTED) {
        for (i = (uint16_t)(m + 1); i < end; i++) {
            DAMPF_RANGE(i, 0, MOVES_MAX - 1);
            if (mv_score[i] > top) {
                top = mv_score[i];
                pick = i;
            }
        }
    }
    DAMPF_RANGE(pick, 0, MOVES_MAX - 1);
    from = mv_from[pick];
    to = mv_to[pick];
    flag = mv_flag[pick];
    if (pick != m) {
        mv_from[pick] = mv_from[m];
        mv_to[pick] = mv_to[m];
        mv_flag[pick] = mv_flag[m];
        mv_score[pick] = mv_score[m];
        mv_from[m] = from;
        mv_to[m] = to;
        mv_flag[m] = flag;
        mv_score[m] = top;
    }
    s_cur[p] = (uint16_t)(m + 1);
    if (s_depth[p] <= 0 && skip_capture(p, from, to, flag)) {
        return;
    }
    make_move(from, to, flag);
    if (illegal_after(from, s_check[p])) {
        unmake_move();
        return;
    }
    s_check[p + 1] = gave_check();
    s_legal[p]++;
    s_made[p] = m;
    /* a futile node: of the quiet moves only the checks are searched */
    if (s_futile[p] && s_quiet[p] && m >= s_batch[p] && s_batch[p] != s_first[p] && !s_check[p + 1]) {
        unmake_move();
        return;
    }
    s_alpha[p + 1] = (int16_t)-s_beta[p];
    s_beta[p + 1] = (int16_t)-s_alpha[p];
    s_depth[p + 1] = (int8_t)(s_depth[p] - 1);
    s_null_ok[p + 1] = 1;
    s_stage[p + 1] = ST_ENTER;
    s_stage[p] = ST_CHILD;
    ply = (uint8_t)(p + 1);
}

static void child(void)
{
    uint8_t p = ply;
    uint16_t m = s_made[p];
    int16_t v = (int16_t)-ret;

    DAMPF_RANGE(m, 0, MOVES_MAX - 1);
    unmake_move();
    s_stage[p] = ST_NEXT;
    if (v <= s_best[p]) {
        return;
    }
    s_best[p] = v;
    s_bfrom[p] = mv_from[m];
    s_bto[p] = mv_to[m];
    s_bflag[p] = mv_flag[m];
    if (v <= s_alpha[p]) {
        return;
    }
    s_alpha[p] = v;
    if (v < s_beta[p]) {
        return;
    }
    /* a cut: a quiet move that did it is a killer of this ply */
    if (board[mv_to[m]] == EMPTY && mv_flag[m] < MF_EP) {
        if (killer_from[p][0] != mv_from[m] || killer_to[p][0] != mv_to[m]) {
            killer_from[p][1] = killer_from[p][0];
            killer_to[p][1] = killer_to[p][0];
            killer_from[p][0] = mv_from[m];
            killer_to[p][0] = mv_to[m];
        }
    }
    if (s_depth[p] > 0) {
        tt_store(p, v, TT_LOWER);
    }
    leave(v);
}

void search_start(uint16_t limit, uint8_t max_depth)
{
    uint8_t i;

    nodes = 0;
    done = 0;
    node_limit = limit;
    depth_limit = max_depth;
    root_depth = 1;
    best_from = NO_SQ;
    best_to = NO_SQ;
    best_flag = 0;
    best_score = 0;
    best_depth = 0;
    for (i = 0; i < MAX_PLY; i++) {
        killer_from[i][0] = NO_SQ;
        killer_from[i][1] = NO_SQ;
        killer_to[i][0] = NO_SQ;
        killer_to[i][1] = NO_SQ;
    }
    list_legal();
    if (legal_n <= 1) {                  /* nothing to think about */
        if (legal_n == 1) {
            best_from = legal_from[0];
            best_to = legal_to[0];
            best_flag = legal_flag[0];
        }
        done = 1;
        return;
    }
    start_iteration();
}

uint8_t search_run(uint16_t steps)
{
    while (!done && steps != 0) {
        steps--;
        switch (s_stage[ply]) {
        case ST_ENTER:
            enter();
            break;
        case ST_NEXT:
            next();
            break;
        case ST_NULL:
            null_done();
            break;
        default:
            child();
            break;
        }
    }
    return done;
}
