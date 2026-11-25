#ifndef SDEVKIT_QUEUE_H
#define SDEVKIT_QUEUE_H

/**
 * @file queue.h
 * @brief The upload queue, an optional layer above the core (ADR-6FNPPCSX): copies into VRAM and
 *        CGRAM asked for in game_tick, made in game_vblank by one call.
 *
 * VRAM and CGRAM take writes only in the vertical blank (sdevkit/snes.h, "The frame and the
 * vertical blank"). With this layer the tick asks for a copy where it knows what changed, and
 * sdevkit_queue_commit in game_vblank makes the copies, in the order queued. A copy returns 1 when
 * it fits into that vertical blank and 0 (nothing queued) when it does not: then the game asks
 * again in the next tick. What fits is decided when the game asks, in bytes, the same on the SNES
 * and the PC, so the two take and refuse the same copies and the checks compare them: the
 * vertical blank takes SDEVKIT_QUEUE_BYTES a frame, and every copy counts SDEVKIT_QUEUE_COST more
 * for its setup; at most SDEVKIT_QUEUE_ENTRIES copies. The numbers hold when the commit runs at the
 * start of game_vblank, after at most the other layers' commits (sdevkit/easy.h); lib/README.md
 * ("The commit's time") has where they come from. The source is read at the commit, so it must
 * still hold the data then: a buffer reused for the next copy in the same tick needs a copy of
 * its own. A game that copies with the core in its own game_vblank pays nothing for this layer:
 * dampf leaves out what a program does not call.
 *
 * @code
 * static uint16_t map[32 * 32];      // the map in RAM, changed by the tick
 * static uint8_t map_changed;
 *
 * void game_tick(const uint16_t pad[SDEVKIT_PADS])
 * {
 *     // ... change map[], set map_changed ...
 *     if (map_changed && sdevkit_queue_vram(0x1000, map, sizeof map)) {
 *         map_changed = 0;              // queued; a refused copy keeps it set, for the next tick
 *     }
 * }
 *
 * void game_vblank(void)
 * {
 *     sdevkit_queue_commit();
 * }
 * @endcode
 */

#include "sdevkit/snes.h"

/**
 * @name The budget
 * @{
 */
#define SDEVKIT_QUEUE_BYTES   3072   /**< The bytes a vertical blank takes, setups counted. */
#define SDEVKIT_QUEUE_COST    136    /**< What each copy's setup counts, in bytes. */
#define SDEVKIT_QUEUE_ENTRIES 24     /**< The copies a vertical blank takes at most. */
/** @} */

/**
 * @brief Queues a copy in its raw form; the functions below fill it, with half the parameters.
 * @param port  the register it writes, the low byte of $21xx: 0x18 or 0x19 (VRAM), 0x22 (CGRAM)
 * @param dmap  the transfer mode (sdevkit_dma)
 * @param vmain how the VRAM address steps (SDEVKIT_PPU_VMAIN); 0 for CGRAM
 * @param dest  a VRAM word address or a CGRAM colour
 * @param src   the bytes, which must still hold them at the commit
 * @param bytes how many, 1..SDEVKIT_QUEUE_BYTES (0 is refused)
 * @return 1 if queued, 0 if it does not fit into this frame's vertical blank
 */
uint8_t  sdevkit_queue_dma(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes);

/**
 * @brief Queues words to VRAM from word address @p a (as sdevkit_vram_copy).
 * @param a the VRAM word address @param src the words @param bytes how many bytes
 * @return 1 if queued, 0 if refused
 */
uint8_t  sdevkit_queue_vram(uint16_t a, const void *src, uint16_t bytes);

/**
 * @brief Queues words down a column of a map 32 entries wide (as sdevkit_vram_column_copy).
 * @param a the column's first entry @param src the entries @param bytes 2 per entry, at most 64
 * @return 1 if queued, 0 if refused
 */
uint8_t  sdevkit_queue_vram_column(uint16_t a, const void *src, uint16_t bytes);

/**
 * @brief Queues colours to CGRAM from colour @p i (as sdevkit_cgram_copy).
 * @param i the first colour @param src the colours @param bytes 2 per colour
 * @return 1 if queued, 0 if refused
 */
uint8_t  sdevkit_queue_cgram(uint8_t i, const void *src, uint16_t bytes);

/**
 * @brief Queues one colour; the queue keeps the value, so it needs no source that stays.
 * @param i the colour index @param bgr the colour, SDEVKIT_RGB5()
 * @return 1 if queued, 0 if refused
 */
uint8_t  sdevkit_queue_color(uint8_t i, uint16_t bgr);

/**
 * @brief Queues mode 7 map bytes from entry @p e (as sdevkit_mode7_map_copy).
 * @param e row * 128 + column @param src one byte an entry @param bytes how many entries
 * @return 1 if queued, 0 if refused
 */
uint8_t  sdevkit_queue_mode7_map(uint16_t e, const void *src, uint16_t bytes);

/**
 * @brief Queues mode 7 tile bytes from byte @p b of the tiles (as sdevkit_mode7_tiles_copy).
 * @param b tile * 64 + row * 8 + column @param src one byte a pixel @param bytes how many pixels
 * @return 1 if queued, 0 if refused
 */
uint8_t  sdevkit_queue_mode7_tiles(uint16_t b, const void *src, uint16_t bytes);

/** @brief How many bytes one more copy may have now. @return the bytes; 0 when none fits */
uint16_t sdevkit_queue_room(void);

/**
 * @brief In game_vblank: the queued copies, in the order queued, and the queue empty for the next
 *        tick. About 150 cycles a copy besides the DMA itself.
 */
void sdevkit_queue_commit(void);

#endif /* SDEVKIT_QUEUE_H */
