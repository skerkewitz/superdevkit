#ifndef SDEVKIT_SNES_H
#define SDEVKIT_SNES_H

/*
 * The console API of superdevkit (ADR-6F3CJX8T and ADR-6F3CK2AR): how a game draws on the SNES. Every
 * name a game can use starts with sdevkit_ or SDEVKIT_; nothing else of the kit is meant for it.
 *
 * lib/snes implements it on the console, compiled by dampf with the game; lib/rachael
 * (Rachael, the SNES rebuilt in portable C) implements it on the desktop. Every function exists on every platform; lib/README.md
 * lists per function whether it is built or a stub.
 *
 * Three groups of functions:
 *   - PVSnesLib's functions, renamed: the same parameters and meaning as in PVSnesLib (the headers
 *     in pvsneslib/include/snes), sdevkit_ + the name in snake_case (setMode is
 *     sdevkit_set_mode). One deliberate difference: sources are const uint8_t * (ROM data);
 *     PVSnesLib takes u8 *.
 *   - Extensions that PVSnesLib has no function for. On the SNES each is a few register writes.
 *   - sdevkit_dbg_*: for the debug checks on the desktop and the SNES. They exist on every
 *     platform and never change what the game computes.
 *
 * Addresses are SNES addresses: VRAM addresses count 16-bit words (0x0000..0x7FFF), CGRAM
 * addresses count colours (0..255), OAM ids are byte offsets (sprite n = n * 4), as in
 * PVSnesLib.
 *
 * The frame rhythm (what the library's main loop calls) is in sdevkit/loop.h. Code written for
 * the old names converts with tools/sdevkit_rename.py.
 */

#include <stdint.h>

/* --- Colours (video.h) -------------------------------------------------------- */
#define SDEVKIT_RGB5(r, g, b) ((uint16_t)((r) | ((g) << 5) | ((b) << 10)))
#define SDEVKIT_RGB8(r, g, b) ((uint16_t)(((r) >> 3) | (((g) >> 3) << 5) | (((b) >> 3) << 10)))

/* --- Modes and layers (background.h) -------------------------------------------- */
#define SDEVKIT_BG_MODE1                1
#define SDEVKIT_BG_MODE7                7    /* one layer (BG1), rotated and scaled: sdevkit_mode7_* */
#define SDEVKIT_BG3_MODE1_PRIORITY_HIGH (1 << 3)   /* BG3 tiles with priority 1 above everything */
#define SDEVKIT_BG1_ENABLE (1 << 0)
#define SDEVKIT_BG2_ENABLE (1 << 1)
#define SDEVKIT_BG3_ENABLE (1 << 2)
#define SDEVKIT_BG4_ENABLE (1 << 3)
#define SDEVKIT_OBJ_ENABLE (1 << 4)

#define SDEVKIT_SC_32x32 0
#define SDEVKIT_SC_64x32 1
#define SDEVKIT_SC_32x64 2
#define SDEVKIT_SC_64x64 3

#define SDEVKIT_BG_4COLORS  4
#define SDEVKIT_BG_16COLORS 16

/* Background map entries */
#define SDEVKIT_BG_TIL_NUM(n)  ((uint16_t)(n))          /* 0..1023 */
#define SDEVKIT_BG_TIL_PAL(n)  ((uint16_t)((n) << 10))  /* 0..7 */
#define SDEVKIT_BG_TIL_PRIO    ((uint16_t)(1 << 13))
#define SDEVKIT_BG_TIL_FLIPX   ((uint16_t)(1 << 14))
#define SDEVKIT_BG_TIL_FLIPY   ((uint16_t)(1 << 15))

/* --- Sprites (sprite.h) ---------------------------------------------------------- */
#define SDEVKIT_OBJ_SIZE8_L16  (0 << 5)
#define SDEVKIT_OBJ_SIZE8_L32  (1 << 5)
#define SDEVKIT_OBJ_SIZE8_L64  (2 << 5)
#define SDEVKIT_OBJ_SIZE16_L32 (3 << 5)
#define SDEVKIT_OBJ_SIZE16_L64 (4 << 5)
#define SDEVKIT_OBJ_SIZE32_L64 (5 << 5)
#define SDEVKIT_OBJ_SMALL 0
#define SDEVKIT_OBJ_LARGE 1
#define SDEVKIT_OBJ_SHOW  0
#define SDEVKIT_OBJ_HIDE  1
#define SDEVKIT_ATTR2_DISABLED 0xE8                /* y of a hidden sprite */

/* The OAM shadow: sdevkit_oam_set() and friends write here; the vertical blank
 * copies it into the PPU's OAM (Rachael's vertical blank on the PC). 128 entries of
 * 4 bytes, then 32 bytes with 2 bits per sprite (x bit 8, size). */
