/*
 * Schach: how the console plays, shared by the game (game.c) and the tool that plays a game on the
 * PC as the SNES does (test/game_pc.c).
 */
#ifndef SCHACH_H
#define SCHACH_H

/* The strength: nodes per move at each level, chosen on the title screen. Measured against
 * Stockfish's limited levels (test/vs_stockfish.sh, its UCI_Elo scale, not human Elo): about
 * 1310, 1620 and 1750. */
#define LEVELS        3
#define LEVEL_DEFAULT 1
#define NODES_EASY    250
#define NODES_NORMAL  600
#define NODES_STRONG  1500
#define MAX_DEPTH  20
#define STEPS      24                   /* search steps per frame: about two frames of CPU time */
#define MIN_THINK  40                   /* frames: an answer, also from the book, is not shown at once */

#endif
