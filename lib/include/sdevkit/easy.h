#ifndef SDEVKIT_EASY_H
#define SDEVKIT_EASY_H

/**
 * @file easy.h
 * @brief All optional layers above the core and one commit for them (ADR-6FNPPCSX).
 *
 * A game that wants none of the vertical blank's rules writes its next picture in game_tick, into
 * the register shadow (sdevkit/shadow.h), the sprite list (sdevkit/sprites.h) and the upload
 * queue (sdevkit/queue.h), and its game_vblank is one line. sdevkit/input.h's buttons pressed
 * come with it. The layers cost what their functions cost; a game that outgrows one replaces it
 * by the core where it matters (sdevkit/snes.h), the rest stays.
 *
 * @code
 * #include "sdevkit/loop.h"
 * #include "sdevkit/easy.h"
 *
 * static int16_t x = 120, y = 100;
 *
 * void game_init(uint16_t seed)
 * {
 *     (void)seed;
 *     sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;
 *     sdevkit_ppu.tm = SDEVKIT_OBJ_ENABLE;
 *     sdevkit_ppu.obsel = SDEVKIT_OBSEL(0x4000, SDEVKIT_OBJ_SIZE8_L16);
 *     // the sprite's tiles and colours at once, the screen is off:
 *     // sdevkit_vram_copy(0x4000, tiles, sizeof tiles); sdevkit_cgram_copy(128, colours, 32);
 *     sdevkit_ppu.inidisp = 15;
 * }
 *
 * void game_tick(const uint16_t pad[SDEVKIT_PADS])
 * {
 *     sdevkit_input_update(pad);
 *     if (sdevkit_input.pad[0] & SDEVKIT_KEY_RIGHT) {
 *         x++;
 *     }
 *     sdevkit_sprite(x, y, 0, SDEVKIT_OAM_ATTR(0, 3, 0, 0), 1);
 * }
 *
 * void game_vblank(void)
 * {
 *     sdevkit_commit();
 * }
 * @endcode
 */

#include "sdevkit/shadow.h"
#include "sdevkit/sprites.h"
#include "sdevkit/queue.h"
#include "sdevkit/input.h"

/**
 * @brief Commits all layers: in game_vblank, as its first call.
 *
 * A screen to be switched off goes off first; then the sprite list, the registers of the groups
 * marked, the queue; then INIDISP, which may switch the screen on. Also allowed in game_init (the
 * screen is off there), for a picture before a long job such as sdevkit_sfx_init. 249 to 6,381
 * cycles; lib/README.md ("The commit's time") has its worst case against the vertical blank.
 */
void sdevkit_commit(void);

#endif /* SDEVKIT_EASY_H */