extern uint8_t sdevkit_oam_memory[128 * 4 + 8 * 4];

/* --- Colour math (video.h) -------------------------------------------------------- */
/* CGWSEL (sdevkit_set_color_effect's first value) */
#define SDEVKIT_CM_SUBBGOBJ_ENABLE  (1 << 1)       /* add/subtract the sub screen, not the fixed colour */
#define SDEVKIT_CM_ALWAYS_ENABLE    (0 << 4)       /* colour math: everywhere ... */
#define SDEVKIT_CM_MATWIN_ENABLE    (1 << 4)       /* ... only inside the colour window */
#define SDEVKIT_CM_NOMATWIN_ENABLE  (2 << 4)       /* ... only outside the colour window */
#define SDEVKIT_CM_NEVER_ENABLE     (3 << 4)
#define SDEVKIT_CM_SCBLK_ALWAYS_ENABLE   (0 << 6)  /* main screen black: never */
#define SDEVKIT_CM_SCBLK_MATWIN_ENABLE   (1 << 6)  /* ... outside the colour window */
#define SDEVKIT_CM_SCBLK_NOMATWIN_ENABLE (2 << 6)  /* ... inside the colour window */
#define SDEVKIT_CM_SCBLK_NEVER_ENABLE    (3 << 6)  /* ... always */
/* CGADSUB (sdevkit_set_color_effect's second value) */
#define SDEVKIT_CM_SUB_MAINSUB (1 << 7)            /* subtract instead of add */
#define SDEVKIT_CM_DIV2_DIVIDE (1 << 6)            /* halve the result */
#define SDEVKIT_CM_MSCR_BACK   (1 << 5)            /* colour math on the backdrop ... */
#define SDEVKIT_CM_MSCR_PAL47  (1 << 4)            /* ... on sprites with palettes 4..7 */
#define SDEVKIT_CM_MSCR_BG4    (1 << 3)
#define SDEVKIT_CM_MSCR_BG3    (1 << 2)
#define SDEVKIT_CM_MSCR_BG2    (1 << 1)
#define SDEVKIT_CM_MSCR_BG1    (1 << 0)
/* COLDATA channels (sdevkit_set_color_intensity) */
#define SDEVKIT_CM_APPLY_BLUE  (1 << 7)
#define SDEVKIT_CM_APPLY_GREEN (1 << 6)
#define SDEVKIT_CM_APPLY_RED   (1 << 5)

/* --- HDMA (dma.h) ---------------------------------------------------------------- */
#define SDEVKIT_HDMA_CHANNEL0 (1 << 0)
#define SDEVKIT_HDMA_CHANNEL1 (1 << 1)
#define SDEVKIT_HDMA_CHANNEL2 (1 << 2)
#define SDEVKIT_HDMA_CHANNEL3 (1 << 3)
#define SDEVKIT_HDMA_CHANNEL4 (1 << 4)
#define SDEVKIT_HDMA_CHANNEL5 (1 << 5)
#define SDEVKIT_HDMA_CHANNEL6 (1 << 6)
#define SDEVKIT_HDMA_CHANNEL7 (1 << 7)

/* --- PPU registers (low byte of $21xx), for sdevkit_reg_write and HDMA ------------------ */
#define SDEVKIT_PPU_INIDISP 0x00
#define SDEVKIT_PPU_OBSEL   0x01
#define SDEVKIT_PPU_BGMODE  0x05
#define SDEVKIT_PPU_BG1SC   0x07
#define SDEVKIT_PPU_BG2SC   0x08
#define SDEVKIT_PPU_BG3SC   0x09
#define SDEVKIT_PPU_BG4SC   0x0A
#define SDEVKIT_PPU_BG12NBA 0x0B
#define SDEVKIT_PPU_BG34NBA 0x0C
#define SDEVKIT_PPU_BG1HOFS 0x0D   /* BGnHOFS = 0x0D + 2(n-1), BGnVOFS = 0x0E + 2(n-1); written twice, low then high */
#define SDEVKIT_PPU_BG1VOFS 0x0E
#define SDEVKIT_PPU_M7SEL   0x1A   /* mode 7: written by sdevkit_mode7_set_select */
#define SDEVKIT_PPU_M7A     0x1B   /* M7A..M7D, M7X, M7Y = 0x1B..0x20: written twice, low then high */
#define SDEVKIT_PPU_M7B     0x1C
#define SDEVKIT_PPU_M7C     0x1D
#define SDEVKIT_PPU_M7D     0x1E
#define SDEVKIT_PPU_M7X     0x1F
#define SDEVKIT_PPU_M7Y     0x20
#define SDEVKIT_PPU_W12SEL  0x23
#define SDEVKIT_PPU_W34SEL  0x24
#define SDEVKIT_PPU_WOBJSEL 0x25
#define SDEVKIT_PPU_WH0     0x26   /* window 1 left */
#define SDEVKIT_PPU_WH1     0x27   /* window 1 right */
#define SDEVKIT_PPU_WH2     0x28   /* window 2 left */
#define SDEVKIT_PPU_WH3     0x29   /* window 2 right */
#define SDEVKIT_PPU_WBGLOG  0x2A
#define SDEVKIT_PPU_WOBJLOG 0x2B
#define SDEVKIT_PPU_TM      0x2C
#define SDEVKIT_PPU_TS      0x2D
#define SDEVKIT_PPU_TMW     0x2E
#define SDEVKIT_PPU_TSW     0x2F
#define SDEVKIT_PPU_CGWSEL  0x30
#define SDEVKIT_PPU_CGADSUB 0x31
#define SDEVKIT_PPU_COLDATA 0x32

