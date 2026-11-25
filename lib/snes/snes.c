/*
 * The core of superdevkit's console API (lib/include/sdevkit/snes.h, ADR-6FNPPCSX) on the real
 * SNES, for dampf: each function is the register writes it names and nothing else, small enough
 * that dampf inlines it and folds a constant register into the store. General DMA uses channel 0;
 * HDMA the channels the game names (1 to 7). And what main.c needs from the hardware: the reset,
 * the wait for the vertical blank, the joypads.
 *
 * The optional layers (sdevkit/shadow.h, sprites.h, queue.h, input.h, easy.h) are layers.c.
 */
#include "sdevkit/snes.h"
#include "hw.h"

static uint8_t fill_value;             /* the source of sdevkit_vram_fill, a fixed-source DMA */

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
static void dma0(uint8_t mode, uint8_t reg, const void *source, uint16_t size)
{
    DMAP(0) = mode;
    BBAD(0) = reg;
    dma_source(0, source);
    DAS(0) = size;
    MDMAEN = 1;
}

/* --- Registers ------------------------------------------------------------------------------- */
void sdevkit_reg_write(uint8_t reg, uint8_t value)
{
    REG8(0x2100 + reg) = value;
}

void sdevkit_reg_write_twice(uint8_t reg, uint16_t value)
{
    REG8(0x2100 + reg) = (uint8_t)value;
    REG8(0x2100 + reg) = (uint8_t)(value >> 8);
}

/* --- Copies ---------------------------------------------------------------------------------- */
void sdevkit_dma(uint8_t dmap, uint8_t bbad, const void *src, uint16_t bytes)
{
    dma0(dmap, bbad, src, bytes);
}

void sdevkit_vram_copy(uint16_t a, const void *src, uint16_t bytes)
{
    VMAIN = 0x80;                      /* step after the high byte */
    VMADD = a;
    dma0(0x01, 0x18, src, bytes);      /* two registers, $2118/$2119 */
}

void sdevkit_vram_column_copy(uint16_t a, const void *src, uint16_t bytes)
{
    VMAIN = 0x81;                      /* step 32 words after the high byte */
    VMADD = a;
    dma0(0x01, 0x18, src, bytes);
}

void sdevkit_vram_fill(uint16_t a, uint8_t value, uint16_t bytes)
{
    fill_value = value;
    VMAIN = 0x80;
    VMADD = a;
    dma0(0x09, 0x18, &fill_value, bytes);   /* a fixed source, to $2118/$2119 in turn */
}

void sdevkit_cgram_copy(uint8_t i, const void *src, uint16_t bytes)
{
    CGADD = i;
    dma0(0x00, 0x22, src, bytes);
}

void sdevkit_cgram_set(uint8_t i, uint16_t bgr)
{
    CGADD = i;
    CGDATA = (uint8_t)bgr;
    CGDATA = (uint8_t)(bgr >> 8);
}

void sdevkit_oam_copy(uint16_t o, const void *src, uint16_t bytes)
{
    OAMADD = (uint16_t)(o >> 1);       /* OAM's address counts words; the high table is word 0x100 */
    dma0(0x00, 0x04, src, bytes);
}

/* Mode 7: the map is the low byte of each VRAM word from word 0, the tiles the high byte. VMAIN
 * 0x00 steps the address after a write of $2118 (low), 0x80 after $2119 (high), so a DMA of single
 * bytes to one of the two fills only that half of each word. */
void sdevkit_mode7_map_copy(uint16_t e, const void *src, uint16_t bytes)
{
    VMAIN = 0x00;
    VMADD = e;
    dma0(0x00, 0x18, src, bytes);
}

