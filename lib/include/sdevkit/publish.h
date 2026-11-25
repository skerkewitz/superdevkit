#ifndef SDEVKIT_PUBLISH_H
#define SDEVKIT_PUBLISH_H

/**
 * @file publish.h
 * @brief The data the tick publishes for interrupt code (ADR-6FPV1EQV). Not for games yet.
 *
 * Interrupt code runs in the middle of a tick, at any instruction. If it read the game's
 * variables there, it could find a 16-bit value with its low byte new and its high byte old, or
 * half of a palette. So the tick publishes what interrupt code needs: one struct, which the tick
 * writes as an ordinary variable, and a copy of it, which the kit makes right after each tick and
 * which interrupt code reads. Interrupt code only reads it, and always finds one tick's data,
 * complete.
 *
 * The game declares the data once, in one of its C files, and names its tick, which takes the
 * place of `game_tick`:
 *
 * @code
 * #include "sdevkit/publish.h"
 *
 * struct split { uint16_t scroll_x; uint16_t colors[16]; };
 *
 * SDEVKIT_PUBLISH(struct split, split, tick)   // defines split, its copy and game_tick
 *
 * static void tick(const uint16_t pad[SDEVKIT_PADS])
 * {
 *     split.scroll_x += 1;                     // an ordinary variable, all of the tick
 * }
 * @endcode
 *
 * and interrupt code reads it through SDEVKIT_PUBLISHED:
 *
 * @code
 * const struct split *s = SDEVKIT_PUBLISHED(split);
 * sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, s->scroll_x);
 * @endcode
 *
 * How it works. SDEVKIT_PUBLISH defines `game_tick`: it calls the game's tick, then copies the
 * struct into the published copy. While it copies, the copy is half old and half new, so for
 * that time interrupt code reads the tick's own struct instead, which is complete then: the tick
 * has ended, and nothing writes it until the next one. One byte, sdevkit_publishing, says which
 * one to read, and SDEVKIT_PUBLISHED reads it. The tick keeps writing its own struct as any
 * variable: what it does not change keeps the value it had, nothing is a frame old, and the copy
 * always has all of it.
 *
 * The cost: the published copy, as many bytes as the struct (the tick's own struct is the game's
 * data anyway), and the copy after every tick, about 55 CPU cycles and 10.5 a byte: 1,397 for
 * 128 bytes, 5,429 for 512, of the about 50,000 of a frame (lib/README.md). Interrupt code reads
 * through a pointer, a few cycles more than a variable. A game that does not include this header
 * pays nothing.
 *
 * The same C runs on the desktop and the web: there `game_tick` copies just the same, and the
 * checksums of the ticks are the same as on the SNES.
 *
 * Not for games yet: no interrupt runs game code so far. The game's IRQ, and the kit's NMI that
 * runs game_vblank, come later (docs/roadmap.md, M10). Until then dampf's interrupt handlers
 * (`DAMPF_INTERRUPT`, docs/subset.md) are the only readers, in the kit's tests.
 */

#include <stdint.h>
#include "sdevkit/loop.h"

/**
 * @brief 1 while the kit copies the published struct, else 0.
 *
 * Defined by SDEVKIT_PUBLISH, written only by the `game_tick` it defines, read only by
 * SDEVKIT_PUBLISHED. A game does not touch it.
 */
extern uint8_t sdevkit_publishing;

/**
 * @brief Declares the data the tick publishes, and defines `game_tick`.
 *
 * Once in a program, at file scope in one of the game's C files, before the tick: defines the
 * struct `name` of type `type` (the tick's own, an ordinary variable), its published copy,
 * sdevkit_publishing, and `game_tick`, which calls `tick(pad)` and then publishes. The game then
 * defines no `game_tick` of its own. `type` is a struct (an array goes into one), `tick` a
 * function `static void tick(const uint16_t pad[SDEVKIT_PADS])` of the same file, after the
 * macro.
 *
 * The flag's two writes are volatile: a C compiler may otherwise drop the first, which a later
 * write of the same byte overwrites with nothing between them that reads it. dampf keeps every
 * store and its order (ADR-6FPV1EQV).
 */
#define SDEVKIT_PUBLISH(type, name, tick)                                    \
    type name;                                                               \
    type sdevkit_published_##name;                                           \
    uint8_t sdevkit_publishing;                                              \
    static void tick(const uint16_t pad[SDEVKIT_PADS]);                      \
    void game_tick(const uint16_t pad[SDEVKIT_PADS])                         \
    {                                                                        \
        tick(pad);                                                           \
        *(volatile uint8_t *)&sdevkit_publishing = 1;                        \
        sdevkit_published_##name = name;                                     \
        *(volatile uint8_t *)&sdevkit_publishing = 0;                        \
    }

/**
 * @brief Declares the published data for the game's other C files (in a header): the struct
 *        and its copy, as SDEVKIT_PUBLISH defines them.
 */
#define SDEVKIT_PUBLISH_DECLARE(type, name)                                  \
    extern type name;                                                        \
    extern type sdevkit_published_##name

/**
 * @brief The published data: a pointer to one tick's data, complete. For interrupt code.
 *
 * Read it once at the start of the handler and keep the pointer; the data does not change while
 * the handler runs, since the tick it interrupted does not run meanwhile. In the tick it gives
 * the data of the tick before.
 */
#define SDEVKIT_PUBLISHED(name)                                              \
    (*(volatile uint8_t *)&sdevkit_publishing ? &(name) : &sdevkit_published_##name)

#endif /* SDEVKIT_PUBLISH_H */
