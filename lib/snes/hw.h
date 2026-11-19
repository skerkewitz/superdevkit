/*
 * SNES hardware registers for the platform layer (dampf only, never built on the PC).
 *
 * The registers are in bank 0; dampf reaches them through volatile pointers (one access each,
 * in order, the exact width). A 16-bit access writes the low byte, then the high byte at the
 * next address, which is what the two-byte registers ($2116, $43x2, ...) expect. Registers
 * that are written twice at the same address (scroll, CGDATA) take two 8-bit writes.
 */
#ifndef SDEVKIT__HW_H
#define SDEVKIT__HW_H

#include <stdint.h>

#define REG8(a)  (*(volatile uint8_t *)(a))
#define REG16(a) (*(volatile uint16_t *)(a))

/* PPU */
#define INIDISP  REG8(0x2100)
#define OBSEL    REG8(0x2101)
#define OAMADD   REG16(0x2102)
#define BGMODE   REG8(0x2105)
#define BG12NBA  REG8(0x210B)
#define BG34NBA  REG8(0x210C)
#define VMAIN    REG8(0x2115)
#define VMADD    REG16(0x2116)
#define M7SEL    REG8(0x211A)
#define M7A      REG8(0x211B)   /* M7A..M7Y: each written twice, low byte then high byte */
#define M7B      REG8(0x211C)
#define M7C      REG8(0x211D)
#define M7D      REG8(0x211E)
#define M7X      REG8(0x211F)
#define M7Y      REG8(0x2120)
#define CGADD    REG8(0x2121)
#define CGDATA   REG8(0x2122)
#define TM       REG8(0x212C)
#define TS       REG8(0x212D)
#define SLHV     REG8(0x2137)   /* reading it latches the H/V counters */
#define OPVCT    REG8(0x213D)   /* latched V counter: low byte, then bit 8 */
#define STAT78   REG8(0x213F)   /* reading it resets the OPHCT/OPVCT byte order */

/* Ports no hardware uses, for the checks (dampf's emulator, dev/example_check.lua). $21FA and
 * $21FB are the benchmark's and the tests' (a tick of the benchmark, the end of a test). */
#define DBG_HASH REG8(0x21FC)   /* sdevkit_dbg_hash: five bytes, address and size */
#define DBG_TICK REG8(0x21FD)   /* the main loop: 1 after game_init, 0 after each game_tick */

/* CPU */
#define NMITIMEN REG8(0x4200)
#define WRIO     REG8(0x4201)
#define MDMAEN   REG8(0x420B)
#define HDMAEN   REG8(0x420C)
#define MEMSEL   REG8(0x420D)
#define HVBJOY   REG8(0x4212)   /* bit 7: vertical blank, bit 0: auto joypad read busy */
#define JOY1     REG16(0x4218)
#define JOY2     REG16(0x421A)

/* DMA channel n: $43n0.. */
#define DMAP(n)  REG8(0x4300 + ((n) << 4))
#define BBAD(n)  REG8(0x4301 + ((n) << 4))
#define A1T(n)   REG16(0x4302 + ((n) << 4))
#define A1B(n)   REG8(0x4304 + ((n) << 4))
#define DAS(n)   REG16(0x4305 + ((n) << 4))

/* NTSC timing */
#define VBLANK_FIRST_LINE 225
#define LINES             262

#endif
