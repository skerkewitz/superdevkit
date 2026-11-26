#ifndef SDEVKIT__RACHAEL_H
#define SDEVKIT__RACHAEL_H

/* Rachael's own interface (ADR-6F3CK2AR: internal names start with sdevkit__): what the
 * frontends (lib/desktop) and the tests call, and the state shared by ppu.c (memories, registers)
 * and render.c (the picture). Not for games: they see only sdevkit/snes.h. Taken over from
 * Blubber Blasen's src/snes (snes.h's PC side, snes_internal.h); 16 x 16 background tiles are new. */

#include "sdevkit/snes.h"

#define SDEVKIT__VRAM_WORDS 0x8000
#define SDEVKIT__W 256
#define SDEVKIT__H 224

/* What one vertical blank can copy into VRAM, CGRAM and OAM: NTSC lines
 * 225..261 are 37 x 1364 master cycles, DMA takes 8 per byte, less a margin
 * for the setup. Writes beyond it, or outside the vertical blank, are lost
 * unless the screen is switched off (forced blank). */
#define SDEVKIT__VBLANK_DMA_BYTES 6244

typedef struct {
    uint16_t dma_bytes;          /* bytes copied by DMA in the last vertical blank */
    uint16_t dma_lost;           /* bytes written to VRAM / CGRAM / OAM that did not arrive (outside the vertical blank or over budget) */
    uint8_t  range_over_lines;   /* lines with more than 32 sprites (the rest are not drawn) */
    uint8_t  time_over_lines;    /* lines with more than 34 sprite tiles (some tiles are not drawn) */
    uint8_t  most_sprites;       /* on the busiest line */
    uint8_t  most_tiles;
} sdevkit__frame_stats;

void                 sdevkit__reset(void);              /* power on: memories cleared, screen off */
/* The frame rhythm of the console: sdevkit__vblank() starts the vertical blank
 * (the DMA budget restarts), sdevkit__render() draws the picture, which ends
 * it. VRAM, CGRAM and OAM accept writes only in between, within
 * SDEVKIT__VBLANK_DMA_BYTES, or while the screen is off; a register written
 * outside is counted (sdevkit__rachael.drawing). */
void                 sdevkit__vblank(void);
void                 sdevkit__render(uint16_t *out);    /* SDEVKIT__W * SDEVKIT__H colours, BGR 5:5:5 */
void                 sdevkit__display(void);            /* the picture is drawn, without making it (tests): VRAM closes */
sdevkit__frame_stats sdevkit__stats(void);              /* of the last sdevkit__render (DMA: of the last frame) */
const uint16_t      *sdevkit__cgram(void);              /* 256 colours */
const uint8_t       *sdevkit__oam(void);                /* 544 bytes, as OAM holds them */
uint8_t              sdevkit__reg(uint8_t reg);         /* last value written to a PPU register */
/* The checksum of the tick that just ended (the main loop calls it after game_tick): the bytes the
 * game named with sdevkit_dbg_hash since the last; the next tick starts afresh. */
#define SDEVKIT__HASH_START 2166136261u
uint32_t             sdevkit__tick_hash(void);

typedef struct {
    uint8_t        reg;          /* SDEVKIT_PPU_* the channel writes */
    uint8_t        two;          /* SDEVKIT_HDMA_*: one byte, reg and reg + 1, or reg twice */
    const uint8_t *table;
} sdevkit__hdma_channel;

typedef struct {
    uint16_t       vram[SDEVKIT__VRAM_WORDS];
    uint16_t       cgram[256];
    uint8_t        oam[128 * 4 + 8 * 4];   /* OAM, as the port and DMA wrote it */
    uint8_t        regs[0x40];             /* last value written, by SDEVKIT_PPU_* */
    uint16_t       hofs[4], vofs[4];       /* background scroll, 10 bits */
    uint8_t        scroll_latch;
    uint16_t       m7[6];                  /* M7A, M7B, M7C, M7D, M7X, M7Y as written (16 bits) */
    uint16_t       m7hofs, m7vofs;         /* BG1's scroll as mode 7 reads it (13 bits with sign) */
    uint8_t        m7_latch;               /* the previous byte written to $210D/E or $211B..$2120 */
    uint8_t        fixed_r, fixed_g, fixed_b;   /* COLDATA, 5 bits each */
    sdevkit__hdma_channel  hdma[8];
    uint8_t        hdma_enabled;
    uint16_t       dma_count;              /* bytes since the last vertical blank */
    uint16_t       dma_lost;               /* bytes that did not arrive since then */
    uint8_t        in_vblank;              /* between sdevkit__vblank() and sdevkit__render() */
    /* the ports of VRAM, CGRAM and OAM, as the SNES has them */
    uint8_t        vmain;                  /* VMAIN: the step, and after which byte */
    uint16_t       vram_addr;              /* VMADD: a word address */
    uint8_t        cg_addr;                /* CGADD: a colour */
    uint8_t        cg_latch, cg_high;      /* the low byte written, and that the next is the high one */
    uint16_t       oam_addr;               /* a byte address: OAMADD * 2, 0..543 */
    uint8_t        oam_latch;              /* the even byte of the low table, written with the odd one */
    uint16_t       drawing;                /* register writes while the picture was drawn, since power on */
    uint8_t        drawing_reg, drawing_value;   /* the first of them */
    uint16_t       unmarked;               /* sdevkit/shadow.h: groups changed without their mark, since power on */
    uint8_t        unmarked_group;         /* the first of them, SDEVKIT_MARK_* */
    uint16_t       undrawn;                /* register writes Rachael does not draw, since power on */
    uint8_t        irq_on, irq_row;        /* sdevkit/irq.h: the game's IRQ runs before picture row irq_row */
    uint8_t        in_irq;                 /* game_irq is running */
    uint8_t        irq_waited;             /* ... and has called sdevkit_irq_wait_hblank: its register writes are free */
    uint8_t        undrawn_reg, undrawn_value;   /* the first of them */
    sdevkit__frame_stats stats;
    uint32_t       tick_hash;              /* sdevkit_dbg_hash: FNV-1a of the bytes named this tick */
} sdevkit__rachael_state;

