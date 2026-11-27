#ifndef SDEVKIT_SNES_H
#define SDEVKIT_SNES_H

/**
 * @file snes.h
 * @brief The console API's core: the SNES's picture hardware, its copies, sound, save RAM and the
 *        debug calls (ADR-6FNPPCSX).
 *
 * Every name a game can use starts with `sdevkit_` or `SDEVKIT_`; nothing else of the kit is
 * meant for it. `lib/snes` implements this header on the console, compiled by dampf with the
 * game; `lib/rachael` (Rachael, the SNES rebuilt in portable C) on the desktop and the web.
 * `lib/README.md` lists what each function costs in CPU cycles. This header, `sdevkit/loop.h`
 * and the optional layers' headers are all a game needs: what the hardware does is explained
 * here, at the names that reach it.
 *
 * @section snes_picture How the SNES makes a picture
 *
 * The picture is 256 x 224 pixels, drawn line by line 60 times a second (NTSC). The PPU (the
 * picture processor) builds it from three memories of its own, which the CPU cannot address
 * directly, only through ports:
 *
 * - **VRAM**, 64 KB as 32,768 16-bit words (addresses 0x0000..0x7FFF count words): the tiles
 *   (8 x 8 pixel images) and the maps (which tile goes where on a layer).
 * - **CGRAM**, 256 colours of 15 bits, BGR 5:5:5 (sdevkit_cgram_*): every pixel of a tile is an
 *   index into it. Colour 0 is the backdrop, shown where no layer and no sprite has a pixel.
 * - **OAM**, the sprite table: 128 sprites, each a position, a tile, a palette and flags
 *   ("Sprites" below).
 *
 * On top of the backdrop the PPU lays background layers (BG1 to BG4, how many depends on the
 * mode) and the sprites, and decides per pixel which is in front by their priorities. Rachael
 * draws these modes as the SNES does ("BGMODE" below):
 *
 * - **Mode 0** (SDEVKIT_BG_MODE0): four layers, BG1 to BG4, with 4 colours a tile (2 bits a
 *   pixel), each layer with 32 colours of its own in CGRAM.
 * - **Mode 1** (SDEVKIT_BG_MODE1): BG1 and BG2 with 16 colours a tile (4 bits a pixel), BG3 with
 *   4 colours (2 bits a pixel), and the sprites. The usual mode of a 2D game.
 * - **Mode 2** (SDEVKIT_BG_MODE2): BG1 and BG2 with 16 colours, and a scroll per column of tiles
 *   from BG3's map ("Offset per tile").
 * - **Mode 3** (SDEVKIT_BG_MODE3): BG1 with 256 colours a tile (8 bits a pixel, or direct colour),
 *   BG2 with 16.
 * - **Mode 4** (SDEVKIT_BG_MODE4): BG1 with 256 colours, BG2 with 4, and a scroll per column.
 * - **Mode 7** (SDEVKIT_BG_MODE7): one layer, BG1, of 1024 x 1024 pixels, rotated, scaled and
 *   sheared by a matrix, 256 colours a pixel; and the sprites; and its second layer (EXTBG).
 *
 * Mosaic works on every layer. Modes 5 and 6 and the high-resolution, overscan and interlaced modes
 * of SETINI exist on the SNES, but Rachael does not draw them: the picture would be 512 pixels wide
 * or 239 or 448 lines high. A game that sets them gets a desktop check that fails ("undrawn"),
 * since the desktop would show something else than the console.
 *
 * @section snes_time The frame and the vertical blank
 *
 * A frame is 262 lines of 1,364 master cycles (21.477 MHz): 357,368 master cycles, about 60 a
 * second. The CPU takes 6 master cycles for an internal cycle and a read of fast ROM, 8 for work
 * RAM (3.58 and 2.68 MHz): a frame is about 45,000 to 60,000 CPU cycles, less 40 master cycles a
 * line that the RAM's refresh takes. Lines 1 to 224 are drawn; lines 225 to 261 are
 * the **vertical blank**, about 49,000 master cycles in which the PPU draws nothing.
 *
 * - While a line is drawn, VRAM, CGRAM and OAM take no writes: the PPU drops them (VRAM) or puts
 *   them at a wrong address (CGRAM, OAM). They take writes only in the vertical blank, or while
 *   the screen is off (forced blank: SDEVKIT_FORCED_BLANK in INIDISP, the picture is black).
 * - A register written while a line is drawn changes the picture from that line on: a scroll
 *   written in the middle of the picture tears it. Switching the screen off at once is the one
 *   exception a game may want (the rest of that frame is black, and VRAM opens).
 *
 * So a game computes the next picture in its own memory in `game_tick`, while the current one is
 * drawn, and writes registers and copies in `game_vblank` (sdevkit/loop.h). `game_init` runs with
 * the screen off and may write anything at any time. The desktop checks the rule: a byte for
 * VRAM, CGRAM or OAM written while the picture is drawn is lost there as on the SNES, a register
 * or an HDMA channel written then is counted ("drawing"), and the desktop's check fails on
 * either.
 *
 * @section snes_core The core and the layers
 *
 * Everything in this header goes to the hardware at once: a register write is one store on the
 * SNES (dampf inlines the call and folds the register's address), a copy one DMA. The kit does
 * nothing in the vertical blank a game did not ask for. Above the core, optional layers in
 * portable C, each costing a game only what it calls (dampf leaves out what a program does not
 * call): sdevkit/shadow.h (the registers in memory, written by one call in game_vblank),
 * sdevkit/sprites.h (a sprite list), sdevkit/queue.h (copies queued in game_tick),
 * sdevkit/input.h (the buttons pressed since the last tick), and sdevkit/easy.h, all of them and
 * one commit.
 *
 * Units: VRAM addresses count 16-bit words (0x0000..0x7FFF), CGRAM addresses count colours
 * (0..255), OAM addresses count bytes (0..543). Sizes count bytes.
 *
 * @section snes_first A first picture
 *
 * The smallest complete picture: one colour on the backdrop, one tile on BG1.
 * @code
 * static const uint8_t TILE[32] = {                 // a 4-bit tile: colour 1 everywhere
 *     0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0,
 * };
 * static const uint16_t ENTRY = SDEVKIT_BG_TIL_NUM(1) | SDEVKIT_BG_TIL_PAL(0);
 *
 * void game_init(uint16_t seed)                     // the screen is off: write anything
 * {
 *     (void)seed;
 *     sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1);
 *     sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(0x1000, SDEVKIT_SC_32x32));
 *     sdevkit_reg_write(SDEVKIT_PPU_BG12NBA, SDEVKIT_BGNBA(0x0000, 0x0000));
 *     sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE);
 *     sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, 0x3FF);   // map line 0 on screen line 0
 *     sdevkit_vram_copy(0x0000 + 16, TILE, sizeof TILE);     // tile 1 (16 words a 4-bit tile)
 *     sdevkit_vram_copy(0x1000 + 5 * 32 + 3, &ENTRY, 2);     // map row 5, column 3
 *     sdevkit_cgram_set(0, SDEVKIT_RGB5(0, 0, 8));           // the backdrop: dark blue
 *     sdevkit_cgram_set(1, SDEVKIT_RGB5(31, 31, 0));         // palette 0, colour 1: yellow
 *     sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);            // the screen on, full brightness
 * }
 * @endcode
 * The map and VRAM start as zeros, so every other map entry shows tile 0, which is transparent
 * (all zeros), and the backdrop shows through.
 */

#include <stdint.h>

