#ifndef SDEVKIT_SNES_H
#define SDEVKIT_SNES_H

/*
 * The console API of superdevkit (ADR-6F3CJX8T, ADR-6F3CK2AR): how a game draws on the SNES. Every
 * name a game can use starts with sdevkit_ or SDEVKIT_; nothing else of the kit is meant for it.
 * lib/snes implements it on the console, compiled by dampf with the game; lib/rachael (Rachael,
 * the SNES rebuilt in portable C) on the desktop and the web. lib/README.md lists what each
 * function costs.
 *
 * Shadow state (docs/brainstorm/console-api-shadow-state_2026-11-24.md). A game changes the
 * picture by writing memory, not by calling functions:
 *
 *   sdevkit_ppu      the PPU's registers, named after them: BG1HOFS is sdevkit_ppu.hofs[0]
 *   sdevkit_hdma[n]  HDMA channels 1 to 7
 *   sdevkit_oam      the sprite table, in the hardware's format
 *
 * game_tick writes them; after the tick the kit writes them into the hardware in the vertical
 * blank (the commit), on Rachael at the same point. So a write is never too early or too late,
 * and the checks compare all of it between the SNES and the PC after every tick.
 *
 * The commit writes INIDISP (the brightness) every frame and the other registers only when the
 * tick marks their group in sdevkit_marks, since the vertical blank is short and most registers
 * change seldom:
 *
 *   every frame          inidisp
 *   SDEVKIT_MARK_SCROLL  hofs, vofs
 *   SDEVKIT_MARK_COLOR   coldata, cgwsel, cgadsub
 *   SDEVKIT_MARK_MODE7   m7a to m7y, m7sel
 *   SDEVKIT_MARK_SETUP   bgmode, obsel, mosaic, bgsc, bgnba, setini, tm, ts
 *   SDEVKIT_MARK_WINDOW  w12sel, w34sel, wobjsel, wh, wbglog, wobjlog, tmw, tsw
 *   SDEVKIT_MARK_HDMA    the channels of sdevkit_hdma, hdmaen
 *
 * The kit marks every group itself once after game_init. A change to a group without its mark
 * is not committed; Rachael finds it at the commit and the desktop check fails ("unmarked"), so a
 * forgotten mark shows on the desktop, not only on the console.
 *
 * Two kinds of write:
 *   - the shadow (above) and the upload queue (sdevkit_queue_*): in game_tick, committed in the
 *     next vertical blank;
 *   - now (sdevkit_*_now): at once, only while the screen is off (in game_init, or after
 *     sdevkit_screen_off_now). The SNES drops what VRAM and CGRAM get while the picture is drawn;
 *     Rachael counts such bytes as lost and the desktop check fails.
 *
 * Addresses are SNES addresses: VRAM addresses count 16-bit words (0x0000..0x7FFF), CGRAM
 * addresses count colours (0..255). Sizes count bytes. The frame rhythm (game_init, game_tick and
 * the joypads) is in sdevkit/loop.h.
 */

#include <stdint.h>

/* --- Colours ------------------------------------------------------------------------------ */
#define SDEVKIT_RGB5(r, g, b) ((uint16_t)((r) | ((g) << 5) | ((b) << 10)))
#define SDEVKIT_RGB8(r, g, b) ((uint16_t)(((r) >> 3) | (((g) >> 3) << 5) | (((b) >> 3) << 10)))

/* --- The PPU's registers ------------------------------------------------------------------ */
/* Laid out without padding (16-bit fields first), the same on the SNES and the PC: the checks
 * hash it byte for byte. */
typedef struct {
    /* SDEVKIT_MARK_SCROLL */
    uint16_t hofs[4];          /* BG1HOFS..BG4HOFS: 10 bits; BG1 in mode 7: 13 bits with sign */
    uint16_t vofs[4];          /* BG1VOFS..BG4VOFS */
    /* SDEVKIT_MARK_COLOR (with cgwsel, cgadsub below) */
    uint16_t coldata;          /* COLDATA: the fixed colour of colour math, BGR 5:5:5 */
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
    uint8_t  hdmaen;           /* HDMAEN ($420C): the HDMA channels that run, SDEVKIT_HDMA_CHANNELn */
    /* committed every frame */
    uint8_t  inidisp;          /* INIDISP: brightness 0..15, or SDEVKIT_FORCED_BLANK */
} sdevkit_ppu_regs;

