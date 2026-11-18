/*
 * Schach: the console's opening book (book.c). A table in ROM of positions, by their hash, with
 * one or more answers each; one is picked at random, from a seed taken when the player pressed
 * Start, the same on the SNES and on the desktop.
 */
#ifndef BOOK_H
#define BOOK_H

#include "chess.h"

void book_init(void);
void book_seed(uint16_t seed);
uint8_t book_find(void);                /* 1 when the book has a move for the position */
extern uint8_t book_from, book_to, book_flag;

#endif
