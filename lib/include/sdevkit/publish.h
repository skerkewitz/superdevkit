#ifndef SDEVKIT_PUBLISH_H
#define SDEVKIT_PUBLISH_H

/**
 * @file publish.h
 * @brief The data the tick publishes for interrupt code (ADR-6FPV1EQV, ADR-6FRDS030): for the game's
 *        IRQ (sdevkit/irq.h).
 *
 * Interrupt code runs in the middle of a tick, at any instruction. If it read the game's
 * variables there, it could find a 16-bit value with its low byte new and its high byte old, or
 * half of a palette. So the tick publishes what interrupt code needs: one struct, which the tick
 * writes as an ordinary variable, and a copy of it, which interrupt code reads. Interrupt code
 * only reads it, and always finds the data of the tick whose game_vblank came last: the tick the
 * picture on the screen was made from.
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
 * static void vblank(void)
 * {
 *     sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, split.scroll_x);   // as game_vblank would
 * }
 * @endcode
 *
 * and interrupt code reads it through SDEVKIT_PUBLISHED:
 *
 * @code
 * const struct split *s = SDEVKIT_PUBLISHED(split);
 * sdevkit_reg_write_twice(SDEVKIT_PPU_BG2HOFS, s->scroll_x);
 * @endcode
 *
 * How it works. There are two published copies. Interrupt code reads one of them, the current
 * one; the other is free. SDEVKIT_PUBLISH defines `game_tick`: it calls the game's tick, copies
 * the struct into the free copy and marks it ready. And it defines `game_vblank`: if a copy is
 * ready, it makes it the current one, with one write of a byte, and then calls the game's
 * vblank. So nothing interrupt code reads ever changes: the tick fills the copy interrupt code
 * does not read, and the switch is one byte. And the switch comes with game_vblank, so interrupt
 * code in the picture after it (the game's IRQ, sdevkit/irq.h) reads the data the picture was
 * made from, also when the next tick has already ended, on the SNES as on the desktop. The tick
 * keeps writing its own struct as any variable: what it does not change keeps the value it had,
 * and the copy always has all of it.
 *
 * The cost: two copies, each as many bytes as the struct (the tick's own struct is the game's
 * data anyway), and two bytes; the copy after every tick, about 10.5 CPU cycles a byte (1,419
 * for 128 bytes, of the about 50,000 of a frame), and the switch in game_vblank, 16 to 38 cycles
 * of the vertical blank (lib/README.md). Interrupt code reads through a pointer, a few cycles
 * more than a variable. A game that does not include this header pays nothing.
 *
 * The same C runs on the desktop and the web: there `game_tick` copies and `game_vblank` switches
 * just the same, and the checksums of the ticks are the same as on the SNES.
 *
 * The reader is the game's IRQ, game_irq (sdevkit/irq.h). The kit's NMI that runs game_vblank
 * comes later (docs/roadmap.md, M10).
 */

#include <stdint.h>
#include "sdevkit/loop.h"

/**
 * @brief Which of the two published copies interrupt code reads: 0 or 1.
 *
 * 16 bits, so that interrupt code reads it with one load and no masking: an interrupt that
 * writes in the horizontal blank has little time.
 * Defined by SDEVKIT_PUBLISH, written only by the `game_vblank` it defines, read by
 * SDEVKIT_PUBLISHED and by `game_tick`, which fills the other copy. A game does not touch it.
 */
extern uint16_t sdevkit_publish_side;

/**
 * @brief 1 when `game_tick` has filled the free copy and `game_vblank` has not yet made it the
 *        current one, else 0.
 *
 * Defined by SDEVKIT_PUBLISH, set by the `game_tick` it defines and cleared by its `game_vblank`.
 * A game does not touch it.
 */
extern uint8_t sdevkit_publish_ready;

/**
 * @brief Declares the data the tick publishes, and defines `game_tick` and `game_vblank`.
 *
 * Once in a program, at file scope in one of the game's C files, before the tick: defines the
 * struct `name` of type `type` (the tick's own, an ordinary variable), its two published copies,
 * sdevkit_publish_side and sdevkit_publish_ready; `game_tick`, which calls `tick(pad)`, copies
 * `name` into the copy interrupt code does not read and marks it ready; and `game_vblank`, which
 * makes a ready copy the current one and then calls `vblank()`. The game then defines no
 * `game_tick` and no `game_vblank` of its own. `type` is a struct (an array goes into one),
 * `tick` a function `static void tick(const uint16_t pad[SDEVKIT_PADS])` and `vblank` a function
 * `static void vblank(void)` of the same file, after the macro.
 *
 * The two variables are read and written volatile: the copy must be complete before it is marked ready,
 * and interrupt code must read the current copy's number anew. dampf keeps every store and its
 * order (ADR-6FPV1EQV).
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
    void game_vblank(void)                                                   \
    {                                                                        \
        if (*(volatile uint8_t *)&sdevkit_publish_ready) {                   \
            *(volatile uint16_t *)&sdevkit_publish_side =                    \
                (uint16_t)(*(volatile uint16_t *)&sdevkit_publish_side ^ 1u); \
            *(volatile uint8_t *)&sdevkit_publish_ready = 0;                 \
        }                                                                    \
        vblank();                                                            \
    }

/**
 * @brief Declares the published data for the game's other C files (in a header): the struct
 *        and its copies, as SDEVKIT_PUBLISH defines them.
 */
#define SDEVKIT_PUBLISH_DECLARE(type, name)                                  \
    extern type name;                                                        \
    extern type sdevkit_published_##name[2]

/**
 * @brief The published data: a pointer to the current copy, the data of the tick whose
 *        game_vblank came last. For interrupt code.
 *
 * Read it once at the start of the handler and keep the pointer; the copy does not change while
 * the handler runs. In the tick it gives the data of the tick before.
 */
#define SDEVKIT_PUBLISHED(name)                                              \
    (*(volatile uint16_t *)&sdevkit_publish_side ? &sdevkit_published_##name[1] \
                                                 : &sdevkit_published_##name[0])

#endif /* SDEVKIT_PUBLISH_H */