extern sdevkit_ppu_regs sdevkit_ppu;

extern uint8_t sdevkit_marks;  /* the groups this tick changed; the commit clears it */
#define SDEVKIT_MARK_SETUP  0x01
#define SDEVKIT_MARK_WINDOW 0x02
#define SDEVKIT_MARK_MODE7  0x04
#define SDEVKIT_MARK_HDMA   0x08
#define SDEVKIT_MARK_SCROLL 0x10
#define SDEVKIT_MARK_COLOR  0x20
#define SDEVKIT_MARK_ALL    0x3F

/* INIDISP */
#define SDEVKIT_FORCED_BLANK 0x80  /* the screen off: black, and VRAM and CGRAM take writes */

/* BGMODE */
#define SDEVKIT_BG_MODE1                1
#define SDEVKIT_BG_MODE7                7    /* one layer (BG1), rotated and scaled: sdevkit_ppu.m7* */
#define SDEVKIT_BG3_MODE1_PRIORITY_HIGH (1 << 3)   /* BG3 tiles with priority 1 above everything */
#define SDEVKIT_BG1_TILES_16 (1 << 4)        /* BG1 with tiles of 16 x 16 pixels */
#define SDEVKIT_BG2_TILES_16 (1 << 5)
#define SDEVKIT_BG3_TILES_16 (1 << 6)
#define SDEVKIT_BG4_TILES_16 (1 << 7)

/* TM, TS (and TMW, TSW): the layers */
#define SDEVKIT_BG1_ENABLE (1 << 0)
#define SDEVKIT_BG2_ENABLE (1 << 1)
#define SDEVKIT_BG3_ENABLE (1 << 2)
#define SDEVKIT_BG4_ENABLE (1 << 3)
#define SDEVKIT_OBJ_ENABLE (1 << 4)

/* BGnSC: a map at a VRAM word address (a multiple of 0x400) and its size */
#define SDEVKIT_SC_32x32 0
#define SDEVKIT_SC_64x32 1
#define SDEVKIT_SC_32x64 2
#define SDEVKIT_SC_64x64 3
#define SDEVKIT_BGSC(address, size) ((uint8_t)((((address) >> 8) & 0xFC) | (size)))
/* BG12NBA, BG34NBA: the tiles of two layers, each at a VRAM word address (a multiple of 0x1000) */
#define SDEVKIT_BGNBA(first, second) ((uint8_t)((((first) >> 12) & 0x0F) | ((((second) >> 12) & 0x0F) << 4)))
/* OBSEL: the sprite tiles at a VRAM word address (a multiple of 0x2000) and the two sizes */
#define SDEVKIT_OBJ_SIZE8_L16  (0 << 5)
#define SDEVKIT_OBJ_SIZE8_L32  (1 << 5)
#define SDEVKIT_OBJ_SIZE8_L64  (2 << 5)
#define SDEVKIT_OBJ_SIZE16_L32 (3 << 5)
#define SDEVKIT_OBJ_SIZE16_L64 (4 << 5)
#define SDEVKIT_OBJ_SIZE32_L64 (5 << 5)
#define SDEVKIT_OBSEL(address, sizes) ((uint8_t)((sizes) | (((address) >> 13) & 7)))

/* Background map entries */
#define SDEVKIT_BG_TIL_NUM(n)  ((uint16_t)(n))          /* 0..1023 */
#define SDEVKIT_BG_TIL_PAL(n)  ((uint16_t)((n) << 10))  /* 0..7 */
#define SDEVKIT_BG_TIL_PRIO    ((uint16_t)(1 << 13))
#define SDEVKIT_BG_TIL_FLIPX   ((uint16_t)(1 << 14))
#define SDEVKIT_BG_TIL_FLIPY   ((uint16_t)(1 << 15))

