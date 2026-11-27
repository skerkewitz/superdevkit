#ifndef SDEVKIT_IRQ_H
#define SDEVKIT_IRQ_H

/**
 * @file irq.h
 * @brief The game's IRQ: code of the game that runs at a row of the picture, while it is drawn
 *        (ADR-6FRX1GYC). Optional.
 *
 * The PPU draws the picture row by row, from the registers as they are when it gets to a row.
 * HDMA changes a register at every row from a table (sdevkit/snes.h); the IRQ runs C code at the
 * rows the game chooses: a status bar below a scrolling playfield, a second scroll for the lower
 * half, a fade that starts in the middle of the screen. The game writes the IRQ as a function of
 * its own and switches it on with the row it is to run at:
 *
 * @code
 * #include <dampf.h>
 * #include "sdevkit/irq.h"
 * #include "sdevkit/publish.h"
 *
 * struct split { uint16_t top_x; uint16_t bar_x; };
 * SDEVKIT_PUBLISH(struct split, split, tick, vblank)
 *
 * void game_init(uint16_t seed)
 * {
 *     // ... the picture ...
 *     sdevkit_irq_line(176);                    // the IRQ before row 176, every frame from now on
 * }
 *
 * static void vblank(void)
 * {
 *     sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, split.top_x);   // rows 0 to 175
 * }
 *
 * DAMPF_INTERRUPT(irq) void game_irq(void)
 * {
 *     uint16_t x = SDEVKIT_PUBLISHED(split)->bar_x;   // read and decide first,
 *     sdevkit_irq_wait_hblank();                         // then wait for the blank,
 *     sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, x);  // then write: rows 176 to 223
 * }
 * @endcode
 *
 * **When it runs.** The PPU takes a register for a row only in the horizontal blank before it:
 * a write while it draws the row shows in the row's middle, half old and half new. So the IRQ
 * comes in two steps. sdevkit_irq_line(row) makes it come in the scanline before picture row
 * `row` (row y of the picture is scanline y + 1), early enough for the handler to read its data
 * and decide; then the handler calls sdevkit_irq_wait_hblank(), which returns when the horizontal
 * blank before the row has begun, and writes. A register it writes then shows from that row on,
 * to the end of the picture or until something writes it again.
 *
 * **The time it has**, measured in Mesen, in CPU cycles (dampf's timing report counts master cycles,
 * about 6.3 a CPU cycle here):
 * - Before the wait: about 53 cycles from the handler's first instruction to the start of the
 *   blank. Reading a value from the published data takes about 33 (SDEVKIT_PUBLISHED finds the
 *   current copy in about 15). What the handler does before the wait that it does not need for
 *   its writes belongs after them.
 * - After the wait: its register writes must end within about 25 cycles of the wait's return,
 *   158 master cycles, less about 6 for each HDMA channel of one byte on that line (more for more
 *   bytes). dampf checks it: the timing report gives the master cycles from the wait to the last
 *   register write, and the build fails above 158 less the HDMA the handler declares for its line
 *   with SDEVKIT_IRQ_HDMA (ADR-6FVWK4JE); without a declaration the line has none, and the
 *   desktop's check fails when more HDMA runs there than declared. The wait
 *   returns up to 12 cycles after the blank has begun, a round of its loop, and the rest of the
 *   blank is the writes'. A scroll register (sdevkit_reg_write_twice) takes 17, a register of one
 *   byte about 10: one scroll register, or two registers of a byte. More goes in HDMA, or in an
 *   IRQ at another row.
 * - After its last write the handler may go on, up to the next IRQ; the timing report has a line
 *   for the IRQ, `dampf_irq`, with the entry stub's 97 cycles (660 master cycles). While it waits, the tick does not
 *   run: the IRQ takes about 60 cycles of the tick's time beyond its code.
 * - Too long before the wait: the handler reaches the wait inside the blank, and the wait returns
 *   at once; its writes come late and may tear the row's left edge. Later still, after the blank,
 *   the wait waits for the next row's blank, and the writes show a row lower. The wait cannot tell
 *   either from a handler in time, and Rachael, who has no time, cannot either: the Mesen check
 *   (dev/compare_example.sh) shows it as a different picture. Keep the code before the wait short
 *   and look at the timing report. Writes too late after the wait fail the build.
 *
 * **Several rows.** The handler may call sdevkit_irq_line itself, for the next split of the same
 * frame: a row below the one it runs at comes in this frame, a row at or above it in the next
 * one. So a handler with two splits sets the second row in the first and the first row in the
 * second.
 *
 * **What it must not do.** Interrupt code runs in the middle of the tick, at any instruction
 * (dampf's interrupt contexts, docs/subset.md):
 * - It reads the game's data only through SDEVKIT_PUBLISHED, which is complete and the data of
 *   the picture being drawn; a global the tick writes may be half written.
 * - It writes no variable that the main code (game_init, game_tick, game_vblank) writes too:
 *   one of the writes could be lost. A variable only the handler writes is fine.
 * - It writes registers with sdevkit_reg_write and sdevkit_reg_write_twice, after the wait, and
 *   nothing into VRAM, CGRAM or OAM: the PPU takes nothing there while it draws, and the desktop
 *   counts it as lost.
 * - It uses no HDMA call: Rachael does not draw an HDMA channel changed in the middle of the
 *   picture, and the desktop counts it as a write while the picture is drawn.
 * - game_vblank, or the vertical blank the kit's NMI runs (sdevkit/publish.h), ends before the
 *   first row the IRQ runs at: a register written there would show in the middle of the picture,
 *   and in game_vblank the IRQ could even come between the two bytes of a scroll register.
 *
 * **The desktop and the web.** Rachael calls game_irq while she draws the picture, before the
 * row it is set for, after that row's HDMA, as the SNES does. A register it writes before
 * sdevkit_irq_wait_hblank counts as written while the picture is drawn, so a forgotten wait
 * fails the desktop check; the writes after it are free. The picture is the SNES's, pixel for
 * pixel, as long as the handler keeps the times above, which the desktop does not see: the Mesen
 * check does (dev/compare_example.sh).
 *
 * **What it costs.** The IRQ takes the entry stub's 97 CPU cycles and the handler's of the frame,
 * each time it comes. The main code's frames have a page less RAM (docs/memory-map.md). A game
 * that does not include this header and defines no game_irq pays nothing.
 */

