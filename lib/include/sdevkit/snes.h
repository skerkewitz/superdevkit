#ifndef SDEVKIT_SNES_H
#define SDEVKIT_SNES_H

/*
 * The console API of superdevkit (ADR records 29 and 34): how a game draws on the SNES. Every
 * name a game can use starts with sdevkit_ or SDEVKIT_; nothing else of the kit is meant for it.
 *
 * lib/snes implements it on the console, compiled by dampf with the game; lib/pseudo, the pseudo
 * SNES, implements it on the desktop. Every function exists on every platform; lib/README.md
 * lists per function whether it is built or a stub.
 *
 * Three groups of functions:
 *   - PVSnesLib's functions, renamed: the same parameters and meaning as in PVSnesLib (the headers
 *     in pvsneslib/include/snes), sdevkit_ + the name in snake_case (setMode is
 *     sdevkit_set_mode). One deliberate difference: sources are const uint8_t * (ROM data);
 *     PVSnesLib takes u8 *.
 *   - Extensions that PVSnesLib has no function for. On the SNES each is a few register writes.
 *   - sdevkit_dbg_*: for debug checks and the debug view of the desktop. They exist on every
 *     platform and never change what the game computes.
 *
 * Addresses are SNES addresses: VRAM addresses count 16-bit words (0x0000..0x7FFF), CGRAM
 * addresses count colours (0..255), OAM ids are byte offsets (sprite n = n * 4), as in
 * PVSnesLib.
 *
 * The frame rhythm (what the library's main loop calls) is in sdevkit/loop.h. Code written for
 * the old names converts with tools/sdevkit_rename.py.
 */

#include <stdint.h>

/* --- Colours (video.h) -------------------------------------------------------- */
#define SDEVKIT_RGB5(r, g, b) ((uint16_t)((r) | ((g) << 5) | ((b) << 10)))
#define SDEVKIT_RGB8(r, g, b) ((uint16_t)(((r) >> 3) | (((g) >> 3) << 5) | (((b) >> 3) << 10)))

/* --- Modes and layers (background.h) -------------------------------------------- */
#define SDEVKIT_BG_MODE1                1
#define SDEVKIT_BG3_MODE1_PRIORITY_HIGH (1 << 3)   /* BG3 tiles with priority 1 above everything */
#define SDEVKIT_BG1_ENABLE (1 << 0)
#define SDEVKIT_BG2_ENABLE (1 << 1)
#define SDEVKIT_BG3_ENABLE (1 << 2)
#define SDEVKIT_BG4_ENABLE (1 << 3)
#define SDEVKIT_OBJ_ENABLE (1 << 4)

#define SDEVKIT_SC_32x32 0
#define SDEVKIT_SC_64x32 1
#define SDEVKIT_SC_32x64 2
#define SDEVKIT_SC_64x64 3

#define SDEVKIT_BG_4COLORS  4
#define SDEVKIT_BG_16COLORS 16

/* Background map entries */
#define SDEVKIT_BG_TIL_NUM(n)  ((uint16_t)(n))          /* 0..1023 */
#define SDEVKIT_BG_TIL_PAL(n)  ((uint16_t)((n) << 10))  /* 0..7 */
#define SDEVKIT_BG_TIL_PRIO    ((uint16_t)(1 << 13))
#define SDEVKIT_BG_TIL_FLIPX   ((uint16_t)(1 << 14))
#define SDEVKIT_BG_TIL_FLIPY   ((uint16_t)(1 << 15))

/* --- Sprites (sprite.h) ---------------------------------------------------------- */
#define SDEVKIT_OBJ_SIZE8_L16  (0 << 5)
#define SDEVKIT_OBJ_SIZE8_L32  (1 << 5)
#define SDEVKIT_OBJ_SIZE8_L64  (2 << 5)
#define SDEVKIT_OBJ_SIZE16_L32 (3 << 5)
#define SDEVKIT_OBJ_SIZE16_L64 (4 << 5)
#define SDEVKIT_OBJ_SIZE32_L64 (5 << 5)
#define SDEVKIT_OBJ_SMALL 0
#define SDEVKIT_OBJ_LARGE 1
#define SDEVKIT_OBJ_SHOW  0
#define SDEVKIT_OBJ_HIDE  1
#define SDEVKIT_ATTR2_DISABLED 0xE8                /* y of a hidden sprite */

/* The OAM shadow: sdevkit_oam_set() and friends write here; the vertical blank
 * copies it into the PPU's OAM (the pseudo SNES's vertical blank on the PC). 128 entries of
 * 4 bytes, then 32 bytes with 2 bits per sprite (x bit 8, size). */
extern uint8_t sdevkit_oam_memory[128 * 4 + 8 * 4];