/* --- PVSnesLib's functions, renamed (ADR-6F3CK2AR) ------------------------------------ */
/* video.h */
void sdevkit_set_mode(uint8_t mode, uint8_t size);            /* BGMODE; shows BG1..BG3 and sprites on the main screen */
void sdevkit_set_brightness(uint8_t level);              /* 0: screen off (forced blank), 1..15: on, that bright */
void sdevkit_set_screen_on(void);                    /* PVSnesLib waits for a vertical blank here: never call it in one */
void sdevkit_set_screen_off(void);                   /* forced blank: black */
void sdevkit_set_color_effect(uint8_t colorMathA, uint8_t colorMathB);    /* CGWSEL, CGADSUB */
void sdevkit_set_color_intensity(uint8_t colorApply, uint8_t intensity);  /* COLDATA: the fixed colour */
void sdevkit_set_palette_color(uint16_t paletteEntry, uint16_t paletteColor);
/* dma.h */
void sdevkit_dma_copy_vram(const uint8_t *source, uint16_t address, uint16_t size);    /* size in bytes */
void sdevkit_dma_copy_cgram(const uint8_t *source, uint16_t address, uint16_t size);   /* size in bytes */
void sdevkit_dma_clear_vram(void);
/* background.h */
void sdevkit_bg_set_gfx_ptr(uint8_t bgNumber, uint16_t address);             /* bgNumber 0..3; 4K-word steps */
void sdevkit_bg_set_map_ptr(uint8_t bgNumber, uint16_t address, uint8_t mapSize); /* 1K-word steps; SDEVKIT_SC_* */
void sdevkit_bg_set_scroll(uint8_t bgNumber, uint16_t x, uint16_t y);
void sdevkit_bg_set_enable(uint8_t bgNumber);
void sdevkit_bg_set_disable(uint8_t bgNumber);
void sdevkit_bg_set_enable_sub(uint8_t bgNumber);
void sdevkit_bg_set_disable_sub(uint8_t bgNumber);
void sdevkit_bg_init_tile_set(uint8_t bgNumber, const uint8_t *tileSource, const uint8_t *tilePalette, uint8_t paletteEntry, uint16_t tileSize,
                   uint16_t paletteSize, uint16_t colorMode, uint16_t address);
void sdevkit_bg_init_map_set(uint8_t bgNumber, const uint8_t *mapSource, uint16_t mapSize, uint8_t sizeMode, uint16_t address);
/* sprite.h */
void sdevkit_oam_init(void);
void sdevkit_oam_init_gfx_set(const uint8_t *tileSource, uint16_t tileSize, const uint8_t *tilePalette, uint16_t paletteSize, uint8_t tilePaletteNumber,
                   uint16_t address, uint8_t oamsize);
void sdevkit_oam_init_gfx_attr(uint16_t address, uint8_t oamsize);
void sdevkit_oam_set(uint16_t id, uint16_t xspr, uint16_t yspr, uint8_t priority, uint8_t hflip, uint8_t vflip, uint16_t gfxoffset, uint8_t paletteoffset);
void sdevkit_oam_set_xy(uint16_t id, uint16_t xspr, uint16_t yspr);
void sdevkit_oam_set_ex(uint16_t id, uint8_t size, uint8_t hide);
void sdevkit_oam_set_visible(uint16_t id, uint8_t hide);
void sdevkit_oam_clear(uint16_t first, uint8_t numEntries);   /* hides numEntries sprites from id first; 0 = all */

/* --- Extensions (no PVSnesLib function; a few register writes on the SNES) ----------- */
/* Writes a PPU register (low byte of its $21xx address, SDEVKIT_PPU_*). PVSnesLib
 * programs write the REG_* variables for this. */
