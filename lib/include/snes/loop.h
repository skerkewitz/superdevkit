#ifndef SNES_LOOP_H
#define SNES_LOOP_H

/*
 * The frame rhythm of superdevkit (ADR record 29): the library's main loop starts the console,
 * then calls three functions that every game defines.
 *
 *   game_init(seed)    once, with the screen off;
 *   game_tick(pads)    once per frame, while the picture is drawn: the game logic, and what the
 *                      next vertical blank is to show (sprite table, maps to upload);
 *   game_vblank()      in the vertical blank after each tick, after the library has copied the
 *                      sprite table (oamMemory): uploads to VRAM and CGRAM, scroll registers.
 *
 * A tick that takes longer than a frame makes the loop wait for the next vertical blank; the
 * game is never interrupted. The pads are in the SNES's bit layout (KEY_*), one per port.
 */

#include <stdint.h>

#define SNES_PADS 2

#define KEY_B      0x8000u
#define KEY_Y      0x4000u
#define KEY_SELECT 0x2000u
#define KEY_START  0x1000u
#define KEY_UP     0x0800u
#define KEY_DOWN   0x0400u
#define KEY_LEFT   0x0200u
#define KEY_RIGHT  0x0100u
#define KEY_A      0x0080u
#define KEY_X      0x0040u
#define KEY_L      0x0020u
#define KEY_R      0x0010u

void game_init(uint16_t seed);
void game_tick(const uint16_t pad[SNES_PADS]);
void game_vblank(void);

#endif /* SNES_LOOP_H */
