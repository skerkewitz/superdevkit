#ifndef SDEVKIT_PUBLISH_H
#define SDEVKIT_PUBLISH_H

/**
 * @file publish.h
 * @brief The data the tick publishes for interrupt code, and the game's vertical blank run by the
 *        kit's NMI (ADR-6FPV1EQV, ADR-6FRDS030, ADR-6FS0T4WB). Optional.
 *
 * Interrupt code runs in the middle of a tick, at any instruction. If it read the game's
 * variables there, it could find a 16-bit value with its low byte new and its high byte old, or
 * half of a palette. So the tick publishes what interrupt code needs: one struct, which the tick
 * writes as an ordinary variable, and a copy of it, which interrupt code reads. Interrupt code
 * only reads it, and always finds the data of the tick whose vertical blank came last: the tick
 * the picture on the screen was made from.
 *
 * The game declares the data once, in one of its C files, and names its tick and its vertical
 * blank, which take the place of `game_tick` and `game_vblank`:
 *
 * @code
 * #include "sdevkit/publish.h"
 *
 * struct split { uint16_t scroll_x; uint16_t colors[16]; };
 *
 * SDEVKIT_PUBLISH(struct split, split, tick, vblank)   // defines split, its copies, game_tick, game_vblank
 *
 * static void tick(const uint16_t pad[SDEVKIT_PADS])
 * {
 *     split.scroll_x += 1;                     // an ordinary variable, all of the tick
 * }
 *
 * static void vblank(void)                     // in the kit's NMI: published data only
 * {
 *     const struct split *s = SDEVKIT_PUBLISHED(split);
 *     sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, s->scroll_x);
 * }
 * @endcode
 *
 * and the game's IRQ (sdevkit/irq.h) reads it the same way:
 *
 * @code
 * const struct split *s = SDEVKIT_PUBLISHED(split);
 * sdevkit_reg_write_twice(SDEVKIT_PPU_BG2HOFS, s->scroll_x);
 * @endcode
 *
 * **The vertical blank in the NMI.** A game that publishes gets its `vblank` run by the kit's NMI,
 * at the start of every vertical blank, on the SNES. A tick that takes longer than a frame no
 * longer delays it: the NMI comes in the middle of the tick, and `vblank` writes the picture of
 * the last tick that ended once more, so scrolls, splits and fades stay in place while the game
 * runs at 30 frames a second or slower. A game that does not publish keeps game_vblank as
 * sdevkit/loop.h describes it, called by the kit's loop after the tick.
 *
 * `vblank` is interrupt code then, in the NMI's context (dampf's interrupt contexts,
 * docs/subset.md):
 * - It reads the game's data only through SDEVKIT_PUBLISHED. The tick's own variables, also the
 *   struct `name`, may be half written when it runs.
 * - What it writes depends only on the published data, so running it again on the same data
 *   writes the same: on the SNES it runs once a frame, also while a tick overruns, on the desktop
 *   and the web once a tick. The desktop's check runs it twice after each tick and counts every
 *   register, VRAM, CGRAM or OAM byte the second run changes (`repeated`): such a byte would make
 *   the SNES's picture differ from the desktop's after an overrun.
 * - It writes no variable the main code (game_init, the tick) writes too, and uses none of the
 *   optional layers (sdevkit_commit, the queue, the shadow registers, the sprite list): their state
 *   is the tick's. A game on the layers keeps game_vblank.
 * - It writes the hardware as game_vblank would: registers, copies into VRAM, CGRAM and OAM, HDMA,
 *   and sdevkit_irq_line. Sound effects (sdevkit_sfx_*) stay in the tick.
 * - It multiplies in software, as all interrupt code (docs/subset.md).
 *
 * **How it works.** There are two published copies. Interrupt code reads one of them, the current
 * one; the other is free. SDEVKIT_PUBLISH defines `game_tick`: it calls the game's tick, copies the
 * struct into the free copy and marks it ready. It defines the kit's NMI handler: if a copy is
 * ready, it makes it the current one, with one write of a byte, and then calls `vblank`. And it
 * defines `game_vblank`, which the kit's loop calls after each tick at the start of the vertical
 * blank: on the SNES it switches the NMI on the first time and waits until the NMI has taken the
 * ready copy. So the next tick begins only after the switch, and the NMI never switches to a copy
 * that a tick is still filling: while a tick runs, no copy is ready. On the PC, which has no NMI,
 * `game_vblank` switches and calls `vblank` itself, at the same point; that is the one difference
 * between the two, and dampf leaves the PC's branch out (`DAMPF_HAS_INTERRUPTS`, `<dampf.h>`).
 * The tick keeps writing its own struct as any variable: what it does not change keeps the value
 * it had, and the copy always has all of it.
 *
 * **The cost**, in CPU cycles on the SNES (lib/README.md): two copies, each as many bytes as the
 * struct (the tick's own struct is the game's data anyway), and two bytes; the copy after every
 * tick, about 10.5 cycles a byte (1,419 for 128 bytes, of the about 50,000 of a frame); in every
 * vertical blank the NMI, 118 to 140 cycles beyond `vblank` itself (its entry and exit, the switch
 * and the call); and a page of RAM for the NMI's direct page and frames, which the main code's
 * frames lose (docs/memory-map.md). Interrupt code reads through a pointer,
 * a few cycles more than a variable. A game that does not include this header pays nothing.
 *
 * The same C runs on the desktop and the web: there `game_tick` copies and `game_vblank` switches
 * just the same, and the checksums of the ticks are the same as on the SNES.
 */

