#ifndef SDEVKIT_SHADOW_H
#define SDEVKIT_SHADOW_H

/**
 * @file shadow.h
 * @brief The register shadow, an optional layer above the core (ADR-6FNPPCSX): the PPU's
 *        registers and the HDMA channels in memory, written into the hardware by one call.
 *
 * The PPU's registers are write-only, and they may only be written in the vertical blank
 * (sdevkit/snes.h, "The frame and the vertical blank"). This layer keeps them in memory, laid out
 * as the hardware has them: game_tick writes and reads `sdevkit_ppu` whenever it likes, and
 * sdevkit_shadow_commit in game_vblank writes it into the hardware. The register's meaning, its
 * bits and the macros for its values are at its `SDEVKIT_PPU_*` name in sdevkit/snes.h. A game
 * that uses the core's sdevkit_reg_write instead pays nothing for this layer: dampf leaves out
 * what a program does not call, and the memory only that uses.
 *
 * The commit writes INIDISP every time and the other registers only when the tick marks their
 * group in sdevkit_marks, since the vertical blank is short and most registers change seldom:
 *
 * | Mark                  | Fields |
 * | --------------------- | ------ |
 * | (every commit)        | inidisp |
 * | SDEVKIT_MARK_SCROLL   | hofs, vofs |
 * | SDEVKIT_MARK_COLOR    | coldata, cgwsel, cgadsub |
 * | SDEVKIT_MARK_MODE7    | m7a to m7y, m7sel |
 * | SDEVKIT_MARK_SETUP    | bgmode, obsel, mosaic, bgsc, bgnba, setini, tm, ts |
 * | SDEVKIT_MARK_WINDOW   | w12sel, w34sel, wobjsel, wh, wbglog, wobjlog, tmw, tsw |
 * | SDEVKIT_MARK_HDMA     | the channels of sdevkit_hdma, hdmaen |
 *
 * Every group starts marked, so the first commit writes all of them. A change to a group without
 * its mark is not committed; on the desktop the commit finds it and the check fails
 * ("unmarked"), so a forgotten mark shows there, not only on the console. A game may mix the
 * layer with sdevkit_reg_write: what it writes itself stays until a commit of its group.
 *
 * @code
 * void game_init(uint16_t seed)
 * {
 *     (void)seed;
 *     sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;
 *     sdevkit_ppu.tm = SDEVKIT_BG1_ENABLE;
 *     sdevkit_ppu.bgsc[0] = SDEVKIT_BGSC(0x1000, SDEVKIT_SC_32x32);
 *     sdevkit_ppu.inidisp = 15;          // on at the first commit; no mark needed at the start
 * }
 *
 * void game_tick(const uint16_t pad[SDEVKIT_PADS])
 * {
 *     if (pad[0] & SDEVKIT_KEY_RIGHT) {
 *         sdevkit_ppu.hofs[0]++;
 *         sdevkit_marks |= SDEVKIT_MARK_SCROLL;
 *     }
 * }
 *
 * void game_vblank(void)
 * {
 *     sdevkit_shadow_commit();
 * }
 * @endcode
 */

#include "sdevkit/snes.h"

/**
 * @brief The PPU's registers, by their names.
 *
 * Laid out without padding (16-bit fields first), the same on the SNES and the PC, so a game may
 * name it to sdevkit_dbg_hash. It starts as the console after the kit's reset, but in mode 1 and
 * with the screen off.
 */