#include <stdint.h>

/**
 * @brief The game's IRQ handler: optional; a game that has one defines it as
 *        `DAMPF_INTERRUPT(irq) void game_irq(void)` (`<dampf.h>`).
 *
 * The hardware calls it at the row sdevkit_irq_line sets, C code never does. It runs in a context
 * of its own: it and every function it calls have their own frames and multiply in software
 * (docs/subset.md). The name is the game's, so it keeps the `game_` prefix.
 */
void game_irq(void);

/**
 * @brief The picture's rows, 0 to 223, at which the IRQ can run. sdevkit_irq_line with a row from
 *        SDEVKIT_IRQ_ROWS on switches the IRQ off.
 */
#define SDEVKIT_IRQ_ROWS 224

/**
 * @brief Runs game_irq right before picture row `row` is drawn, in every frame from now on.
 *
 * Callable anywhere: in game_init (the IRQ then comes from the first frame on), in game_vblank,
 * and in game_irq itself for the next row of the same frame (a row below the one it runs at) or
 * of the next frame. A row from SDEVKIT_IRQ_ROWS on switches the IRQ off, as sdevkit_irq_off. The
 * kit writes the CPU's timer registers (VTIME, HTIME) and switches the timer's IRQ on in NMITIMEN,
 * which is the kit's: a game does not write `$4200` to `$420A` itself.
 * @param row the row of the picture, 0 to 223
 */
void sdevkit_irq_line(uint8_t row);

/** @brief Switches the IRQ off: game_irq runs no more until the next sdevkit_irq_line. */
void sdevkit_irq_off(void);

/**
 * @brief In game_irq, once, before its register writes: waits for the horizontal blank before
 *        the IRQ's row.
 *
 * On the SNES it waits until the horizontal blank begins (HVBJOY's bit 6); the writes after it
 * then land in the blank, within the time the file's description gives. Call it once in each
 * call of the handler: a second call waits for the next row's blank. On Rachael it does nothing
 * but note that the handler waited: a register
 * write in game_irq before it counts as written while the picture is drawn, so a forgotten wait
 * fails the desktop check.
 */
void sdevkit_irq_wait_hblank(void);

/**
 * @brief In game_irq, before the wait: the HDMA that runs on the IRQ's line, a statement of its own.
 *
 * HDMA runs at the start of the horizontal blank, the one the handler waits for, and stops the CPU
 * meanwhile, so it takes from the time the handler's writes have after the wait. dampf checks
 * that time at build time and takes the declared HDMA from it: per line 18 master cycles once and,
 * for each channel, 16 (8 for the channel, 8 for a new entry of its table) and 8 for each byte
 * it writes (fullsnes, "SNES Timings"). One channel of one byte: 42 of the 158.
 * Without a declaration the line has no HDMA. On the desktop and the web Rachael compares it with
 * the HDMA that runs on the line, and the check fails when more channels run, or they write more
 * bytes, than declared. Declared more than once, the channels of all and the most bytes count.
 * @code
 * DAMPF_INTERRUPT(irq) void game_irq(void)
 * {
 *     SDEVKIT_IRQ_HDMA(SDEVKIT_HDMA_CHANNEL1, 1);   // channel 1 writes one byte on this line
 *     ...
 * }
 * @endcode
 * @param channels the channels that may run on the line, SDEVKIT_HDMA_CHANNELn or'ed (constant)
 * @param bytes    the most bytes they write on it together: 1 a channel of SDEVKIT_HDMA_ONE,
 *                 2 of SDEVKIT_HDMA_TWO_REGS or SDEVKIT_HDMA_TWICE (constant)
 */
#ifdef __DAMPF__
#define SDEVKIT_IRQ_HDMA(channels, bytes) __dampf_irq_hdma(channels, bytes)
#else
#define SDEVKIT_IRQ_HDMA(channels, bytes) sdevkit_irq_hdma((uint8_t)(channels), (uint8_t)(bytes))
#endif

/**
 * @brief What SDEVKIT_IRQ_HDMA calls on the desktop and the web, where Rachael checks it; write
 *        the macro, which dampf reads at build time (a call of this function is not seen there).
 * @param channels as SDEVKIT_IRQ_HDMA's
 * @param bytes    as SDEVKIT_IRQ_HDMA's
 */
void sdevkit_irq_hdma(uint8_t channels, uint8_t bytes);

#endif /* SDEVKIT_IRQ_H */
