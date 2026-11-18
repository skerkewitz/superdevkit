#ifndef SDEVKIT__RACHAEL_H
#define SDEVKIT__RACHAEL_H

/* Rachael's own interface (ADR record 34: internal names start with sdevkit__): what the
 * frontends (lib/desktop) and the tests call, and the state shared by ppu.c (memories, registers)
 * and render.c (the picture). Not for games: they see only sdevkit/snes.h. Taken over from
 * Blubber Blasen's src/snes (snes.h's PC side, snes_internal.h); 16 x 16 background tiles are new. */

#include "sdevkit/snes.h"

#define SDEVKIT__VRAM_WORDS 0x8000
#define SDEVKIT__W 256
#define SDEVKIT__H 224

/* What one vertical blank can copy into VRAM and CGRAM: NTSC lines 225..261
 * are 37 x 1364 master cycles, DMA takes 8 per byte, less the 544 bytes of
 * the OAM copy. Writes beyond it, or outside the vertical blank, are lost
 * unless the screen is switched off (forced blank). */
#define SDEVKIT__VBLANK_DMA_BYTES 5700

typedef struct {
    uint16_t dma_bytes;          /* bytes copied by DMA in the last vertical blank */
    uint16_t dma_lost;           /* bytes written to VRAM / CGRAM that did not arrive (outside the vertical blank or over budget) */
    uint8_t  range_over_lines;   /* lines with more than 32 sprites (the rest are not drawn) */
    uint8_t  time_over_lines;    /* lines with more than 34 sprite tiles (some tiles are not drawn) */
    uint8_t  most_sprites;       /* on the busiest line */
    uint8_t  most_tiles;
} sdevkit__frame_stats;

void                 sdevkit__reset(void);              /* power on: memories cleared, screen off */
/* The frame rhythm of the console: sdevkit__vblank() starts the vertical blank
 * (OAM shadow to OAM, DMA budget restarts), sdevkit__render() draws the picture,
 * which ends it. VRAM and CGRAM accept writes only in between, within
 * SDEVKIT__VBLANK_DMA_BYTES, or while the screen is off. */
void                 sdevkit__vblank(void);
void                 sdevkit__render(uint16_t *out);    /* SDEVKIT__W * SDEVKIT__H colours, BGR 5:5:5 */
void                 sdevkit__display(void);            /* the picture is drawn, without making it (tests): VRAM closes */
sdevkit__frame_stats sdevkit__stats(void);              /* of the last sdevkit__render (DMA: of the last frame) */
const uint16_t      *sdevkit__cgram(void);              /* 256 colours */
const uint8_t       *sdevkit__oam(void);                /* 544 bytes, as copied at the last vertical blank */
uint8_t              sdevkit__reg(uint8_t reg);         /* last value written to a PPU register */

typedef struct {
    uint8_t        reg;          /* SDEVKIT_PPU_* the channel writes */
    uint8_t        two;          /* also reg + 1 */
    const uint8_t *table;
} sdevkit__hdma_channel;

typedef struct {
    uint16_t       vram[SDEVKIT__VRAM_WORDS];
    uint16_t       cgram[256];
    uint8_t        oam[128 * 4 + 8 * 4];   /* copied from sdevkit_oam_memory at the vertical blank */
    uint8_t        regs[0x40];             /* last value written, by SDEVKIT_PPU_* */
    uint16_t       hofs[4], vofs[4];       /* background scroll, 10 bits */
    uint8_t        scroll_latch;
    uint8_t        fixed_r, fixed_g, fixed_b;   /* COLDATA, 5 bits each */
    sdevkit__hdma_channel  hdma[8];
    uint8_t        hdma_enabled;
    uint16_t       dma_count;              /* bytes since the last vertical blank */
    uint16_t       dma_lost;               /* bytes that did not arrive since then */
    uint8_t        in_vblank;              /* between sdevkit__vblank() and sdevkit__render() */
    sdevkit__frame_stats stats;
} sdevkit__rachael_state;

extern sdevkit__rachael_state sdevkit__rachael;

void sdevkit__write_reg(uint8_t reg, uint8_t value);

#endif /* SDEVKIT__RACHAEL_H */
