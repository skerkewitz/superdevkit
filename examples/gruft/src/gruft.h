/*
 * Gruft: a hero goes down through a dungeon of rooms and corridors, finds gold and potions, and
 * fights rats, bats and ghouls. A turn passes only when the hero moves. An example of superdevkit
 * whose variables do not fit into the SNES's first bank of RAM: the dungeon's maps take about
 * 100 KB, and dampf puts the largest of them into the second bank, $7F (docs/memory-map.md).
 */
#ifndef GRUFT_H
#define GRUFT_H

#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

/* --- the dungeon (dungeon.c) ----------------------------------------------------------------- */
#define MAP_W 160              /* cells of 8 x 8 pixels: 1280 x 1024 pixels, 20 screens */
#define MAP_H 128
#define THINGS 160             /* creatures and things lying around, at most */
#define SIGHT 4                /* the hero sees this many cells to each side */

enum { T_ROCK, T_WALL, T_FLOOR, T_STAIRS };                  /* what a cell is; below T_FLOOR it blocks */
enum { K_NONE, K_RAT, K_BAT, K_GHOUL, K_GOLD, K_POTION };    /* what lies or stands on it */

struct thing {
    uint8_t kind;
    uint8_t x, y;
    uint8_t hp;
};

/* The maps, one byte a cell each (20,480 bytes), and the distance map of two bytes a cell in two
 * halves, since no object may be larger than 32 KB. Together more than bank $7E holds. */
extern uint8_t tiles[MAP_H][MAP_W];
extern uint8_t seen[MAP_H][MAP_W];          /* 1 where the hero has been able to see */
extern uint8_t occupant[MAP_H][MAP_W];      /* 0, or 1 + the index of a thing */
extern struct thing things[THINGS];

extern uint8_t hero_x, hero_y;
extern uint8_t hero_hp;
extern uint16_t gold;
extern uint8_t depth;
extern const char *message;                 /* what happened in the last turn, or 0 */

void dungeon_make(uint16_t seed);
uint8_t hero_turn(uint8_t dir);             /* 1 if a turn passed; dir: 0 right, 1 up, 2 left, 3 down */

#define HP_MAX 20

#endif
