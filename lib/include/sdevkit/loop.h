#ifndef SDEVKIT_LOOP_H
#define SDEVKIT_LOOP_H

/*
 * The frame rhythm of superdevkit (ADR-6F3CJX8T and ADR-6F3CK2AR): the library's main loop starts the
 * console, then calls three functions that every game defines.
 *
 *   game_init(seed)    once, with the screen off;
 *   game_tick(pads)    once per frame, while the picture is drawn: the game logic, and what the
 *                      next vertical blank is to show (sprite table, maps to upload);
 *   game_vblank()      in the vertical blank after each tick, after the library has copied the
 *                      sprite table (sdevkit_oam_memory): uploads to VRAM and CGRAM, scroll
 *                      registers.
 *
 * A tick that takes longer than a frame makes the loop wait for the next vertical blank; the
 * game is never interrupted. The pads are in the SNES's bit layout (SDEVKIT_KEY_*), one per port.
 * The three callbacks are the game's names, so they keep the game_ prefix; no name of the kit
 * starts with game_.
 */

#include <stdint.h>

#define SDEVKIT_PADS 2

#define SDEVKIT_KEY_B      0x8000u
#define SDEVKIT_KEY_Y      0x4000u
#define SDEVKIT_KEY_SELECT 0x2000u
#define SDEVKIT_KEY_START  0x1000u
#define SDEVKIT_KEY_UP     0x0800u
#define SDEVKIT_KEY_DOWN   0x0400u
#define SDEVKIT_KEY_LEFT   0x0200u
#define SDEVKIT_KEY_RIGHT  0x0100u
#define SDEVKIT_KEY_A      0x0080u
#define SDEVKIT_KEY_X      0x0040u
#define SDEVKIT_KEY_L      0x0020u
#define SDEVKIT_KEY_R      0x0010u

void game_init(uint16_t seed);
void game_tick(const uint16_t pad[SDEVKIT_PADS]);
void game_vblank(void);

#endif /* SDEVKIT_LOOP_H */
