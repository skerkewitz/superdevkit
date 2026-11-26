#ifndef SDEVKIT_LOOP_H
#define SDEVKIT_LOOP_H

/**
 * @file loop.h
 * @brief The frame rhythm: the three functions every game defines, and the joypads
 *        (ADR-6F3CJX8T, ADR-6FNPPCSX).
 *
 * The kit's main loop starts the console, shows the splash screen (the kit's logo, about four
 * seconds; a button ends it once it has faded in), resets the PPU (the screen off, every register
 * 0, VRAM and CGRAM zero, every sprite hidden), and then calls the game:
 *
 * | Function            | When                                         | What belongs in it |
 * | ------------------- | -------------------------------------------- | ------------------ |
 * | `game_init(seed)`   | once, with the screen off                    | everything into VRAM, CGRAM and the registers at once; the screen on (INIDISP) last |
 * | `game_tick(pad)`    | once a frame, while the picture is drawn     | the game logic; the next picture prepared in the game's own memory, no write to the hardware |
 * | `game_vblank()`     | in the vertical blank after each tick        | the writes to the hardware: registers, copies into VRAM, CGRAM and OAM |
 *
 * Between them the kit does only what it must: it waits for the vertical blank, reads the
 * joypads (the console's automatic read, at the start of the vertical blank), starts the sound
 * effects of the tick after game_vblank, and tells the checks where a tick ends. It copies
 * nothing and writes no register, so the whole vertical blank (about 49,000 master cycles,
 * sdevkit/snes.h) is the game's. A tick that takes longer than a frame makes the loop wait for the
 * next vertical blank: the game then runs at 30 frames a second, never wrong, and the picture
 * shows the last one game_vblank wrote. The kit interrupts the game nowhere; a game may have an
 * IRQ of its own at rows of the picture (sdevkit/irq.h). The three callbacks are
 * the game's names, so they keep the `game_` prefix; no name of the kit starts with `game_`.
 *
 * @code
 * #include "sdevkit/loop.h"
 * #include "sdevkit/snes.h"
 *
 * static int16_t x = 120;
 *
 * void game_init(uint16_t seed)
 * {
 *     (void)seed;
 *     // graphics and registers at once: the screen is off (sdevkit/snes.h, "A first picture")
 *     sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);
 * }
 *
 * void game_tick(const uint16_t pad[SDEVKIT_PADS])
 * {
 *     if (pad[0] & SDEVKIT_KEY_RIGHT) {
 *         x++;                                 // only the game's memory
 *     }
 * }
 *
 * void game_vblank(void)
 * {
 *     sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, (uint16_t)x);   // the hardware
 * }
 * @endcode
 */

#include <stdint.h>

/**
 * @name The joypads
 * The buttons of a pad, in the SNES's bit layout: one uint16_t per port in game_tick's pad[],
 * a bit set while the button is held. sdevkit/input.h adds the ones pressed since the last tick.
 * @{
 */
#define SDEVKIT_PADS 2              /**< The ports: pad[0] is the first pad, pad[1] the second. */
#define SDEVKIT_KEY_B      0x8000u  /**< B. */
#define SDEVKIT_KEY_Y      0x4000u  /**< Y. */
#define SDEVKIT_KEY_SELECT 0x2000u  /**< Select. */
#define SDEVKIT_KEY_START  0x1000u  /**< Start. */
#define SDEVKIT_KEY_UP     0x0800u  /**< Up on the cross. */
#define SDEVKIT_KEY_DOWN   0x0400u  /**< Down. */
#define SDEVKIT_KEY_LEFT   0x0200u  /**< Left. */
#define SDEVKIT_KEY_RIGHT  0x0100u  /**< Right. */
#define SDEVKIT_KEY_A      0x0080u  /**< A. */
#define SDEVKIT_KEY_X      0x0040u  /**< X. */
#define SDEVKIT_KEY_L      0x0020u  /**< The left shoulder button. */
#define SDEVKIT_KEY_R      0x0010u  /**< The right shoulder button. */
/** @} */

/**
 * @brief Once, before the first tick, with the screen off.
 *
 * VRAM, CGRAM, OAM and the registers take writes at any time here. The game switches the screen
 * on when it is ready (`sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15)`, or `sdevkit_ppu.inidisp`
 * with the layers); after that, writes belong in game_vblank. A long job (sdevkit_sfx_init)
 * after the screen is on shows the picture meanwhile.
 * @param seed 0 on the SNES; the desktop's `--seed N`, for a game that wants a varied start
 */
void game_init(uint16_t seed);

/**
 * @brief Once a frame, while the picture is drawn: the game logic.
 *
 * The tick changes the game's own memory: positions, maps and sprite tables in RAM, what the next
 * vertical blank is to write. It writes no register and copies nothing into VRAM, CGRAM or OAM
 * (the desktop's check fails on that), except switching the screen off at once.
 * @param pad the buttons held, one uint16_t per port (SDEVKIT_KEY_*)
 */
void game_tick(const uint16_t pad[SDEVKIT_PADS]);

/**
 * @brief In the vertical blank after each tick: the writes to the hardware.
 *
 * The registers and the copies the tick prepared (sdevkit/snes.h), within the vertical blank's
 * time; or one call of sdevkit_commit (sdevkit/easy.h) for a game on the optional layers.
 */
void game_vblank(void);

#endif /* SDEVKIT_LOOP_H */
