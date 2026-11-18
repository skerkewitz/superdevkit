/*
 * Schach: how the console plays, shared by the game (game.c) and the tool that plays a game on the
 * PC as the SNES does (test/game_pc.c).
 */
#ifndef SCHACH_H
#define SCHACH_H

#define NODE_LIMIT 1500                 /* nodes per move: the strength */
#define MAX_DEPTH  20
#define STEPS      24                   /* search steps per frame: about two frames of CPU time */
#define MIN_THINK  40                   /* frames: an answer, also from the book, is not shown at once */

#endif
