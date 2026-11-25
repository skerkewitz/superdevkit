#ifndef SDEVKIT_SPRITES_H
#define SDEVKIT_SPRITES_H

/**
 * @file sprites.h
 * @brief The sprite list, an optional layer above the core (ADR-6FNPPCSX): sprites drawn anew
 *        every tick, copied into OAM by one call.
 *
 * A game that draws its sprites anew each tick appends them to the list with sdevkit_sprite, in
 * game_tick, and sdevkit_sprites_commit in game_vblank copies into OAM what the list filled, and
 * hides what it filled the tick before and not in this one. Only the entries in use are copied,
 * not the whole table. The sprites' tiles and sizes are OBSEL's (sdevkit/snes.h, "OBSEL" and
 * "Sprites"). A game that keeps its sprite table itself uses the core's sdevkit_oam_copy instead
 * and pays nothing for this layer: dampf leaves out what a program does not call, and the memory
 * only that uses.
 *
 * @code
 * void game_tick(const uint16_t pad[SDEVKIT_PADS])
 * {
 *     uint8_t i;
 *     for (i = 0; i < enemies; i++) {           // every tick, all of them, the most important first
 *         sdevkit_sprite(enemy_x[i] - scroll_x, enemy_y[i], 4, SDEVKIT_OAM_ATTR(1, 2, 0, 0), 1);
 *     }
 * }
 *
 * void game_vblank(void)
 * {
 *     sdevkit_sprites_commit();
 * }
 * @endcode
 */

#include "sdevkit/snes.h"

/**
 * The table the list fills, in the hardware's format (sdevkit/snes.h, "Sprites"). The entries
 * after the list's are not kept up to date: the commit hides them in OAM, not here.
 */
extern uint8_t sdevkit_oam[SDEVKIT_OAM_BYTES];

/**
 * @brief Appends one sprite to this tick's list.
 *
 * Sprites go in the order of their importance: the first is in front, and the last is the one
 * the SNES leaves out when a line has more than 32. One out of the picture is left out (for
 * sprites up to 64 pixels), as is the 129th. 21 to 133 cycles.
 * @param x     the left edge: where a background scrolled by (0, 0) shows its pixel x, so a
 *              sprite at its world position minus the scroll lines up with the background it
 *              moves on; -64..255
 * @param y     the top edge, in the same way (the list writes y - 1, since the SNES draws a
 *              sprite one line lower than its entry says); -64..223
 * @param tile  0..511, the sprite's top left tile (OBSEL's sheet, 16 tiles a row)
 * @param attr  SDEVKIT_OAM_ATTR(palette, priority, hflip, vflip)
 * @param large 0 for OBSEL's small size, 1 for its large one
 */
void sdevkit_sprite(int16_t x, int16_t y, uint16_t tile, uint8_t attr, uint8_t large);

/**
 * @brief In game_vblank: the list into OAM, and the list empty for the next tick.
 *
 * Up to four DMAs, 544 bytes at most: the entries filled (4 bytes each) and their bytes of the
 * high table, the entries of the tick before to hide and theirs, zero. 60 to 560 cycles besides
 * the DMAs. A tick that appends nothing hides what the tick before showed.
 */
void sdevkit_sprites_commit(void);

#endif /* SDEVKIT_SPRITES_H */
