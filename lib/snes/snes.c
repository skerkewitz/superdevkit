/*
 * The game's console API (snapshots/blubberblasen/src/snes/snes.h) on the real SNES, for dampf.
 *
 * The PVSnesLib-shaped functions do what PVSnesLib's do, as snes.h describes them; the snesXxx()
 * extensions are a few register writes each. General DMA uses channel 0; HDMA uses the channels
 * the game names (3 to 5). Nothing here waits for a vertical blank: the main loop (main.c) calls
 * the game's vertical blank code at the right time.
 *
 * Registers that the API changes bit by bit (the main and sub screen layers, the tile bases) are
 * kept in shadows, since PPU registers cannot be read back.
 */
#include "snes/snes.h"
#include "hw.h"

u8 oamMemory[128 * 4 + 8 * 4];

static u8 tm_shadow;          /* TM: layers on the main screen */
static u8 ts_shadow;          /* TS: layers on the sub screen */
static u8 nba_shadow[2];      /* BG12NBA, BG34NBA: tile bases */

static const u16 zero_word = 0;   /* the source of dmaClearVram */

/* A source address for a DMA channel: its 16-bit address and its bank. The bank is byte 2 of the
 * SNES address, read as a byte: dampf shifts a 32-bit value by 16 in a loop today (about 600
 * cycles), which the vertical blank cannot spare. */
static void dma_source(u8 channel, const void *source)
{
    u32 a = (u32)source;
    A1T(channel) = (u16)a;
    A1B(channel) = ((const u8 *)&a)[2];
}

/* One general DMA on channel 0: mode (DMAP), destination register (low byte of $21xx). */
static void dma0(u8 mode, u8 reg, const u8 *source, u16 size)
{
    DMAP(0) = mode;
    BBAD(0) = reg;
    dma_source(0, source);
    DAS(0) = size;
    MDMAEN = 1;
}

/* --- video.h ------------------------------------------------------------------------ */
void setMode(u8 mode, u8 size)
{
    BGMODE = (u8)(mode | size);
    tm_shadow = BG1_ENABLE | BG2_ENABLE | BG3_ENABLE | OBJ_ENABLE;
    TM = tm_shadow;
}

void setBrightness(u8 level)
{
    INIDISP = (u8)(level == 0 ? 0x80 : (level & 0x0F));
}

void setScreenOn(void)
{
    /* PVSnesLib waits for a vertical blank first */
    while (HVBJOY & 0x80) {
    }
    while (!(HVBJOY & 0x80)) {
    }
    INIDISP = 0x0F;
}

void setScreenOff(void)
{
    INIDISP = 0x80;
}

void setColorEffect(u8 colorMathA, u8 colorMathB)
{
    REG8(0x2130) = colorMathA;
    REG8(0x2131) = colorMathB;
}

void setColorIntensity(u8 colorApply, u8 intensity)
{
    REG8(0x2132) = (u8)(colorApply | (intensity & 0x1F));
}

void setPaletteColor(u16 paletteEntry, u16 paletteColor)
{
    CGADD = (u8)paletteEntry;
    CGDATA = (u8)paletteColor;
    CGDATA = (u8)(paletteColor >> 8);
}

/* --- dma.h ----------------------------------------------------------------------------- */
void dmaCopyVram(const u8 *source, u16 address, u16 size)
{
    VMAIN = 0x80;                   /* step after the high byte */
    VMADD = address;
    dma0(0x01, 0x18, source, size); /* two registers, $2118/$2119 */
}

void dmaCopyCGram(const u8 *source, u16 address, u16 size)
{
    CGADD = (u8)address;
    dma0(0x00, 0x22, source, size);
}

void dmaClearVram(void)
{
    VMAIN = 0x80;
    VMADD = 0;
    dma0(0x09, 0x18, (const u8 *)&zero_word, 0);   /* fixed source; size 0 is 64 KB */
}

/* --- background.h ------------------------------------------------------------------------ */
void bgSetGfxPtr(u8 bgNumber, u16 address)
{
    u8 i = (u8)(bgNumber >> 1);
    u8 base = (u8)((address >> 12) & 0x0F);
    if (bgNumber & 1) {
        nba_shadow[i] = (u8)((nba_shadow[i] & 0x0F) | (base << 4));
    } else {
        nba_shadow[i] = (u8)((nba_shadow[i] & 0xF0) | base);
    }
    REG8(0x210B + i) = nba_shadow[i];
}

void bgSetMapPtr(u8 bgNumber, u16 address, u8 mapSize)
{
    REG8(0x2107 + bgNumber) = (u8)(((address >> 8) & 0xFC) | mapSize);
}

void bgSetScroll(u8 bgNumber, u16 x, u16 y)
{
    u16 h = (u16)(0x210D + (bgNumber << 1));
    REG8(h) = (u8)x;
    REG8(h) = (u8)(x >> 8);
    REG8(h + 1) = (u8)y;
    REG8(h + 1) = (u8)(y >> 8);
}

void bgSetEnable(u8 bgNumber)
{
    tm_shadow = (u8)(tm_shadow | (1 << bgNumber));
    TM = tm_shadow;
}

void bgSetDisable(u8 bgNumber)
{
    tm_shadow = (u8)(tm_shadow & ~(1 << bgNumber));
    TM = tm_shadow;
}

void bgSetEnableSub(u8 bgNumber)
{
    ts_shadow = (u8)(ts_shadow | (1 << bgNumber));
    TS = ts_shadow;
}

