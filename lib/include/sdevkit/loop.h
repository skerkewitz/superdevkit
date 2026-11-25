#ifndef SDEVKIT_LOOP_H
#define SDEVKIT_LOOP_H

/*
 * The frame rhythm of superdevkit (ADR-6F3CJX8T, ADR-6FNPPCSX): the library's main loop starts the
 * console, then calls three functions that every game defines.
 *
 *   game_init(seed)    once, with the screen off: VRAM, CGRAM and the registers take writes at
 *                      any time; the game switches the screen on (INIDISP) when it is ready;
 *   game_tick(pad)     once per frame, while the picture is drawn: the game logic, and what the
 *                      next vertical blank is to write (in the game's own memory);
 *   game_vblank()      in the vertical blank after each tick: the writes to the hardware, the
 *                      registers and the copies into VRAM, CGRAM and OAM (sdevkit/snes.h).
 *
 * Between the three the kit does only what it must: it waits for the vertical blank, reads the
 * joypads (the console's automatic read, at the start of the vertical blank), starts the sound
 * effects of the tick after game_vblank, and tells the checks where a tick ends. It copies
 * nothing and writes no register, so the whole vertical blank is the game's. A tick that takes
 * longer than a frame makes the loop wait for the next vertical blank; the game is never
 * interrupted. The three callbacks are the game's names, so they keep the game_ prefix; no name
 * of the kit starts with game_.
 */

#include <stdint.h>

#define SDEVKIT_PADS 2

/* The joypads' buttons, in the SNES's bit layout: one uint16_t per port in pad[] */
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
/* The buttons held, one uint16_t per port (SDEVKIT_KEY_*); sdevkit/input.h adds the ones pressed
 * since the last tick. */
void game_tick(const uint16_t pad[SDEVKIT_PADS]);
void game_vblank(void);

#endif /* SDEVKIT_LOOP_H */
