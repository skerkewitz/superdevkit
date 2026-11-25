#ifndef SDEVKIT_SPRITES_H
#define SDEVKIT_SPRITES_H

/*
 * The sprite list, an optional layer above the core (ADR-6FNPPCSX): a game that draws its sprites
 * anew each tick appends them to the list with sdevkit_sprite, and sdevkit_sprites_commit in
 * game_vblank copies into OAM what the list filled, and hides what it filled the tick before and
 * not in this one. Only the entries in use are copied, not the whole table. A game that keeps its
 * sprite table itself uses the core's sdevkit_oam_copy instead and pays nothing for this layer:
 * dampf leaves out what a program does not call, and the memory only that uses.
 */

#include "sdevkit/snes.h"

/* The table the list fills, in the hardware's format (sdevkit/snes.h, "Sprites"). The entries
 * after the list's are not kept up to date: the commit hides them in OAM, not here. */
extern uint8_t sdevkit_oam[SDEVKIT_OAM_BYTES];

/* One sprite, in the order of their importance (the first in front, and the last to go when a
 * line has more than 32). The upper left corner goes where a background scrolled by (0, 0) shows
 * its pixel (x, y), so a sprite at its world position minus the scroll lines up with the
 * background it moves on (the SNES draws a sprite one line lower than its entry says, and the
 * list writes y - 1). tile 0..511, attr SDEVKIT_OAM_ATTR(...), large: the large size of OBSEL. One
 * out of the picture is left out (for sprites up to 64 pixels), as is the 129th. 21 to 133
 * cycles. */
void sdevkit_sprite(int16_t x, int16_t y, uint16_t tile, uint8_t attr, uint8_t large);

/* In game_vblank: the list into OAM, and the list empty for the next tick. Up to four DMAs, 544
 * bytes at most: the entries filled (4 bytes each) and their bytes of the high table, the entries
 * of the tick before to hide and theirs, zero. 60 to 560 cycles besides the DMAs. */
void sdevkit_sprites_commit(void);

#endif /* SDEVKIT_SPRITES_H */
