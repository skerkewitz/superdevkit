#ifndef BB_SNES_H
#define BB_SNES_H

/*
 * The console API of superdevkit (ADR record 29): how a game draws on the SNES.
 *
 * lib/snes implements it on the console, compiled by dampf with the game; on the PC, Blubber
 * Blasen's pseudo SNES implements it for now (until it moves to lib/pc). Every function of the
 * first two groups exists on every platform; lib/README.md lists per function whether it is
 * built or a stub.
 *
 * Three groups of functions:
 *   - PVSnesLib functions: same name, same parameters, same meaning as in PVSnesLib (the headers
 *     in pvsneslib/include/snes). One deliberate difference: sources are `const u8 *` (ROM
 *     data); PVSnesLib takes `u8 *`.
 *   - snesXxx(): extensions that PVSnesLib has no function for. On the SNES each is a few
 *     register writes.
 *   - snes_xxx(): the PC side, for the PC's platform layer, tests and debug views. Games do not
 *     call them; on the SNES only snes_vram() exists, for a debug check of Blubber Blasen.
 *
 * Addresses are SNES addresses: VRAM addresses count 16-bit words (0x0000..0x7FFF), CGRAM
 * addresses count colours (0..255), OAM ids are byte offsets (sprite n = n * 4), as in
 * PVSnesLib.
 *
 * The frame rhythm (what the library's main loop calls) is in snes/loop.h.
 *
 * This header was taken over from Blubber Blasen's src/snes/snes.h; its declarations stay the
 * same as the game's copy until the game uses this one (dev/update_game_snapshot.sh warns when
 * they differ).
 */

#include <stdint.h>

typedef uint8_t  u8;
typedef uint16_t u16;
typedef int16_t  s16;
typedef uint32_t u32;

/* --- Colours (video.h) -------------------------------------------------------- */
#define RGB5(r, g, b) ((u16)((r) | ((g) << 5) | ((b) << 10)))
#define RGB8(r, g, b) ((u16)(((r) >> 3) | (((g) >> 3) << 5) | (((b) >> 3) << 10)))

/* --- Modes and layers (background.h) -------------------------------------------- */
#define BG_MODE1                1
#define BG3_MODE1_PRIORITY_HIGH (1 << 3)   /* BG3 tiles with priority 1 above everything */
#define BG1_ENABLE (1 << 0)
#define BG2_ENABLE (1 << 1)
#define BG3_ENABLE (1 << 2)
#define BG4_ENABLE (1 << 3)
#define OBJ_ENABLE (1 << 4)

#define SC_32x32 0
#define SC_64x32 1
#define SC_32x64 2
#define SC_64x64 3

#define BG_4COLORS  4
#define BG_16COLORS 16

/* Background map entries */
#define BG_TIL_NUM(n)  ((u16)(n))          /* 0..1023 */
#define BG_TIL_PAL(n)  ((u16)((n) << 10))  /* 0..7 */
#define BG_TIL_PRIO    ((u16)(1 << 13))
#define BG_TIL_FLIPX   ((u16)(1 << 14))
#define BG_TIL_FLIPY   ((u16)(1 << 15))

/* --- Sprites (sprite.h) ---------------------------------------------------------- */
#define OBJ_SIZE8_L16  (0 << 5)
#define OBJ_SIZE8_L32  (1 << 5)
#define OBJ_SIZE8_L64  (2 << 5)
#define OBJ_SIZE16_L32 (3 << 5)
#define OBJ_SIZE16_L64 (4 << 5)
#define OBJ_SIZE32_L64 (5 << 5)
#define OBJ_SMALL 0
#define OBJ_LARGE 1
#define OBJ_SHOW  0
#define OBJ_HIDE  1
#define ATTR2_DISABLED 0xE8                /* y of a hidden sprite */

/* The OAM shadow: oamSet() and friends write here; the vertical blank
 * copies it into the PPU's OAM (snes_vblank on the PC). 128 entries of
 * 4 bytes, then 32 bytes with 2 bits per sprite (x bit 8, size). */
extern u8 oamMemory[128 * 4 + 8 * 4];