/* Colour math: CGWSEL */
#define SDEVKIT_CM_SUBBGOBJ_ENABLE  (1 << 1)       /* add/subtract the sub screen, not the fixed colour */
#define SDEVKIT_CM_ALWAYS_ENABLE    (0 << 4)       /* colour math: everywhere ... */
#define SDEVKIT_CM_MATWIN_ENABLE    (1 << 4)       /* ... only inside the colour window */
#define SDEVKIT_CM_NOMATWIN_ENABLE  (2 << 4)       /* ... only outside the colour window */
#define SDEVKIT_CM_NEVER_ENABLE     (3 << 4)
#define SDEVKIT_CM_SCBLK_ALWAYS_ENABLE   (0 << 6)  /* main screen black: never */
#define SDEVKIT_CM_SCBLK_MATWIN_ENABLE   (1 << 6)  /* ... outside the colour window */
#define SDEVKIT_CM_SCBLK_NOMATWIN_ENABLE (2 << 6)  /* ... inside the colour window */
#define SDEVKIT_CM_SCBLK_NEVER_ENABLE    (3 << 6)  /* ... always */
/* CGADSUB */
#define SDEVKIT_CM_SUB_MAINSUB (1 << 7)            /* subtract instead of add */
#define SDEVKIT_CM_DIV2_DIVIDE (1 << 6)            /* halve the result */
#define SDEVKIT_CM_MSCR_BACK   (1 << 5)            /* colour math on the backdrop ... */
#define SDEVKIT_CM_MSCR_PAL47  (1 << 4)            /* ... on sprites with palettes 4..7 */
#define SDEVKIT_CM_MSCR_BG4    (1 << 3)
#define SDEVKIT_CM_MSCR_BG3    (1 << 2)
#define SDEVKIT_CM_MSCR_BG2    (1 << 1)
#define SDEVKIT_CM_MSCR_BG1    (1 << 0)

/* Mode 7. With bgmode SDEVKIT_BG_MODE7 the screen has one background, BG1: a map of 128 x 128
 * tiles (1024 x 1024 pixels, one byte a tile) and 256 tiles of 8 x 8 pixels with a byte a pixel
 * (an index into all 256 colours of CGRAM, 0 transparent), rotated, scaled and sheared by a
 * matrix. Both fill the first 32 KB of VRAM, the map the low byte of each word and the tiles the
 * high byte; nothing else of a game may be there. Sprites stay as in mode 1: those of priority 1
 * to 3 in front of the layer, priority 0 behind it. A screen pixel (x, y) shows the map pixel
 *     (a b; c d) * (x + hscroll - cx, y + vscroll - cy) + (cx, cy)
 * with a, b, c, d in 8.8 fixed point and the centre (cx, cy) = (m7x, m7y) and the scroll
 * (hofs[0], vofs[0]) in pixels, 13 bits with sign. */
#define SDEVKIT_M7_WRAP        0x00   /* m7sel: outside the map, it repeats */
#define SDEVKIT_M7_TRANSPARENT 0x80   /* ... outside the map, nothing (the backdrop) */
#define SDEVKIT_M7_TILE0       0xC0   /* ... outside the map, tile 0 everywhere */
#define SDEVKIT_M7_FLIPX       0x01   /* the whole screen mirrored */
#define SDEVKIT_M7_FLIPY       0x02

/* --- HDMA ------------------------------------------------------------------------------------ */
/* A channel writes data from its table into a PPU register at the start of every line, from the
 * frame after the commit on, while its bit is set in sdevkit_ppu.hdmaen (both marked
 * SDEVKIT_MARK_HDMA). Channel 0 is the kit's
 * (general DMA); a game has 1 to 7. The table is read while the frame is drawn, so it must stay
 * valid: a static or ROM array; two tables and switching between them (a new table pointer,
 * marked) changes it without tearing. Table format as on the SNES: a line count byte, then the
 * data; count 1..127 = data once, then hold for that many lines; 129..255 = new data on each of
 * (count - 128) lines; 0 ends the table. Rachael draws the direct modes below; another mode, or
 * indirect HDMA (dmap bit 6), is counted as a write she does not draw. */
