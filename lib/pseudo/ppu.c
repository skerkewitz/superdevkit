/*
 * Pseudo SNES, the desktop side of superdevkit's library: the memories, the picture registers
 * and the functions of the console API that write them (snes/snes.h). render.c draws the picture
 * from this state; pseudo.h is what the two share. Taken over from Blubber Blasen's src/snes.
 *
 * Portable C99: no raylib, no heap, no floating point. Everything is static, like
 * the console's memory.
 */

#include "pseudo.h"

#include <string.h>

SnesState g_snes;
u8 oamMemory[128 * 4 + 8 * 4];

/* --- Registers ----------------------------------------------------------------------- */
void snes_write_reg(u8 reg, u8 value)
{
    if (reg >= PPU_BG1HOFS && reg <= PPU_BG1HOFS + 7) {
        /* BGnHOFS / BGnVOFS: written twice, low byte then high byte, through a
         * shared latch (simplified from the SNES formula; same result for
         * two writes in a row). */
        u8 bg = (u8)((reg - PPU_BG1HOFS) >> 1);
        u16 v = (u16)(((u16)value << 8) | g_snes.scroll_latch);
        if ((reg - PPU_BG1HOFS) & 1) {
            g_snes.vofs[bg] = (u16)(v & 0x3FF);
        } else {
            g_snes.hofs[bg] = (u16)(v & 0x3FF);
        }
        g_snes.scroll_latch = value;
    } else if (reg == PPU_COLDATA) {
        u8 i = (u8)(value & 0x1F);
        if (value & CM_APPLY_RED)   g_snes.fixed_r = i;
        if (value & CM_APPLY_GREEN) g_snes.fixed_g = i;
        if (value & CM_APPLY_BLUE)  g_snes.fixed_b = i;
    }
    g_snes.regs[reg] = value;
}

u8 snes_reg(u8 reg)
{
    return g_snes.regs[reg];
}

void snesRegWrite(u8 reg, u8 value)
{
    snes_write_reg(reg, value);
}

/* --- Power on and vertical blank -------------------------------------------------------- */
void snes_reset(void)
{
    memset(&g_snes, 0, sizeof g_snes);
    memset(oamMemory, 0, sizeof oamMemory);
    g_snes.regs[PPU_INIDISP] = 0x80;   /* forced blank until the game turns the screen on */
}

void snes_vblank(void)
{
    memcpy(g_snes.oam, oamMemory, sizeof g_snes.oam);
    g_snes.dma_count = 0;
    g_snes.dma_lost = 0;
    g_snes.in_vblank = 1;
}

void snes_display(void)
{
    g_snes.in_vblank = 0;
}

/* May this many bytes go to VRAM / CGRAM now? Counts them either way. */
static u8 dma_allowed(u16 size)
{
    u8 forced_blank = (u8)((g_snes.regs[PPU_INIDISP] & 0x80) != 0);
    if (forced_blank || (g_snes.in_vblank && g_snes.dma_count + size <= SNES_VBLANK_DMA_BYTES)) {
        g_snes.dma_count = (u16)(g_snes.dma_count + size);
        return 1;
    }
    g_snes.dma_lost = (u16)(g_snes.dma_lost + size);
    return 0;
}

SnesStats snes_stats(void)
{
    SnesStats s = g_snes.stats;
    s.dma_bytes = g_snes.dma_count;   /* of the current / last vertical blank */
    s.dma_lost = g_snes.dma_lost;
    return s;
}

const u16 *snes_vram(void)
{
    return g_snes.vram;
}

const u16 *snes_cgram(void)
{
    return g_snes.cgram;
}

const u8 *snes_oam(void)
{
    return g_snes.oam;
}

/* --- video.h ------------------------------------------------------------------------------ */
void setMode(u8 mode, u8 size)
{
    snes_write_reg(PPU_BGMODE, (u8)(mode | size));
    snes_write_reg(PPU_TM, BG1_ENABLE | BG2_ENABLE | BG3_ENABLE | OBJ_ENABLE);
}