void sdevkit_reg_write(uint8_t reg, uint8_t value);
/* HDMA in direct mode: from the next frame on, the channel writes table
 * data into reg at the start of every line; twoRegs is the transfer mode:
 * SDEVKIT_HDMA_ONE one byte to reg, SDEVKIT_HDMA_TWO_REGS two bytes to reg and
 * reg + 1, SDEVKIT_HDMA_TWICE two bytes to reg (the scroll registers, which are
 * written twice: a wave down the screen with BG1HOFS).
 * Table format as on the SNES: a line count byte, then the data; count
 * 1..127 = data once, then hold for that many lines; 129..255 = new data on
 * each of (count - 128) lines; 0 ends the table. The table is read when the
 * frame is drawn, so it must stay valid (a static or ROM array). channel is a number, 1 to 7:
 * general DMA uses channel 0. sdevkit_hdma_enable takes a mask of SDEVKIT_HDMA_CHANNELn. */
#define SDEVKIT_HDMA_ONE      0
#define SDEVKIT_HDMA_TWO_REGS 1
#define SDEVKIT_HDMA_TWICE    2
void sdevkit_hdma_set(uint8_t channel, uint8_t reg, uint8_t twoRegs, const uint8_t *table);
void sdevkit_hdma_enable(uint8_t channels);          /* HDMAEN: which channels run */
/* 1 if a copy of this many bytes to VRAM or CGRAM still ends inside the
 * current vertical blank. On the SNES it reads the line counter, so the code
 * around the copies counts too; with the screen off the caller does not ask.
 * What does not fit waits for the next vertical blank instead of being lost. */
uint8_t   sdevkit_dma_fits(uint16_t bytes);
/* Like sdevkit_dma_copy_vram, but the VRAM address steps by 32 words after each word: the words
 * go down a column of a 32 x 32 map, which is how a game that scrolls sideways writes the column
 * coming into view. size in bytes; a column of a 32 x 32 map is at most 64. */
void sdevkit_dma_copy_vram_column(const uint8_t *source, uint16_t address, uint16_t size);

/* --- Mode 7 (sdevkit_mode7_*) ------------------------------------------------------------ */
/* With sdevkit_set_mode(SDEVKIT_BG_MODE7, 0) the screen has one background, BG1: a map of
 * 128 x 128 tiles (1024 x 1024 pixels, one byte a tile) and 256 tiles of 8 x 8 pixels with a
 * byte a pixel (an index into all 256 colours of CGRAM, 0 transparent), rotated, scaled and
 * sheared by a matrix. Both fill the first 32 KB of VRAM, the map the low byte of each word and
 * the tiles the high byte; nothing else of a game may be there. Sprites stay as in mode 1: those
 * of priority 1 to 3 in front of the layer, priority 0 behind it.
 * A screen pixel (x, y) shows the map pixel
 *     (a b; c d) * (x + hscroll - cx, y + vscroll - cy) + (cx, cy)
 * with a, b, c, d in 8.8 fixed point (0x0100 is 1.0) and the centre (cx, cy) and the scroll
 * (sdevkit_bg_set_scroll(0, ...)) in pixels, 13 bits with sign. */
#define SDEVKIT_M7_WRAP        0x00   /* sdevkit_mode7_set_select: outside the map, it repeats */
#define SDEVKIT_M7_TRANSPARENT 0x80   /* ... outside the map, nothing (the backdrop) */
#define SDEVKIT_M7_TILE0       0xC0   /* ... outside the map, tile 0 everywhere */
#define SDEVKIT_M7_FLIPX       0x01   /* the whole screen mirrored */
#define SDEVKIT_M7_FLIPY       0x02
/* Map bytes from map entry address on (entry = row * 128 + column), tile bytes from byte address
 * of the tiles (tile * 64 + row * 8 + column). DMA: in a vertical blank or with the screen off. */
void sdevkit_mode7_load_map(const uint8_t *map, uint16_t address, uint16_t size);
void sdevkit_mode7_load_tiles(const uint8_t *tiles, uint16_t address, uint16_t size);
void sdevkit_mode7_set_matrix(int16_t a, int16_t b, int16_t c, int16_t d);
void sdevkit_mode7_set_center(int16_t x, int16_t y);
void sdevkit_mode7_set_select(uint8_t select);       /* SDEVKIT_M7_*: outside the map, flips */

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
/* Loads the bank's samples into the audio RAM and starts the sound: once, in game_init (the screen
 * is off). On the SNES it waits for the sound CPU's boot code and sends every byte through it,
 * about 350 bytes a frame (docs/sound.md). A null bank does nothing. */
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