/* --- Colour math (video.h) -------------------------------------------------------- */
/* CGWSEL (setColorEffect's first value) */
#define CM_SUBBGOBJ_ENABLE  (1 << 1)       /* add/subtract the sub screen, not the fixed colour */
#define CM_ALWAYS_ENABLE    (0 << 4)       /* colour math: everywhere ... */
#define CM_MATWIN_ENABLE    (1 << 4)       /* ... only inside the colour window */
#define CM_NOMATWIN_ENABLE  (2 << 4)       /* ... only outside the colour window */
#define CM_NEVER_ENABLE     (3 << 4)
#define CM_SCBLK_ALWAYS_ENABLE   (0 << 6)  /* main screen black: never */
#define CM_SCBLK_MATWIN_ENABLE   (1 << 6)  /* ... outside the colour window */
#define CM_SCBLK_NOMATWIN_ENABLE (2 << 6)  /* ... inside the colour window */
#define CM_SCBLK_NEVER_ENABLE    (3 << 6)  /* ... always */
/* CGADSUB (setColorEffect's second value) */
#define CM_SUB_MAINSUB (1 << 7)            /* subtract instead of add */
#define CM_DIV2_DIVIDE (1 << 6)            /* halve the result */
#define CM_MSCR_BACK   (1 << 5)            /* colour math on the backdrop ... */
#define CM_MSCR_PAL47  (1 << 4)            /* ... on sprites with palettes 4..7 */
#define CM_MSCR_BG4    (1 << 3)
#define CM_MSCR_BG3    (1 << 2)
#define CM_MSCR_BG2    (1 << 1)
#define CM_MSCR_BG1    (1 << 0)
/* COLDATA channels (setColorIntensity) */
#define CM_APPLY_BLUE  (1 << 7)
#define CM_APPLY_GREEN (1 << 6)
#define CM_APPLY_RED   (1 << 5)

/* --- HDMA (dma.h) ---------------------------------------------------------------- */
#define HDMA_CHANNEL0 (1 << 0)
#define HDMA_CHANNEL1 (1 << 1)
#define HDMA_CHANNEL2 (1 << 2)
#define HDMA_CHANNEL3 (1 << 3)
#define HDMA_CHANNEL4 (1 << 4)
#define HDMA_CHANNEL5 (1 << 5)
#define HDMA_CHANNEL6 (1 << 6)
#define HDMA_CHANNEL7 (1 << 7)

/* --- PPU registers (low byte of $21xx), for snesRegWrite and HDMA ------------------ */
#define PPU_INIDISP 0x00
#define PPU_OBSEL   0x01
#define PPU_BGMODE  0x05
#define PPU_BG1SC   0x07
#define PPU_BG2SC   0x08
#define PPU_BG3SC   0x09
#define PPU_BG4SC   0x0A
#define PPU_BG12NBA 0x0B
#define PPU_BG34NBA 0x0C
#define PPU_BG1HOFS 0x0D   /* BGnHOFS = 0x0D + 2(n-1), BGnVOFS = 0x0E + 2(n-1); written twice, low then high */
#define PPU_BG1VOFS 0x0E
#define PPU_W12SEL  0x23
#define PPU_W34SEL  0x24
#define PPU_WOBJSEL 0x25
#define PPU_WH0     0x26   /* window 1 left */
#define PPU_WH1     0x27   /* window 1 right */
#define PPU_WH2     0x28   /* window 2 left */
#define PPU_WH3     0x29   /* window 2 right */
#define PPU_WBGLOG  0x2A
#define PPU_WOBJLOG 0x2B
#define PPU_TM      0x2C
#define PPU_TS      0x2D
#define PPU_TMW     0x2E
#define PPU_TSW     0x2F
#define PPU_CGWSEL  0x30
#define PPU_CGADSUB 0x31
#define PPU_COLDATA 0x32

/* --- PVSnesLib functions ------------------------------------------------------------ */
/* video.h */
void setMode(u8 mode, u8 size);            /* BGMODE; shows BG1..BG3 and sprites on the main screen */
void setBrightness(u8 level);              /* 0: screen off (forced blank), 1..15: on, that bright */
void setScreenOn(void);                    /* PVSnesLib waits for a vertical blank here: never call it in one */
void setScreenOff(void);                   /* forced blank: black */
void setColorEffect(u8 colorMathA, u8 colorMathB);    /* CGWSEL, CGADSUB */
void setColorIntensity(u8 colorApply, u8 intensity);  /* COLDATA: the fixed colour */
void setPaletteColor(u16 paletteEntry, u16 paletteColor);
/* dma.h */
void dmaCopyVram(const u8 *source, u16 address, u16 size);    /* size in bytes */
void dmaCopyCGram(const u8 *source, u16 address, u16 size);   /* size in bytes */
void dmaClearVram(void);
/* background.h */
void bgSetGfxPtr(u8 bgNumber, u16 address);             /* bgNumber 0..3; 4K-word steps */
void bgSetMapPtr(u8 bgNumber, u16 address, u8 mapSize); /* 1K-word steps; SC_* */
void bgSetScroll(u8 bgNumber, u16 x, u16 y);
void bgSetEnable(u8 bgNumber);
void bgSetDisable(u8 bgNumber);
void bgSetEnableSub(u8 bgNumber);
void bgSetDisableSub(u8 bgNumber);
void bgInitTileSet(u8 bgNumber, const u8 *tileSource, const u8 *tilePalette, u8 paletteEntry, u16 tileSize,
                   u16 paletteSize, u16 colorMode, u16 address);
