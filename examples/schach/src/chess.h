/*
 * Schach: the rules of chess and the console's player, shared by the screen (game.c), the
 * search (search.c) and the tests (test/).
 *
 * The board is 0x88: 128 bytes, a square is rank * 16 + file (a1 = 0x00, h1 = 0x07, a8 = 0x70),
 * and sq & 0x88 is not zero exactly when a step left the board. A piece is its type (1 to 6)
 * plus its colour (WHITE 0, BLACK 8). Each side has a piece list of 16 entries, the king first;
 * a piece taken has the square NO_SQ there.
 *
 * Moves are generated pseudo-legally into one shared list (mv_from, mv_to, mv_flag), made on the
 * board, and dropped when the own king is then attacked. Nothing is recursive: perft and the
 * search keep one entry per ply in fixed arrays.
 */
#ifndef CHESS_H
#define CHESS_H

#include <stdint.h>
#include <dampf.h>

/* --- pieces and squares ---------------------------------------------------------------------- */
enum { EMPTY, PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING };
#define WHITE 0
#define BLACK 8
#define TYPE(p)   ((uint8_t)((p) & 7))
#define COLOR(p)  ((uint8_t)((p) & 8))
#define NO_SQ     0x7F                  /* off the board: 0x7F & 0x88 != 0 */
#define OFF(sq)   (((sq) & 0x88) != 0)
#define SQ(file, rank) ((uint8_t)((rank) * 16 + (file)))
#define FILE_OF(sq) ((uint8_t)((sq) & 7))
#define RANK_OF(sq) ((uint8_t)((sq) >> 4))

/* Castling rights. */
#define CASTLE_WK 1
#define CASTLE_WQ 2
#define CASTLE_BK 4
#define CASTLE_BQ 8

/* The flag of a move. */
enum { MF_NORMAL, MF_DOUBLE, MF_EP, MF_CASTLE, MF_PROMO_N, MF_PROMO_B, MF_PROMO_R, MF_PROMO_Q };
#define PROMO_TYPE(flag) ((uint8_t)((flag) - 2))   /* MF_PROMO_N -> KNIGHT ... */

/* --- the position (board.c) ------------------------------------------------------------------ */
extern uint8_t board[128];
extern uint8_t plist[32];               /* white 0..15, black 16..31; the kings at 0 and 16 */
extern uint8_t pidx[128];               /* the piece list entry of the piece on a square */
extern uint8_t side;                    /* WHITE or BLACK: who moves */
extern uint8_t castle;                  /* CASTLE_* bits */
extern uint8_t ep;                      /* the square a pawn may take en passant, or NO_SQ */
extern uint8_t half;                    /* half moves since the last capture or pawn move */
extern uint16_t fullmove;
extern uint16_t hash_lo, hash_hi;       /* Zobrist, in two halves (rule slow-32-bit) */
uint32_t chess_hash(void);              /* the two halves as one number */
extern int16_t psq;                     /* material and piece squares, without kings, white's view */
extern uint8_t phase;                   /* 0 (bare kings and pawns) to 24 (all pieces) */

#define KING_SQ(color) (plist[(color) << 1])

/* The move list, shared by every ply. */
#define MOVES_MAX 1600
#define MOVES_ROOM 220                  /* the most moves one position can have, rounded up */
extern uint8_t mv_from[MOVES_MAX], mv_to[MOVES_MAX], mv_flag[MOVES_MAX];
extern int16_t mv_score[MOVES_MAX];
extern uint16_t mv_count;

/* The way back, one entry per move made on the board and not committed. */
#define UNDO_MAX 40
extern uint8_t sp;

/* Positions since the last capture or pawn move, for repetitions. */
#define HIST_MAX 160
extern uint16_t hist_lo[HIST_MAX], hist_hi[HIST_MAX];
extern uint8_t hist_len;

/* The values of the piece squares; psq_val[piece][sq] is negative for black's pieces. */
extern int16_t psq_val[16][128];
extern const int16_t KING_MG[64], KING_EG[64];
extern const uint8_t PHASE_OF[8];
extern const int16_t PIECE_VALUE[8];   /* centipawns, by type */

void chess_init(void);                  /* tables: Zobrist keys, piece squares; once */
uint8_t set_fen(const char *fen);       /* 1 when the FEN was read */
void set_start(void);
uint8_t attacked(uint8_t sq, uint8_t by);
uint8_t in_check(void);                 /* the side to move */
uint8_t left_in_check(void);            /* after make_move: the side that moved */
uint8_t illegal_after(uint8_t from, uint8_t was_in_check);   /* the same, cheaper */
uint8_t gave_check(void);               /* after make_move: the side to move is in check */
/* Appends the moves of the side to move to the move list: GEN_ALL, GEN_CAPTURES (with every
 * promotion) or GEN_QUIETS (the rest); check says whether the side is in check (no castling). */
enum { GEN_ALL, GEN_CAPTURES, GEN_QUIETS };
void gen_moves(uint8_t kind, uint8_t check);
void make_move(uint8_t from, uint8_t to, uint8_t flag);
void unmake_move(void);
uint8_t repeated(void);                 /* the position was there before, same side to move */
void make_null(void);                   /* the search's null move: the other side moves again */
void unmake_null(void);
uint8_t has_pieces(void);               /* the side to move has more than king and pawns */

/* perft: the number of legal move sequences of a depth; divide[] gets the count per first move. */
#define DIVIDE_MAX 64
extern uint32_t divide[DIVIDE_MAX];
extern uint8_t divide_from[DIVIDE_MAX], divide_to[DIVIDE_MAX], divide_flag[DIVIDE_MAX], divide_n;
uint32_t perft(uint8_t depth);

/* --- the game (board.c) ---------------------------------------------------------------------- */
#define LEGAL_MAX 220
extern uint8_t legal_from[LEGAL_MAX], legal_to[LEGAL_MAX], legal_flag[LEGAL_MAX];
extern uint8_t legal_n;
void list_legal(void);                  /* the legal moves of the position into legal_* */

enum { END_NONE, END_MATE, END_STALEMATE, END_FIFTY, END_REPETITION, END_MATERIAL };
uint8_t game_end(void);                 /* list_legal first */
void play_move(uint8_t from, uint8_t to, uint8_t flag);   /* a move of the game, kept */
void move_text(uint8_t from, uint8_t to, uint8_t flag, char *out);   /* algebraic, before the move */

#endif
