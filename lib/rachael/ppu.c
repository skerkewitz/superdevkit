/*
 * Rachael, the SNES rebuilt in portable C for the desktop and the web (lib/README.md): the memories, the picture registers
 * and the functions of the console API that write them (sdevkit/snes.h). render.c draws the
 * picture from this state; rachael.h is what the two share. Taken over from Blubber Blasen's src/snes.
 *
 * Portable C99: no raylib, no heap, no floating point. Everything is static, like
 * the console's memory.
 */

#include "rachael.h"

#include <string.h>

sdevkit__rachael_state sdevkit__rachael;
uint8_t sdevkit_oam_memory[128 * 4 + 8 * 4];

/* --- Registers ----------------------------------------------------------------------- */
uint8_t sdevkit__undrawn(uint8_t reg, uint8_t value)
{
    switch (reg) {
    case SDEVKIT_PPU_BGMODE:
        return (uint8_t)((value & 7) != 1);           /* only mode 1 is drawn */
    case 0x06:                                         /* MOSAIC: a size and a layer */
        return (uint8_t)((value & 0xF0) != 0 && (value & 0x0F) != 0);
    case 0x33:                                         /* SETINI */
        return (uint8_t)(value != 0);
    case 0x02: case 0x03: case 0x04:                   /* OAMADDL, OAMADDH, OAMDATA */
    case 0x15: case 0x16: case 0x17: case 0x18: case 0x19:   /* VMAIN, VMADDL/H, VMDATAL/H */
    case 0x21: case 0x22:                              /* CGADD, CGDATA */
        return 1;
    default:                                           /* drawn, or without effect in mode 1 */
        return 0;
    }
}

void sdevkit__write_reg(uint8_t reg, uint8_t value)
{
    if (sdevkit__undrawn(reg, value)) {
        if (sdevkit__rachael.undrawn == 0) {
            sdevkit__rachael.undrawn_reg = reg;
            sdevkit__rachael.undrawn_value = value;
        }
        sdevkit__rachael.undrawn++;
    }
    if (reg >= SDEVKIT_PPU_BG1HOFS && reg <= SDEVKIT_PPU_BG1HOFS + 7) {
        /* BGnHOFS / BGnVOFS: written twice, low byte then high byte, through a
         * shared latch (simplified from the SNES formula; same result for
         * two writes in a row). */
        uint8_t bg = (uint8_t)((reg - SDEVKIT_PPU_BG1HOFS) >> 1);
        uint16_t v = (uint16_t)(((uint16_t)value << 8) | sdevkit__rachael.scroll_latch);
        if ((reg - SDEVKIT_PPU_BG1HOFS) & 1) {
            sdevkit__rachael.vofs[bg] = (uint16_t)(v & 0x3FF);
        } else {
            sdevkit__rachael.hofs[bg] = (uint16_t)(v & 0x3FF);
        }
        sdevkit__rachael.scroll_latch = value;
    } else if (reg == SDEVKIT_PPU_COLDATA) {
        uint8_t i = (uint8_t)(value & 0x1F);
        if (value & SDEVKIT_CM_APPLY_RED)   sdevkit__rachael.fixed_r = i;
        if (value & SDEVKIT_CM_APPLY_GREEN) sdevkit__rachael.fixed_g = i;
        if (value & SDEVKIT_CM_APPLY_BLUE)  sdevkit__rachael.fixed_b = i;
    }
    sdevkit__rachael.regs[reg] = value;
}

uint8_t sdevkit__reg(uint8_t reg)
{
    return sdevkit__rachael.regs[reg];
}

void sdevkit_reg_write(uint8_t reg, uint8_t value)
{
    sdevkit__write_reg(reg, value);
}

/* --- Power on and vertical blank -------------------------------------------------------- */
void sdevkit__reset(void)
{
    memset(&sdevkit__rachael, 0, sizeof sdevkit__rachael);
    memset(sdevkit_oam_memory, 0, sizeof sdevkit_oam_memory);
    sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP] = 0x80;   /* forced blank until the game turns the screen on */
}