/* --- Colours ------------------------------------------------------------------------------ */
/**
 * @name Colours
 * A CGRAM colour is 15 bits, BGR 5:5:5: bits 0-4 red, 5-9 green, 10-14 blue, each 0..31; bit 15
 * is ignored. CGRAM holds 256 of them. In mode 1, a background palette p of 16 colours is CGRAM
 * colours 16p..16p+15 (BG1, BG2, palettes 0 to 7), a palette p of 4 colours is 4p..4p+3 (BG3,
 * palettes 0 to 7, so CGRAM 0..31); sprite palette p is 128 + 16p..128 + 16p + 15. Colour 0 of
 * every palette is transparent; CGRAM colour 0 itself is the backdrop. In mode 7 a pixel is an
 * index into all 256 colours (0 transparent).
 * @{
 */
/** A colour from red, green, blue in 0..31. */
#define SDEVKIT_RGB5(r, g, b) ((uint16_t)((r) | ((g) << 5) | ((b) << 10)))
/** A colour from red, green, blue in 0..255 (the low 3 bits are dropped). */
#define SDEVKIT_RGB8(r, g, b) ((uint16_t)(((r) >> 3) | (((g) >> 3) << 5) | (((b) >> 3) << 10)))
/** @} */

/* --- The PPU's registers ------------------------------------------------------------------ */
/**
 * @brief Writes a PPU register.
 *
 * The PPU's registers are write-only (a game keeps its own copy of what it wrote, or uses
 * sdevkit/shadow.h). A write changes the picture from the line being drawn on, so a game writes
 * them in game_vblank or with the screen off.
 * @param reg   the low byte of the register's address $21xx: an `SDEVKIT_PPU_*` name, $00..$33
 * @param value the byte, built with the macros below
 *
 * One store on the SNES when @p reg is a constant (dampf inlines the call).
 */
void sdevkit_reg_write(uint8_t reg, uint8_t value);

/**
 * @brief Writes a register that takes two bytes in a row, low byte then high byte.
 *
 * The scroll registers (BGnHOFS, BGnVOFS) and mode 7's (M7A..M7Y) are each one register written
 * twice: a single write would set only half of the value.
 * @param reg   the register, an `SDEVKIT_PPU_*` name marked "written twice"
 * @param value the 16-bit value
 */
void sdevkit_reg_write_twice(uint8_t reg, uint16_t value);

/**
 * @name The registers
 * The low byte of each register's address $21xx, for sdevkit_reg_write and as the target of an
 * HDMA channel. "Twice": written twice, with sdevkit_reg_write_twice.
 * @{
 */
#define SDEVKIT_PPU_INIDISP 0x00   /**< Screen: bit 7 forced blank (SDEVKIT_FORCED_BLANK: black, memories open); bits 0-3 brightness 0..15 (0 is black too, but the memories stay closed). */
#define SDEVKIT_PPU_OBSEL   0x01   /**< Sprites: their tiles' VRAM address and their two sizes, SDEVKIT_OBSEL(). */
#define SDEVKIT_PPU_OAMADDL 0x02   /**< OAM port: the word address, low byte (sdevkit_oam_copy sets it). */
#define SDEVKIT_PPU_OAMADDH 0x03   /**< OAM port: bit 0 the word address's bit 8 (the high table is word 0x100); bit 7 priority rotation, leave 0. */
#define SDEVKIT_PPU_OAMDATA 0x04   /**< OAM port: data; the low table takes bytes in pairs (the even one is kept until the odd one), the high table one at a time. */
#define SDEVKIT_PPU_BGMODE  0x05   /**< Mode: bits 0-2 the mode (SDEVKIT_BG_MODE0 to _MODE4, _MODE7), bit 3 BG3 in front in mode 1, bits 4-7 16 x 16 tiles for BG1..BG4. */
#define SDEVKIT_PPU_MOSAIC  0x06   /**< Mosaic: bits 4-7 the block size - 1, bits 0-3 BG1..BG4 on: SDEVKIT_MOSAIC(). */
#define SDEVKIT_PPU_BG1SC   0x07   /**< BG1's map: SDEVKIT_BGSC(address, size). BGnSC = 0x07 + (n - 1). */
#define SDEVKIT_PPU_BG2SC   0x08   /**< BG2's map. */
#define SDEVKIT_PPU_BG3SC   0x09   /**< BG3's map. */
#define SDEVKIT_PPU_BG4SC   0x0A   /**< BG4's map (no BG4 in modes 1 and 7). */
#define SDEVKIT_PPU_BG12NBA 0x0B   /**< BG1's and BG2's tiles: SDEVKIT_BGNBA(BG1's address, BG2's address). */
#define SDEVKIT_PPU_BG34NBA 0x0C   /**< BG3's and BG4's tiles. */
#define SDEVKIT_PPU_BG1HOFS 0x0D   /**< BG1's horizontal scroll, 10 bits; twice. BGnHOFS = 0x0D + 2(n - 1). In mode 7 also mode 7's scroll, 13 bits with sign. */
#define SDEVKIT_PPU_BG1VOFS 0x0E   /**< BG1's vertical scroll; twice. BGnVOFS = 0x0E + 2(n - 1). Screen line y shows map line y + VOFS + 1: write 0x3FF (-1) for map line 0 on screen line 0. */
#define SDEVKIT_PPU_BG2HOFS 0x0F   /**< BG2's horizontal scroll; twice. */
#define SDEVKIT_PPU_BG2VOFS 0x10   /**< BG2's vertical scroll; twice. */
#define SDEVKIT_PPU_BG3HOFS 0x11   /**< BG3's horizontal scroll; twice. */
#define SDEVKIT_PPU_BG3VOFS 0x12   /**< BG3's vertical scroll; twice. */
#define SDEVKIT_PPU_BG4HOFS 0x13   /**< BG4's horizontal scroll; twice. */
#define SDEVKIT_PPU_BG4VOFS 0x14   /**< BG4's vertical scroll; twice. */
#define SDEVKIT_PPU_VMAIN   0x15   /**< VRAM port: bit 7 step the address after the high byte ($2119; else after the low one), bits 0-1 the step (0: 1 word, 1: 32, 2 and 3: 128); bits 2-3 address remapping (SDEVKIT_VMAIN_REMAP_*). The copy functions set it. */
#define SDEVKIT_PPU_VMADDL  0x16   /**< VRAM port: the word address, low byte. */
#define SDEVKIT_PPU_VMADDH  0x17   /**< VRAM port: the word address, high byte. */
#define SDEVKIT_PPU_VMDATAL 0x18   /**< VRAM port: the low byte of the word at the address. */
#define SDEVKIT_PPU_VMDATAH 0x19   /**< VRAM port: the high byte of the word at the address. */
#define SDEVKIT_PPU_M7SEL   0x1A   /**< Mode 7: bits 6-7 what lies outside the map (SDEVKIT_M7_WRAP, _TRANSPARENT, _TILE0), bit 0 mirror the screen across, bit 1 up and down. */
#define SDEVKIT_PPU_M7A     0x1B   /**< Mode 7's matrix a, 8.8 fixed point with sign (0x0100 is 1.0); twice. M7A..M7D, M7X, M7Y = 0x1B..0x20. */
#define SDEVKIT_PPU_M7B     0x1C   /**< Mode 7's matrix b; twice. */
#define SDEVKIT_PPU_M7C     0x1D   /**< Mode 7's matrix c; twice. */
#define SDEVKIT_PPU_M7D     0x1E   /**< Mode 7's matrix d; twice. */
#define SDEVKIT_PPU_M7X     0x1F   /**< Mode 7's centre x, 13 bits with sign, in map pixels; twice. */
#define SDEVKIT_PPU_M7Y     0x20   /**< Mode 7's centre y; twice. */
#define SDEVKIT_PPU_CGADD   0x21   /**< CGRAM port: the colour index (sdevkit_cgram_* set it). */
#define SDEVKIT_PPU_CGDATA  0x22   /**< CGRAM port: the colour, low byte then high byte; then the index steps. */
#define SDEVKIT_PPU_W12SEL  0x23   /**< Windows of BG1 (bits 0-3) and BG2 (bits 4-7), each SDEVKIT_WSEL(). */
#define SDEVKIT_PPU_W34SEL  0x24   /**< Windows of BG3 (bits 0-3) and BG4 (bits 4-7). */
#define SDEVKIT_PPU_WOBJSEL 0x25   /**< Windows of the sprites (bits 0-3) and the colour window (bits 4-7, where colour math applies). */
#define SDEVKIT_PPU_WH0     0x26   /**< Window 1's left edge, 0..255: the window covers x with left <= x <= right; empty if left > right. */
#define SDEVKIT_PPU_WH1     0x27   /**< Window 1's right edge. */
#define SDEVKIT_PPU_WH2     0x28   /**< Window 2's left edge. */
#define SDEVKIT_PPU_WH3     0x29   /**< Window 2's right edge. */
#define SDEVKIT_PPU_WBGLOG  0x2A   /**< How a layer's two windows combine when both are on: 2 bits a layer, BG1 bits 0-1 .. BG4 bits 6-7: SDEVKIT_WLOG_OR, _AND, _XOR, _XNOR. */
#define SDEVKIT_PPU_WOBJLOG 0x2B   /**< The same for the sprites (bits 0-1) and the colour window (bits 2-3). */
#define SDEVKIT_PPU_TM      0x2C   /**< The layers on the main screen (the picture): SDEVKIT_BG1_ENABLE | ... | SDEVKIT_OBJ_ENABLE. */
#define SDEVKIT_PPU_TS      0x2D   /**< The layers on the sub screen (only used by colour math with SDEVKIT_CM_SUBBGOBJ_ENABLE). */
#define SDEVKIT_PPU_TMW     0x2E   /**< The main screen's layers that their windows mask: where a layer's window area is, the layer is not drawn. */
#define SDEVKIT_PPU_TSW     0x2F   /**< The same for the sub screen. */
#define SDEVKIT_PPU_CGWSEL  0x30   /**< Colour math, where and with what: SDEVKIT_CM_* of CGWSEL; bit 0 direct colour (SDEVKIT_CM_DIRECT_COLOR). */
#define SDEVKIT_PPU_CGADSUB 0x31   /**< Colour math, how and on which layers: SDEVKIT_CM_* of CGADSUB. */
#define SDEVKIT_PPU_COLDATA 0x32   /**< The fixed colour of colour math: one write per channel, SDEVKIT_CM_APPLY_RED/_GREEN/_BLUE | intensity 0..31; one write may set several channels. */
#define SDEVKIT_PPU_SETINI  0x33   /**< Bit 6 mode 7's second layer (SDEVKIT_SETINI_EXTBG); interlace, high resolution and overscan, the other bits, are not drawn on Rachael: keep them 0. */
/** @} */