#include <stdint.h>
#include <dampf.h>
#include "sdevkit/loop.h"

/**
 * @brief Which of the two published copies interrupt code reads: 0 or 1.
 *
 * 16 bits, so that interrupt code reads it with one load and no masking: an interrupt that
 * writes in the horizontal blank has little time.
 * Defined by SDEVKIT_PUBLISH, written only by the kit's NMI handler it defines (on the PC by its
 * `game_vblank`), read by SDEVKIT_PUBLISHED and by `game_tick`, which fills the other copy. A game
 * does not touch it.
 */
extern uint16_t sdevkit_publish_side;

/**
 * @brief 1 when `game_tick` has filled the free copy and no vertical blank has made it the
 *        current one yet, else 0.
 *
 * Defined by SDEVKIT_PUBLISH, set by the `game_tick` it defines and cleared by the switch. A game
 * does not touch it.
 */
extern uint8_t sdevkit_publish_ready;

/**
 * @brief Switches the kit's NMI on, if it is not on yet. The `game_vblank` that SDEVKIT_PUBLISH
 *        defines calls it on the SNES; a game does not.
 *
 * Writes NMITIMEN, which is the kit's, keeping the joypads' automatic read and the IRQ of
 * sdevkit_irq_line. It is called in the vertical blank, so the NMI comes at once and serves this
 * one. On the desktop and the web it does nothing.
 */
void sdevkit_publish_nmi_on(void);

/**
 * @brief Declares the data the tick publishes, and defines `game_tick`, `game_vblank` and the
 *        kit's NMI handler.
 *
 * Once in a program, at file scope in one of the game's C files, before the tick: defines the
 * struct `name` of type `type` (the tick's own, an ordinary variable), its two published copies,
 * sdevkit_publish_side and sdevkit_publish_ready; `game_tick`, which calls `tick(pad)`, copies
 * `name` into the copy interrupt code does not read and marks it ready; `sdevkit_publish_nmi`, the
 * kit's NMI handler, which makes a ready copy the current one and then calls `vblank()`; and
 * `game_vblank`, which on the SNES waits for that and on the PC does it. The game then defines no
 * `game_tick`, no `game_vblank` and no NMI handler of its own. `type` is a struct (an array goes
 * into one), `tick` a function `static void tick(const uint16_t pad[SDEVKIT_PADS])` and `vblank`
 * a function `static void vblank(void)` of the same file, after the macro.
 *
 * The two variables are read and written volatile: the copy must be complete before it is marked
 * ready, and interrupt code must read the current copy's number anew. dampf keeps every store and
 * its order (ADR-6FPV1EQV).
 */
#define SDEVKIT_PUBLISH(type, name, tick, vblank)                            \
    type name;                                                               \
    type sdevkit_published_##name[2];                                        \
    uint16_t sdevkit_publish_side;                                           \
    uint8_t sdevkit_publish_ready;                                           \
    static void tick(const uint16_t pad[SDEVKIT_PADS]);                      \
    static void vblank(void);                                                \
    void game_tick(const uint16_t pad[SDEVKIT_PADS])                         \
    {                                                                        \
        tick(pad);                                                           \
        if (*(volatile uint16_t *)&sdevkit_publish_side) {                   \
            sdevkit_published_##name[0] = name;                              \
        } else {                                                             \
            sdevkit_published_##name[1] = name;                              \
        }                                                                    \
        *(volatile uint8_t *)&sdevkit_publish_ready = 1;                     \
    }                                                                        \
    DAMPF_INTERRUPT(nmi) void sdevkit_publish_nmi(void)                      \
    {                                                                        \
        if (*(volatile uint8_t *)&sdevkit_publish_ready) {                   \
            *(volatile uint16_t *)&sdevkit_publish_side =                    \
                (uint16_t)(*(volatile uint16_t *)&sdevkit_publish_side ^ 1u); \
            *(volatile uint8_t *)&sdevkit_publish_ready = 0;                 \
        }                                                                    \
        vblank();                                                            \
    }                                                                        \
    void game_vblank(void)                                                   \
    {                                                                        \
        if (DAMPF_HAS_INTERRUPTS) {                                          \
            sdevkit_publish_nmi_on();                                        \
            while (*(volatile uint8_t *)&sdevkit_publish_ready) {            \
            }                                                                \
        } else {                                                             \
            if (*(volatile uint8_t *)&sdevkit_publish_ready) {               \
                *(volatile uint16_t *)&sdevkit_publish_side =                \
                    (uint16_t)(*(volatile uint16_t *)&sdevkit_publish_side ^ 1u); \
                *(volatile uint8_t *)&sdevkit_publish_ready = 0;             \
            }                                                                \
            vblank();                                                        \
        }                                                                    \
    }

/**
 * @brief Declares the published data for the game's other C files (in a header): the struct
 *        and its copies, as SDEVKIT_PUBLISH defines them.
 */
#define SDEVKIT_PUBLISH_DECLARE(type, name)                                  \
    extern type name;                                                        \
    extern type sdevkit_published_##name[2]

/**
 * @brief The published data: a pointer to the current copy, the data of the tick whose vertical
 *        blank came last. For interrupt code: the game's IRQ and `vblank`.
 *
 * Read it once at the start of the handler and keep the pointer; the copy does not change while
 * the handler runs. In the tick it gives the data of the tick before.
 */
#define SDEVKIT_PUBLISHED(name)                                              \
    (*(volatile uint16_t *)&sdevkit_publish_side ? &sdevkit_published_##name[1] \
                                                 : &sdevkit_published_##name[0])

#endif /* SDEVKIT_PUBLISH_H */