extern sdevkit__rachael_state sdevkit__rachael;

/* The game's IRQ handler (sdevkit/irq.h), or NULL: the frontend sets it from sdevkit__game_irq,
 * which superdevkit build generates next to the program (game_irq, or none if the game has no
 * game_irq). sdevkit__render calls it before the row sdevkit_irq_line set, after that row's HDMA. */
extern void (*sdevkit__irq_handler)(void);

/* A register write as the hardware takes it, without the check of when (the kit's own writes). */
void sdevkit__write_reg(uint8_t reg, uint8_t value);

/* The optional layers (layers.c): back to their state at power on; copies the queue refused. */
void    sdevkit__layers_reset(void);
uint8_t sdevkit__queue_refused(void);

/* --- Sound (dsp.c, sfx.c; docs/sound.md) ---------------------------------------------------- */
#define SDEVKIT__DSP_RATE 32000   /* the S-DSP's samples a second */

typedef struct {
    uint8_t  aram[65536];          /* the audio RAM: driver, sample directory, samples (sfx.c) */
    uint8_t  regs[128];            /* the DSP's registers, as last written */
    uint32_t upload_hash;          /* FNV-1a of the bytes uploaded, each with its address (sound.txt's "aram") */
    uint16_t unheard;              /* writes that switch on what Rachael does not play, since power on */
    uint8_t  unheard_reg, unheard_value;   /* the first of them ($FF: a sample with a loop) */
    void   (*log)(uint8_t reg, uint8_t value);   /* the frontend's check: told of every DSP write */
} sdevkit__sound_state;

extern sdevkit__sound_state sdevkit__sound;

void sdevkit__sound_reset(void);                       /* power on: silent, the registers as after reset */
void sdevkit__dsp_write(uint8_t reg, uint8_t value);   /* what the SPC700's driver does on the SNES */
/* The DSP's next samples at 32 kHz, left and right after each other: frames * 2 values. */
void sdevkit__dsp_render(int16_t *stereo, unsigned frames);
/* After each game_vblank (the frontend, as lib/snes/main.c): the effects the tick asked for start. */
void sdevkit__sfx_frame(void);

/* A register write whose effect Rachael does not draw, so the SNES would show something else:
 * a mode other than 1 and 7, mosaic, SETINI (interlace, high resolution, overscan, EXTBG),
 * direct colour (CGWSEL bit 0), VMAIN's address remapping, and a DMA she does not model (from
 * the PPU to memory, or HDMA with indirect tables).
 * Counted in sdevkit__rachael.undrawn; the frontends report it as they report lost bytes. */
uint8_t sdevkit__undrawn(uint8_t reg, uint8_t value);

/* --- Music (music.c; ADR-6F3CK03Z) --------------------------------------------------------- */
/* The track the game asked for (-1: none), and whether it changed since the frontend looked. The
 * frontend plays it from sdevkit__music_files, which the build writes (music_files.c next to the
 * program): the files of superdevkit.toml's [sound] music, in their order. */
typedef struct {
    int16_t track;
    uint8_t changed;
} sdevkit__music_state;

extern sdevkit__music_state sdevkit__music;
extern const char *const sdevkit__music_files[];
extern const int sdevkit__music_count;

void sdevkit__music_reset(void);   /* power on: no music */

/* --- The debug registrations (debug.c; ADR-6F3CK03Z) --------------------------------------- */
/* What the game registered with sdevkit_dbg_toggle, _range, _color, _action, _text and _value, for the desktop's
 * debug view, and the boxes sdevkit_dbg_box drew in the current tick. */
#define SDEVKIT__DBG_MAX   32
#define SDEVKIT__DBG_BOXES 256
enum { SDEVKIT__DBG_TOGGLE, SDEVKIT__DBG_RANGE, SDEVKIT__DBG_COLOR, SDEVKIT__DBG_ACTION, SDEVKIT__DBG_TEXT,
       SDEVKIT__DBG_VALUE };

typedef struct {
    uint8_t     kind;              /* SDEVKIT__DBG_* */
    const char *name;
    void       *p;                 /* uint8_t * (toggle, action), int16_t * (range), uint16_t * (colour,
                                      value), char * (text) */
    int16_t     lo, hi;            /* a range's bounds */
} sdevkit__dbg_entry;

enum { SDEVKIT__DBG_OUTLINE, SDEVKIT__DBG_FILLED, SDEVKIT__DBG_ARROW };

typedef struct {
    int16_t x, y;
    uint8_t w, h, kind;
    uint8_t style;                 /* SDEVKIT__DBG_OUTLINE, _FILLED or _ARROW */
    int8_t  dx, dy;                /* an arrow's way from (x, y) */
} sdevkit__dbg_rect;

extern sdevkit__dbg_entry sdevkit__dbg_entries[SDEVKIT__DBG_MAX];
extern int sdevkit__dbg_count;
extern sdevkit__dbg_rect sdevkit__dbg_boxes[SDEVKIT__DBG_BOXES];
extern int sdevkit__dbg_nboxes;

void sdevkit__dbg_reset(void);     /* power on: no registrations, no boxes */

#endif /* SDEVKIT__RACHAEL_H */
