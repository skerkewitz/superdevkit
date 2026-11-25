#ifndef SDEVKIT_SHADOW_H
#define SDEVKIT_SHADOW_H

/*
 * The register shadow, an optional layer above the core (ADR-6FNPPCSX): the PPU's registers and
 * the HDMA channels in memory, laid out as the hardware is. game_tick writes them whenever it
 * likes; sdevkit_shadow_commit in game_vblank writes them into the hardware. A game that uses the
 * core's sdevkit_reg_write instead pays nothing for this layer: dampf leaves out what a program
 * does not call, and the memory only that uses.
 *
 * The commit writes INIDISP every time and the other registers only when the tick marks their
 * group in sdevkit_marks, since the vertical blank is short and most registers change seldom:
 *
 *   every commit         inidisp
 *   SDEVKIT_MARK_SCROLL  hofs, vofs
 *   SDEVKIT_MARK_COLOR   coldata, cgwsel, cgadsub
 *   SDEVKIT_MARK_MODE7   m7a to m7y, m7sel
 *   SDEVKIT_MARK_SETUP   bgmode, obsel, mosaic, bgsc, bgnba, setini, tm, ts
 *   SDEVKIT_MARK_WINDOW  w12sel, w34sel, wobjsel, wh, wbglog, wobjlog, tmw, tsw
 *   SDEVKIT_MARK_HDMA    the channels of sdevkit_hdma, hdmaen
 *
 * Every group starts marked, so the first commit writes all of them. A change to a group without
 * its mark is not committed; on the desktop the commit finds it and the check fails
 * ("unmarked"), so a forgotten mark shows there, not only on the console. A game may mix the
 * layer with sdevkit_reg_write: what it writes itself stays until a commit of its group.
 */

#include "sdevkit/snes.h"

/* Laid out without padding (16-bit fields first), the same on the SNES and the PC. Starts as the
 * console after the kit's reset, but mode 1 and the screen off. */
typedef struct {
    /* SDEVKIT_MARK_SCROLL */
    uint16_t hofs[4];          /* BG1HOFS..BG4HOFS: 10 bits; BG1 in mode 7: 13 bits with sign */
    uint16_t vofs[4];          /* BG1VOFS..BG4VOFS */
    /* SDEVKIT_MARK_COLOR (with cgwsel, cgadsub below) */
    uint16_t coldata;          /* the fixed colour of colour math, BGR 5:5:5 (written as COLDATA's three) */
    /* SDEVKIT_MARK_MODE7 */
    int16_t  m7a, m7b, m7c, m7d;   /* M7A..M7D: the matrix, 8.8 fixed point (0x0100 is 1.0) */
    int16_t  m7x, m7y;         /* M7X, M7Y: the centre, 13 bits with sign */
    uint8_t  m7sel;            /* M7SEL: SDEVKIT_M7_* */
    /* SDEVKIT_MARK_COLOR */
    uint8_t  cgwsel, cgadsub;  /* CGWSEL, CGADSUB: colour math, SDEVKIT_CM_* */
    /* SDEVKIT_MARK_SETUP */
    uint8_t  bgmode;           /* BGMODE: SDEVKIT_BG_MODE1 or _MODE7, SDEVKIT_BG1_TILES_16, ... */
    uint8_t  obsel;            /* OBSEL: SDEVKIT_OBSEL(sprite tiles, sizes) */
    uint8_t  mosaic;           /* MOSAIC */
    uint8_t  bgsc[4];          /* BG1SC..BG4SC: SDEVKIT_BGSC(map, size) */
    uint8_t  bgnba[2];         /* BG12NBA, BG34NBA: SDEVKIT_BGNBA(tiles of the first, of the second) */
    uint8_t  setini;           /* SETINI */
    uint8_t  tm, ts;           /* TM, TS: layers on the main and the sub screen, SDEVKIT_BG1_ENABLE... */
    /* SDEVKIT_MARK_WINDOW */
    uint8_t  w12sel, w34sel, wobjsel;
    uint8_t  wh[4];            /* WH0..WH3: window 1 left and right, window 2 left and right */
    uint8_t  wbglog, wobjlog;
    uint8_t  tmw, tsw;
    /* SDEVKIT_MARK_HDMA (with the channels of sdevkit_hdma) */
    uint8_t  hdmaen;           /* HDMAEN: the HDMA channels that run, SDEVKIT_HDMA_CHANNELn */
    /* written by every commit */
    uint8_t  inidisp;          /* INIDISP: brightness 0..15, or SDEVKIT_FORCED_BLANK */
} sdevkit_ppu_regs;

extern sdevkit_ppu_regs sdevkit_ppu;

/* An HDMA channel (sdevkit_hdma_set's arguments), committed for the channels hdmaen switches on. */
typedef struct {
    const uint8_t *table;      /* the table, static or in ROM */
    uint8_t        dmap;       /* SDEVKIT_HDMA_ONE, _TWO_REGS or _TWICE */
    uint8_t        bbad;       /* the register, SDEVKIT_PPU_* */
} sdevkit_hdma_channel;

extern sdevkit_hdma_channel sdevkit_hdma[8];   /* 1..7; channel 0 is the copies' */

extern uint8_t sdevkit_marks;  /* the groups this tick changed; the commit clears it */
#define SDEVKIT_MARK_SETUP  0x01
#define SDEVKIT_MARK_WINDOW 0x02
#define SDEVKIT_MARK_MODE7  0x04
#define SDEVKIT_MARK_HDMA   0x08
#define SDEVKIT_MARK_SCROLL 0x10
#define SDEVKIT_MARK_COLOR  0x20
#define SDEVKIT_MARK_ALL    0x3F

/* In game_vblank (or in game_init, with the screen off): a screen to be switched off goes off
 * first; the registers of the groups marked; then INIDISP, which may switch the screen on. About
 * 260 cycles with nothing marked; the worst case, every group and seven HDMA channels, is in
 * lib/README.md ("The commit's time"). */
void sdevkit_shadow_commit(void);

#endif /* SDEVKIT_SHADOW_H */