/**
 * @name INIDISP
 * @{
 */
#define SDEVKIT_FORCED_BLANK 0x80  /**< The screen off: black, and VRAM, CGRAM and OAM take writes at any time. Write it at any time; to switch the screen on, write a brightness 1..15 in game_vblank. */
/** @} */

/**
 * @name BGMODE
 * In mode 1 the layers are in front of each other in this order, front to back (P: the priority
 * bit of a map entry, SDEVKIT_BG_TIL_PRIO; a sprite's priority 0..3 is in its attributes):
 * sprites 3, BG1 P, BG2 P, sprites 2, BG1, BG2, sprites 1, BG3 P, sprites 0, BG3, the backdrop.
 * With SDEVKIT_BG3_MODE1_PRIORITY_HIGH, BG3's tiles with P go in front of everything (a status
 * line). In mode 0: sprites 3, BG1 P, BG2 P, sprites 2, BG1, BG2, sprites 1, BG3 P, BG4 P,
 * sprites 0, BG3, BG4. In modes 2 to 4: sprites 3, BG1 P, sprites 2, BG2 P, sprites 1, BG1,
 * sprites 0, BG2. In mode 7: sprites 3, 2, 1, BG1, sprites 0, the backdrop; with EXTBG
 * (SDEVKIT_SETINI_EXTBG): sprites 3, 2, BG2 P, sprites 1, BG1, BG2, sprites 0.
 *
 * The colours of a tile: in mode 0 a 2-bit tile of BGn takes CGRAM colours (n - 1) * 32 +
 * palette * 4 + c; elsewhere a 2-bit tile palette * 4 + c, a 4-bit one palette * 16 + c, and an
 * 8-bit one (BG1 of modes 3 and 4) colour c of all 256, the map entry's palette ignored, or with
 * direct colour (SDEVKIT_CM_DIRECT_COLOR) no CGRAM at all: c is BBGGGRRR, the palette's three bits
 * bgr below them (BBb00 GGGg0 RRRr0). An 8-bit tile is 64 bytes, 32 VRAM words: the bit planes
 * 0-1, 2-3, 4-5 and 6-7, eight words each (`superdevkit png tiles8`).
 * @{
 */
#define SDEVKIT_BG_MODE0                0          /**< Mode 0: BG1..BG4 with 4 colours each. */
#define SDEVKIT_BG_MODE1                1          /**< Mode 1: BG1, BG2 with 16 colours, BG3 with 4. */
#define SDEVKIT_BG_MODE2                2          /**< Mode 2: BG1, BG2 with 16 colours, offsets per tile from BG3. */
#define SDEVKIT_BG_MODE3                3          /**< Mode 3: BG1 with 256 colours, BG2 with 16. */
#define SDEVKIT_BG_MODE4                4          /**< Mode 4: BG1 with 256 colours, BG2 with 4, offsets per tile from BG3. */
#define SDEVKIT_BG_MODE7                7          /**< Mode 7: one layer (BG1), rotated and scaled. */
#define SDEVKIT_BG3_MODE1_PRIORITY_HIGH (1 << 3)   /**< Mode 1: BG3's tiles with the priority bit in front of everything. */
#define SDEVKIT_BG1_TILES_16 (1 << 4)              /**< BG1 with tiles of 16 x 16 pixels: map entry tile t draws tiles t, t + 1 (right), t + 16 (below), t + 17. */
#define SDEVKIT_BG2_TILES_16 (1 << 5)              /**< BG2 with tiles of 16 x 16 pixels. */
#define SDEVKIT_BG3_TILES_16 (1 << 6)              /**< BG3 with tiles of 16 x 16 pixels. */
#define SDEVKIT_BG4_TILES_16 (1 << 7)              /**< BG4 with tiles of 16 x 16 pixels. */
/** @} */

/**
 * @name Offset per tile
 * In modes 2 and 4, BG3 is no layer: its map holds a scroll for each column of tiles of BG1 and
 * BG2 instead. Column k of the screen (k = 1..32, counted from the first tile whose left edge the
 * layer's own scroll puts at or right of x = 0; column 0 keeps the layer's scroll) takes the
 * entry at tile column k - 1 + BG3HOFS / 8 of BG3's map, in the row BG3VOFS / 8. In mode 2 the row
 * below holds a second entry for the vertical scroll. An entry applies to BG1 with
 * SDEVKIT_OPT_BG1, to BG2 with SDEVKIT_OPT_BG2; its bits 0-9 are the scroll (a horizontal one
 * keeps the layer's own lowest three bits). In mode 4 there is one entry, and
 * SDEVKIT_OPT_VERTICAL makes it the vertical scroll instead of the horizontal one.
 * @code
 * // mode 2: BG1's column k + 1 scrolled down by k pixels, a ramp across the screen
 * for (k = 0; k < 32; k++) {
 *     opt[32 + k] = (uint16_t)(SDEVKIT_OPT_BG1 | k);   // BG3's second row: the vertical offsets
 * }
 * @endcode
 * @{
 */
