#ifndef SNES_PSEUDO_H
#define SNES_PSEUDO_H

/* State shared by ppu.c (memories, registers) and render.c (the picture) of the pseudo SNES. Taken
 * over from Blubber Blasen's src/snes (snes_internal.h); 16 x 16 background tiles are new. */

#include "snes/snes.h"

typedef struct {
    u8        reg;          /* PPU_* the channel writes */
    u8        two;          /* also reg + 1 */
    const u8 *table;
} SnesHdma;

typedef struct {
    u16       vram[SNES_VRAM_WORDS];
    u16       cgram[256];
    u8        oam[128 * 4 + 8 * 4];   /* copied from oamMemory at the vertical blank */
    u8        regs[0x40];             /* last value written, by PPU_* */
    u16       hofs[4], vofs[4];       /* background scroll, 10 bits */
    u8        scroll_latch;
    u8        fixed_r, fixed_g, fixed_b;   /* COLDATA, 5 bits each */
    SnesHdma  hdma[8];
    u8        hdma_enabled;
    u16       dma_count;              /* bytes since the last vertical blank */
    u16       dma_lost;               /* bytes that did not arrive since then */
    u8        in_vblank;              /* between snes_vblank() and snes_render() */
    SnesStats stats;
} SnesState;

extern SnesState g_snes;

void snes_write_reg(u8 reg, u8 value);

#endif /* SNES_PSEUDO_H */