void sdevkit__vblank(void)
{
    memcpy(sdevkit__rachael.oam, sdevkit_oam_memory, sizeof sdevkit__rachael.oam);
    sdevkit__rachael.dma_count = 0;
    sdevkit__rachael.dma_lost = 0;
    sdevkit__rachael.in_vblank = 1;
}

void sdevkit__display(void)
{
    sdevkit__rachael.in_vblank = 0;
}

/* May this many bytes go to VRAM / CGRAM now? Counts them either way. */
static uint8_t dma_allowed(uint16_t size)
{
    uint8_t forced_blank = (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP] & 0x80) != 0);
    if (forced_blank || (sdevkit__rachael.in_vblank && sdevkit__rachael.dma_count + size <= SDEVKIT__VBLANK_DMA_BYTES)) {
        sdevkit__rachael.dma_count = (uint16_t)(sdevkit__rachael.dma_count + size);
        return 1;
    }
    sdevkit__rachael.dma_lost = (uint16_t)(sdevkit__rachael.dma_lost + size);
    return 0;
}

sdevkit__frame_stats sdevkit__stats(void)
{
    sdevkit__frame_stats s = sdevkit__rachael.stats;
    s.dma_bytes = sdevkit__rachael.dma_count;   /* of the current / last vertical blank */
    s.dma_lost = sdevkit__rachael.dma_lost;
    return s;
}

const uint16_t *sdevkit_dbg_vram(void)
{
    return sdevkit__rachael.vram;
}

const uint16_t *sdevkit__cgram(void)
{
    return sdevkit__rachael.cgram;
}

const uint8_t *sdevkit__oam(void)
{
    return sdevkit__rachael.oam;
}

/* --- video.h ------------------------------------------------------------------------------ */
void sdevkit_set_mode(uint8_t mode, uint8_t size)
{
    sdevkit__write_reg(SDEVKIT_PPU_BGMODE, (uint8_t)(mode | size));
    sdevkit__write_reg(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_BG3_ENABLE | SDEVKIT_OBJ_ENABLE);
}

/* As in PVSnesLib: 0 switches the screen off (forced blank), 1..15 on. */
void sdevkit_set_brightness(uint8_t level)
{
    sdevkit__write_reg(SDEVKIT_PPU_INIDISP, (uint8_t)(level ? (level & 0x0F) : 0x80));
}

void sdevkit_set_screen_on(void)
{
    sdevkit__write_reg(SDEVKIT_PPU_INIDISP, 0x0F);
}

void sdevkit_set_screen_off(void)
{
    sdevkit__write_reg(SDEVKIT_PPU_INIDISP, (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP] | 0x80));
}

void sdevkit_set_color_effect(uint8_t colorMathA, uint8_t colorMathB)
{
    sdevkit__write_reg(SDEVKIT_PPU_CGWSEL, colorMathA);
    sdevkit__write_reg(SDEVKIT_PPU_CGADSUB, colorMathB);
}

void sdevkit_set_color_intensity(uint8_t colorApply, uint8_t intensity)
{
    sdevkit__write_reg(SDEVKIT_PPU_COLDATA, (uint8_t)(colorApply | (intensity & 0x1F)));
}

void sdevkit_set_palette_color(uint16_t paletteEntry, uint16_t paletteColor)
{
    if (dma_allowed(2)) {
        sdevkit__rachael.cgram[paletteEntry & 0xFF] = (uint16_t)(paletteColor & 0x7FFF);
    }
}

/* --- dma.h ---------------------------------------------------------------------------------- */
void sdevkit_dma_copy_vram(const uint8_t *source, uint16_t address, uint16_t size)
{
    uint16_t i;
    if (!dma_allowed(size)) {
        return;
    }
    for (i = 0; i + 1 < size; i += 2) {
        sdevkit__rachael.vram[(uint16_t)(address + (i >> 1)) & (SDEVKIT__VRAM_WORDS - 1)] = (uint16_t)(source[i] | (source[i + 1] << 8));
    }
}

