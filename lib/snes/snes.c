/*
 * The console API of superdevkit (lib/include/sdevkit/snes.h, ADR record 34) on the real SNES,
 * for dampf.
 *
 * PVSnesLib's functions, renamed, do what PVSnesLib's do, as sdevkit/snes.h describes them; the
 * extensions (sdevkit_reg_write, sdevkit_hdma_*, sdevkit_dma_fits) are a few register writes each. General DMA uses channel 0; HDMA uses the channels
 * the game names (3 to 5). Nothing here waits for a vertical blank: the main loop (main.c) calls
 * the game's vertical blank code at the right time.
 *
 * Registers that the API changes bit by bit (the main and sub screen layers, the tile bases) are
 * kept in shadows, since PPU registers cannot be read back.
 */
#include "sdevkit/snes.h"
#include "hw.h"

uint8_t sdevkit_oam_memory[128 * 4 + 8 * 4];

static uint8_t tm_shadow;          /* TM: layers on the main screen */
static uint8_t ts_shadow;          /* TS: layers on the sub screen */
static uint8_t nba_shadow[2];      /* BG12NBA, BG34NBA: tile bases */

static const uint16_t zero_word = 0;   /* the source of sdevkit_dma_clear_vram */

/* A source address for a DMA channel: its 16-bit address and its bank. The bank is byte 2 of the
 * SNES address, read as a byte: dampf shifts a 32-bit value by 16 in a loop today (about 600
 * cycles), which the vertical blank cannot spare. */
static void dma_source(uint8_t channel, const void *source)
{
    uint32_t a = (uint32_t)source;
    A1T(channel) = (uint16_t)a;
    A1B(channel) = ((const uint8_t *)&a)[2];
}

/* One general DMA on channel 0: mode (DMAP), destination register (low byte of $21xx). */
static void dma0(uint8_t mode, uint8_t reg, const uint8_t *source, uint16_t size)
{
    DMAP(0) = mode;
    BBAD(0) = reg;
    dma_source(0, source);
    DAS(0) = size;
    MDMAEN = 1;
}

/* --- video.h ------------------------------------------------------------------------ */
void sdevkit_set_mode(uint8_t mode, uint8_t size)
{
    BGMODE = (uint8_t)(mode | size);
    tm_shadow = SDEVKIT_BG1_ENABLE | SDEVKIT_BG2_ENABLE | SDEVKIT_BG3_ENABLE | SDEVKIT_OBJ_ENABLE;
    TM = tm_shadow;
}

void sdevkit_set_brightness(uint8_t level)
{
    INIDISP = (uint8_t)(level == 0 ? 0x80 : (level & 0x0F));
}

void sdevkit_set_screen_on(void)
{
    /* PVSnesLib waits for a vertical blank first */
    while (HVBJOY & 0x80) {
    }
    while (!(HVBJOY & 0x80)) {
    }
    INIDISP = 0x0F;
}

void sdevkit_set_screen_off(void)
{
    INIDISP = 0x80;
}

void sdevkit_set_color_effect(uint8_t colorMathA, uint8_t colorMathB)
{
    REG8(0x2130) = colorMathA;
    REG8(0x2131) = colorMathB;
}

void sdevkit_set_color_intensity(uint8_t colorApply, uint8_t intensity)
{
    REG8(0x2132) = (uint8_t)(colorApply | (intensity & 0x1F));
}

void sdevkit_set_palette_color(uint16_t paletteEntry, uint16_t paletteColor)
{
    CGADD = (uint8_t)paletteEntry;
    CGDATA = (uint8_t)paletteColor;
    CGDATA = (uint8_t)(paletteColor >> 8);
}

/* --- dma.h ----------------------------------------------------------------------------- */
void sdevkit_dma_copy_vram(const uint8_t *source, uint16_t address, uint16_t size)
{
    VMAIN = 0x80;                   /* step after the high byte */
    VMADD = address;
    dma0(0x01, 0x18, source, size); /* two registers, $2118/$2119 */
}

