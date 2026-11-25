#ifndef SDEVKIT_SNES_H
#define SDEVKIT_SNES_H

/*
 * The console API of superdevkit, its core (ADR-6FNPPCSX): how a game reaches the SNES. Every
 * name a game can use starts with sdevkit_ or SDEVKIT_; nothing else of the kit is meant for it.
 * lib/snes implements it on the console, compiled by dampf with the game; lib/rachael (Rachael,
 * the SNES rebuilt in portable C) on the desktop and the web. lib/README.md lists what each
 * function costs.
 *
 * The core is the hardware, as directly as C reaches it: a register write is one store on the
 * SNES (dampf inlines the call and folds the register's address), a copy is one DMA. Every write
 * goes to the hardware at once, and the kit does nothing a game did not ask for: in the vertical
 * blank it runs only the game's game_vblank (sdevkit/loop.h), no copy of its own.
 *
 * When a write may happen is the hardware's rule. The PPU draws the picture while game_tick runs;
 * its registers change the picture from the line being drawn on, and VRAM, CGRAM and OAM take
 * nothing while it draws. So a game writes them in game_vblank, or while the screen is off (in
 * game_init, which the kit starts with the screen off, or after writing SDEVKIT_FORCED_BLANK,
 * which may be written at any time: the rest of that frame is black). The desktop checks the
 * rule: a byte for VRAM, CGRAM or OAM written while the picture is drawn is lost there as on the
 * SNES, a register or an HDMA channel written while it is drawn is counted, and the desktop's
 * check fails on either.
 *
 * Above the core, optional layers in portable C, each costing a game only what it calls:
 * sdevkit/shadow.h (the registers in memory, written by one call in game_vblank), sdevkit/sprites.h
 * (a sprite list), sdevkit/queue.h (copies queued in game_tick), sdevkit/input.h (the buttons
 * pressed since the last tick), and sdevkit/easy.h, all of them and one commit.
 *
 * Addresses are SNES addresses: VRAM addresses count 16-bit words (0x0000..0x7FFF), CGRAM
 * addresses count colours (0..255), OAM addresses count bytes (0..543). Sizes count bytes.
 */

#include <stdint.h>

/* --- Colours ------------------------------------------------------------------------------ */
#define SDEVKIT_RGB5(r, g, b) ((uint16_t)((r) | ((g) << 5) | ((b) << 10)))
#define SDEVKIT_RGB8(r, g, b) ((uint16_t)(((r) >> 3) | (((g) >> 3) << 5) | (((b) >> 3) << 10)))

/* --- The PPU's registers ------------------------------------------------------------------ */
/* Writes a PPU register: reg is the low byte of its address $21xx (SDEVKIT_PPU_*). One store on
 * the SNES. Registers $2100 to $2133 only. */
void sdevkit_reg_write(uint8_t reg, uint8_t value);

/* Writes a register that takes two bytes in a row, low byte then high byte: the scroll registers
 * (BGnHOFS, BGnVOFS) and mode 7's (M7A..M7Y). */
void sdevkit_reg_write_twice(uint8_t reg, uint16_t value);