void bgInitMapSet(u8 bgNumber, const u8 *mapSource, u16 mapSize, u8 sizeMode, u16 address);
/* sprite.h */
void oamInit(void);
void oamInitGfxSet(const u8 *tileSource, u16 tileSize, const u8 *tilePalette, u16 paletteSize, u8 tilePaletteNumber,
                   u16 address, u8 oamsize);
void oamInitGfxAttr(u16 address, u8 oamsize);
void oamSet(u16 id, u16 xspr, u16 yspr, u8 priority, u8 hflip, u8 vflip, u16 gfxoffset, u8 paletteoffset);
void oamSetXY(u16 id, u16 xspr, u16 yspr);
void oamSetEx(u16 id, u8 size, u8 hide);
void oamSetVisible(u16 id, u8 hide);
void oamClear(u16 first, u8 numEntries);   /* hides numEntries sprites from id first; 0 = all */

/* --- Extensions (no PVSnesLib function; a few register writes on the SNES) ----------- */
/* Writes a PPU register (low byte of its $21xx address, PPU_*). PVSnesLib
 * programs write the REG_* variables for this. */
void snesRegWrite(u8 reg, u8 value);
/* HDMA in direct mode: from the next frame on, the channel writes table
 * data into reg (and reg + 1 when twoRegs) at the start of every line.
 * Table format as on the SNES: a line count byte, then the data; count
 * 1..127 = data once, then hold for that many lines; 129..255 = new data on
 * each of (count - 128) lines; 0 ends the table. The table is read when the
 * frame is drawn, so it must stay valid (a static or ROM array). */
void snesHdmaSet(u8 channel, u8 reg, u8 twoRegs, const u8 *table);
void snesHdmaEnable(u8 channels);          /* HDMAEN: which channels run */
/* 1 if a copy of this many bytes to VRAM or CGRAM still ends inside the
 * current vertical blank. On the SNES it reads the line counter, so the code
 * around the copies counts too; with the screen off the caller does not ask.
 * What does not fit waits for the next vertical blank instead of being lost. */
u8   snesDmaFits(u16 bytes);

/* --- PC side: platform layer, tests and debug view ------------------------------------ */
#define SNES_VRAM_WORDS 0x8000
#define SNES_W 256
#define SNES_H 224

/* What one vertical blank can copy into VRAM and CGRAM: NTSC lines 225..261
 * are 37 x 1364 master cycles, DMA takes 8 per byte, less the 544 bytes of
 * the OAM copy. Writes beyond it, or outside the vertical blank, are lost
 * unless the screen is switched off (forced blank). */
#define SNES_VBLANK_DMA_BYTES 5700

typedef struct {
    u16 dma_bytes;          /* bytes copied by DMA in the last vertical blank */
    u16 dma_lost;           /* bytes written to VRAM / CGRAM that did not arrive (outside the vertical blank or over budget) */
    u8  range_over_lines;   /* lines with more than 32 sprites (the rest are not drawn) */
    u8  time_over_lines;    /* lines with more than 34 sprite tiles (some tiles are not drawn) */
    u8  most_sprites;       /* on the busiest line */
    u8  most_tiles;
} SnesStats;

void        snes_reset(void);              /* power on: memories cleared, screen off */
/* The frame rhythm of the console: snes_vblank() starts the vertical blank
 * (OAM shadow to OAM, DMA budget restarts), snes_render() draws the picture,
 * which ends it. VRAM and CGRAM accept writes only in between, within
 * SNES_VBLANK_DMA_BYTES, or while the screen is off. */
void        snes_vblank(void);
void        snes_render(u16 *out);         /* SNES_W * SNES_H colours, BGR 5:5:5 */
void        snes_display(void);            /* the picture is drawn, without making it (tests): VRAM closes */
SnesStats   snes_stats(void);              /* of the last snes_render (DMA: of the last frame) */
const u16  *snes_vram(void);               /* SNES_VRAM_WORDS words */
const u16  *snes_cgram(void);              /* 256 colours */
const u8   *snes_oam(void);                /* 544 bytes, as copied at the last vertical blank */
u8          snes_reg(u8 reg);              /* last value written to a PPU register */

#endif /* BB_SNES_H */