/* As in PVSnesLib: 0 switches the screen off (forced blank), 1..15 on. */
void setBrightness(u8 level)
{
    snes_write_reg(PPU_INIDISP, (u8)(level ? (level & 0x0F) : 0x80));
}

void setScreenOn(void)
{
    snes_write_reg(PPU_INIDISP, 0x0F);
}

void setScreenOff(void)
{
    snes_write_reg(PPU_INIDISP, (u8)(g_snes.regs[PPU_INIDISP] | 0x80));
}

void setColorEffect(u8 colorMathA, u8 colorMathB)
{
    snes_write_reg(PPU_CGWSEL, colorMathA);
    snes_write_reg(PPU_CGADSUB, colorMathB);
}

void setColorIntensity(u8 colorApply, u8 intensity)
{
    snes_write_reg(PPU_COLDATA, (u8)(colorApply | (intensity & 0x1F)));
}

void setPaletteColor(u16 paletteEntry, u16 paletteColor)
{
    if (dma_allowed(2)) {
        g_snes.cgram[paletteEntry & 0xFF] = (u16)(paletteColor & 0x7FFF);
    }
}

/* --- dma.h ---------------------------------------------------------------------------------- */
void dmaCopyVram(const u8 *source, u16 address, u16 size)
{
    u16 i;
    if (!dma_allowed(size)) {
        return;
    }
    for (i = 0; i + 1 < size; i += 2) {
        g_snes.vram[(u16)(address + (i >> 1)) & (SNES_VRAM_WORDS - 1)] = (u16)(source[i] | (source[i + 1] << 8));
    }
}

void dmaCopyCGram(const u8 *source, u16 address, u16 size)
{
    u16 i;
    if (!dma_allowed(size)) {
        return;
    }
    for (i = 0; i + 1 < size; i += 2) {
        g_snes.cgram[(u16)(address + (i >> 1)) & 0xFF] = (u16)((source[i] | (source[i + 1] << 8)) & 0x7FFF);
    }
}

void dmaClearVram(void)
{
    if (dma_allowed(0) && (g_snes.regs[PPU_INIDISP] & 0x80)) {
        memset(g_snes.vram, 0, sizeof g_snes.vram);
    }
}

/* --- background.h ------------------------------------------------------------------------------ */
void bgSetGfxPtr(u8 bgNumber, u16 address)
{
    u8 reg = (u8)(bgNumber < 2 ? PPU_BG12NBA : PPU_BG34NBA);
    u8 n = (u8)((address >> 12) & 0x0F);
    u8 v = g_snes.regs[reg];
    v = (u8)((bgNumber & 1) ? (v & 0x0F) | (n << 4) : (v & 0xF0) | n);
    snes_write_reg(reg, v);
}

void bgSetMapPtr(u8 bgNumber, u16 address, u8 mapSize)
{
    snes_write_reg((u8)(PPU_BG1SC + (bgNumber & 3)), (u8)(((address >> 8) & 0xFC) | (mapSize & 3)));
}

void bgSetScroll(u8 bgNumber, u16 x, u16 y)
{
    g_snes.hofs[bgNumber & 3] = (u16)(x & 0x3FF);
    g_snes.vofs[bgNumber & 3] = (u16)(y & 0x3FF);
}

void bgSetEnable(u8 bgNumber)
{
    snes_write_reg(PPU_TM, (u8)(g_snes.regs[PPU_TM] | (1 << (bgNumber & 3))));
}

void bgSetDisable(u8 bgNumber)
{
    snes_write_reg(PPU_TM, (u8)(g_snes.regs[PPU_TM] & ~(1 << (bgNumber & 3))));
}

void bgSetEnableSub(u8 bgNumber)
{
    snes_write_reg(PPU_TS, (u8)(g_snes.regs[PPU_TS] | (1 << (bgNumber & 3))));
}

void bgSetDisableSub(u8 bgNumber)
{
    snes_write_reg(PPU_TS, (u8)(g_snes.regs[PPU_TS] & ~(1 << (bgNumber & 3))));
}

