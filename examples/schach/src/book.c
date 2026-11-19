/*
 * Schach: the opening book. The answers are in src/book_data.c (made by book/make_book.sh from
 * book/lines.txt), sorted by the hash of the position: a binary search finds the first, the
 * others follow it. One is picked at random by their weights, from a seed that the game takes
 * when Start is pressed, so the console does not play the same game every time and the SNES and
 * the desktop still play the same one. A move found is checked against the legal moves, in case
 * two positions share a hash.
 */
#include "book.h"
#include "book_data.h"

uint8_t book_from, book_to, book_flag;
static uint16_t book_rnd;

void book_init(void)
{
    book_rnd = 1;
}

void book_seed(uint16_t seed)
{
    book_rnd = seed;
}

static uint16_t next_random(void)
{
    book_rnd = (uint16_t)(book_rnd * 25173u + 13849u);
    return book_rnd;
}

uint8_t book_find(void)
{
    uint16_t lo = 0, hi = BOOK_ENTRIES, i, first;
    uint16_t total = 0, pick;
    uint32_t hash = chess_hash();
    uint8_t k;

    while (lo < hi) {                    /* the first entry not below the hash */
        uint16_t mid = (uint16_t)((lo + hi) >> 1);
        DAMPF_RANGE(mid, 0, BOOK_ENTRIES - 1);
        if (BOOK_HASH[mid] < hash) {
            lo = (uint16_t)(mid + 1);
        } else {
            hi = mid;
        }
    }
    first = lo;
    for (i = first; i < BOOK_ENTRIES; i++) {
        DAMPF_RANGE(i, 0, BOOK_ENTRIES - 1);
        if (BOOK_HASH[i] != hash) {
            break;
        }
        total = (uint16_t)(total + BOOK_WEIGHT[i]);
    }
    if (total == 0) {
        return 0;
    }
    pick = (uint16_t)((next_random() >> 4) % total);
    for (i = first;; i++) {
        DAMPF_RANGE(i, 0, BOOK_ENTRIES - 1);
        if (pick < BOOK_WEIGHT[i]) {
            break;
        }
        pick = (uint16_t)(pick - BOOK_WEIGHT[i]);
    }
    book_from = BOOK_FROM[i];
    book_to = BOOK_TO[i];
    book_flag = BOOK_FLAG[i];
    list_legal();
    for (k = 0; k < legal_n; k++) {
        if (legal_from[k] == book_from && legal_to[k] == book_to && legal_flag[k] == book_flag) {
            return 1;
        }
    }
    return 0;
}