#define SDEVKIT_OPT_BG1      (1u << 13)            /**< The entry applies to BG1. */
#define SDEVKIT_OPT_BG2      (1u << 14)            /**< The entry applies to BG2. */
#define SDEVKIT_OPT_VERTICAL (1u << 15)            /**< Mode 4: the entry is the vertical scroll. */
/** @} */

/**
 * @name MOSAIC
 * Each layer with mosaic on shows blocks of size x size pixels, each the colour of its top left
 * pixel, counted from the picture's top left corner: a fade into blocks, a picture that dissolves.
 * @{
 */
/** MOSAIC: blocks of @p size pixels (1..16; 1 is off) on the layers @p layers (SDEVKIT_BG1_ENABLE ...). */
#define SDEVKIT_MOSAIC(size, layers) ((uint8_t)((((size) - 1) << 4) | ((layers) & 0x0F)))
/** @} */

/**
 * @name SETINI
 * @{
 */
#define SDEVKIT_SETINI_EXTBG (1 << 6)   /**< Mode 7's second layer: BG2 shows BG1's picture with bit 7 of a pixel as its priority and bits 0-6 as its colour, so mode 7 can be in front of and behind sprites. */
/** @} */

/**
 * @name VMAIN: address remapping
 * Or'ed into VMAIN: the port rotates the low bits of the address, so that a game that draws into
 * tiles as into a bitmap steps along a row of pixels: with SDEVKIT_VMAIN_REMAP_4BPP, address bits
 * aaaaaaaYYYxxxxxx reach the word aaaaaaaxxxxxxYYY. The step is still VMAIN's bits 0-1.
 * @{
 */
#define SDEVKIT_VMAIN_REMAP_2BPP (1 << 2)   /**< The low 8 bits rotated by 3: 2-bit tiles 32 to a row. */
#define SDEVKIT_VMAIN_REMAP_4BPP (2 << 2)   /**< The low 9 bits: 4-bit tiles 32 to a row. */
#define SDEVKIT_VMAIN_REMAP_8BPP (3 << 2)   /**< The low 10 bits: 8-bit tiles 32 to a row. */
/** @} */

/**
 * @name TM, TS, TMW, TSW: the layers
 * Or'ed: which layers a screen shows (TM, TS) or masks by their windows (TMW, TSW).
 * @{
 */
#define SDEVKIT_BG1_ENABLE (1 << 0)   /**< BG1. */
#define SDEVKIT_BG2_ENABLE (1 << 1)   /**< BG2. */
#define SDEVKIT_BG3_ENABLE (1 << 2)   /**< BG3. */
#define SDEVKIT_BG4_ENABLE (1 << 3)   /**< BG4. */
#define SDEVKIT_OBJ_ENABLE (1 << 4)   /**< The sprites. */
/** @} */

/**
 * @name BGnSC: a layer's map
 * A map is 32 x 32 entries of one word each (2 KB, 0x400 words), row by row; with 8 x 8 tiles a
 * layer of 256 x 256 pixels, which scrolling wraps around. A larger layer is two or four such
 * maps one after the other in VRAM: 64 x 32 is the left map, then the right one; 32 x 64 the top,
 * then the bottom; 64 x 64 top left, top right, bottom left, bottom right. Entry (column c, row r)
 * of a 32 x 32 map is the word at map + r * 32 + c.
 * @{
 */
#define SDEVKIT_SC_32x32 0   /**< One map: 32 x 32 entries. */
#define SDEVKIT_SC_64x32 1   /**< Two maps side by side: 64 x 32 entries. */
#define SDEVKIT_SC_32x64 2   /**< Two maps one below the other: 32 x 64 entries. */
#define SDEVKIT_SC_64x64 3   /**< Four maps: 64 x 64 entries. */
/** BGnSC: the map at a VRAM word address (a multiple of 0x400) and its size, SDEVKIT_SC_*. */
#define SDEVKIT_BGSC(address, size) ((uint8_t)((((address) >> 8) & 0xFC) | (size)))
/** @} */

/**
 * @name BG12NBA, BG34NBA: a layer's tiles
 * A layer's tiles start at a VRAM word address, a multiple of 0x1000; tile n of a 16-colour layer
 * (4 bits a pixel, 32 bytes = 16 words a tile) is at address + 16n, of a 4-colour layer (2 bits a
 * pixel, 16 bytes = 8 words) at address + 8n. A tile number has 10 bits (0..1023).
 *
 * A tile is 8 rows of 8 pixels in bit planes; bit 7 of a byte is the row's leftmost pixel. A
 * 2-bit tile: for row y, byte 2y holds bit 0 of each pixel and byte 2y + 1 bit 1. A 4-bit tile:
 * those 16 bytes for bits 0 and 1, then 16 more in the same layout for bits 2 and 3. A pixel's
 * bits are its colour in the palette its map entry names; colour 0 is transparent. The kit's
 * `superdevkit png` converts a PNG into these bytes.
 * @{
 */
/** BG12NBA (BG34NBA): the tiles of two layers, each at a VRAM word address, a multiple of 0x1000. */
#define SDEVKIT_BGNBA(first, second) ((uint8_t)((((first) >> 12) & 0x0F) | ((((second) >> 12) & 0x0F) << 4)))
/** @} */

/**
 * @name OBSEL: the sprites' tiles and sizes
 * Every sprite is one of two sizes, chosen per sprite by its large bit (the high table). The
 * sprites' tiles are 4-bit tiles (16 words each) from a VRAM word address, a multiple of 0x2000:
 * tiles 0..255 there, tiles 256..511 the 0x1000 words after them. They are laid out as a sheet 16
 * tiles wide: a sprite larger than 8 x 8 at tile t takes t, t + 1, ... to the right and t + 16,
 * t + 32, ... below (a 16 x 16 sprite: t, t + 1, t + 16, t + 17), wrapping inside its 16 x 16
 * block of 256 tiles.
 * @{
 */
#define SDEVKIT_OBJ_SIZE8_L16  (0 << 5)   /**< Small 8 x 8, large 16 x 16. */
#define SDEVKIT_OBJ_SIZE8_L32  (1 << 5)   /**< Small 8 x 8, large 32 x 32. */
#define SDEVKIT_OBJ_SIZE8_L64  (2 << 5)   /**< Small 8 x 8, large 64 x 64. */
#define SDEVKIT_OBJ_SIZE16_L32 (3 << 5)   /**< Small 16 x 16, large 32 x 32. */
#define SDEVKIT_OBJ_SIZE16_L64 (4 << 5)   /**< Small 16 x 16, large 64 x 64. */
#define SDEVKIT_OBJ_SIZE32_L64 (5 << 5)   /**< Small 32 x 32, large 64 x 64. */
/** OBSEL: the sprites' tiles at a VRAM word address (a multiple of 0x2000) and their sizes, SDEVKIT_OBJ_SIZE*. */
#define SDEVKIT_OBSEL(address, sizes) ((uint8_t)((sizes) | (((address) >> 13) & 7)))
/** @} */

/**
 * @name Map entries
 * One 16-bit word of a map: the tile and how it is drawn. Or'ed:
 * `SDEVKIT_BG_TIL_NUM(t) | SDEVKIT_BG_TIL_PAL(p) | SDEVKIT_BG_TIL_FLIPX`.
 * @{
 */
