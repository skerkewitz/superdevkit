/*
 * Rachael, the SNES rebuilt in portable C for the desktop and the web (lib/README.md): the memories,
 * the picture registers and the core of the console API that writes them (sdevkit/snes.h,
 * ADR-6FNPPCSX). render.c draws the picture from this state; rachael.h is what the two share; the
 * optional layers are layers.c. Taken over from Blubber Blasen's src/snes.
 *
 * The ports of VRAM, CGRAM and OAM work as the SNES's: an address register, then data bytes that
 * step it, by the CPU or by DMA. Rachael also checks when a game writes: a byte for VRAM, CGRAM or
 * OAM arrives only in the vertical blank (within SDEVKIT__VBLANK_DMA_BYTES) or with the screen
 * off, else it is lost; any other register written while the picture is drawn is counted
 * (drawing), since on the SNES it would change the picture from the line being drawn on.
 *
 * Portable C99: no raylib, no heap, no floating point. Everything is static, like
 * the console's memory.
 */

#include "rachael.h"

#include <string.h>

sdevkit__rachael_state sdevkit__rachael;

/* --- Registers ----------------------------------------------------------------------- */
uint8_t sdevkit__undrawn(uint8_t reg, uint8_t value)
{
    switch (reg) {
    case SDEVKIT_PPU_BGMODE:
        return (uint8_t)((value & 7) != 1 && (value & 7) != 7);   /* modes 1 and 7 are drawn */
    case SDEVKIT_PPU_CGWSEL:
        return (uint8_t)(value & 1);                  /* direct colour */
    case SDEVKIT_PPU_MOSAIC:                           /* a size and a layer */
        return (uint8_t)((value & 0xF0) != 0 && (value & 0x0F) != 0);
    case SDEVKIT_PPU_SETINI:
        return (uint8_t)(value != 0);
    case SDEVKIT_PPU_VMAIN:                            /* the address remapped */
        return (uint8_t)((value & 0x0C) != 0);
    default:                                           /* drawn, or without effect in mode 1 */
        return 0;
    }
}

static void count_undrawn(uint8_t reg, uint8_t value)
{
    if (sdevkit__rachael.undrawn == 0) {
        sdevkit__rachael.undrawn_reg = reg;
        sdevkit__rachael.undrawn_value = value;
    }
    sdevkit__rachael.undrawn++;
}

/* A byte through the VRAM port: the low or the high byte of the word at the address, which steps
 * after the byte VMAIN names, by 1, 32 or 128 words. */
static void vram_port(uint8_t high, uint8_t value)
{
    static const uint16_t steps[4] = { 1, 32, 128, 128 };
    uint8_t vmain = sdevkit__rachael.vmain;
    uint16_t *w = &sdevkit__rachael.vram[sdevkit__rachael.vram_addr & (SDEVKIT__VRAM_WORDS - 1)];

    if (high) {
        *w = (uint16_t)((*w & 0x00FF) | (value << 8));
    } else {
        *w = (uint16_t)((*w & 0xFF00) | value);
    }
    if ((uint8_t)((vmain >> 7) & 1) == high) {
        sdevkit__rachael.vram_addr = (uint16_t)(sdevkit__rachael.vram_addr + steps[vmain & 3]);
    }
}

/* A byte through the CGRAM port: the low byte is kept, the high byte writes the colour. */
static void cgram_port(uint8_t value)
{
    if (!sdevkit__rachael.cg_high) {
        sdevkit__rachael.cg_latch = value;
        sdevkit__rachael.cg_high = 1;
        return;
    }
    sdevkit__rachael.cgram[sdevkit__rachael.cg_addr] = (uint16_t)(((value << 8) | sdevkit__rachael.cg_latch) & 0x7FFF);
    sdevkit__rachael.cg_addr++;
    sdevkit__rachael.cg_high = 0;
}

/* A byte through the OAM port: the low table takes two bytes at once (the even one is kept until
 * the odd one), the high table one at a time. */