/* The registers, by the low byte of their $21xx address */
#define SDEVKIT_PPU_INIDISP 0x00   /* brightness 0..15, or SDEVKIT_FORCED_BLANK */
#define SDEVKIT_PPU_OBSEL   0x01   /* SDEVKIT_OBSEL(sprite tiles, sizes) */
#define SDEVKIT_PPU_OAMADDL 0x02   /* the OAM port (sdevkit_oam_copy sets them) */
#define SDEVKIT_PPU_OAMADDH 0x03
#define SDEVKIT_PPU_OAMDATA 0x04
#define SDEVKIT_PPU_BGMODE  0x05   /* SDEVKIT_BG_MODE1 or _MODE7, SDEVKIT_BG1_TILES_16, ... */
#define SDEVKIT_PPU_MOSAIC  0x06
#define SDEVKIT_PPU_BG1SC   0x07   /* BGnSC = 0x07 + (n - 1): SDEVKIT_BGSC(map, size) */
#define SDEVKIT_PPU_BG2SC   0x08
#define SDEVKIT_PPU_BG3SC   0x09
#define SDEVKIT_PPU_BG4SC   0x0A
#define SDEVKIT_PPU_BG12NBA 0x0B   /* SDEVKIT_BGNBA(tiles of BG1, tiles of BG2) */
#define SDEVKIT_PPU_BG34NBA 0x0C
#define SDEVKIT_PPU_BG1HOFS 0x0D   /* BGnHOFS = 0x0D + 2(n - 1), BGnVOFS = 0x0E + 2(n - 1): written twice */
#define SDEVKIT_PPU_BG1VOFS 0x0E
#define SDEVKIT_PPU_BG2HOFS 0x0F
#define SDEVKIT_PPU_BG2VOFS 0x10
#define SDEVKIT_PPU_BG3HOFS 0x11
#define SDEVKIT_PPU_BG3VOFS 0x12
#define SDEVKIT_PPU_BG4HOFS 0x13
#define SDEVKIT_PPU_BG4VOFS 0x14
#define SDEVKIT_PPU_VMAIN   0x15   /* the VRAM port (the copy functions set them) */
#define SDEVKIT_PPU_VMADDL  0x16
#define SDEVKIT_PPU_VMADDH  0x17
#define SDEVKIT_PPU_VMDATAL 0x18
#define SDEVKIT_PPU_VMDATAH 0x19
#define SDEVKIT_PPU_M7SEL   0x1A   /* SDEVKIT_M7_* */
#define SDEVKIT_PPU_M7A     0x1B   /* M7A..M7D, M7X, M7Y = 0x1B..0x20: written twice */
#define SDEVKIT_PPU_M7B     0x1C
#define SDEVKIT_PPU_M7C     0x1D
#define SDEVKIT_PPU_M7D     0x1E
#define SDEVKIT_PPU_M7X     0x1F
#define SDEVKIT_PPU_M7Y     0x20
#define SDEVKIT_PPU_CGADD   0x21   /* the CGRAM port (sdevkit_cgram_* set them) */
#define SDEVKIT_PPU_CGDATA  0x22
#define SDEVKIT_PPU_W12SEL  0x23
#define SDEVKIT_PPU_W34SEL  0x24
#define SDEVKIT_PPU_WOBJSEL 0x25
#define SDEVKIT_PPU_WH0     0x26   /* window 1 left */
#define SDEVKIT_PPU_WH1     0x27   /* window 1 right */
#define SDEVKIT_PPU_WH2     0x28   /* window 2 left */
#define SDEVKIT_PPU_WH3     0x29   /* window 2 right */
#define SDEVKIT_PPU_WBGLOG  0x2A
#define SDEVKIT_PPU_WOBJLOG 0x2B
#define SDEVKIT_PPU_TM      0x2C   /* the layers on the main screen: SDEVKIT_BG1_ENABLE, ... */
#define SDEVKIT_PPU_TS      0x2D   /* ... on the sub screen */
#define SDEVKIT_PPU_TMW     0x2E
#define SDEVKIT_PPU_TSW     0x2F
#define SDEVKIT_PPU_CGWSEL  0x30   /* colour math: SDEVKIT_CM_* */
#define SDEVKIT_PPU_CGADSUB 0x31
#define SDEVKIT_PPU_COLDATA 0x32   /* the fixed colour: SDEVKIT_CM_APPLY_* | intensity 0..31 */
#define SDEVKIT_PPU_SETINI  0x33

/* INIDISP */
#define SDEVKIT_FORCED_BLANK 0x80  /* the screen off: black, and VRAM, CGRAM and OAM take writes */

/* BGMODE */
#define SDEVKIT_BG_MODE1                1
#define SDEVKIT_BG_MODE7                7    /* one layer (BG1), rotated and scaled (below) */
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
/* COLDATA: which of the fixed colour's components the intensity sets */
#define SDEVKIT_CM_APPLY_BLUE  (1 << 7)
#define SDEVKIT_CM_APPLY_GREEN (1 << 6)
#define SDEVKIT_CM_APPLY_RED   (1 << 5)

/* Mode 7. With SDEVKIT_BG_MODE7 the screen has one background, BG1: a map of 128 x 128 tiles
 * (1024 x 1024 pixels, one byte a tile) and 256 tiles of 8 x 8 pixels with a byte a pixel (an
 * index into all 256 colours of CGRAM, 0 transparent), rotated, scaled and sheared by a matrix.
 * Both fill the first 32 KB of VRAM, the map the low byte of each word and the tiles the high
 * byte (sdevkit_mode7_map_copy, sdevkit_mode7_tiles_copy); nothing else of a game may be there.
 * Sprites stay as in mode 1: those of priority 1 to 3 in front of the layer, priority 0 behind
 * it. A screen pixel (x, y) shows the map pixel
 *     (a b; c d) * (x + hscroll - cx, y + vscroll - cy) + (cx, cy)
 * with a, b, c, d (M7A..M7D) in 8.8 fixed point (0x0100 is 1.0), the centre (cx, cy) = (M7X,
 * M7Y) and the scroll (BG1HOFS, BG1VOFS) in pixels, 13 bits with sign. */
#define SDEVKIT_M7_WRAP        0x00   /* M7SEL: outside the map, it repeats */
#define SDEVKIT_M7_TRANSPARENT 0x80   /* ... outside the map, nothing (the backdrop) */
#define SDEVKIT_M7_TILE0       0xC0   /* ... outside the map, tile 0 everywhere */
#define SDEVKIT_M7_FLIPX       0x01   /* the whole screen mirrored */
#define SDEVKIT_M7_FLIPY       0x02

/* --- Copies into VRAM, CGRAM and OAM ------------------------------------------------------- */
/* Each is one DMA on channel 0, at once: in game_vblank or with the screen off. DMA moves a byte
 * every 8 master cycles; a vertical blank has room for about 5,700 bytes when the game does
 * nothing else in it. The CPU waits while a DMA runs. */