void sdevkit_dma_copy_cgram(const uint8_t *source, uint16_t address, uint16_t size)
{
    CGADD = (uint8_t)address;
    dma0(0x00, 0x22, source, size);
}

void sdevkit_dma_clear_vram(void)
{
    VMAIN = 0x80;
    VMADD = 0;
    dma0(0x09, 0x18, (const uint8_t *)&zero_word, 0);   /* fixed source; size 0 is 64 KB */
}

/* --- Mode 7 ----------------------------------------------------------------------------- */
/* The map is the low byte of each VRAM word from word 0, the tiles the high byte: VMAIN 0x00
 * steps the address after a write of $2118 (low), 0x80 after $2119 (high), so a DMA of single
 * bytes to one of the two fills only that half of each word. */
void sdevkit_mode7_load_map(const uint8_t *map, uint16_t address, uint16_t size)
{
    VMAIN = 0x00;
    VMADD = address;
    dma0(0x00, 0x18, map, size);
}

void sdevkit_mode7_load_tiles(const uint8_t *tiles, uint16_t address, uint16_t size)
{
    VMAIN = 0x80;
    VMADD = address;
    dma0(0x00, 0x19, tiles, size);
}

void sdevkit_mode7_set_matrix(int16_t a, int16_t b, int16_t c, int16_t d)
{
    M7A = (uint8_t)a;
    M7A = (uint8_t)((uint16_t)a >> 8);
    M7B = (uint8_t)b;
    M7B = (uint8_t)((uint16_t)b >> 8);
    M7C = (uint8_t)c;
    M7C = (uint8_t)((uint16_t)c >> 8);
    M7D = (uint8_t)d;
    M7D = (uint8_t)((uint16_t)d >> 8);
}

void sdevkit_mode7_set_center(int16_t x, int16_t y)
{
    M7X = (uint8_t)x;
    M7X = (uint8_t)((uint16_t)x >> 8);
    M7Y = (uint8_t)y;
    M7Y = (uint8_t)((uint16_t)y >> 8);
}

void sdevkit_mode7_set_select(uint8_t select)
{
    M7SEL = select;
}

/* --- background.h ------------------------------------------------------------------------ */
void sdevkit_bg_set_gfx_ptr(uint8_t bgNumber, uint16_t address)
{
    uint8_t i = (uint8_t)(bgNumber >> 1);
    uint8_t base = (uint8_t)((address >> 12) & 0x0F);
    if (bgNumber & 1) {
        nba_shadow[i] = (uint8_t)((nba_shadow[i] & 0x0F) | (base << 4));
    } else {
        nba_shadow[i] = (uint8_t)((nba_shadow[i] & 0xF0) | base);
    }
    REG8(0x210B + i) = nba_shadow[i];
}

void sdevkit_bg_set_map_ptr(uint8_t bgNumber, uint16_t address, uint8_t mapSize)
{
    REG8(0x2107 + bgNumber) = (uint8_t)(((address >> 8) & 0xFC) | mapSize);
}

void sdevkit_bg_set_scroll(uint8_t bgNumber, uint16_t x, uint16_t y)
{
    uint16_t h = (uint16_t)(0x210D + (bgNumber << 1));
    REG8(h) = (uint8_t)x;
    REG8(h) = (uint8_t)(x >> 8);
    REG8(h + 1) = (uint8_t)y;
    REG8(h + 1) = (uint8_t)(y >> 8);
}

void sdevkit_bg_set_enable(uint8_t bgNumber)
{
    tm_shadow = (uint8_t)(tm_shadow | (1 << bgNumber));
    TM = tm_shadow;
}

void sdevkit_bg_set_disable(uint8_t bgNumber)
{
    tm_shadow = (uint8_t)(tm_shadow & ~(1 << bgNumber));
    TM = tm_shadow;
}

void sdevkit_bg_set_enable_sub(uint8_t bgNumber)
{
    ts_shadow = (uint8_t)(ts_shadow | (1 << bgNumber));
    TS = ts_shadow;
}

void sdevkit_bg_set_disable_sub(uint8_t bgNumber)
{
    ts_shadow = (uint8_t)(ts_shadow & ~(1 << bgNumber));
    TS = ts_shadow;
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
    sdevkit_bg_set_map_ptr(bgNumber, address, sizeMode);
}

