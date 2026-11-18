/*
 * Schach: the console's player (search.c). Alpha-beta with iterative deepening, quiescence
 * search over captures and a transposition table, written as a loop over one entry per ply, so
 * that it can stop after any step and go on in the next frame.
 */
#ifndef SEARCH_H
#define SEARCH_H

#include "chess.h"

int16_t evaluate(void);                 /* from the side to move's view, in centipawns */

void search_clear(void);                /* a new game: forget the transposition table */
/* Start thinking about the position on the board: at most node_limit nodes (whole iterations
 * finished before it count; the first iteration always runs to its end) and max_depth plies. */
void search_start(uint16_t node_limit, uint8_t max_depth);
uint8_t search_run(uint16_t steps);     /* go on for some steps; 1 when the move is chosen */

extern uint8_t best_from, best_to, best_flag;   /* the move, once search_run returned 1 */
extern int16_t best_score;              /* of the last finished iteration */
extern uint8_t best_depth;              /* the depth of the last finished iteration */
extern uint16_t nodes;                  /* nodes of this search so far */

#endif
