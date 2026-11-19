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
        return (uint8_t)((value & 7) != 1 && (value & 7) != 7);   /* modes 1 and 7 are drawn */
    case SDEVKIT_PPU_CGWSEL:
        return (uint8_t)(value & 1);                  /* direct colour */
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
    /* Mode 7's registers, and BG1's scroll as mode 7 reads it, share a latch: each write is the
     * high byte, the byte written before it the low one. */
    if (reg == SDEVKIT_PPU_BG1HOFS || reg == SDEVKIT_PPU_BG1VOFS || (reg >= SDEVKIT_PPU_M7A && reg <= SDEVKIT_PPU_M7Y)) {
        uint16_t v = (uint16_t)(((uint16_t)value << 8) | sdevkit__rachael.m7_latch);
        if (reg == SDEVKIT_PPU_BG1HOFS) {
            sdevkit__rachael.m7hofs = (uint16_t)(v & 0x1FFF);
        } else if (reg == SDEVKIT_PPU_BG1VOFS) {
            sdevkit__rachael.m7vofs = (uint16_t)(v & 0x1FFF);
        } else {
            sdevkit__rachael.m7[reg - SDEVKIT_PPU_M7A] = v;
        }
        sdevkit__rachael.m7_latch = value;
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
/* The layers on the main and the sub screen and the tile bases as the library last set them, kept
 * as lib/snes/snes.c keeps them (the SNES cannot read its registers back): the functions that
 * change one field start from these, not from the register, so a game's own sdevkit_reg_write of
 * TM, TS, BG12NBA or BG34NBA is undone by the next of them on both platforms alike. Zeroed with
 * the console, as sdevkit__reset_ppu zeroes the SNES's. */
static uint8_t tm_shadow;
static uint8_t ts_shadow;
static uint8_t nba_shadow[2];

void sdevkit__reset(void)
{
    tm_shadow = ts_shadow = 0;
    nba_shadow[0] = nba_shadow[1] = 0;
    memset(&sdevkit__rachael, 0, sizeof sdevkit__rachael);
    memset(sdevkit_oam_memory, 0, sizeof sdevkit_oam_memory);
    sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP] = 0x80;   /* forced blank until the game turns the screen on */
    sdevkit__rachael.tick_hash = SDEVKIT__HASH_START;
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

/* FNV-1a over the bytes the game names during a tick (sdevkit__tick_hash closes it). */
void sdevkit_dbg_hash(const void *data, uint16_t bytes)
{
    const uint8_t *p = (const uint8_t *)data;
    uint32_t h = sdevkit__rachael.tick_hash;
    uint16_t i;

    for (i = 0; i < bytes; i++) {
        h = (h ^ p[i]) * 16777619u;
    }
    sdevkit__rachael.tick_hash = h;
}

uint32_t sdevkit__tick_hash(void)
{
    uint32_t h;

    sdevkit_dbg_hash(sdevkit_oam_memory, sizeof sdevkit_oam_memory);
    h = sdevkit__rachael.tick_hash;
    sdevkit__rachael.tick_hash = SDEVKIT__HASH_START;
    return h;
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
    tm_shadow = SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_BG3_ENABLE | SDEVKIT_OBJ_ENABLE;
    sdevkit__write_reg(SDEVKIT_PPU_TM, tm_shadow);
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

void sdevkit_dma_copy_vram_column(const uint8_t *source, uint16_t address, uint16_t size)
{
    uint16_t i;
    if (!dma_allowed(size)) {
        return;
    }
    for (i = 0; i + 1 < size; i += 2) {
        sdevkit__rachael.vram[(uint16_t)(address + (i >> 1) * 32) & (SDEVKIT__VRAM_WORDS - 1)] = (uint16_t)(source[i] | (source[i + 1] << 8));
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
    uint8_t i = (uint8_t)(bgNumber >> 1);
    uint8_t n = (uint8_t)((address >> 12) & 0x0F);
    uint8_t v = nba_shadow[i];
    nba_shadow[i] = (uint8_t)((bgNumber & 1) ? (v & 0x0F) | (n << 4) : (v & 0xF0) | n);
    sdevkit__write_reg((uint8_t)(SDEVKIT_PPU_BG12NBA + i), nba_shadow[i]);
}

void sdevkit_bg_set_map_ptr(uint8_t bgNumber, uint16_t address, uint8_t mapSize)
{
    sdevkit__write_reg((uint8_t)(SDEVKIT_PPU_BG1SC + (bgNumber & 3)), (uint8_t)(((address >> 8) & 0xFC) | (mapSize & 3)));
}

void sdevkit_bg_set_scroll(uint8_t bgNumber, uint16_t x, uint16_t y)
{
    if ((bgNumber & 3) == 0) {          /* the same register pair as on the SNES: also mode 7's */
        sdevkit__write_reg(SDEVKIT_PPU_BG1HOFS, (uint8_t)x);
        sdevkit__write_reg(SDEVKIT_PPU_BG1HOFS, (uint8_t)(x >> 8));
        sdevkit__write_reg(SDEVKIT_PPU_BG1VOFS, (uint8_t)y);
        sdevkit__write_reg(SDEVKIT_PPU_BG1VOFS, (uint8_t)(y >> 8));
    }
    sdevkit__rachael.hofs[bgNumber & 3] = (uint16_t)(x & 0x3FF);
    sdevkit__rachael.vofs[bgNumber & 3] = (uint16_t)(y & 0x3FF);
}

/* --- Mode 7 ----------------------------------------------------------------------------- */
void sdevkit_mode7_load_map(const uint8_t *map, uint16_t address, uint16_t size)
{
    uint16_t i;
    if (!dma_allowed(size)) {
        return;
    }
    for (i = 0; i < size; i++) {
        uint16_t *w = &sdevkit__rachael.vram[(uint16_t)(address + i) & (SDEVKIT__VRAM_WORDS - 1)];
        *w = (uint16_t)((*w & 0xFF00) | map[i]);
    }
}

void sdevkit_mode7_load_tiles(const uint8_t *tiles, uint16_t address, uint16_t size)
{
    uint16_t i;
    if (!dma_allowed(size)) {
        return;
    }
    for (i = 0; i < size; i++) {
        uint16_t *w = &sdevkit__rachael.vram[(uint16_t)(address + i) & (SDEVKIT__VRAM_WORDS - 1)];
        *w = (uint16_t)((*w & 0x00FF) | (tiles[i] << 8));
    }
}

static void write_twice(uint8_t reg, int16_t v)
{
    sdevkit__write_reg(reg, (uint8_t)v);
    sdevkit__write_reg(reg, (uint8_t)((uint16_t)v >> 8));
}

void sdevkit_mode7_set_matrix(int16_t a, int16_t b, int16_t c, int16_t d)
{
    write_twice(SDEVKIT_PPU_M7A, a);
    write_twice(SDEVKIT_PPU_M7B, b);
    write_twice(SDEVKIT_PPU_M7C, c);
    write_twice(SDEVKIT_PPU_M7D, d);
}

void sdevkit_mode7_set_center(int16_t x, int16_t y)
{
    write_twice(SDEVKIT_PPU_M7X, x);
    write_twice(SDEVKIT_PPU_M7Y, y);
}

void sdevkit_mode7_set_select(uint8_t select)
{
    sdevkit__write_reg(SDEVKIT_PPU_M7SEL, select);
}

void sdevkit_bg_set_enable(uint8_t bgNumber)
{
    tm_shadow = (uint8_t)(tm_shadow | (1 << bgNumber));
    sdevkit__write_reg(SDEVKIT_PPU_TM, tm_shadow);
}

void sdevkit_bg_set_disable(uint8_t bgNumber)
{
    tm_shadow = (uint8_t)(tm_shadow & ~(1 << bgNumber));
    sdevkit__write_reg(SDEVKIT_PPU_TM, tm_shadow);
}

void sdevkit_bg_set_enable_sub(uint8_t bgNumber)
{
    ts_shadow = (uint8_t)(ts_shadow | (1 << bgNumber));
    sdevkit__write_reg(SDEVKIT_PPU_TS, ts_shadow);
}

void sdevkit_bg_set_disable_sub(uint8_t bgNumber)
{
    ts_shadow = (uint8_t)(ts_shadow & ~(1 << bgNumber));
    sdevkit__write_reg(SDEVKIT_PPU_TS, ts_shadow);
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