#define SDEVKIT_BG_TIL_NUM(n)  ((uint16_t)(n))          /**< The tile, 0..1023 (bits 0-9). */
#define SDEVKIT_BG_TIL_PAL(n)  ((uint16_t)((n) << 10))  /**< The palette, 0..7 (bits 10-12). */
#define SDEVKIT_BG_TIL_PRIO    ((uint16_t)(1 << 13))    /**< The priority bit (see BGMODE's order). */
#define SDEVKIT_BG_TIL_FLIPX   ((uint16_t)(1 << 14))    /**< Mirrored left to right. */
#define SDEVKIT_BG_TIL_FLIPY   ((uint16_t)(1 << 15))    /**< Mirrored top to bottom. */
/** @} */

/**
 * @name Windows: W12SEL, W34SEL, WOBJSEL, WBGLOG, WOBJLOG
 * Two windows, each a span of columns WH0..WH1 and WH2..WH3 on every line (HDMA can change them
 * per line for a shape). Each layer, and the colour window, takes 4 bits that say which windows
 * make its area; TMW and TSW then hide a layer inside its area, and CGWSEL limits colour math to
 * the colour window's area or its outside.
 * @{
 */
#define SDEVKIT_WIN1_ON     (1 << 1)   /**< Window 1 counts for this layer. */
#define SDEVKIT_WIN1_INVERT (1 << 0)   /**< ... as the columns outside it. */
#define SDEVKIT_WIN2_ON     (1 << 3)   /**< Window 2 counts for this layer. */
#define SDEVKIT_WIN2_INVERT (1 << 2)   /**< ... as the columns outside it. */
/** The 4 bits of a layer, or'ed SDEVKIT_WIN*, for W12SEL, W34SEL, WOBJSEL: the first layer of the register, or the second shifted by 4. */
#define SDEVKIT_WSEL(first, second) ((uint8_t)((first) | ((second) << 4)))
#define SDEVKIT_WLOG_OR   0   /**< With both windows on: the layer's area is in either. */
#define SDEVKIT_WLOG_AND  1   /**< ... in both. */
#define SDEVKIT_WLOG_XOR  2   /**< ... in exactly one. */
#define SDEVKIT_WLOG_XNOR 3   /**< ... in both or in neither. */
/** @} */

/**
 * @name Colour math: CGWSEL
 * Colour math adds the fixed colour (COLDATA) or the sub screen (TS) to the main screen's pixel,
 * or subtracts it, per channel, clamped to 0..31: for shadows, fades, light, transparency. CGWSEL
 * says where (in relation to the colour window) and with what; CGADSUB how and on which layers.
 * The names are PVSnesLib's and read against their meaning in places: "MATWIN" means "inside the
 * colour window", "SCBLK" "the main screen black".
 * @{
 */
#define SDEVKIT_CM_DIRECT_COLOR     (1 << 0)       /**< The 256-colour tiles of BG1 (modes 3, 4 and 7) are direct colours, not CGRAM indices ("BGMODE" above). */
#define SDEVKIT_CM_SUBBGOBJ_ENABLE  (1 << 1)       /**< Add or subtract the sub screen (TS), not the fixed colour; where the sub screen is transparent, the fixed colour. */
#define SDEVKIT_CM_ALWAYS_ENABLE    (0 << 4)       /**< Colour math everywhere. */
#define SDEVKIT_CM_MATWIN_ENABLE    (1 << 4)       /**< Colour math only inside the colour window. */
#define SDEVKIT_CM_NOMATWIN_ENABLE  (2 << 4)       /**< Colour math only outside the colour window. */
#define SDEVKIT_CM_NEVER_ENABLE     (3 << 4)       /**< No colour math. */
#define SDEVKIT_CM_SCBLK_ALWAYS_ENABLE   (0 << 6)  /**< The main screen black: never. */
#define SDEVKIT_CM_SCBLK_MATWIN_ENABLE   (1 << 6)  /**< The main screen black outside the colour window. */
#define SDEVKIT_CM_SCBLK_NOMATWIN_ENABLE (2 << 6)  /**< The main screen black inside the colour window. */
#define SDEVKIT_CM_SCBLK_NEVER_ENABLE    (3 << 6)  /**< The main screen black everywhere (black before colour math: the fixed colour or the sub screen alone). */
/** @} */

/**
 * @name Colour math: CGADSUB and COLDATA
 * @{
 */
#define SDEVKIT_CM_SUB_MAINSUB (1 << 7)            /**< Subtract instead of add. */
#define SDEVKIT_CM_DIV2_DIVIDE (1 << 6)            /**< Halve the result (not where the main screen is made black). */
#define SDEVKIT_CM_MSCR_BACK   (1 << 5)            /**< Colour math on the backdrop ... */
#define SDEVKIT_CM_MSCR_PAL47  (1 << 4)            /**< ... on sprites with palettes 4..7 (never 0..3) ... */
#define SDEVKIT_CM_MSCR_BG4    (1 << 3)            /**< ... on BG4 ... */
#define SDEVKIT_CM_MSCR_BG3    (1 << 2)            /**< ... on BG3 ... */
#define SDEVKIT_CM_MSCR_BG2    (1 << 1)            /**< ... on BG2 ... */
#define SDEVKIT_CM_MSCR_BG1    (1 << 0)            /**< ... on BG1. */
#define SDEVKIT_CM_APPLY_BLUE  (1 << 7)            /**< COLDATA: this write sets the fixed colour's blue ... */
#define SDEVKIT_CM_APPLY_GREEN (1 << 6)            /**< ... its green ... */
#define SDEVKIT_CM_APPLY_RED   (1 << 5)            /**< ... its red, to the intensity in bits 0-4. */
/** @} */

/**
 * @name Mode 7
 * With SDEVKIT_BG_MODE7 the screen has one background, BG1: a map of 128 x 128 tiles (1024 x
 * 1024 pixels, one byte a tile, row by row) and 256 tiles of 8 x 8 pixels with a byte a pixel
 * (an index into all 256 colours of CGRAM, 0 transparent; row by row, no bit planes). Both fill
 * the first 32 KB of VRAM, interleaved: the map in the low byte of words 0..0x3FFF, the tiles in
 * the high byte (sdevkit_mode7_map_copy, sdevkit_mode7_tiles_copy); nothing else of a game may be
 * there, so its sprite tiles go to 0x4000 or above. A screen pixel (x, y) shows the map pixel
 *
 *     (a b; c d) * (x + hscroll - cx, y + vscroll - cy) + (cx, cy)
 *
 * with a, b, c, d (M7A..M7D) in 8.8 fixed point (0x0100 is 1.0), the centre (cx, cy) = (M7X,
 * M7Y) and the scroll (BG1HOFS, BG1VOFS) in pixels, 13 bits with sign. Turned by an angle t and
 * zoomed by z (2.0: half the size): a = d = cos(t) * z, b = sin(t) * z, c = -b, each * 256. To
 * put map pixel (mx, my) at the middle of the screen: centre (mx, my), scroll (mx - 128,
 * my - 112).
 * @code
 * // in game_vblank, from a matrix the tick computed
 * sdevkit_reg_write_twice(SDEVKIT_PPU_M7A, (uint16_t)m7a);
 * sdevkit_reg_write_twice(SDEVKIT_PPU_M7B, (uint16_t)m7b);
 * sdevkit_reg_write_twice(SDEVKIT_PPU_M7C, (uint16_t)-m7b);
 * sdevkit_reg_write_twice(SDEVKIT_PPU_M7D, (uint16_t)m7a);
 * @endcode
 * @{
 */
#define SDEVKIT_M7_WRAP        0x00   /**< M7SEL: outside the map, it repeats. */
#define SDEVKIT_M7_TRANSPARENT 0x80   /**< ... outside the map, nothing (the backdrop). */
#define SDEVKIT_M7_TILE0       0xC0   /**< ... outside the map, tile 0 everywhere. */
#define SDEVKIT_M7_FLIPX       0x01   /**< The whole screen mirrored left to right. */
#define SDEVKIT_M7_FLIPY       0x02   /**< The whole screen mirrored top to bottom. */
/** @} */