/* --- sprite.h ------------------------------------------------------------------------------ */
/* The bits of sprite id (a byte offset) in the high table: x bit 8, then the size. */
static uint8_t *high_byte(uint16_t id)
{
    return &sdevkit_oam_memory[512 + (id >> 4)];
}

static uint8_t high_shift(uint16_t id)
{
    return (uint8_t)((id >> 1) & 6);
}

static void set_x8(uint16_t id, uint16_t x)
{
    uint8_t *h = high_byte(id);
    uint8_t bit = (uint8_t)(1 << high_shift(id));
    *h = (uint8_t)((x & 0x100) ? (*h | bit) : (*h & ~bit));
}

void sdevkit_oam_init(void)
{
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
    OBSEL = (uint8_t)(oamsize | ((address >> 13) & 7));
}

void sdevkit_oam_set(uint16_t id, uint16_t xspr, uint16_t yspr, uint8_t priority, uint8_t hflip, uint8_t vflip, uint16_t gfxoffset, uint8_t paletteoffset)
{
    sdevkit_oam_memory[id] = (uint8_t)xspr;
    sdevkit_oam_memory[id + 1] = (uint8_t)yspr;
    sdevkit_oam_memory[id + 2] = (uint8_t)gfxoffset;
    sdevkit_oam_memory[id + 3] = (uint8_t)((vflip << 7) | (hflip << 6) | ((priority & 3) << 4) | ((paletteoffset & 7) << 1)
                             | ((gfxoffset >> 8) & 1));
    set_x8(id, xspr);
}

void sdevkit_oam_set_xy(uint16_t id, uint16_t xspr, uint16_t yspr)
{
    sdevkit_oam_memory[id] = (uint8_t)xspr;
    sdevkit_oam_memory[id + 1] = (uint8_t)yspr;
    set_x8(id, xspr);
}

void sdevkit_oam_set_ex(uint16_t id, uint8_t size, uint8_t hide)
{
    uint8_t *h = high_byte(id);
    uint8_t bit = (uint8_t)(2 << high_shift(id));
    *h = (uint8_t)(size == SDEVKIT_OBJ_LARGE ? (*h | bit) : (*h & ~bit));
    sdevkit_oam_set_visible(id, hide);
}

void sdevkit_oam_set_visible(uint16_t id, uint8_t hide)
{
    /* hidden: y below the picture (SDEVKIT_ATTR2_DISABLED), as the console API and Rachael have
     * it; shown: nothing, the next sdevkit_oam_set gives it its y. (It used to set bit 8 of x to hide and
     * clear it to show, which moved a sprite at a negative x to the right edge, and did not hide
     * one whose low byte of x was above 240.) */
    if (hide == SDEVKIT_OBJ_HIDE) {
        sdevkit_oam_memory[id + 1] = SDEVKIT_ATTR2_DISABLED;
    }
}

void sdevkit_oam_clear(uint16_t first, uint8_t numEntries)
{
    uint16_t id = first;
    uint16_t end = (uint16_t)(numEntries == 0 ? 512 : first + ((uint16_t)numEntries << 2));
    for (; id < end && id < 512; id = (uint16_t)(id + 4)) {   /* as Rachael: x 0, hidden, small */
        uint8_t *h = high_byte(id);
        sdevkit_oam_memory[id] = 0;
        sdevkit_oam_memory[id + 1] = SDEVKIT_ATTR2_DISABLED;
        *h = (uint8_t)(*h & ~(3 << high_shift(id)));
    }
}

/* --- Extensions ------------------------------------------------------------------------------ */
void sdevkit_reg_write(uint8_t reg, uint8_t value)
{
    REG8(0x2100 + reg) = value;
}

void sdevkit_hdma_set(uint8_t channel, uint8_t reg, uint8_t twoRegs, const uint8_t *table)
{
    DMAP(channel) = (uint8_t)(twoRegs ? 0x01 : 0x00);   /* direct, CPU to PPU, one or two registers */
    BBAD(channel) = reg;
    dma_source(channel, table);
}