typedef struct {
    const uint8_t *table;      /* A1T, A1B: the table */
    uint8_t        dmap;       /* DMAP: SDEVKIT_HDMA_ONE, _TWO_REGS or _TWICE */
    uint8_t        bbad;       /* BBAD: the register, the low byte of $21xx (SDEVKIT_PPU_*) */
    uint8_t        dasb;       /* DASB: the bank of the data of indirect HDMA */
} sdevkit_hdma_channel;

extern sdevkit_hdma_channel sdevkit_hdma[8];

#define SDEVKIT_HDMA_ONE      0    /* one byte to the register */
#define SDEVKIT_HDMA_TWO_REGS 1    /* two bytes, to the register and the next */
#define SDEVKIT_HDMA_TWICE    2    /* two bytes to the register (a scroll register: a wave down the screen) */
#define SDEVKIT_HDMA_CHANNEL1 (1 << 1)
#define SDEVKIT_HDMA_CHANNEL2 (1 << 2)
#define SDEVKIT_HDMA_CHANNEL3 (1 << 3)
#define SDEVKIT_HDMA_CHANNEL4 (1 << 4)
#define SDEVKIT_HDMA_CHANNEL5 (1 << 5)
#define SDEVKIT_HDMA_CHANNEL6 (1 << 6)
#define SDEVKIT_HDMA_CHANNEL7 (1 << 7)

/* The PPU's registers by the low byte of their $21xx address, for HDMA (bbad) */
#define SDEVKIT_PPU_INIDISP 0x00
#define SDEVKIT_PPU_OBSEL   0x01
#define SDEVKIT_PPU_BGMODE  0x05
#define SDEVKIT_PPU_MOSAIC  0x06
#define SDEVKIT_PPU_BG1SC   0x07
#define SDEVKIT_PPU_BG12NBA 0x0B
#define SDEVKIT_PPU_BG34NBA 0x0C
#define SDEVKIT_PPU_BG1HOFS 0x0D   /* BGnHOFS = 0x0D + 2(n-1), BGnVOFS = 0x0E + 2(n-1) */
#define SDEVKIT_PPU_BG1VOFS 0x0E
#define SDEVKIT_PPU_M7SEL   0x1A
#define SDEVKIT_PPU_M7A     0x1B   /* M7A..M7D, M7X, M7Y = 0x1B..0x20 */
#define SDEVKIT_PPU_M7B     0x1C
#define SDEVKIT_PPU_M7C     0x1D
#define SDEVKIT_PPU_M7D     0x1E
#define SDEVKIT_PPU_M7X     0x1F
#define SDEVKIT_PPU_M7Y     0x20
#define SDEVKIT_PPU_W12SEL  0x23
#define SDEVKIT_PPU_W34SEL  0x24
#define SDEVKIT_PPU_WOBJSEL 0x25
#define SDEVKIT_PPU_WH0     0x26
#define SDEVKIT_PPU_WH1     0x27
#define SDEVKIT_PPU_WH2     0x28
#define SDEVKIT_PPU_WH3     0x29
#define SDEVKIT_PPU_WBGLOG  0x2A
#define SDEVKIT_PPU_WOBJLOG 0x2B
#define SDEVKIT_PPU_TM      0x2C
#define SDEVKIT_PPU_TS      0x2D
#define SDEVKIT_PPU_TMW     0x2E
#define SDEVKIT_PPU_TSW     0x2F
#define SDEVKIT_PPU_CGWSEL  0x30
#define SDEVKIT_PPU_CGADSUB 0x31
#define SDEVKIT_PPU_COLDATA 0x32
#define SDEVKIT_PPU_SETINI  0x33

/* --- Sprites ---------------------------------------------------------------------------------- */
/* The sprite table in the hardware's format, committed every frame: 128 entries of 4 bytes (x,
 * y, tile, attributes), then 32 bytes with 2 bits a sprite (bit 8 of x, large). The SNES draws a
 * sprite one line lower than its y. */
extern uint8_t sdevkit_oam[128 * 4 + 8 * 4];

