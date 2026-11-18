/*
 * CatchMe: a mouse eats the crumbs of a maze while four cats chase it. A chili turns the tables
 * for a while. An example of superdevkit: one screen, a background layer for the maze and the
 * text, sprites for the mouse and the cats.
 */
#ifndef CATCHME_H
#define CATCHME_H

#include <stdint.h>
#include "snes/loop.h"
#include "snes/snes.h"

/* --- the maze (maze.c) ----------------------------------------------------------------------- */
#define MAZE_W 16              /* cells of 16 x 16 pixels */
#define MAZE_H 13
#define MAZE_TOP 16            /* the maze starts below the status line, in pixels */
#define TUNNEL_ROW 6           /* the row that leaves the screen on both sides */

enum { CELL_EMPTY, CELL_WALL, CELL_CRUMB, CELL_CHILI, CELL_DOOR, CELL_HOUSE };

extern uint8_t maze[MAZE_H][MAZE_W];
extern uint8_t maze_crumbs;            /* crumbs and chilis left */
extern uint16_t bg_map[32 * 32];       /* the background map, copied to VRAM when it changed */
extern uint8_t bg_map_dirty;

void maze_reset(void);
void maze_draw(void);
void maze_clear_cell(uint8_t x, uint8_t y);
void maze_blink(uint8_t on);           /* the walls in white, when a level is cleared */
uint8_t maze_cell(int8_t x, int8_t y); /* CELL_* of a cell; off the sides of the tunnel row: empty */
void text(uint8_t x, uint8_t y, const char *s, uint8_t yellow);
void number(uint8_t x, uint8_t y, uint32_t value, uint8_t digits);

/* --- the mouse and the cats (actors.c) ------------------------------------------------------- */
enum { DIR_RIGHT, DIR_UP, DIR_LEFT, DIR_DOWN, DIR_NONE };
enum { CAT_HOME, CAT_LEAVING, CAT_CHASE, CAT_SCARED, CAT_EYES };

typedef struct {
    int16_t x, y;              /* pixels in the maze, the top left corner of the sprite */
    uint8_t dir;               /* DIR_* it moves in */
    uint8_t sub;               /* sixteenths of a pixel carried to the next frame */
    uint8_t mode;              /* CAT_* (cats only) */
    uint8_t wait;              /* frames until a cat leaves the house */
} Actor;

#define CATS 4
extern Actor mouse;
extern Actor cats[CATS];
extern uint16_t scared_left;           /* frames the chili still works */
extern uint8_t level;

void actors_reset(void);
void actors_scare(void);
void mouse_move(uint16_t pad);         /* returns nothing; eating is game.c's (cell_entered) */
void cats_move(void);
uint8_t aligned(const Actor *a);
void actors_draw(uint8_t frame);
void actors_hide(void);

/* --- the game (game.c) ----------------------------------------------------------------------- */
void eat(uint8_t x, uint8_t y);        /* the mouse reached the middle of a cell */
uint16_t random16(void);

#endif