/* --- Colour math (video.h) -------------------------------------------------------- */
/* CGWSEL (sdevkit_set_color_effect's first value) */
#define SDEVKIT_CM_SUBBGOBJ_ENABLE  (1 << 1)       /* add/subtract the sub screen, not the fixed colour */
#define SDEVKIT_CM_ALWAYS_ENABLE    (0 << 4)       /* colour math: everywhere ... */
#define SDEVKIT_CM_MATWIN_ENABLE    (1 << 4)       /* ... only inside the colour window */
#define SDEVKIT_CM_NOMATWIN_ENABLE  (2 << 4)       /* ... only outside the colour window */
#define SDEVKIT_CM_NEVER_ENABLE     (3 << 4)
#define SDEVKIT_CM_SCBLK_ALWAYS_ENABLE   (0 << 6)  /* main screen black: never */
#define SDEVKIT_CM_SCBLK_MATWIN_ENABLE   (1 << 6)  /* ... outside the colour window */
#define SDEVKIT_CM_SCBLK_NOMATWIN_ENABLE (2 << 6)  /* ... inside the colour window */
#define SDEVKIT_CM_SCBLK_NEVER_ENABLE    (3 << 6)  /* ... always */
/* CGADSUB (sdevkit_set_color_effect's second value) */
#define SDEVKIT_CM_SUB_MAINSUB (1 << 7)            /* subtract instead of add */
#define SDEVKIT_CM_DIV2_DIVIDE (1 << 6)            /* halve the result */
#define SDEVKIT_CM_MSCR_BACK   (1 << 5)            /* colour math on the backdrop ... */
#define SDEVKIT_CM_MSCR_PAL47  (1 << 4)            /* ... on sprites with palettes 4..7 */
#define SDEVKIT_CM_MSCR_BG4    (1 << 3)
#define SDEVKIT_CM_MSCR_BG3    (1 << 2)
#define SDEVKIT_CM_MSCR_BG2    (1 << 1)
#define SDEVKIT_CM_MSCR_BG1    (1 << 0)
/* COLDATA channels (sdevkit_set_color_intensity) */
#define SDEVKIT_CM_APPLY_BLUE  (1 << 7)
#define SDEVKIT_CM_APPLY_GREEN (1 << 6)
#define SDEVKIT_CM_APPLY_RED   (1 << 5)

/* --- HDMA (dma.h) ---------------------------------------------------------------- */
#define SDEVKIT_HDMA_CHANNEL0 (1 << 0)
#define SDEVKIT_HDMA_CHANNEL1 (1 << 1)
#define SDEVKIT_HDMA_CHANNEL2 (1 << 2)
#define SDEVKIT_HDMA_CHANNEL3 (1 << 3)
#define SDEVKIT_HDMA_CHANNEL4 (1 << 4)
#define SDEVKIT_HDMA_CHANNEL5 (1 << 5)
#define SDEVKIT_HDMA_CHANNEL6 (1 << 6)
#define SDEVKIT_HDMA_CHANNEL7 (1 << 7)

/* --- PPU registers (low byte of $21xx), for sdevkit_reg_write and HDMA ------------------ */
#define SDEVKIT_PPU_INIDISP 0x00
#define SDEVKIT_PPU_OBSEL   0x01
#define SDEVKIT_PPU_BGMODE  0x05
#define SDEVKIT_PPU_BG1SC   0x07
#define SDEVKIT_PPU_BG2SC   0x08
#define SDEVKIT_PPU_BG3SC   0x09
#define SDEVKIT_PPU_BG4SC   0x0A
#define SDEVKIT_PPU_BG12NBA 0x0B
#define SDEVKIT_PPU_BG34NBA 0x0C
#define SDEVKIT_PPU_BG1HOFS 0x0D   /* BGnHOFS = 0x0D + 2(n-1), BGnVOFS = 0x0E + 2(n-1); written twice, low then high */
#define SDEVKIT_PPU_BG1VOFS 0x0E
#define SDEVKIT_PPU_W12SEL  0x23
#define SDEVKIT_PPU_W34SEL  0x24
#define SDEVKIT_PPU_WOBJSEL 0x25
#define SDEVKIT_PPU_WH0     0x26   /* window 1 left */
#define SDEVKIT_PPU_WH1     0x27   /* window 1 right */
#define SDEVKIT_PPU_WH2     0x28   /* window 2 left */
#define SDEVKIT_PPU_WH3     0x29   /* window 2 right */
#define SDEVKIT_PPU_WBGLOG  0x2A
#define SDEVKIT_PPU_WOBJLOG 0x2B
#define SDEVKIT_PPU_TM      0x2C
#define SDEVKIT_PPU_TS      0x2D
#define SDEVKIT_PPU_TMW     0x2E
#define SDEVKIT_PPU_TSW     0x2F
#define SDEVKIT_PPU_CGWSEL  0x30
#define SDEVKIT_PPU_CGADSUB 0x31
#define SDEVKIT_PPU_COLDATA 0x32