#define SDEVKIT_OAM_HIDE_Y 0xE8    /* the y of a hidden sprite: below the picture */
/* Byte 3 of an entry: palette 0..7, priority 0..3, the flips; or it with bit 8 of the tile. */
#define SDEVKIT_OAM_ATTR(palette, priority, hflip, vflip) \
    ((uint8_t)(((vflip) ? 0x80 : 0) | ((hflip) ? 0x40 : 0) | (((priority) & 3) << 4) | (((palette) & 7) << 1)))

/* The sprite list: a game that draws its sprites anew each tick appends them in the order of
 * their importance (the first in front, and the last to go when a line has more than 32). The
 * upper left corner goes where a background scrolled by (0, 0) shows its pixel (x, y), so a sprite
 * at its world position minus the scroll lines up with the background it moves on (the SNES draws
 * a sprite one line lower than its entry says, and the kit writes y - 1 into the table). large:
 * the large size of OBSEL. One out of the picture is left out (for sprites up to 64 pixels).
 * After the tick the kit hides the entries the list filled the tick before and not in this one.
 * A game that never calls it writes the table itself and the kit leaves the table alone. */
void sdevkit_sprite(int16_t x, int16_t y, uint16_t tile, uint8_t attr, uint8_t large);

/* --- Uploads in game_tick: the queue ------------------------------------------------------------ */
/* A copy into VRAM or CGRAM in the next vertical blank: from the source (which must still hold
 * the data then, at the end of the tick), to the destination, so many bytes. It returns 1 when the
 * copy fits into that vertical blank and 0 (nothing queued) when it does not: then the game tries
 * again in the next tick. What fits is counted in bytes, the same on the SNES and the PC: the
 * vertical blank takes SDEVKIT_QUEUE_BYTES a frame, and every copy counts SDEVKIT_QUEUE_COST more
 * for its setup; at most SDEVKIT_QUEUE_ENTRIES copies. sdevkit_queue_room says how many bytes one
 * more copy may have. */
#define SDEVKIT_QUEUE_BYTES   3840
#define SDEVKIT_QUEUE_COST    136
#define SDEVKIT_QUEUE_ENTRIES 24

/* The raw form: port (the low byte of $21xx it writes: 0x18 or 0x19 VRAM, 0x22 CGRAM), DMAP,
 * VMAIN (how the VRAM address steps), and the destination (a VRAM word address or a CGRAM
 * colour). The functions below fill it, with half the parameters to pass. */
uint8_t  sdevkit_queue_dma(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes);
uint8_t  sdevkit_queue_color(uint8_t index, uint16_t bgr);   /* one colour; the entry keeps the value */
uint16_t sdevkit_queue_room(void);

/* words to VRAM from word address a */
uint8_t  sdevkit_queue_vram(uint16_t a, const void *src, uint16_t bytes);
/* words down a column of a 32 x 32 map: the address steps by 32 words; at most 64 bytes */
uint8_t  sdevkit_queue_vram_column(uint16_t a, const void *src, uint16_t bytes);
/* colours from colour index i */
uint8_t  sdevkit_queue_cgram(uint8_t i, const void *src, uint16_t bytes);
/* mode 7: map bytes from entry e (row * 128 + column), tile bytes from byte b of the tiles
 * (tile * 64 + row * 8 + column) */
uint8_t  sdevkit_queue_mode7_map(uint16_t e, const void *src, uint16_t bytes);
uint8_t  sdevkit_queue_mode7_tiles(uint16_t b, const void *src, uint16_t bytes);

/* --- Writes now, with the screen off ------------------------------------------------------------ */
/* The same copies at once. Only while the screen is off: in game_init, which the kit starts with
 * the screen off, or after sdevkit_screen_off_now; the shadow's inidisp switches it on again at
 * the next commit. */