void sdevkit_dma_copy_cgram(const uint8_t *source, uint16_t address, uint16_t size)
{
    uint16_t i;
    if (!dma_allowed(size)) {
        return;
    }
    for (i = 0; i + 1 < size; i += 2) {
        sdevkit__rachael.cgram[(uint16_t)(address + (i >> 1)) & 0xFF] = (uint16_t)((source[i] | (source[i + 1] << 8)) & 0x7FFF);
    }
}

void sdevkit_dma_clear_vram(void)
{
    if (dma_allowed(0) && (sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP] & 0x80)) {
        memset(sdevkit__rachael.vram, 0, sizeof sdevkit__rachael.vram);
    }
}

/* --- background.h ------------------------------------------------------------------------------ */
void sdevkit_bg_set_gfx_ptr(uint8_t bgNumber, uint16_t address)
{
    uint8_t reg = (uint8_t)(bgNumber < 2 ? SDEVKIT_PPU_BG12NBA : SDEVKIT_PPU_BG34NBA);
    uint8_t n = (uint8_t)((address >> 12) & 0x0F);
    uint8_t v = sdevkit__rachael.regs[reg];
    v = (uint8_t)((bgNumber & 1) ? (v & 0x0F) | (n << 4) : (v & 0xF0) | n);
    sdevkit__write_reg(reg, v);
}

void sdevkit_bg_set_map_ptr(uint8_t bgNumber, uint16_t address, uint8_t mapSize)
{
    sdevkit__write_reg((uint8_t)(SDEVKIT_PPU_BG1SC + (bgNumber & 3)), (uint8_t)(((address >> 8) & 0xFC) | (mapSize & 3)));
}

void sdevkit_bg_set_scroll(uint8_t bgNumber, uint16_t x, uint16_t y)
{
    sdevkit__rachael.hofs[bgNumber & 3] = (uint16_t)(x & 0x3FF);
    sdevkit__rachael.vofs[bgNumber & 3] = (uint16_t)(y & 0x3FF);
}

void sdevkit_bg_set_enable(uint8_t bgNumber)
{
    sdevkit__write_reg(SDEVKIT_PPU_TM, (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_TM] | (1 << (bgNumber & 3))));
}

void sdevkit_bg_set_disable(uint8_t bgNumber)
{
    sdevkit__write_reg(SDEVKIT_PPU_TM, (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_TM] & ~(1 << (bgNumber & 3))));
}

void sdevkit_bg_set_enable_sub(uint8_t bgNumber)
{
    sdevkit__write_reg(SDEVKIT_PPU_TS, (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_TS] | (1 << (bgNumber & 3))));
}

void sdevkit_bg_set_disable_sub(uint8_t bgNumber)
{
    sdevkit__write_reg(SDEVKIT_PPU_TS, (uint8_t)(sdevkit__rachael.regs[SDEVKIT_PPU_TS] & ~(1 << (bgNumber & 3))));
}

void sdevkit_bg_init_tile_set(uint8_t bgNumber, const uint8_t *tileSource, const uint8_t *tilePalette, uint8_t paletteEntry, uint16_t tileSize,
                   uint16_t paletteSize, uint16_t colorMode, uint16_t address)
{
    sdevkit_dma_copy_vram(tileSource, address, tileSize);
    sdevkit_dma_copy_cgram(tilePalette, (uint16_t)(paletteEntry * colorMode), paletteSize);
    sdevkit_bg_set_gfx_ptr(bgNumber, address);
}

void sdevkit_bg_init_map_set(uint8_t bgNumber, const uint8_t *mapSource, uint16_t mapSize, uint8_t sizeMode, uint16_t address)
{
    sdevkit_dma_copy_vram(mapSource, address, mapSize);
    if (bgNumber != 0xFF) {
        sdevkit_bg_set_map_ptr(bgNumber, address, sizeMode);
    }
}

/* --- sprite.h -------------------------------------------------------------------------------- */
static void set_high_bits(uint16_t id, uint8_t mask, uint8_t bits)
{
    uint16_t n = (uint16_t)(id >> 2);
    uint8_t shift = (uint8_t)((n & 3) * 2);
    uint8_t *b = &sdevkit_oam_memory[512 + (n >> 2)];
    *b = (uint8_t)((*b & ~(mask << shift)) | ((bits & mask) << shift));
}