static void oam_port(uint8_t value)
{
    uint16_t a = sdevkit__rachael.oam_addr;

    if (a < 512) {
        if (a & 1) {
            sdevkit__rachael.oam[a - 1] = sdevkit__rachael.oam_latch;
            sdevkit__rachael.oam[a] = value;
        } else {
            sdevkit__rachael.oam_latch = value;
        }
    } else {
        sdevkit__rachael.oam[512 + (a & 31)] = value;
    }
    sdevkit__rachael.oam_addr = (uint16_t)((a + 1) % (128 * 4 + 8 * 4));
}

void sdevkit__write_reg(uint8_t reg, uint8_t value)
{
    if (sdevkit__undrawn(reg, value)) {
        count_undrawn(reg, value);
    }
    switch (reg) {
    case SDEVKIT_PPU_OAMADDL:
        sdevkit__rachael.oam_addr = (uint16_t)((sdevkit__rachael.oam_addr & 0x200) | (value << 1));
        break;
    case SDEVKIT_PPU_OAMADDH:
        sdevkit__rachael.oam_addr = (uint16_t)((sdevkit__rachael.oam_addr & 0x1FE) | ((value & 1) << 9));
        break;
    case SDEVKIT_PPU_OAMDATA:
        oam_port(value);
        break;
    case SDEVKIT_PPU_VMAIN:
        sdevkit__rachael.vmain = value;
        break;
    case SDEVKIT_PPU_VMADDL:
        sdevkit__rachael.vram_addr = (uint16_t)((sdevkit__rachael.vram_addr & 0xFF00) | value);
        break;
    case SDEVKIT_PPU_VMADDH:
        sdevkit__rachael.vram_addr = (uint16_t)((sdevkit__rachael.vram_addr & 0x00FF) | (value << 8));
        break;
    case SDEVKIT_PPU_VMDATAL:
        vram_port(0, value);
        break;
    case SDEVKIT_PPU_VMDATAH:
        vram_port(1, value);
        break;
    case SDEVKIT_PPU_CGADD:
        sdevkit__rachael.cg_addr = value;
        sdevkit__rachael.cg_high = 0;
        break;
    case SDEVKIT_PPU_CGDATA:
        cgram_port(value);
        break;
    default:
        break;
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
    if (reg >= SDEVKIT_PPU_BG1HOFS && reg <= SDEVKIT_PPU_BG4VOFS) {
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

static uint8_t screen_off(void)
{
    return (uint8_t)((sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP] & 0x80) != 0);
}

/* May this many bytes go to VRAM, CGRAM or OAM now? Counts them either way. */
static uint8_t dma_allowed(uint16_t size)
{
    if (screen_off() || (sdevkit__rachael.in_vblank && sdevkit__rachael.dma_count + size <= SDEVKIT__VBLANK_DMA_BYTES)) {
        sdevkit__rachael.dma_count = (uint16_t)(sdevkit__rachael.dma_count + size);
        return 1;
    }
    sdevkit__rachael.dma_lost = (uint16_t)(sdevkit__rachael.dma_lost + size);
    return 0;
}

static uint8_t is_data_port(uint8_t reg)
{
    return (uint8_t)(reg == SDEVKIT_PPU_OAMDATA || reg == SDEVKIT_PPU_VMDATAL || reg == SDEVKIT_PPU_VMDATAH
                     || reg == SDEVKIT_PPU_CGDATA);
}

/* The address registers of the ports: written while the picture is drawn they change nothing
 * on the screen. Nor does switching the screen off: the rest of the frame is black, and a game
 * does it to load (sdevkit/snes.h). */
static uint8_t harmless(uint8_t reg, uint8_t value)
{
    return (uint8_t)(reg == SDEVKIT_PPU_OAMADDL || reg == SDEVKIT_PPU_OAMADDH || reg == SDEVKIT_PPU_VMAIN
                     || reg == SDEVKIT_PPU_VMADDL || reg == SDEVKIT_PPU_VMADDH || reg == SDEVKIT_PPU_CGADD
                     || (reg == SDEVKIT_PPU_INIDISP && (value & SDEVKIT_FORCED_BLANK)));
}

/* A write that changes the picture: counted when the picture is being drawn. reg is the low byte
 * of $21xx, or of $43xx and $420C for HDMA. */
static void check_drawing(uint8_t reg, uint8_t value)
{
    if (!sdevkit__rachael.in_vblank && !screen_off()) {
        if (sdevkit__rachael.drawing == 0) {
            sdevkit__rachael.drawing_reg = reg;
            sdevkit__rachael.drawing_value = value;
        }
        sdevkit__rachael.drawing++;
    }
}

void sdevkit_reg_write(uint8_t reg, uint8_t value)
{
    if (is_data_port(reg)) {
        if (!dma_allowed(1)) {
            return;
        }
    } else if (!harmless(reg, value) && !(sdevkit__rachael.in_irq && sdevkit__rachael.irq_waited)) {
        check_drawing(reg, value);     /* the game's IRQ writes after sdevkit_irq_wait_hblank: allowed */
    }
    sdevkit__write_reg(reg, value);
}

void sdevkit_reg_write_twice(uint8_t reg, uint16_t value)
{
    sdevkit_reg_write(reg, (uint8_t)value);
    sdevkit_reg_write(reg, (uint8_t)(value >> 8));
}

/* --- Copies ---------------------------------------------------------------------------------- */
/* A DMA from memory to the PPU as the SNES makes it: the bytes go to bbad, bbad + 1, ... in the
 * pattern of DMAP's mode, from one fixed byte or one after the other. From the PPU to memory
 * (DMAP bit 7) she does not model. */
void sdevkit_dma(uint8_t dmap, uint8_t bbad, const void *src, uint16_t bytes)
{
    static const uint8_t pattern[8][4] = {
        { 0, 0, 0, 0 }, { 0, 1, 0, 1 }, { 0, 0, 0, 0 }, { 0, 0, 1, 1 },
        { 0, 1, 2, 3 }, { 0, 1, 0, 1 }, { 0, 0, 0, 0 }, { 0, 0, 1, 1 },
    };
    const uint8_t *s = (const uint8_t *)src;
    uint32_t n = bytes == 0 ? 0x10000u : bytes, i;

    if (dmap & 0x80) {
        count_undrawn(bbad, dmap);
        return;
    }
    if (!dma_allowed((uint16_t)(n > 0xFFFF ? 0xFFFF : n))) {
        return;
    }
    for (i = 0; i < n; i++) {
        uint8_t reg = (uint8_t)(bbad + pattern[dmap & 7][i & 3]);
        sdevkit__write_reg(reg, (dmap & 0x08) ? s[0] : s[i]);
    }
}

void sdevkit_vram_copy(uint16_t a, const void *src, uint16_t bytes)
{
    sdevkit__write_reg(SDEVKIT_PPU_VMAIN, 0x80);
    sdevkit__write_reg(SDEVKIT_PPU_VMADDL, (uint8_t)a);
    sdevkit__write_reg(SDEVKIT_PPU_VMADDH, (uint8_t)(a >> 8));
    sdevkit_dma(0x01, SDEVKIT_PPU_VMDATAL, src, bytes);
}

void sdevkit_vram_column_copy(uint16_t a, const void *src, uint16_t bytes)
{
    sdevkit__write_reg(SDEVKIT_PPU_VMAIN, 0x81);
    sdevkit__write_reg(SDEVKIT_PPU_VMADDL, (uint8_t)a);
    sdevkit__write_reg(SDEVKIT_PPU_VMADDH, (uint8_t)(a >> 8));
    sdevkit_dma(0x01, SDEVKIT_PPU_VMDATAL, src, bytes);
}

void sdevkit_vram_fill(uint16_t a, uint8_t value, uint16_t bytes)
{
    sdevkit__write_reg(SDEVKIT_PPU_VMAIN, 0x80);
    sdevkit__write_reg(SDEVKIT_PPU_VMADDL, (uint8_t)a);
    sdevkit__write_reg(SDEVKIT_PPU_VMADDH, (uint8_t)(a >> 8));
    sdevkit_dma(0x09, SDEVKIT_PPU_VMDATAL, &value, bytes);
}

void sdevkit_cgram_copy(uint8_t i, const void *src, uint16_t bytes)
{
    sdevkit__write_reg(SDEVKIT_PPU_CGADD, i);
    sdevkit_dma(0x00, SDEVKIT_PPU_CGDATA, src, bytes);
}

void sdevkit_cgram_set(uint8_t i, uint16_t bgr)
{
    sdevkit__write_reg(SDEVKIT_PPU_CGADD, i);
    sdevkit_reg_write(SDEVKIT_PPU_CGDATA, (uint8_t)bgr);
    sdevkit_reg_write(SDEVKIT_PPU_CGDATA, (uint8_t)(bgr >> 8));
}

void sdevkit_oam_copy(uint16_t o, const void *src, uint16_t bytes)
{
    sdevkit__write_reg(SDEVKIT_PPU_OAMADDL, (uint8_t)(o >> 1));
    sdevkit__write_reg(SDEVKIT_PPU_OAMADDH, (uint8_t)(o >> 9));
    sdevkit_dma(0x00, SDEVKIT_PPU_OAMDATA, src, bytes);
}

void sdevkit_mode7_map_copy(uint16_t e, const void *src, uint16_t bytes)
{
    sdevkit__write_reg(SDEVKIT_PPU_VMAIN, 0x00);
    sdevkit__write_reg(SDEVKIT_PPU_VMADDL, (uint8_t)e);
    sdevkit__write_reg(SDEVKIT_PPU_VMADDH, (uint8_t)(e >> 8));
    sdevkit_dma(0x00, SDEVKIT_PPU_VMDATAL, src, bytes);
}

void sdevkit_mode7_tiles_copy(uint16_t b, const void *src, uint16_t bytes)
{
    sdevkit__write_reg(SDEVKIT_PPU_VMAIN, 0x80);
    sdevkit__write_reg(SDEVKIT_PPU_VMADDL, (uint8_t)b);
    sdevkit__write_reg(SDEVKIT_PPU_VMADDH, (uint8_t)(b >> 8));
    sdevkit_dma(0x00, SDEVKIT_PPU_VMDATAH, src, bytes);
}

uint8_t sdevkit_dma_fits(uint16_t bytes)
{
    if (screen_off()) {
        return 1;
    }
    return (uint8_t)(sdevkit__rachael.in_vblank && sdevkit__rachael.dma_count + bytes <= SDEVKIT__VBLANK_DMA_BYTES);
}

/* --- HDMA ----------------------------------------------------------------------------------- */
void sdevkit_hdma_set(uint8_t channel, uint8_t reg, uint8_t mode, const void *table)
{
    sdevkit__hdma_channel *h = &sdevkit__rachael.hdma[channel & 7];

    check_drawing(reg, mode);
    if (mode > SDEVKIT_HDMA_TWICE) {   /* another mode, or indirect HDMA */
        count_undrawn(reg, mode);
    }
    h->reg = reg;
    h->two = (uint8_t)(mode & 3);
    h->table = (const uint8_t *)table;
}

void sdevkit_hdma_enable(uint8_t channels)
{
    check_drawing(0x0C, channels);                    /* $420C */
    sdevkit__rachael.hdma_enabled = channels;
}

/* --- Power on and vertical blank -------------------------------------------------------- */
/* As lib/snes/snes.c's sdevkit__reset_ppu leaves the SNES: the screen off, the mode 7 matrix the
 * identity, every sprite hidden; and the optional layers as at power on. */
void sdevkit__reset(void)
{
    uint16_t i;

    memset(&sdevkit__rachael, 0, sizeof sdevkit__rachael);
    sdevkit__rachael.regs[SDEVKIT_PPU_INIDISP] = 0x80;   /* forced blank until the game turns the screen on */
    sdevkit__rachael.m7[0] = 0x100;
    sdevkit__rachael.m7[3] = 0x100;
    for (i = 0; i < 512; i += 4) {
        sdevkit__rachael.oam[i + 1] = SDEVKIT_OAM_HIDE_Y;
    }
    sdevkit__rachael.tick_hash = SDEVKIT__HASH_START;
    sdevkit__sound_reset();
    sdevkit__layers_reset();
}

void sdevkit__vblank(void)
{
    sdevkit__rachael.dma_count = 0;
    sdevkit__rachael.dma_lost = 0;
    sdevkit__rachael.in_vblank = 1;
}

void sdevkit__display(void)
{
    sdevkit__rachael.in_vblank = 0;
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
    uint32_t h = sdevkit__rachael.tick_hash;
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