typedef struct {
    uint16_t hofs[4];          /**< BG1HOFS..BG4HOFS (SDEVKIT_MARK_SCROLL): 10 bits; BG1 in mode 7 13 bits with sign. */
    uint16_t vofs[4];          /**< BG1VOFS..BG4VOFS (SDEVKIT_MARK_SCROLL): 0x3FF puts map line 0 on screen line 0. */
    uint16_t coldata;          /**< The fixed colour of colour math, BGR 5:5:5 (SDEVKIT_MARK_COLOR), written as COLDATA's three channels. */
    int16_t  m7a;              /**< M7A (SDEVKIT_MARK_MODE7): mode 7's matrix, 8.8 fixed point (0x0100 is 1.0). */
    int16_t  m7b;              /**< M7B. */
    int16_t  m7c;              /**< M7C. */
    int16_t  m7d;              /**< M7D. */
    int16_t  m7x;              /**< M7X: mode 7's centre, 13 bits with sign. */
    int16_t  m7y;              /**< M7Y. */
    uint8_t  m7sel;            /**< M7SEL: SDEVKIT_M7_* (SDEVKIT_MARK_MODE7). */
    uint8_t  cgwsel;           /**< CGWSEL: colour math, where and with what, SDEVKIT_CM_* (SDEVKIT_MARK_COLOR). */
    uint8_t  cgadsub;          /**< CGADSUB: colour math, how and on which layers (SDEVKIT_MARK_COLOR). */
    uint8_t  bgmode;           /**< BGMODE: SDEVKIT_BG_MODE1 or _MODE7, SDEVKIT_BG1_TILES_16, ... (SDEVKIT_MARK_SETUP). */
    uint8_t  obsel;            /**< OBSEL: SDEVKIT_OBSEL(sprite tiles, sizes) (SDEVKIT_MARK_SETUP). */
    uint8_t  mosaic;           /**< MOSAIC (SDEVKIT_MARK_SETUP); not drawn on Rachael: keep 0. */
    uint8_t  bgsc[4];          /**< BG1SC..BG4SC: SDEVKIT_BGSC(map, size) (SDEVKIT_MARK_SETUP). */
    uint8_t  bgnba[2];         /**< BG12NBA, BG34NBA: SDEVKIT_BGNBA(tiles of the first, of the second) (SDEVKIT_MARK_SETUP). */
    uint8_t  setini;           /**< SETINI (SDEVKIT_MARK_SETUP); not drawn on Rachael: keep 0. */
    uint8_t  tm;               /**< TM: the layers on the main screen, SDEVKIT_BG1_ENABLE ... (SDEVKIT_MARK_SETUP). */
    uint8_t  ts;               /**< TS: the layers on the sub screen (SDEVKIT_MARK_SETUP). */
    uint8_t  w12sel;           /**< W12SEL: BG1's and BG2's windows, SDEVKIT_WSEL() (SDEVKIT_MARK_WINDOW). */
    uint8_t  w34sel;           /**< W34SEL: BG3's and BG4's windows. */
    uint8_t  wobjsel;          /**< WOBJSEL: the sprites' and the colour window's. */
    uint8_t  wh[4];            /**< WH0..WH3: window 1 left and right, window 2 left and right. */
    uint8_t  wbglog;           /**< WBGLOG: SDEVKIT_WLOG_* per BG, 2 bits each. */
    uint8_t  wobjlog;          /**< WOBJLOG: SDEVKIT_WLOG_* for the sprites and the colour window. */
    uint8_t  tmw;              /**< TMW: the main screen's layers their windows mask. */
    uint8_t  tsw;              /**< TSW: the sub screen's. */
    uint8_t  hdmaen;           /**< HDMAEN: the HDMA channels that run, SDEVKIT_HDMA_CHANNELn (SDEVKIT_MARK_HDMA). */
    uint8_t  inidisp;          /**< INIDISP: brightness 0..15, or SDEVKIT_FORCED_BLANK; written by every commit. */
} sdevkit_ppu_regs;

/** The registers the next commit writes. */
extern sdevkit_ppu_regs sdevkit_ppu;

/** An HDMA channel (sdevkit_hdma_set's arguments), committed for the channels hdmaen switches on. */
typedef struct {
    const uint8_t *table;      /**< The table (sdevkit/snes.h, "HDMA"), static or in ROM. */
    uint8_t        dmap;       /**< SDEVKIT_HDMA_ONE, _TWO_REGS or _TWICE. */
    uint8_t        bbad;       /**< The register, an SDEVKIT_PPU_* name. */
} sdevkit_hdma_channel;

/** The HDMA channels 1..7 (SDEVKIT_MARK_HDMA); channel 0 is the copies'. */
extern sdevkit_hdma_channel sdevkit_hdma[8];

/** The groups this tick changed, SDEVKIT_MARK_* or'ed; the commit clears it. */
extern uint8_t sdevkit_marks;

/**
 * @name Marks
 * @{
 */
#define SDEVKIT_MARK_SETUP  0x01   /**< bgmode, obsel, mosaic, bgsc, bgnba, setini, tm, ts. */
#define SDEVKIT_MARK_WINDOW 0x02   /**< w12sel, w34sel, wobjsel, wh, wbglog, wobjlog, tmw, tsw. */
#define SDEVKIT_MARK_MODE7  0x04   /**< m7a to m7y, m7sel. */
#define SDEVKIT_MARK_HDMA   0x08   /**< The channels hdmaen switches on, and hdmaen. */
#define SDEVKIT_MARK_SCROLL 0x10   /**< hofs, vofs. */
#define SDEVKIT_MARK_COLOR  0x20   /**< coldata, cgwsel, cgadsub. */
#define SDEVKIT_MARK_ALL    0x3F   /**< Every group. */
/** @} */

/**
 * @brief Writes the shadow into the hardware: in game_vblank (or in game_init, with the screen off).
 *
 * A screen to be switched off goes off first; then the registers of the groups marked; then
 * INIDISP, which may switch the screen on. About 260 cycles with nothing marked; the worst case,
 * every group and seven HDMA channels, is in lib/README.md ("The commit's time").
 */
void sdevkit_shadow_commit(void);

#endif /* SDEVKIT_SHADOW_H */