void sdevkit_mode7_tiles_copy(uint16_t b, const void *src, uint16_t bytes)
{
    VMAIN = 0x80;
    VMADD = b;
    dma0(0x00, 0x19, src, bytes);
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
 * in Mesen on Blubber Blasen's ROM: from the check to the start of a tileset copy in its video.c
 * takes 3.5 lines, the setup of the palette copy after it (video.c asks once for both) 1.5 more. */
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

/* --- HDMA ------------------------------------------------------------------------------------ */
void sdevkit_hdma_set(uint8_t channel, uint8_t reg, uint8_t mode, const void *table)
{
    DMAP(channel) = mode;              /* direct, CPU to PPU, unless the game sets more bits */
    BBAD(channel) = reg;
    dma_source(channel, table);
}

void sdevkit_hdma_enable(uint8_t channels)
{
    HDMAEN = channels;
}

/* --- for main.c ------------------------------------------------------------------------------ */
/* The PPU as at power-on, the screen off: registers, VRAM, CGRAM, and every sprite hidden (x 0,
 * y below the picture, small). Written by the CPU, the sprites too. */
void sdevkit__reset_ppu(void)
{
    uint8_t r;
    uint16_t i;

    INIDISP = SDEVKIT_FORCED_BLANK;
    HDMAEN = 0;
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
    REG8(0x211B) = 0;                  /* mode 7 matrix: the identity, 0x0100 (written twice) */
    REG8(0x211B) = 1;
    REG8(0x211E) = 0;
    REG8(0x211E) = 1;
    sdevkit_vram_fill(0, 0, 0);
    CGADD = 0;
    for (r = 0; r < 255; r++) {
        CGDATA = 0;
        CGDATA = 0;
    }
    CGDATA = 0;
    CGDATA = 0;
    OAMADD = 0;
    for (i = 0; i < 128; i++) {
        OAMDATA = 0;
        OAMDATA = SDEVKIT_OAM_HIDE_Y;
        OAMDATA = 0;
        OAMDATA = 0;
    }
    for (r = 0; r < 32; r++) {
        OAMDATA = 0;
    }
}

void sdevkit__wait_vblank(void)
{
    /* the start of a vertical blank: a tick that ended inside one waits for the next, so
     * game_vblank always gets the whole of it */
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

/* The address and the size go to port $21FC, five bytes (address low, high, bank, size low,
 * high); the checker (dev/example_check.lua, dampf's emulator) reads and hashes the memory. */
void sdevkit_dbg_hash(const void *data, uint16_t bytes)
{
    uint32_t a = (uint32_t)data;
    const uint8_t *b = (const uint8_t *)&a;
    DBG_HASH = b[0];
    DBG_HASH = b[1];
    DBG_HASH = b[2];
    DBG_HASH = (uint8_t)bytes;
    DBG_HASH = (uint8_t)(bytes >> 8);
}

/* Music is not built on the SNES yet (docs/sound.md): the calls do nothing, and dampf inlines them
 * away, so a game that plays music on the desktop builds unchanged. */
void sdevkit_music_play(uint8_t track)
{
    (void)track;
}

void sdevkit_music_stop(void)
{
}

/* The debug view exists on the desktop only: here the registrations and the boxes do nothing, and
 * dampf inlines the empty functions away (DebugCallsTest). */
void sdevkit_dbg_toggle(const char *name, uint8_t *flag)
{
    (void)name;
    (void)flag;
}

void sdevkit_dbg_range(const char *name, int16_t *value, int16_t lo, int16_t hi)
{
    (void)name;
    (void)value;
    (void)lo;
    (void)hi;
}

void sdevkit_dbg_color(const char *name, uint16_t *colour)
{
    (void)name;
    (void)colour;
}

void sdevkit_dbg_action(const char *name, uint8_t *pressed)
{
    (void)name;
    (void)pressed;
}

void sdevkit_dbg_text(const char *name, const char *text)
{
    (void)name;
    (void)text;
}

void sdevkit_dbg_value(const char *name, const uint16_t *value)
{
    (void)name;
    (void)value;
}

void sdevkit_dbg_box(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind)
{
    (void)x;
    (void)y;
    (void)w;
    (void)h;
    (void)kind;
}

void sdevkit_dbg_fill(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind)
{
    (void)x;
    (void)y;
    (void)w;
    (void)h;
    (void)kind;
}

void sdevkit_dbg_arrow(int16_t x, int16_t y, int8_t dx, int8_t dy, uint8_t kind)
{
    (void)x;
    (void)y;
    (void)dx;
    (void)dy;
    (void)kind;
}

/* The cartridge's battery-backed RAM: LoROM maps it at $70:0000, and dampf --sram writes into the
 * header that the cartridge has it (ROM, RAM and battery; the size). */
uint8_t *sdevkit_sram(void)
{
#if SDEVKIT_SRAM_SIZE > 0
    return (uint8_t *)0x700000;
#else
    return 0;
#endif
}

/* Both joypads, SNES bit layout (SDEVKIT_KEY_*). The automatic read runs in the first lines of the
 * vertical blank. */
void sdevkit__read_pads(uint16_t *pad1, uint16_t *pad2)
{
    while (HVBJOY & 1) {
    }
    *pad1 = JOY1;
    *pad2 = JOY2;
}
