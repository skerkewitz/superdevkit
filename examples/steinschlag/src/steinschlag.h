/*
 * Steinschlag: a miner digs through a cave for gems while boulders fall and roll, and beetles
 * crawl along the walls. Enough gems open the exit. An example of superdevkit that scrolls: the
 * cave is larger than the screen, and the camera follows the miner in x and y.
 *
 * Sound (assets/sfx/sfx.json, two voices): digging, a gem taken, a boulder landing, a beetle hit,
 * the miner crushed, the exit opening. Much can happen in one tick; the priorities decide what
 * gives way, and digging never cuts off the exit opening.
 */
#ifndef STEINSCHLAG_H
#define STEINSCHLAG_H

#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "sfx.h"                                 /* the sound effects, from assets/sfx/sfx.json */

/* --- the cave (cave.c) ----------------------------------------------------------------------- */
#define CAVE_W 40              /* cells of 16 x 16 pixels: 640 x 352 pixels */
#define CAVE_H 22
#define CAVES 3

/* What a cell holds: the kind in the low 4 bits, more in the high ones. */
enum {
    K_SPACE, K_DIRT, K_BRICK, K_STEEL, K_BOULDER, K_GEM, K_EXIT, K_EXIT_OPEN, K_BEETLE, K_MINER,
    K_BLAST, K_BLAST_GEM
};
#define KIND(c)    ((uint8_t)((c) & 0x0F))
#define EXTRA(c)   ((uint8_t)(((c) >> 4) & 3))   /* a beetle's direction, a blast's stage */
#define FALLING    0x40                          /* a boulder or gem on its way down */
#define DONE       0x80                          /* handled in the tick whose parity it holds */

extern uint8_t cave[CAVE_H][CAVE_W];
extern uint8_t gems_needed, gems_got;
extern uint16_t time_left;                       /* seconds */
extern uint8_t miner_x, miner_y;                 /* the miner's cell */
extern uint8_t miner_dead, cave_won;

void cave_start(uint8_t number);
void cave_scan_rows(uint8_t from, uint8_t to);   /* one part of a tick of the cave's physics */
void cave_tick_begin(void);
uint8_t miner_step(uint8_t dir, uint8_t grab);   /* returns 1 if the miner moved */
uint16_t random16(void);

/* --- the screen (game.c) --------------------------------------------------------------------- */
void cell_changed(uint8_t x, uint8_t y);         /* draw the cell again in the next vertical blank */
void add_score(uint16_t points);

#endif