/* --- PVSnesLib's functions, renamed (ADR record 34) ------------------------------------ */
/* video.h */
void sdevkit_set_mode(uint8_t mode, uint8_t size);            /* BGMODE; shows BG1..BG3 and sprites on the main screen */
void sdevkit_set_brightness(uint8_t level);              /* 0: screen off (forced blank), 1..15: on, that bright */
void sdevkit_set_screen_on(void);                    /* PVSnesLib waits for a vertical blank here: never call it in one */
void sdevkit_set_screen_off(void);                   /* forced blank: black */
void sdevkit_set_color_effect(uint8_t colorMathA, uint8_t colorMathB);    /* CGWSEL, CGADSUB */
void sdevkit_set_color_intensity(uint8_t colorApply, uint8_t intensity);  /* COLDATA: the fixed colour */
void sdevkit_set_palette_color(uint16_t paletteEntry, uint16_t paletteColor);
/* dma.h */
void sdevkit_dma_copy_vram(const uint8_t *source, uint16_t address, uint16_t size);    /* size in bytes */
void sdevkit_dma_copy_cgram(const uint8_t *source, uint16_t address, uint16_t size);   /* size in bytes */
void sdevkit_dma_clear_vram(void);
/* background.h */
void sdevkit_bg_set_gfx_ptr(uint8_t bgNumber, uint16_t address);             /* bgNumber 0..3; 4K-word steps */
void sdevkit_bg_set_map_ptr(uint8_t bgNumber, uint16_t address, uint8_t mapSize); /* 1K-word steps; SDEVKIT_SC_* */
void sdevkit_bg_set_scroll(uint8_t bgNumber, uint16_t x, uint16_t y);
void sdevkit_bg_set_enable(uint8_t bgNumber);
void sdevkit_bg_set_disable(uint8_t bgNumber);
void sdevkit_bg_set_enable_sub(uint8_t bgNumber);
void sdevkit_bg_set_disable_sub(uint8_t bgNumber);
void sdevkit_bg_init_tile_set(uint8_t bgNumber, const uint8_t *tileSource, const uint8_t *tilePalette, uint8_t paletteEntry, uint16_t tileSize,
                   uint16_t paletteSize, uint16_t colorMode, uint16_t address);
void sdevkit_bg_init_map_set(uint8_t bgNumber, const uint8_t *mapSource, uint16_t mapSize, uint8_t sizeMode, uint16_t address);
/* sprite.h */
void sdevkit_oam_init(void);
void sdevkit_oam_init_gfx_set(const uint8_t *tileSource, uint16_t tileSize, const uint8_t *tilePalette, uint16_t paletteSize, uint8_t tilePaletteNumber,
                   uint16_t address, uint8_t oamsize);
void sdevkit_oam_init_gfx_attr(uint16_t address, uint8_t oamsize);
void sdevkit_oam_set(uint16_t id, uint16_t xspr, uint16_t yspr, uint8_t priority, uint8_t hflip, uint8_t vflip, uint16_t gfxoffset, uint8_t paletteoffset);
void sdevkit_oam_set_xy(uint16_t id, uint16_t xspr, uint16_t yspr);
void sdevkit_oam_set_ex(uint16_t id, uint8_t size, uint8_t hide);
void sdevkit_oam_set_visible(uint16_t id, uint8_t hide);
void sdevkit_oam_clear(uint16_t first, uint8_t numEntries);   /* hides numEntries sprites from id first; 0 = all */

/* --- Extensions (no PVSnesLib function; a few register writes on the SNES) ----------- */
/* Writes a PPU register (low byte of its $21xx address, SDEVKIT_PPU_*). PVSnesLib
 * programs write the REG_* variables for this. */
void sdevkit_reg_write(uint8_t reg, uint8_t value);
/* HDMA in direct mode: from the next frame on, the channel writes table
 * data into reg (and reg + 1 when twoRegs) at the start of every line.
 * Table format as on the SNES: a line count byte, then the data; count
 * 1..127 = data once, then hold for that many lines; 129..255 = new data on
 * each of (count - 128) lines; 0 ends the table. The table is read when the
 * frame is drawn, so it must stay valid (a static or ROM array). */
void sdevkit_hdma_set(uint8_t channel, uint8_t reg, uint8_t twoRegs, const uint8_t *table);
void sdevkit_hdma_enable(uint8_t channels);          /* HDMAEN: which channels run */
/* 1 if a copy of this many bytes to VRAM or CGRAM still ends inside the
 * current vertical blank. On the SNES it reads the line counter, so the code
 * around the copies counts too; with the screen off the caller does not ask.
 * What does not fit waits for the next vertical blank instead of being lost. */
uint8_t   sdevkit_dma_fits(uint16_t bytes);

/* --- Debug (sdevkit_dbg_*) --------------------------------------------------------------- */
/* VRAM as 0x8000 words, for a debug check of the tile maps on the desktop. Only the pseudo SNES
 * can read VRAM like memory; on the SNES it returns a single zero word, so a check that uses it
 * runs only on the desktop. */
const uint16_t *sdevkit_dbg_vram(void);

#endif /* SDEVKIT_SNES_H */