/* --- Copies into VRAM, CGRAM and OAM ------------------------------------------------------- */
/**
 * @name Copies
 * Each copy is one DMA on channel 0, at once: in game_vblank or with the screen off. DMA moves a
 * byte every 8 master cycles while the CPU waits; a vertical blank has room for about 6,300 bytes
 * when the game does nothing else in it (the setup of a copy costs about 100 cycles more). A copy
 * of 0 bytes copies 64 KB: never pass 0 by accident.
 * @{
 */
/** @} */

/**
 * @brief The general DMA: so many bytes from memory to a register of the PPU.
 *
 * The game sets the port's address first (sdevkit_reg_write of VMAIN, VMADDL/H, CGADD,
 * OAMADDL/H); the functions below do that for the usual copies.
 * @param dmap  the transfer mode: 0x00 every byte to @p bbad; 0x01 bytes to @p bbad and
 *              @p bbad + 1 in turn (VRAM's two ports); 0x02 every byte to @p bbad, for a register
 *              written twice; or'ed with 0x08 the source does not step (a fill). Or'ed with
 *              SDEVKIT_DMA_FROM_PPU the bytes go the other way, from the PPU into @p src:
 *              VRAM, CGRAM or OAM read back through their ports (`SDEVKIT_PPU_VMDATALREAD`,
 *              `_CGDATAREAD`, `_OAMDATAREAD`), in the vertical blank as a copy to them.
 * @param bbad  the register, the low byte of $21xx (`SDEVKIT_PPU_VMDATAL`, `_CGDATA`, `_OAMDATA`)
 * @param src   the bytes, in any bank
 * @param bytes how many; 0 is 64 KB
 */
void sdevkit_dma(uint8_t dmap, uint8_t bbad, const void *src, uint16_t bytes);

/**
 * @name Reading the PPU back
 * With SDEVKIT_DMA_FROM_PPU, sdevkit_dma copies from a port of the PPU into memory. VRAM's port
 * reads a word the PPU fetched before: setting the address (VMADDL/H) fetches the word there, and
 * each read that steps the address fetches the word at the address before the step. So a copy of
 * words read from address a gives the word at a twice, then a + 1, a + 2 ...: read one word more
 * and drop the first. CGRAM's port gives the low byte, then the high byte of each colour; its bit
 * 7 is not defined on the SNES. OAM's port gives byte by byte.
 * @code
 * // the 16 words of VRAM from 0x1000 into RAM, in the vertical blank
 * static uint16_t back[17];
 * sdevkit_reg_write(SDEVKIT_PPU_VMAIN, 0x80);
 * sdevkit_reg_write(SDEVKIT_PPU_VMADDL, 0x00);
 * sdevkit_reg_write(SDEVKIT_PPU_VMADDH, 0x10);
 * sdevkit_dma(SDEVKIT_DMA_FROM_PPU | 0x01, SDEVKIT_PPU_VMDATALREAD, back, sizeof back);
 * // back[1..16] are the words from 0x1000
 * @endcode
 * @{
 */
#define SDEVKIT_DMA_FROM_PPU      0x80   /**< DMAP bit 7: from the PPU to memory. */
#define SDEVKIT_PPU_OAMDATAREAD   0x38   /**< OAM's read port, a byte at a time. */
#define SDEVKIT_PPU_VMDATALREAD   0x39   /**< VRAM's read port, the low byte. */
#define SDEVKIT_PPU_VMDATAHREAD   0x3A   /**< VRAM's read port, the high byte. */
#define SDEVKIT_PPU_CGDATAREAD    0x3B   /**< CGRAM's read port: low byte, high byte. */
/** @} */

/**
 * @brief Words to VRAM from word address @p a, one after the other: tiles, a map.
 * @param a     the VRAM word address, 0x0000..0x7FFF
 * @param src   the words, low byte first (as C keeps a uint16_t)
 * @param bytes how many bytes, even; 0 is 64 KB
 */
void sdevkit_vram_copy(uint16_t a, const void *src, uint16_t bytes);

/**
 * @brief Words down a column of a map 32 entries wide: the address steps by 32 words.
 *
 * For a game that scrolls sideways and brings in one column of a map per step.
 * @param a     the column's first entry, a VRAM word address
 * @param src   the entries from top to bottom
 * @param bytes 2 per entry, at most 64 for a 32 x 32 map
 */
void sdevkit_vram_column_copy(uint16_t a, const void *src, uint16_t bytes);

/**
 * @brief VRAM from word address @p a filled with one byte, in both bytes of each word.
 * @param a     the first word
 * @param value the byte (0 clears)
 * @param bytes how many bytes; 0 fills 64 KB, all of VRAM
 */
void sdevkit_vram_fill(uint16_t a, uint8_t value, uint16_t bytes);

/**
 * @brief Colours to CGRAM from colour @p i.
 * @param i     the first colour, 0..255
 * @param src   BGR 5:5:5 colours (SDEVKIT_RGB5), two bytes each
 * @param bytes 2 per colour: a palette of 16 colours is 32 bytes
 */
void sdevkit_cgram_copy(uint8_t i, const void *src, uint16_t bytes);

/**
 * @brief One colour, written by the CPU: cheaper than a DMA for one or two.
 * @param i   the colour index, 0..255
 * @param bgr the colour, SDEVKIT_RGB5()
 */
void sdevkit_cgram_set(uint8_t i, uint16_t bgr);

/**
 * @brief Bytes of a sprite table into OAM from byte @p o.
 *
 * A game that uses the first n sprites copies 4 * n bytes from 0 and the (n + 3) / 4 bytes of
 * their high table from 512, not all 544 ("Sprites" below).
 * @param o     the first byte, even, 0..542 (OAM's address counts words)
 * @param src   the bytes, in the table's format
 * @param bytes how many
 */
void sdevkit_oam_copy(uint16_t o, const void *src, uint16_t bytes);

/**
 * @brief Mode 7's map: bytes into the low byte of VRAM's words from entry @p e.
 * @param e     the entry, row * 128 + column (0..16383)
 * @param src   one byte a map entry: its tile, 0..255
 * @param bytes how many entries
 */
void sdevkit_mode7_map_copy(uint16_t e, const void *src, uint16_t bytes);

/**
 * @brief Mode 7's tiles: bytes into the high byte of VRAM's words from byte @p b.
 * @param b     the first pixel, tile * 64 + row * 8 + column
 * @param src   one byte a pixel: its colour, 0..255
 * @param bytes how many pixels; 64 a tile
 */
void sdevkit_mode7_tiles_copy(uint16_t b, const void *src, uint16_t bytes);

/**
 * @brief Whether a copy of so many bytes still fits into this vertical blank.
 *
 * On the SNES from the line the picture is at, on the desktop from the bytes copied so far. The
 * two can answer differently for the same game, so a game whose state depends on the answer gets
 * other checksums on each; sdevkit/queue.h decides the same on both.
 * @param bytes the copy's size
 * @return 1 if it fits, 0 if not (or if the picture is being drawn)
 */
uint8_t sdevkit_dma_fits(uint16_t bytes);