void bgSetDisableSub(u8 bgNumber)
{
    ts_shadow = (u8)(ts_shadow & ~(1 << bgNumber));
    TS = ts_shadow;
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
    bgSetMapPtr(bgNumber, address, sizeMode);
}

/* --- sprite.h ------------------------------------------------------------------------------ */
/* The bits of sprite id (a byte offset) in the high table: x bit 8, then the size. */
static u8 *high_byte(u16 id)
{
    return &oamMemory[512 + (id >> 4)];
}

static u8 high_shift(u16 id)
{
    return (u8)((id >> 1) & 6);
}

static void set_x8(u16 id, u16 x)
{
    u8 *h = high_byte(id);
    u8 bit = (u8)(1 << high_shift(id));
    *h = (u8)((x & 0x100) ? (*h | bit) : (*h & ~bit));
}

void oamInit(void)
{
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
    OBSEL = (u8)(oamsize | ((address >> 13) & 7));
}

void oamSet(u16 id, u16 xspr, u16 yspr, u8 priority, u8 hflip, u8 vflip, u16 gfxoffset, u8 paletteoffset)
{
    oamMemory[id] = (u8)xspr;
    oamMemory[id + 1] = (u8)yspr;
    oamMemory[id + 2] = (u8)gfxoffset;
    oamMemory[id + 3] = (u8)((vflip << 7) | (hflip << 6) | ((priority & 3) << 4) | ((paletteoffset & 7) << 1)
                             | ((gfxoffset >> 8) & 1));
    set_x8(id, xspr);
}

void oamSetXY(u16 id, u16 xspr, u16 yspr)
{
    oamMemory[id] = (u8)xspr;
    oamMemory[id + 1] = (u8)yspr;
    set_x8(id, xspr);
}

void oamSetEx(u16 id, u8 size, u8 hide)
{
    u8 *h = high_byte(id);
    u8 bit = (u8)(2 << high_shift(id));
    *h = (u8)(size == OBJ_LARGE ? (*h | bit) : (*h & ~bit));
    oamSetVisible(id, hide);
}

void oamSetVisible(u16 id, u8 hide)
{
    /* hidden: x bit 8 set, so the sprite is left of the screen */
    u8 *h = high_byte(id);
    u8 bit = (u8)(1 << high_shift(id));
    *h = (u8)(hide == OBJ_HIDE ? (*h | bit) : (*h & ~bit));
}

void oamClear(u16 first, u8 numEntries)
{
    u16 id = first;
    u16 end = (u16)(numEntries == 0 ? 512 : first + ((u16)numEntries << 2));
    for (; id < end; id = (u16)(id + 4)) {
        oamMemory[id + 1] = ATTR2_DISABLED;
    }
}

/* --- Extensions ------------------------------------------------------------------------------ */
void snesRegWrite(u8 reg, u8 value)
{
    REG8(0x2100 + reg) = value;
}

void snesHdmaSet(u8 channel, u8 reg, u8 twoRegs, const u8 *table)
{
    DMAP(channel) = (u8)(twoRegs ? 0x01 : 0x00);   /* direct, CPU to PPU, one or two registers */
    BBAD(channel) = reg;
    dma_source(channel, table);
}

void snesHdmaEnable(u8 channels)
{
    HDMAEN = channels;
}

/* The line the picture is at: 225..261 is the vertical blank. */
static u16 vcount(void)
{
    u16 line;
    (void)SLHV;
    line = OPVCT;
    line = (u16)(line | ((OPVCT & 1) << 8));
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

u8 snesDmaFits(u16 bytes)
{
    u16 line = vcount();
    if (line < VBLANK_FIRST_LINE || line >= LINES - 1 - SETUP_LINES) {
        return 0;
    }
    return (u8)(bytes <= (u16)(LINES - 1 - SETUP_LINES - line) * 160);
}

/* --- for main.c ------------------------------------------------------------------------------ */
/* The OAM shadow into OAM: 544 bytes, in a vertical blank. */
void platform_oam_upload(void)
{
    OAMADD = 0;
    dma0(0x00, 0x04, oamMemory, sizeof oamMemory);
}

/* Power-on state: all PPU registers that start undefined are cleared, VRAM, CGRAM and OAM too. */
void platform_reset_video(void)
{
    u8 r;
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
    dmaClearVram();
    CGADD = 0;
    for (r = 0; r < 255; r++) {
        CGDATA = 0;
        CGDATA = 0;
    }
    CGDATA = 0;
    CGDATA = 0;
    tm_shadow = ts_shadow = 0;
    nba_shadow[0] = nba_shadow[1] = 0;
    oamClear(0, 0);
    for (r = 0; r < 32; r++) {
        oamMemory[512 + r] = 0;
    }
    platform_oam_upload();
}

void platform_wait_vblank(void)
{
    /* the start of a vertical blank: a tick that ended inside one waits for the next, so the
     * uploads always get the whole of it */
    while (HVBJOY & 0x80) {
    }
    while (!(HVBJOY & 0x80)) {
    }
}

#ifdef BB_DEBUG
/* Only video_check_maps() (a debug check on the PC's pseudo SNES) reads VRAM like memory; the
 * SNES cannot, and nothing calls it there. */
const u16 *snes_vram(void)
{
    static const u16 none[1];
    return none;
}
#endif

/* Both joypads, SNES bit layout (game.h). The automatic read runs in the first lines of the
 * vertical blank. */
void platform_read_pads(u16 *pad1, u16 *pad2)
{
    while (HVBJOY & 1) {
    }
    *pad1 = JOY1;
    *pad2 = JOY2;
}