void sdevkit_dma_now(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes);
void sdevkit_color_now(uint8_t index, uint16_t bgr);
#define sdevkit_vram_now(a, src, bytes)          sdevkit_dma_now(0x18, 0x01, 0x80, (a), (src), (bytes))
#define sdevkit_vram_column_now(a, src, bytes)   sdevkit_dma_now(0x18, 0x01, 0x81, (a), (src), (bytes))
#define sdevkit_cgram_now(i, src, bytes)         sdevkit_dma_now(0x22, 0x00, 0x00, (i), (src), (bytes))
#define sdevkit_mode7_map_now(e, src, bytes)     sdevkit_dma_now(0x18, 0x00, 0x00, (e), (src), (bytes))
#define sdevkit_mode7_tiles_now(b, src, bytes)   sdevkit_dma_now(0x19, 0x00, 0x80, (b), (src), (bytes))
/* The screen off at once (and inidisp with it), in game_tick: for a new level's graphics. */
void sdevkit_screen_off_now(void);
/* In game_init only: the commit of the shadow and the queue in the next vertical blank, as after a
 * tick; it returns then. For a picture on the screen before a long job such as sdevkit_sfx_init. */
void sdevkit_commit_now(void);

/* --- Sound effects (sdevkit_sfx_*, docs/sound.md) ----------------------------------------- */
/* A game's effects come from its assets/sfx/sfx.json: superdevkit sfx converts it into a header
 * with a name per effect (SFX_JUMP, ...) and a bank, NAME_bank, which these functions play. The
 * fields are the build's; a game only passes the bank on. */
#define SDEVKIT_SFX_OFF 0xFF              /* the sample of an effect without an active one */
typedef struct {
    uint8_t                effects;       /* how many, SFX_COUNT */
    uint8_t                voices;        /* how many of the DSP's 8 voices the effects use, 1..8 */
    uint8_t                samples;       /* the active samples, one entry each in the sample directory */
    const uint8_t         *sample;        /* per effect: its sample's number, or SDEVKIT_SFX_OFF */
    const uint8_t         *priority;      /* per effect: the higher wins a voice */
    const uint8_t         *volume;        /* per effect: the voice's volume register, 0..127 */
    const uint16_t        *pitch;         /* per effect: the pitch register, rate * 4096 / 32000 */
    const uint16_t        *frames;        /* per effect: how long it plays, in frames, plus one */
    const uint8_t *const  *brr;           /* per sample: its BRR blocks */
    const uint16_t        *brr_size;      /* per sample: their bytes */
} sdevkit_sfx_bank;
/* Loads the bank's samples into the audio RAM and starts the sound: once, in game_init. On the SNES
 * it waits for the sound CPU's boot code and sends every byte through it, about 350 bytes a frame,
 * and the CPU does nothing else meanwhile; a game that switches the screen on first shows its
 * picture during the upload (docs/sound.md: inidisp set, then sdevkit_commit_now). A null bank does
 * nothing. */
void sdevkit_sfx_init(const sdevkit_sfx_bank *bank);
/* Plays an effect: it starts after this tick, when a voice is free or the voice of an effect with
 * a lower priority (or the older one of the same) gives way; otherwise it is dropped. An effect
 * without an active sample, or before sdevkit_sfx_init, does nothing. */
void sdevkit_sfx_play(uint8_t id);

/* --- Music (sdevkit_music_*, ADR-6F3CK03Z) ---------------------------------------------- */
/* A game's music is listed in its superdevkit.toml ([sound] music = ["assets/music/title.ogg",
 * ...]); the build writes music.h with a number per track (MUSIC_TITLE, ..., MUSIC_COUNT), never a
 * file name. On the desktop and the web the track plays from its file (OGG, MP3, WAV, QOA, XM, MOD), over
 * and over, until another one or sdevkit_music_stop; the same track again goes on playing. On the
 * SNES music is not built yet: the calls do nothing (and dampf removes them), so a game can call
 * them already. The checks play no music. */
void sdevkit_music_play(uint8_t track);
void sdevkit_music_stop(void);