void bgInitTileSet(u8 bgNumber, const u8 *tileSource, const u8 *tilePalette, u8 paletteEntry, u16 tileSize,
                   u16 paletteSize, u16 colorMode, u16 address)
{
    dmaCopyVram(tileSource, address, tileSize);
    dmaCopyCGram(tilePalette, (u16)(paletteEntry * colorMode), paletteSize);
    bgSetGfxPtr(bgNumber, address);
}

void bgInitMapSet(u8 bgNumber, const u8 *mapSource, u16 mapSize, u8 sizeMode, u16 address)
{
    dmaCopyVram(mapSource, address, mapSize);
    if (bgNumber != 0xFF) {
        bgSetMapPtr(bgNumber, address, sizeMode);
    }
}

/* --- sprite.h -------------------------------------------------------------------------------- */
static void set_high_bits(u16 id, u8 mask, u8 bits)
{
    u16 n = (u16)(id >> 2);
    u8 shift = (u8)((n & 3) * 2);
    u8 *b = &oamMemory[512 + (n >> 2)];
    *b = (u8)((*b & ~(mask << shift)) | ((bits & mask) << shift));
}

void oamInit(void)
{
    memset(oamMemory, 0, sizeof oamMemory);
    oamClear(0, 0);
}

void oamInitGfxSet(const u8 *tileSource, u16 tileSize, const u8 *tilePalette, u16 paletteSize, u8 tilePaletteNumber,
                   u16 address, u8 oamsize)
{
    dmaCopyVram(tileSource, address, tileSize);
    dmaCopyCGram(tilePalette, (u16)(128 + tilePaletteNumber * 16), paletteSize);
    oamInitGfxAttr(address, oamsize);
}

void oamInitGfxAttr(u16 address, u8 oamsize)
{
    snes_write_reg(PPU_OBSEL, (u8)(oamsize | ((address >> 13) & 7)));
}

void oamSet(u16 id, u16 xspr, u16 yspr, u8 priority, u8 hflip, u8 vflip, u16 gfxoffset, u8 paletteoffset)
{
    oamMemory[id] = (u8)xspr;
    oamMemory[id + 1] = (u8)yspr;
    oamMemory[id + 2] = (u8)gfxoffset;
    oamMemory[id + 3] = (u8)(((vflip & 1) << 7) | ((hflip & 1) << 6) | ((priority & 3) << 4) | ((paletteoffset & 7) << 1) |
                             ((gfxoffset >> 8) & 1));
    set_high_bits(id, 1, (u8)((xspr >> 8) & 1));
}

void oamSetXY(u16 id, u16 xspr, u16 yspr)
{
    oamMemory[id] = (u8)xspr;
    oamMemory[id + 1] = (u8)yspr;
    set_high_bits(id, 1, (u8)((xspr >> 8) & 1));
}

void oamSetEx(u16 id, u8 size, u8 hide)
{
    set_high_bits(id, 2, (u8)((size & 1) << 1));
    oamSetVisible(id, hide);
}

void oamSetVisible(u16 id, u8 hide)
{
    if (hide) {
        oamMemory[id + 1] = ATTR2_DISABLED;
    }
}

void oamClear(u16 first, u8 numEntries)
{
    u16 id, end = (u16)(numEntries ? first + numEntries * 4 : 128 * 4);
    for (id = first; id < end && id < 128 * 4; id += 4) {
        oamMemory[id] = 0;
        oamMemory[id + 1] = ATTR2_DISABLED;
        set_high_bits(id, 3, 0);
    }
}

/* --- HDMA ----------------------------------------------------------------------------------- */
void snesHdmaSet(u8 channel, u8 reg, u8 twoRegs, const u8 *table)
{
    SnesHdma *h = &g_snes.hdma[channel & 7];
    h->reg = reg;
    h->two = twoRegs;
    h->table = table;
}

u8 snesDmaFits(u16 bytes)
{
    if (g_snes.regs[PPU_INIDISP] & 0x80) {
        return 1;
    }
    return (u8)(g_snes.in_vblank && g_snes.dma_count + bytes <= SNES_VBLANK_DMA_BYTES);
}

void snesHdmaEnable(u8 channels)
{
    g_snes.hdma_enabled = channels;
}