/* --- Sprites ---------------------------------------------------------------------------------- */
/**
 * @name Sprites
 * A sprite table in the hardware's format, wherever the game keeps it (544 bytes), which
 * sdevkit_oam_copy brings into OAM:
 *
 * - **The low table**, bytes 0..511: 128 entries of 4 bytes. Entry n at 4n: byte 0 x (its low 8
 *   bits), byte 1 y, byte 2 the tile's low 8 bits, byte 3 the attributes: bit 0 the tile's bit 8,
 *   bits 1-3 the palette (0..7, CGRAM 128 + 16 * palette), bits 4-5 the priority (0..3, see
 *   BGMODE's order), bit 6 mirrored left to right, bit 7 top to bottom (SDEVKIT_OAM_ATTR()).
 * - **The high table**, bytes 512..543: 2 bits a sprite. Sprite n's are in byte 512 + n / 4,
 *   from bit 2 * (n % 4): the lower one is x's bit 8 (x is 9 bits: -256..255, so a sprite can go
 *   off the left edge), the upper one picks the large size of OBSEL.
 *
 * The SNES draws a sprite one line lower than its y: for its top on screen line y, write y - 1.
 * A sprite is hidden below the picture with y = SDEVKIT_OAM_HIDE_Y (and its high bits 0: a large
 * one would reach into the top). A lower entry is in front of a higher one, whatever their
 * priorities. A line shows at most 32 sprites and 34 of their 8-pixel-wide tile columns; beyond,
 * the SNES leaves sprites out (the highest entries first for the 32, the lowest for the 34) and
 * the desktop counts the lines ("sprites").
 * @code
 * static uint8_t oam[SDEVKIT_OAM_BYTES];
 * // sprite 0: a 16 x 16 sprite (large, with SDEVKIT_OBJ_SIZE8_L16) of tile 2 at (100, 50)
 * oam[0] = 100;
 * oam[1] = 50 - 1;
 * oam[2] = 2;
 * oam[3] = SDEVKIT_OAM_ATTR(0, 2, 0, 0);
 * oam[512] = (uint8_t)((oam[512] & ~0x03) | 0x02);    // sprite 0's bits: large, x < 256
 * // in game_vblank: the one entry and its byte of the high table
 * sdevkit_oam_copy(0, oam, 4);
 * sdevkit_oam_copy(512, oam + 512, 1);
 * @endcode
 * @{
 */
#define SDEVKIT_OAM_BYTES  (128 * 4 + 8 * 4)   /**< The bytes of a sprite table: 544. */
#define SDEVKIT_OAM_HIDE_Y 0xE8                /**< The y of a hidden sprite: below the picture. */
/** Byte 3 of an entry: palette 0..7, priority 0..3, the flips (0 or not); or it with the tile's bit 8. */
#define SDEVKIT_OAM_ATTR(palette, priority, hflip, vflip) \
    ((uint8_t)(((vflip) ? 0x80 : 0) | ((hflip) ? 0x40 : 0) | (((priority) & 3) << 4) | (((palette) & 7) << 1)))
/** @} */

/* --- HDMA ------------------------------------------------------------------------------------ */
/**
 * @name HDMA
 * An HDMA channel writes data from a table into a PPU register at the start of every line, from
 * the next frame on, while its bit is set by sdevkit_hdma_enable: a scroll per line (a wave, a
 * floor in perspective), windows per line (a circle), a colour per line (a gradient), a mode per
 * band (a status line above mode 7). Channel 0 is the copies'; a game has 1 to 7.
 *
 * The table: entries of a line count byte and data, ended by a byte 0. A count of 1..128 writes
 * its data once and holds it for that many lines; 129..255 writes new data on each of
 * (count - 128) lines, so the entry has that many data. Data is 1 byte (SDEVKIT_HDMA_ONE) or 2
 * (SDEVKIT_HDMA_TWO_REGS, SDEVKIT_HDMA_TWICE) per write. The first entry applies to the first
 * line of the picture. The table is read while the frame is drawn, so it must stay valid: a
 * static or ROM array; two tables and switching between them in game_vblank change it without
 * tearing. Rachael draws the direct modes below; another mode, or indirect HDMA (mode bit 6), is
 * counted as a write she does not draw.
 * @code
 * // BG1 scrolled 0 for the top 100 lines, then 50 for the rest of the picture
 * static const uint8_t WAVE[] = { 100, 0, 0, 1, 50, 0, 0 };
 * sdevkit_hdma_set(1, SDEVKIT_PPU_BG1HOFS, SDEVKIT_HDMA_TWICE, WAVE);   // in game_vblank
 * sdevkit_hdma_enable(SDEVKIT_HDMA_CHANNEL1);
 * @endcode
 * @{
 */
#define SDEVKIT_HDMA_ONE      0    /**< One byte to the register. */
#define SDEVKIT_HDMA_TWO_REGS 1    /**< Two bytes, to the register and the next (WH0 and WH1: window 1 per line). */
#define SDEVKIT_HDMA_TWICE    2    /**< Two bytes to the register (a scroll register: a wave down the screen). */
#define SDEVKIT_HDMA_CHANNEL1 (1 << 1)   /**< Channel 1's bit for sdevkit_hdma_enable. */
#define SDEVKIT_HDMA_CHANNEL2 (1 << 2)   /**< Channel 2's bit. */
#define SDEVKIT_HDMA_CHANNEL3 (1 << 3)   /**< Channel 3's bit. */
#define SDEVKIT_HDMA_CHANNEL4 (1 << 4)   /**< Channel 4's bit. */
#define SDEVKIT_HDMA_CHANNEL5 (1 << 5)   /**< Channel 5's bit. */
#define SDEVKIT_HDMA_CHANNEL6 (1 << 6)   /**< Channel 6's bit. */
#define SDEVKIT_HDMA_CHANNEL7 (1 << 7)   /**< Channel 7's bit. */
/** @} */

/**
 * @brief Sets up an HDMA channel; it runs once sdevkit_hdma_enable switches it on.
 * @param channel 1..7
 * @param reg     the register it writes, an `SDEVKIT_PPU_*` name
 * @param mode    SDEVKIT_HDMA_ONE, _TWO_REGS or _TWICE
 * @param table   the table, which must stay valid while it runs
 */
void sdevkit_hdma_set(uint8_t channel, uint8_t reg, uint8_t mode, const void *table);

/**
 * @brief HDMAEN: the channels that run from the next frame on.
 * @param channels SDEVKIT_HDMA_CHANNELn or'ed; 0 switches all off
 */
void sdevkit_hdma_enable(uint8_t channels);

/* --- Sound effects (sdevkit_sfx_*, docs/sound.md) ----------------------------------------- */
/**
 * @name Sound effects
 * The SNES plays sound on a CPU of its own (the SPC700) with a DSP of 8 voices, from samples in
 * its own 64 KB of audio RAM. A game's effects come from its `assets/sfx/sfx.json`: `superdevkit
 * sfx` converts it into a header with a name per effect (SFX_JUMP, ...) and a bank, NAME_bank,
 * which these functions play. The fields are the build's; a game only passes the bank on.
 * @{
 */
#define SDEVKIT_SFX_OFF 0xFF              /**< The sample of an effect without an active one. */
/** @} */

/** A bank of sound effects, as `superdevkit sfx` writes it. */
typedef struct {
    uint8_t                effects;       /**< How many, SFX_COUNT. */
    uint8_t                voices;        /**< How many of the DSP's 8 voices the effects use, 1..8. */
    uint8_t                samples;       /**< The active samples, one entry each in the sample directory. */
    const uint8_t         *sample;        /**< Per effect: its sample's number, or SDEVKIT_SFX_OFF. */
    const uint8_t         *priority;      /**< Per effect: the higher wins a voice. */
    const uint8_t         *volume;        /**< Per effect: the voice's volume register, 0..127. */
    const uint16_t        *pitch;         /**< Per effect: the pitch register, rate * 4096 / 32000. */
    const uint16_t        *frames;        /**< Per effect: how long it plays, in frames, plus one. */
    const uint8_t *const  *brr;           /**< Per sample: its BRR blocks. */
    const uint16_t        *brr_size;      /**< Per sample: their bytes. */
} sdevkit_sfx_bank;