/* The general form: a DMA of so many bytes from src to the register bbad (the low byte of $21xx),
 * in mode dmap (DMAP: 0x00 one register, 0x01 bbad and bbad + 1, 0x08 a fixed source, ...). The
 * game sets the port's address first (sdevkit_reg_write of VMAIN, VMADDL/H, CGADD, OAMADDL/H).
 * bytes 0 copies 64 KB. */
void sdevkit_dma(uint8_t dmap, uint8_t bbad, const void *src, uint16_t bytes);

/* Words to VRAM from word address a, one after the other. */
void sdevkit_vram_copy(uint16_t a, const void *src, uint16_t bytes);

/* Words down a column of a map 32 entries wide: the address steps by 32 words. */
void sdevkit_vram_column_copy(uint16_t a, const void *src, uint16_t bytes);

/* VRAM from word address a filled with value, in both bytes of each word; bytes 0 fills 64 KB,
 * all of VRAM. */
void sdevkit_vram_fill(uint16_t a, uint8_t value, uint16_t bytes);

/* Colours (BGR 5:5:5, two bytes each) to CGRAM from colour i. */
void sdevkit_cgram_copy(uint8_t i, const void *src, uint16_t bytes);

/* One colour, written by the CPU: cheaper than a DMA for one or two. */
void sdevkit_cgram_set(uint8_t i, uint16_t bgr);

/* Bytes of a sprite table (below) to OAM from byte o (even). A game that uses the first n
 * sprites copies 4 * n bytes from 0 and the 32 bytes of the high table from 512, not all 544. */
void sdevkit_oam_copy(uint16_t o, const void *src, uint16_t bytes);

/* Mode 7: map bytes from entry e (row * 128 + column); tile bytes from byte b of the tiles
 * (tile * 64 + row * 8 + column). */
void sdevkit_mode7_map_copy(uint16_t e, const void *src, uint16_t bytes);
void sdevkit_mode7_tiles_copy(uint16_t b, const void *src, uint16_t bytes);

/* Whether a copy of so many bytes still fits into this vertical blank, from the line the picture
 * is at (SNES) or the bytes copied so far (desktop). The two can answer differently for the same
 * game, so a game whose state depends on the answer gets other checksums on each; sdevkit/queue.h
 * decides the same on both. */
uint8_t sdevkit_dma_fits(uint16_t bytes);

/* --- Sprites ---------------------------------------------------------------------------------- */
/* A sprite table in the hardware's format, wherever the game keeps it: 128 entries of 4 bytes (x,
 * y, tile, attributes), then 32 bytes with 2 bits a sprite (bit 8 of x, large). The SNES draws a
 * sprite one line lower than its y. sdevkit_oam_copy brings it into OAM. */
#define SDEVKIT_OAM_BYTES  (128 * 4 + 8 * 4)
#define SDEVKIT_OAM_HIDE_Y 0xE8    /* the y of a hidden sprite: below the picture */
/* Byte 3 of an entry: palette 0..7, priority 0..3, the flips; or it with bit 8 of the tile. */
#define SDEVKIT_OAM_ATTR(palette, priority, hflip, vflip) \
    ((uint8_t)(((vflip) ? 0x80 : 0) | ((hflip) ? 0x40 : 0) | (((priority) & 3) << 4) | (((palette) & 7) << 1)))

/* --- HDMA ------------------------------------------------------------------------------------ */
/* A channel writes data from its table into a PPU register at the start of every line, from the
 * next frame on, while its bit is set by sdevkit_hdma_enable. Channel 0 is the copies'; a game
 * has 1 to 7. The table is read while the frame is drawn, so it must stay valid: a static or ROM
 * array; two tables and switching between them in game_vblank change it without tearing. Table
 * format as on the SNES: a line count byte, then the data; count 1..127 = data once, then hold
 * for that many lines; 129..255 = new data on each of (count - 128) lines; 0 ends the table.
 * Rachael draws the direct modes below; another mode, or indirect HDMA (mode bit 6), is counted
 * as a write she does not draw. */
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

/* Channel 1..7 writes register reg (SDEVKIT_PPU_*) in mode (DMAP: SDEVKIT_HDMA_*) from table. */
void sdevkit_hdma_set(uint8_t channel, uint8_t reg, uint8_t mode, const void *table);

/* HDMAEN: the channels that run, SDEVKIT_HDMA_CHANNELn or'ed. */
void sdevkit_hdma_enable(uint8_t channels);

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
 * picture during the upload. A null bank does nothing. */
void sdevkit_sfx_init(const sdevkit_sfx_bank *bank);
/* Plays an effect: it starts after this tick's game_vblank, when a voice is free or the voice of
 * an effect with a lower priority (or the older one of the same) gives way; otherwise it is
 * dropped. An effect without an active sample, or before sdevkit_sfx_init, does nothing. */
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
 * ExampleChecksumsTest): the bytes named between the end of one game_tick and the end of the next
 * (game_vblank included) are hashed (FNV-1a, 32 bits) in the order named. The kit names nothing
 * itself. Call it on data whose bytes are the same on both: arrays of uint8_t and uint16_t (both
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