void sdevkit_oam_init(void)
{
    memset(sdevkit_oam_memory, 0, sizeof sdevkit_oam_memory);
    sdevkit_oam_clear(0, 0);
}

void sdevkit_oam_init_gfx_set(const uint8_t *tileSource, uint16_t tileSize, const uint8_t *tilePalette, uint16_t paletteSize, uint8_t tilePaletteNumber,
                   uint16_t address, uint8_t oamsize)
{
    sdevkit_dma_copy_vram(tileSource, address, tileSize);
    sdevkit_dma_copy_cgram(tilePalette, (uint16_t)(128 + tilePaletteNumber * 16), paletteSize);
    sdevkit_oam_init_gfx_attr(address, oamsize);
}

void sdevkit_oam_init_gfx_attr(uint16_t address, uint8_t oamsize)
{
    sdevkit__write_reg(SDEVKIT_PPU_OBSEL, (uint8_t)(oamsize | ((address >> 13) & 7)));
}

void sdevkit_oam_set(uint16_t id, uint16_t xspr, uint16_t yspr, uint8_t priority, uint8_t hflip, uint8_t vflip, uint16_t gfxoffset, uint8_t paletteoffset)
{
    sdevkit_oam_memory[id] = (uint8_t)xspr;
    sdevkit_oam_memory[id + 1] = (uint8_t)yspr;
    sdevkit_oam_memory[id + 2] = (uint8_t)gfxoffset;
    sdevkit_oam_memory[id + 3] = (uint8_t)(((vflip & 1) << 7) | ((hflip & 1) << 6) | ((priority & 3) << 4) | ((paletteoffset & 7) << 1) |
                             ((gfxoffset >> 8) & 1));
    set_high_bits(id, 1, (uint8_t)((xspr >> 8) & 1));
}

void sdevkit_oam_set_xy(uint16_t id, uint16_t xspr, uint16_t yspr)
{
    sdevkit_oam_memory[id] = (uint8_t)xspr;
    sdevkit_oam_memory[id + 1] = (uint8_t)yspr;
    set_high_bits(id, 1, (uint8_t)((xspr >> 8) & 1));
}

void sdevkit_oam_set_ex(uint16_t id, uint8_t size, uint8_t hide)
{
    set_high_bits(id, 2, (uint8_t)((size & 1) << 1));
    sdevkit_oam_set_visible(id, hide);
}

void sdevkit_oam_set_visible(uint16_t id, uint8_t hide)
{
    if (hide) {
        sdevkit_oam_memory[id + 1] = SDEVKIT_ATTR2_DISABLED;
    }
}

void sdevkit_oam_clear(uint16_t first, uint8_t numEntries)
{
    uint16_t id, end = (uint16_t)(numEntries ? first + numEntries * 4 : 128 * 4);
    for (id = first; id < end && id < 128 * 4; id += 4) {
        sdevkit_oam_memory[id] = 0;
        sdevkit_oam_memory[id + 1] = SDEVKIT_ATTR2_DISABLED;
        set_high_bits(id, 3, 0);
    }
}

/* --- HDMA ----------------------------------------------------------------------------------- */
void sdevkit_hdma_set(uint8_t channel, uint8_t reg, uint8_t twoRegs, const uint8_t *table)
{
    sdevkit__hdma_channel *h = &sdevkit__rachael.hdma[channel & 7];
    h->reg = reg;
    h->two = twoRegs;
    h->table = table;
}

uint8_t sdevkit_dma_fits(uint16_t bytes)
{
    if (sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP] & 0x80) {
        return 1;
    }
    return (uint8_t)(sdevkit__rachael.in_vblank && sdevkit__rachael.dma_count + bytes <= SDEVKIT__VBLANK_DMA_BYTES);
}

void sdevkit_hdma_enable(uint8_t channels)
{
    sdevkit__rachael.hdma_enabled = channels;
}