void sdevkit_hdma_enable(uint8_t channels)
{
    HDMAEN = channels;
}

/* The line the picture is at: 225..261 is the vertical blank. */
static uint16_t vcount(void)
{
    uint16_t line;
    (void)SLHV;
    line = OPVCT;
    line = (uint16_t)(line | ((OPVCT & 1) << 8));
    (void)STAT78;
    return line;
}

/* A DMA moves one byte per 8 master cycles, a line has 1364: 170 bytes. Kept to 160 a line. A copy
 * that starts after the last line of the vertical blank is lost, and the code between this check
 * and the copy runs first: so the line it is in now does not count, nor SETUP_LINES more. Measured
 * in Mesen (dev/check_game_rom.sh): from the check to the start of a tileset copy in video.c
 * takes 3.5 lines, the setup of the palette copy after it (video.c asks once for both) 1.5 more.
 * The setup is that slow because dampf writes each register through a pointer today; when it
 * writes them directly, this margin can shrink. */
#ifndef SETUP_LINES
#define SETUP_LINES 8
#endif

uint8_t sdevkit_dma_fits(uint16_t bytes)
{
    uint16_t line = vcount();
    if (line < VBLANK_FIRST_LINE || line >= LINES - 1 - SETUP_LINES) {
        return 0;
    }
    return (uint8_t)(bytes <= (uint16_t)(LINES - 1 - SETUP_LINES - line) * 160);
}

/* --- for main.c ------------------------------------------------------------------------------ */
/* The OAM shadow into OAM: 544 bytes, in a vertical blank. */
void sdevkit__oam_upload(void)
{
    OAMADD = 0;
    dma0(0x00, 0x04, sdevkit_oam_memory, sizeof sdevkit_oam_memory);
}

/* Power-on state: all PPU registers that start undefined are cleared, VRAM, CGRAM and OAM too. */
void sdevkit__reset_video(void)
{
    uint8_t r;
    INIDISP = 0x80;
    for (r = 0x01; r <= 0x0C; r++) {
        REG8(0x2100 + r) = 0;
    }
    for (r = 0x0D; r <= 0x14; r++) {   /* scroll: written twice */
        REG8(0x2100 + r) = 0;
        REG8(0x2100 + r) = 0;
    }
    for (r = 0x15; r <= 0x33; r++) {
        REG8(0x2100 + r) = 0;
    }
    REG8(0x211B) = 1;                  /* mode 7 matrix: identity (written twice) */
    REG8(0x211B) = 0;
    REG8(0x211E) = 1;
    REG8(0x211E) = 0;
    VMAIN = 0x80;
    sdevkit_dma_clear_vram();
    CGADD = 0;
    for (r = 0; r < 255; r++) {
        CGDATA = 0;
        CGDATA = 0;
    }
    CGDATA = 0;
    CGDATA = 0;
    tm_shadow = ts_shadow = 0;
    nba_shadow[0] = nba_shadow[1] = 0;
    sdevkit_oam_clear(0, 0);
    for (r = 0; r < 32; r++) {
        sdevkit_oam_memory[512 + r] = 0;
    }
    sdevkit__oam_upload();
}

void sdevkit__wait_vblank(void)
{
    /* the start of a vertical blank: a tick that ended inside one waits for the next, so the
     * uploads always get the whole of it */
    while (HVBJOY & 0x80) {
    }
    while (!(HVBJOY & 0x80)) {
    }
}

/* Only Rachael can read VRAM like memory (a debug check of the desktop); the SNES
 * cannot, so here it is a single zero word. */
const uint16_t *sdevkit_dbg_vram(void)
{
    static const uint16_t none[1];
    return none;
}

/* Both joypads, SNES bit layout (game.h). The automatic read runs in the first lines of the
 * vertical blank. */
void sdevkit__read_pads(uint16_t *pad1, uint16_t *pad2)
{
    while (HVBJOY & 1) {
    }
    *pad1 = JOY1;
    *pad2 = JOY2;
}