/* --- Save RAM (sdevkit_sram, ADR-6F3CK03Z) ---------------------------------------------- */
/* Battery-backed RAM on the cartridge, kept while the console is off: high scores, settings, a
 * saved game. A game asks for it in its build (dampf --sram BYTES, which defines
 * SDEVKIT_SRAM_SIZE; the desktop build gets the same -DSDEVKIT_SRAM_SIZE); without, the size is
 * 0 and sdevkit_sram() returns 0. The game reads and writes the block like memory, whenever it
 * likes; the kit keeps it: the cartridge's battery on the SNES (at $70:0000, the header announces
 * it), a file next to the program on the desktop (<game>.srm, written when it changed and on exit).
 * The first time it holds zeros on the desktop and in the checks, on a cartridge whatever the RAM
 * held: a game marks its data as valid (a magic number, a checksum) and clears the block when the
 * mark is missing. A recording starts with the save RAM of its start, so that a replay plays the
 * same game. */
#ifndef SDEVKIT_SRAM_SIZE
#define SDEVKIT_SRAM_SIZE 0
#endif
uint8_t *sdevkit_sram(void);

/* --- Debug (sdevkit_dbg_*) --------------------------------------------------------------- */
/* VRAM as 0x8000 words, for a debug check of the tile maps on the desktop. Only Rachael
 * can read VRAM like memory; on the SNES it returns a single zero word, so a check that uses it
 * runs only on the desktop. */
const uint16_t *sdevkit_dbg_vram(void);
/* Names memory that makes up the game's state, for the checks that play a game on the SNES and on
 * the PC with the same input and compare a checksum after every tick (dev/compare_example.sh,
 * ExampleChecksumsTest): the bytes named during a tick are hashed (FNV-1a, 32 bits) in the order
 * named, then the sprite table, which the main loop names last after every game_tick. Call it
 * in game_tick, on data whose bytes are the same on both: arrays of uint8_t and uint16_t (both
 * are little endian), structs only when they have no padding on the PC, nothing with pointers.
 * On the SNES it costs a few port writes ($21FC, which no hardware uses); the checker reads the
 * memory itself. On Rachael it hashes. */
void sdevkit_dbg_hash(const void *data, uint16_t bytes);

/* The debug view (ADR-6F3CK03Z), on the desktop: F1 opens it beside the picture. A game registers
 * once, in game_init, what it wants to change while it runs, and the view lists it; per tick it
 * can draw boxes over the picture (collision boxes, a target). On the SNES the functions are empty
 * and dampf removes the calls, so the game needs no #ifdef. name is a string constant (the view
 * keeps the pointer); at most 32 registrations. A change made in the view is not part of a
 * recording: from then on a replay plays another game (the window says so). The desktop's --set
 * name=value sets a registration before the first tick, and a recording keeps that. So a bot needs
 * no support of its own: a toggle ("autoplay") that game_tick reads to make its own pad instead of
 * pad[0], switched on with --set autoplay=1. */
void sdevkit_dbg_toggle(const char *name, uint8_t *flag);                         /* 0 or 1 */
void sdevkit_dbg_range(const char *name, int16_t *value, int16_t lo, int16_t hi);  /* lo..hi */
void sdevkit_dbg_color(const char *name, uint16_t *colour);                       /* BGR 5:5:5 */
/* A button: the view sets *pressed to 1, and the game does what it means in its next game_tick
 * and sets it back to 0 (dampf has no function pointers to call, and the game's state changes
 * only inside a tick). */
void sdevkit_dbg_action(const char *name, uint8_t *pressed);
/* A line the view only shows, read again every frame: text the game keeps up to date (a string
 * ending in a zero byte, in memory that stays: the partner's goal, why a life was lost), or a value
 * (shown in decimal and hex). Nothing changes the game, so a recording stays valid. */
void sdevkit_dbg_text(const char *name, const char *text);
void sdevkit_dbg_value(const char *name, const uint16_t *value);
/* A box of w x h pixels at screen position (x, y), drawn over the picture in the debug view in the
 * colour of kind (0..7); for this tick only (the list is cleared before each game_tick). At most
 * 256 a tick. */
void sdevkit_dbg_box(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind);
/* The same as a filled box the picture shows through (a danger zone), and as an arrow from (x, y)
 * by (dx, dy) pixels (a direction, an air current). They count among the 256 of the tick. */
void sdevkit_dbg_fill(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind);
void sdevkit_dbg_arrow(int16_t x, int16_t y, int8_t dx, int8_t dy, uint8_t kind);

#endif /* SDEVKIT_SNES_H */
