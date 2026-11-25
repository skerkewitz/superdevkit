#ifndef SDEVKIT_QUEUE_H
#define SDEVKIT_QUEUE_H

/*
 * The upload queue, an optional layer above the core (ADR-6FNPPCSX): copies into VRAM and CGRAM
 * asked for in game_tick, run by sdevkit_queue_commit in game_vblank. A copy returns 1 when it fits
 * into that vertical blank and 0 (nothing queued) when it does not: then the game asks again in
 * the next tick. What fits is decided when the game asks, in bytes, the same on the SNES and the
 * PC, so the two take and refuse the same copies and the checks compare them: the vertical blank
 * takes SDEVKIT_QUEUE_BYTES a frame, and every copy counts SDEVKIT_QUEUE_COST more for its setup;
 * at most SDEVKIT_QUEUE_ENTRIES copies. The numbers hold when the commit runs at the start of
 * game_vblank, after at most the other layers' commits (sdevkit/easy.h); lib/README.md ("The
 * commit's time") has where they come from. The source must still hold the data at the commit.
 * A game that copies with the core in its own game_vblank pays nothing for this layer: dampf
 * leaves out what a program does not call.
 */

#include "sdevkit/snes.h"

#define SDEVKIT_QUEUE_BYTES   3072
#define SDEVKIT_QUEUE_COST    136
#define SDEVKIT_QUEUE_ENTRIES 24

/* The raw form: port (the low byte of $21xx it writes: 0x18 or 0x19 VRAM, 0x22 CGRAM), DMAP,
 * VMAIN (how the VRAM address steps), and the destination (a VRAM word address or a CGRAM
 * colour). The functions below fill it, with half the parameters to pass. */
uint8_t  sdevkit_queue_dma(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes);

/* Words to VRAM from word address a. */
uint8_t  sdevkit_queue_vram(uint16_t a, const void *src, uint16_t bytes);

/* Words down a column of a map 32 entries wide: the address steps by 32 words. */
uint8_t  sdevkit_queue_vram_column(uint16_t a, const void *src, uint16_t bytes);

/* Colours from colour index i. */
uint8_t  sdevkit_queue_cgram(uint8_t i, const void *src, uint16_t bytes);

/* One colour; the queue keeps the value, so it needs no source that stays. */
uint8_t  sdevkit_queue_color(uint8_t i, uint16_t bgr);

/* Mode 7: map bytes from entry e (row * 128 + column), tile bytes from byte b of the tiles. */
uint8_t  sdevkit_queue_mode7_map(uint16_t e, const void *src, uint16_t bytes);
uint8_t  sdevkit_queue_mode7_tiles(uint16_t b, const void *src, uint16_t bytes);

/* How many bytes one more copy may have now (0: none fits). */
uint16_t sdevkit_queue_room(void);

/* In game_vblank: the queued copies, in the order queued, and the queue empty for the next tick.
 * About 150 cycles a copy besides the DMA itself. */
void sdevkit_queue_commit(void);

#endif /* SDEVKIT_QUEUE_H */