/**
 * @brief Loads the bank's samples into the audio RAM and starts the sound: once, in game_init.
 *
 * On the SNES it waits for the sound CPU's boot code and sends every byte through it, about 350
 * bytes a frame, and the CPU does nothing else meanwhile; a game that switches the screen on
 * first (INIDISP) shows its picture during the upload instead of a black screen.
 * @param bank the build's bank; a null bank does nothing
 */
void sdevkit_sfx_init(const sdevkit_sfx_bank *bank);

/**
 * @brief Plays an effect.
 *
 * It starts after this tick's game_vblank, when a voice is free or the voice of an effect with a
 * lower priority (or the older one of the same) gives way; otherwise it is dropped. An effect
 * without an active sample, or before sdevkit_sfx_init, does nothing.
 * @param id the effect, a name of the build's header (SFX_JUMP)
 */
void sdevkit_sfx_play(uint8_t id);

/* --- Music (sdevkit_music_*, ADR-6F3CK03Z) ---------------------------------------------- */
/**
 * @brief Plays a track, over and over, until another one or sdevkit_music_stop.
 *
 * A game's music is listed in its superdevkit.toml (`[sound] music = ["assets/music/title.ogg",
 * ...]`); the build writes music.h with a number per track (MUSIC_TITLE, ..., MUSIC_COUNT), never
 * a file name. On the desktop and the web the track plays from its file (OGG, MP3, WAV, QOA, XM,
 * MOD); the same track again goes on playing. On the SNES music is not built yet: the calls do
 * nothing (and dampf removes them), so a game can call them already. The checks play no music.
 * @param track the track's number, MUSIC_*
 */
void sdevkit_music_play(uint8_t track);

/** @brief Stops the music (nothing on the SNES yet). */
void sdevkit_music_stop(void);

/* --- Save RAM (sdevkit_sram, ADR-6F3CK03Z) ---------------------------------------------- */
/**
 * @name Save RAM
 * Battery-backed RAM on the cartridge, kept while the console is off: high scores, settings, a
 * saved game. A game asks for it in its superdevkit.toml (`sram = BYTES`, up to 32 KB; dampf
 * --sram, which defines SDEVKIT_SRAM_SIZE; the desktop build gets the same define); without, the
 * size is 0 and sdevkit_sram() returns 0. The game reads and writes the block like memory,
 * whenever it likes; the kit keeps it: the cartridge's battery on the SNES (at $70:0000, the
 * header announces it), a file next to the program on the desktop (`GAME.srm`, written when it
 * changed and on exit). The first time it holds zeros on the desktop and in the checks, on a
 * cartridge whatever the RAM held: a game marks its data as valid (a magic number, a checksum)
 * and clears the block when the mark is missing. A recording starts with the save RAM of its
 * start, so that a replay plays the same game.
 * @code
 * uint8_t *s = sdevkit_sram();
 * if (s[0] != 'H' || s[1] != 'S') {      // not ours yet: start fresh
 *     s[0] = 'H'; s[1] = 'S'; s[2] = 0; s[3] = 0;
 * }
 * best = (uint16_t)(s[2] | s[3] << 8);
 * @endcode
 * @{
 */
#ifndef SDEVKIT_SRAM_SIZE
#define SDEVKIT_SRAM_SIZE 0   /**< The save RAM's bytes, from the build (`sram` in superdevkit.toml); 0 without. */
#endif
/** @} */

/** @brief The save RAM. @return SDEVKIT_SRAM_SIZE bytes; 0 (a null pointer) without save RAM */
uint8_t *sdevkit_sram(void);

/* --- Debug (sdevkit_dbg_*) --------------------------------------------------------------- */
/**
 * @brief VRAM as 0x8000 words, for a debug check of the maps on the desktop.
 *
 * Only Rachael can read VRAM like memory; on the SNES it returns a single zero word, so a check
 * that uses it runs only on the desktop.
 * @return VRAM's words on the desktop; one zero word on the SNES
 */
const uint16_t *sdevkit_dbg_vram(void);

/**
 * @brief Names memory that makes up the game's state, for the checks.
 *
 * The checks play a game on the SNES and on the PC with the same input and compare a checksum
 * after every tick (dev/compare_example.sh, ExamplesTest): the bytes named between the end of one
 * game_tick and the end of the next (game_vblank included) are hashed (FNV-1a, 32 bits) in the
 * order named. The kit names nothing itself: a game names what its next picture depends on (its
 * sprite table, its positions). Name data whose bytes are the same on both: arrays of uint8_t and
 * uint16_t (both are little endian), structs only when they have no padding on the PC, nothing
 * with pointers. On the SNES it costs a few port writes ($21FC, which no hardware uses); the
 * checker reads the memory itself. On Rachael it hashes.
 * @param data  the memory
 * @param bytes how many bytes
 */
void sdevkit_dbg_hash(const void *data, uint16_t bytes);

/**
 * @name The debug view
 * On the desktop, F1 opens the debug view beside the picture (ADR-6F3CK03Z). A game registers
 * once, in game_init, what it wants to change while it runs, and the view lists it; per tick it
 * can draw boxes over the picture (collision boxes, a target). On the SNES the functions are
 * empty and dampf removes the calls, so the game needs no #ifdef. A name is a string constant
 * (the view keeps the pointer); at most 32 registrations. A change made in the view is not part
 * of a recording: from then on a replay plays another game (the window says so). The desktop's
 * `--set name=value` sets a registration before the first tick, and a recording keeps that. So a
 * bot needs no support of its own: a toggle ("autoplay") that game_tick reads to make its own pad
 * instead of pad[0], switched on with `--set autoplay=1`.
 * @{
 */
/** @} */

/** @brief A switch in the debug view. @param name its label @param flag 0 or 1, set by the view */
void sdevkit_dbg_toggle(const char *name, uint8_t *flag);
/** @brief A number in the debug view. @param name its label @param value set by the view @param lo the smallest @param hi the largest */
void sdevkit_dbg_range(const char *name, int16_t *value, int16_t lo, int16_t hi);
/** @brief A colour in the debug view. @param name its label @param colour BGR 5:5:5, set by the view */
void sdevkit_dbg_color(const char *name, uint16_t *colour);
/**
 * @brief A button in the debug view: the view sets *pressed to 1, and the game does what it means
 *        in its next game_tick and sets it back to 0.
 * @param name    its label
 * @param pressed set to 1 by the view
 */
void sdevkit_dbg_action(const char *name, uint8_t *pressed);
/**
 * @brief A line of text the view shows, read again every frame (a string ending in a zero byte,
 *        in memory that stays). Nothing changes the game, so a recording stays valid.
 * @param name its label
 * @param text the text the game keeps up to date
 */
void sdevkit_dbg_text(const char *name, const char *text);
/** @brief A value the view shows, in decimal and hex, read again every frame. @param name its label @param value the value */
void sdevkit_dbg_value(const char *name, const uint16_t *value);
/**
 * @brief A box drawn over the picture in the debug view, for this tick only (the list is cleared
 *        before each game_tick). At most 256 boxes, fills and arrows a tick.
 * @param x    screen position
 * @param y    screen position
 * @param w    width in pixels
 * @param h    height in pixels
 * @param kind its colour, 0..7
 */
void sdevkit_dbg_box(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind);
/** @brief A filled box the picture shows through (a danger zone); as sdevkit_dbg_box. @param x screen x @param y screen y @param w width @param h height @param kind colour 0..7 */
void sdevkit_dbg_fill(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind);
/** @brief An arrow from (x, y) by (dx, dy) pixels (a direction, an air current). @param x start x @param y start y @param dx way x @param dy way y @param kind colour 0..7 */
void sdevkit_dbg_arrow(int16_t x, int16_t y, int8_t dx, int8_t dy, uint8_t kind);

#endif /* SDEVKIT_SNES_H */
