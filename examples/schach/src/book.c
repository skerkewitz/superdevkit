/*
 * Schach: the opening book. Empty for now (step 4 of the proposal fills it).
 */
#include "book.h"

uint8_t book_from, book_to, book_flag;
static uint16_t book_rnd;

void book_init(void)
{
    book_rnd = 1;
}

void book_seed(uint16_t seed)
{
    book_rnd = (uint16_t)(seed | 1);
}

uint8_t book_find(void)
{
    return 0;
}
