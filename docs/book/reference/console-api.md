<!-- GENERATED from doc-specs/reference/console-api.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# The console API

A game reaches the console only through the console API of the
[dev kit](/glossary#dev-kit), and the API exists on every platform: on the SNES, `lib/snes` (the
[platform layer](/glossary#platform-layer), compiled by dampf with the game) implements every
function, and on the desktop and the web [Rachael](/glossary#rachael) implements the same
functions. Every function exists on both, so a game needs no `#ifdef` for the platform; the few
that do nothing on the SNES yet (music, the debug view) say so in their entries. The desktop and
the web builds check that a game calls nothing else, since Rachael cannot see a write that goes
around her. The API is two headers in `<home>/lib/include`, where `<home>` is the
[superdevkit home](/glossary#superdevkit-home): `sdevkit/loop.h` for the frame rhythm and
`sdevkit/snes.h` for everything else. `superdevkit build` passes `-I <home>/lib/include` to the
compiler of every [target](/glossary#target) and builds the game together with the target's
library (see [The superdevkit command](/reference/superdevkit#build)); the game includes both
headers:

```c
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
```

Every name a game can use starts with `sdevkit_` or `SDEVKIT_`; the three functions a game
defines start with `game_`, and no name of the kit does. The headers are the final word on every
declaration below. Addresses are SNES addresses throughout: [VRAM](/glossary#vram) addresses
count 16-bit words (`$0000` to `$7FFF`), [CGRAM](/glossary#cgram) addresses count colours (0 to
255), and sprite ids are byte offsets into the sprite table (sprite *n* is `n * 4`), as in
[PVSnesLib](/glossary#pvsneslib), whose functions the API takes over under new names.

The cost of each function is given in CPU cycles on the SNES, with the calls it makes, from the
[timing report](/glossary#timing-report) of a program that calls every function once with zero
for each argument. Where an entry gives a range, such as 57 to 169, it is the best and the worst
path the timing report finds through the function's code, not a cost for particular arguments.
dampf compiles the library together with the game as a
[whole program](/glossary#whole-program), so the figures in your own game's timing report can
differ a little. On Rachael none of this applies: she has no time of her own, and the game runs
at the PC's speed.

## The frame rhythm

Every game defines three functions, declared in `sdevkit/loop.h`, and the library's main loop
calls them:

```c
void game_init(uint16_t seed);
void game_tick(const uint16_t pad[SDEVKIT_PADS]);
void game_vblank(void);
```

The main loop calls `game_init` once, with the screen off. This is the place for the large
copies: tiles, maps and palettes into VRAM and CGRAM, since with the screen off they may take as
long as they need. Before it returns it switches the screen on, usually with
`sdevkit_set_brightness(15)`. The value of `seed` is 0, unless a desktop build is started with
`--seed N` ([The desktop](/guide/the-desktop#debugging-on-the-pc)).

Once per [frame](/glossary#frame), while the picture is drawn, the main loop calls `game_tick`:
the game logic of one [tick](/glossary#tick). It prepares what the next
[vertical blank](/glossary#vertical-blank) is to show: the sprite table, maps in RAM that are to
be uploaded, new scroll values. It must not copy anything into VRAM or CGRAM, because the
[PPU](/glossary#ppu) takes no new data while it draws. Before each tick the main loop reads both
[joypads](/glossary#joypad) and hands them over in `pad`, `pad[0]` for port 1 and `pad[1]` for
port 2 (`SDEVKIT_PADS` is 2).

In the vertical blank after each tick the main loop calls `game_vblank`. By then it has copied
the sprite table (`sdevkit_oam_memory`, 544 bytes) into [OAM](/glossary#oam), so `game_vblank`
does the rest of the uploads: the [DMA](/glossary#dma) copies to VRAM and CGRAM that the tick
prepared, and the scroll registers. After `game_vblank` the main loop starts the sound effects
the tick asked for (see [Sound effects](#sound-effects)), so that they take none of the vertical
blank.

Between `game_tick` and `game_vblank` the main loop waits for the start of the next vertical
blank. A tick that ends inside a vertical blank waits for the next one, so the uploads always
get a whole vertical blank. A tick that takes longer than a frame makes the loop wait for the
next vertical blank after it: the game runs slower, never wrong, and it is never interrupted.
There is no interrupt handler on the SNES, because dampf keeps the
[frame of a function](/glossary#frame) at a fixed place, and C code called from an interrupt in
the middle of a tick would overwrite the frames of the code it interrupted. On Rachael the
frontend gives the same rhythm, and a tick takes as long as it takes on the PC.

Before `game_init` every game shows the kit's splash screen, on the SNES and on Rachael alike:
the logo fades in from black, stays three seconds and fades out. A button pressed while it fades
in or stays ends it as soon as it has faded in. The main loop then puts the console
back into its state at power-on, the screen off, so `game_init` finds what it would find without
the splash.

The pads are in the SNES's bit layout; a bit is set while its button is held.

| Constant | Value |
| --- | --- |
| `SDEVKIT_KEY_B` | `0x8000u` |
| `SDEVKIT_KEY_Y` | `0x4000u` |
| `SDEVKIT_KEY_SELECT` | `0x2000u` |
| `SDEVKIT_KEY_START` | `0x1000u` |
| `SDEVKIT_KEY_UP` | `0x0800u` |
| `SDEVKIT_KEY_DOWN` | `0x0400u` |
| `SDEVKIT_KEY_LEFT` | `0x0200u` |
| `SDEVKIT_KEY_RIGHT` | `0x0100u` |
| `SDEVKIT_KEY_A` | `0x0080u` |
| `SDEVKIT_KEY_X` | `0x0040u` |
| `SDEVKIT_KEY_L` | `0x0020u` |
| `SDEVKIT_KEY_R` | `0x0010u` |

The program below is `examples/hello/src/hello.c`, cut down: text in a background map, and a
ball that moves left and right with the joypad and has its x position printed under it. It shows
the division of the work: `game_init` copies at once because the screen is off, `game_tick`
changes only the map in RAM and the sprite table, and `game_vblank` copies the map when a tick
changed it. Since `print_number` writes the number every tick, that is every frame here.

```c [examples/hello/src/hello.c]
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "font_gfx.h"
#include "ball_gfx.h"

#define VRAM_FONT    0x0000    /* background tiles, in 16-bit words */
#define VRAM_MAP     0x1000    /* the background map: 32 x 32 entries */
#define VRAM_SPRITES 0x4000    /* sprite tiles */
#define TILE_FONT    32        /* the first character of assets/font_gfx.png */

static const char FONT[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-:.";

static uint16_t map[32 * 32];  /* the background map in RAM; copied in the vertical blank */
static uint8_t map_changed;
static int16_t ball_x = 120;
static int16_t ball_y = 104;

/* Writes text into the map, at column x and row y (in tiles of 8 x 8 pixels). */
static void print(uint8_t x, uint8_t y, const char *s)
{
    while (*s != 0) {
        uint8_t i = 0;
        while (FONT[i] != 0 && FONT[i] != *s) {
            i++;
        }
        map[(uint16_t)y * 32 + x] = (uint16_t)(TILE_FONT + (FONT[i] != 0 ? i : 0));
        x++;
        s++;
    }
    map_changed = 1;
}

/* Writes a number of three digits. */
static void print_number(uint8_t x, uint8_t y, uint16_t n)
{
    char digits[4];
    digits[0] = (char)('0' + n / 100 % 10);
    digits[1] = (char)('0' + n / 10 % 10);
    digits[2] = (char)('0' + n % 10);
    digits[3] = 0;
    print(x, y, digits);
}

void game_init(uint16_t seed)
{
    (void)seed;
    sdevkit_set_mode(SDEVKIT_BG_MODE1, 0);
    sdevkit_bg_set_disable(1);                         /* only the first layer is used */
    sdevkit_bg_set_disable(2);
    sdevkit_bg_init_tile_set(0, font_gfx_tiles, font_gfx_pal, 0, FONT_GFX_TILES_SIZE,
                             FONT_GFX_PAL_SIZE, SDEVKIT_BG_16COLORS, VRAM_FONT);
    sdevkit_bg_set_map_ptr(0, VRAM_MAP, SDEVKIT_SC_32x32);
    sdevkit_bg_set_scroll(0, 0, 0x3FF);                /* the map's first line on the screen's first line */
    sdevkit_oam_init_gfx_set(ball_gfx_tiles, BALL_GFX_TILES_SIZE, ball_gfx_pal, BALL_GFX_PAL_SIZE,
                             0, VRAM_SPRITES, SDEVKIT_OBJ_SIZE16_L32);
    sdevkit_oam_init();
    print(10, 6, "HELLO SNES!");
    print(10, 24, "X:");
    sdevkit_dma_copy_vram((const uint8_t *)map, VRAM_MAP, sizeof map);   /* the screen is off: copy at once */
    map_changed = 0;
    sdevkit_set_brightness(15);                        /* the screen on, at full brightness */
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    if ((pad[0] & SDEVKIT_KEY_LEFT) && ball_x > 0) {
        ball_x -= 2;
    }
    if ((pad[0] & SDEVKIT_KEY_RIGHT) && ball_x < 240) {
        ball_x += 2;
    }
    print_number(12, 24, (uint16_t)ball_x);
    /* sprite 0: one line higher, as the SNES draws sprites one line lower; priority 3,
     * no flips, tile 0, palette 0; small (16 x 16), shown */
    sdevkit_oam_set(0, (uint16_t)ball_x, (uint16_t)(ball_y - 1), 3, 0, 0, 0, 0);
    sdevkit_oam_set_ex(0, SDEVKIT_OBJ_SMALL, SDEVKIT_OBJ_SHOW);
}

void game_vblank(void)
{
    if (map_changed) {
        sdevkit_dma_copy_vram((const uint8_t *)map, VRAM_MAP, sizeof map);
        map_changed = 0;
    }
}
```

The headers `font_gfx.h` and `ball_gfx.h` are written by the converter from the example's PNG
files; [Graphics and sound](/guide/graphics-and-sound) shows how. The whole example, with its
[project file](/glossary#project-file), is in `examples/hello`; it also moves the ball up and
down and prints its y position.

## Screen and colours

A colour is a 16-bit value with 5 bits each of red, green and blue, blue in the high bits. Two
macros build one: `SDEVKIT_RGB5(r, g, b)` from values 0 to 31, and `SDEVKIT_RGB8(r, g, b)` from
values 0 to 255, of which it keeps the top 5 bits.

### `sdevkit_set_mode`

```c
void sdevkit_set_mode(uint8_t mode, uint8_t size);
```

Sets the background mode (register `BGMODE` gets `mode | size`) and shows BG1, BG2, BG3 and the
sprites on the main screen (`TM`). Use `SDEVKIT_BG_MODE1` or `SDEVKIT_BG_MODE7` for `mode`;
`size` is 0 or `SDEVKIT_BG3_MODE1_PRIORITY_HIGH`, which puts the BG3 tiles of priority 1 above
everything. Since it switches the three layers on, disable the layers a game does not use after
it. Which modes the kit draws is in
[The SNES hardware](/guide/snes-hardware#what-the-dev-kit-has). Cost: 45 cycles.

### `sdevkit_set_brightness`

```c
void sdevkit_set_brightness(uint8_t level);
```

Writes `INIDISP`: 0 switches the screen off (forced blank: the PPU draws nothing, and the
picture is black), 1 to 15 switch it on with that brightness. Cost: 20 cycles.

### `sdevkit_set_screen_on`

```c
void sdevkit_set_screen_on(void);
```

Waits for the start of a vertical blank, as PVSnesLib's function does, then switches the screen
on at full brightness. Never call it in a vertical blank, so not in `game_vblank`: it would wait
for that one to end and the next to begin. Cost: 52 cycles to unbounded, since the wait has no
bound the timing report can see. On Rachael it switches the screen on at once.

### `sdevkit_set_screen_off`

```c
void sdevkit_set_screen_off(void);
```

Switches the screen off (forced blank): the picture is black, and VRAM and CGRAM take copies of
any size. Cost: 20 cycles.

### `sdevkit_set_palette_color`

```c
void sdevkit_set_palette_color(uint16_t paletteEntry, uint16_t paletteColor);
```

Writes one colour into CGRAM: `paletteEntry` is the CGRAM address (0 to 255), `paletteColor` a
value from `SDEVKIT_RGB5` or `SDEVKIT_RGB8`. It goes through the CGRAM port (`CGADD`, `CGDATA`),
so like a copy it belongs in `game_vblank` or into a time when the screen is off. Cost: 47
cycles.

## Backgrounds

A background layer is numbered 0 to 3 in `bgNumber` (BG1 to BG4). Its tiles and its map lie in
VRAM; the functions below say where, upload them and scroll the layer. The PPU's registers cannot
be read back, so the library keeps copies of those it changes bit by bit (the tile bases, the
layers on the main screen and on the sub screen, a second picture of the layers that colour math
can combine with the main one).

The functions that show and hide a layer take its number, 0 to 3, not a mask. The masks
`SDEVKIT_BG1_ENABLE` to `SDEVKIT_BG4_ENABLE` and `SDEVKIT_OBJ_ENABLE` (the sprites) are the bits
of the registers `TM` and `TS`, for a game that writes those with `sdevkit_reg_write`:

```c
sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE | SDEVKIT_OBJ_ENABLE);
```

The show and hide functions start from the library's own copy of `TM` and `TS`, on the SNES and
on Rachael alike, so the next of them undoes such a write. Use one way or the other for a
register, not both.

The map is an array of 16-bit entries, each built from these macros, joined with `|`:

| Macro | Bits |
| --- | --- |
| `SDEVKIT_BG_TIL_NUM(n)` | the tile, 0 to 1023 |
| `SDEVKIT_BG_TIL_PAL(n)` | the palette, 0 to 7 |
| `SDEVKIT_BG_TIL_PRIO` | priority 1 |
| `SDEVKIT_BG_TIL_FLIPX` | mirrored left to right |
| `SDEVKIT_BG_TIL_FLIPY` | mirrored top to bottom |

```c
uint16_t entry = SDEVKIT_BG_TIL_NUM(32) | SDEVKIT_BG_TIL_PAL(1) | SDEVKIT_BG_TIL_FLIPX;
```

The size of a map in tiles is one of `SDEVKIT_SC_32x32` (0), `SDEVKIT_SC_64x32` (1),
`SDEVKIT_SC_32x64` (2) and `SDEVKIT_SC_64x64` (3). The number of colours of a layer's tiles is
`SDEVKIT_BG_16COLORS` or `SDEVKIT_BG_4COLORS`. The rules for VRAM and the layers are in
[The SNES hardware](/guide/snes-hardware#video-memory).

### `sdevkit_bg_set_gfx_ptr`

```c
void sdevkit_bg_set_gfx_ptr(uint8_t bgNumber, uint16_t address);
```

Sets where the layer's tiles start in VRAM, in steps of 4K words (`BG12NBA`, `BG34NBA`). Cost:
75 cycles.

### `sdevkit_bg_set_map_ptr`

```c
void sdevkit_bg_set_map_ptr(uint8_t bgNumber, uint16_t address, uint8_t mapSize);
```

Sets where the layer's map starts in VRAM, in steps of 1K words, and its size, one of the
`SDEVKIT_SC_*` values (`BG1SC` to `BG4SC`). Cost: 35 cycles.

### `sdevkit_bg_set_scroll`

```c
void sdevkit_bg_set_scroll(uint8_t bgNumber, uint16_t x, uint16_t y);
```

Scrolls the layer: the map pixel at (`x`, `y`) is at the top left corner of the screen
(`BGnHOFS`, `BGnVOFS`). The SNES shows a background one line lower than its scroll says, so
`examples/hello` passes `y` = `0x3FF` to put the map's first line on the screen's first line.
It writes the registers at once. While the game runs, `sdevkit/loop.h` puts the scroll registers
into `game_vblank`; `examples/hello` sets its scroll once in `game_init`, with the screen off.
Cost: 78 cycles.

### `sdevkit_bg_set_enable`

```c
void sdevkit_bg_set_enable(uint8_t bgNumber);
```

Shows the layer on the main screen (`TM`). Cost: 57 to 169 cycles.

### `sdevkit_bg_set_disable`

```c
void sdevkit_bg_set_disable(uint8_t bgNumber);
```

Hides the layer on the main screen (`TM`). Cost: 60 to 172 cycles.

### `sdevkit_bg_set_enable_sub`

```c
void sdevkit_bg_set_enable_sub(uint8_t bgNumber);
```

Shows the layer on the sub screen (`TS`), which colour math combines with the main screen.
Cost: 57 to 169 cycles.

### `sdevkit_bg_set_disable_sub`

```c
void sdevkit_bg_set_disable_sub(uint8_t bgNumber);
```

Hides the layer on the sub screen (`TS`). Cost: 60 to 172 cycles.

### `sdevkit_bg_init_tile_set`

```c
void sdevkit_bg_init_tile_set(uint8_t bgNumber, const uint8_t *tileSource, const uint8_t *tilePalette, uint8_t paletteEntry, uint16_t tileSize,
                   uint16_t paletteSize, uint16_t colorMode, uint16_t address);
```

Copies `tileSize` bytes of tiles to VRAM word `address`, copies `paletteSize` bytes of palette
to CGRAM from colour `paletteEntry * colorMode`, and sets the layer's tile base to `address`.
`paletteEntry` is the number of the palette, and `colorMode` is `SDEVKIT_BG_16COLORS` or
`SDEVKIT_BG_4COLORS`. It makes two DMA copies, so call it with the screen off or in
`game_vblank`. Cost: 301 cycles, and `tileSize + paletteSize` bytes of the vertical blank.

### `sdevkit_bg_init_map_set`

```c
void sdevkit_bg_init_map_set(uint8_t bgNumber, const uint8_t *mapSource, uint16_t mapSize, uint8_t sizeMode, uint16_t address);
```

Copies `mapSize` bytes of map to VRAM word `address`, and sets the layer's map there with size
`sizeMode` (`SDEVKIT_SC_*`). Like every copy, with the screen off or in `game_vblank`. Cost: 145
cycles, and `mapSize` bytes of the vertical blank.

## Sprites

The sprite functions write the sprite table in RAM, `sdevkit_oam_memory`, never the PPU's OAM
itself; the main loop copies the table into OAM in every vertical blank, before `game_vblank`.
So they may be called at any time, and belong in `game_tick`. A sprite is named by its byte
offset in the table, `id` = sprite number times 4. What the PPU draws per line, and what it
drops, is in [The SNES hardware](/guide/snes-hardware#sprites).

```c
extern uint8_t sdevkit_oam_memory[128 * 4 + 8 * 4];
```

The table has 128 entries of 4 bytes, then 32 bytes with 2 bits per sprite: bit 8 of x and the
size. After every `game_tick` the main loop also names the table to `sdevkit_dbg_hash`, last, so
it is part of every tick's checksum.

The two sprite sizes of the screen are set together, as a pair of a small and a large size:
`SDEVKIT_OBJ_SIZE8_L16`, `SDEVKIT_OBJ_SIZE8_L32`, `SDEVKIT_OBJ_SIZE8_L64`,
`SDEVKIT_OBJ_SIZE16_L32`, `SDEVKIT_OBJ_SIZE16_L64` and `SDEVKIT_OBJ_SIZE32_L64`
(`SDEVKIT_OBJ_SIZE16_L32` is 16 x 16 pixels small and 32 x 32 large). Each sprite picks one with
`SDEVKIT_OBJ_SMALL` or `SDEVKIT_OBJ_LARGE`. A hidden sprite has y `SDEVKIT_ATTR2_DISABLED`
(`0xE8`), below the picture.

### `sdevkit_oam_init`

```c
void sdevkit_oam_init(void);
```

Clears the whole table: every sprite at x 0, hidden and small. It is `sdevkit_oam_clear(0, 0)`.
Cost: 23 to 102,679 cycles. The upper figure is the timing report's bound for the loop over all
128 sprites, and all 128 are what this call clears.

### `sdevkit_oam_init_gfx_set`

```c
void sdevkit_oam_init_gfx_set(const uint8_t *tileSource, uint16_t tileSize, const uint8_t *tilePalette, uint16_t paletteSize, uint8_t tilePaletteNumber,
                   uint16_t address, uint8_t oamsize);
```

Copies `tileSize` bytes of sprite tiles to VRAM word `address`, copies `paletteSize` bytes of
palette to CGRAM from colour `128 + tilePaletteNumber * 16` (the sprite palettes are the upper
half of CGRAM), and sets the sprite tile base and sizes as `sdevkit_oam_init_gfx_attr` does. It
makes two DMA copies: with the screen off or in `game_vblank`. Cost: 250 cycles, and
`tileSize + paletteSize` bytes of the vertical blank.

### `sdevkit_oam_init_gfx_attr`

```c
void sdevkit_oam_init_gfx_attr(uint16_t address, uint8_t oamsize);
```

Sets where the sprite tiles start in VRAM, in steps of 8K words, and the pair of sprite sizes,
one of the `SDEVKIT_OBJ_SIZE*` values (`OBSEL`). Cost: 41 cycles.

### `sdevkit_oam_set`

```c
void sdevkit_oam_set(uint16_t id, uint16_t xspr, uint16_t yspr, uint8_t priority, uint8_t hflip, uint8_t vflip, uint16_t gfxoffset, uint8_t paletteoffset);
```

Sets all of a sprite's entry but its size: the position (`xspr` has 9 bits, `yspr` 8), the
priority 0 to 3 against the background layers, mirroring left to right (`hflip`) and top to
bottom (`vflip`), the tile number `gfxoffset` (0 to 511) and the palette 0 to 7. The SNES draws
a sprite one line lower than its y, so `examples/hello` passes `ball_y - 1`. Cost: 293 to 405
cycles.

### `sdevkit_oam_set_xy`

```c
void sdevkit_oam_set_xy(uint16_t id, uint16_t xspr, uint16_t yspr);
```

Moves a sprite and leaves the rest of its entry alone. Cost: 131 to 243 cycles.

### `sdevkit_oam_set_ex`

```c
void sdevkit_oam_set_ex(uint16_t id, uint8_t size, uint8_t hide);
```

Sets a sprite's size (`SDEVKIT_OBJ_SMALL` or `SDEVKIT_OBJ_LARGE`), then hides or shows it as
`sdevkit_oam_set_visible` does. Cost: 76 to 188 cycles.

### `sdevkit_oam_set_visible`

```c
void sdevkit_oam_set_visible(uint16_t id, uint8_t hide);
```

With `SDEVKIT_OBJ_HIDE`, moves the sprite below the picture (y `SDEVKIT_ATTR2_DISABLED`). With
`SDEVKIT_OBJ_SHOW` it does nothing: a hidden sprite comes back with its next `sdevkit_oam_set`
or `sdevkit_oam_set_xy`, which gives it a y again. Cost: 6 cycles.

### `sdevkit_oam_clear`

```c
void sdevkit_oam_clear(uint16_t first, uint8_t numEntries);
```

Clears `numEntries` sprites from id `first` on, each at x 0, hidden and small; with
`numEntries` 0 it clears from `first` to the last sprite. Cost: 19 to 102,675 cycles; the upper
figure is the bound for clearing all 128 sprites.

## DMA and HDMA

The PPU takes new data in VRAM and CGRAM only in a vertical blank or while the screen is off;
what is written at other times does not arrive. The copies below therefore belong in
`game_init` before the screen goes on, or in `game_vblank`. DMA moves one byte per 8
[master clocks](/glossary#master-clock), and a vertical blank has room for about 5,700 bytes of
the game's copies, after the 544 bytes of the sprite table the main loop copies itself. Rachael
counts every byte that would be lost on the SNES, written outside a vertical blank or over that
budget, and the desktop check fails on it. All general DMA uses channel 0. The rules behind this
are in [The SNES hardware](/guide/snes-hardware#video-memory).

### `sdevkit_dma_copy_vram`

```c
void sdevkit_dma_copy_vram(const uint8_t *source, uint16_t address, uint16_t size);
```

Copies `size` bytes from `source` to VRAM, from word `address` on, one word after the other.
Cost: 116 cycles, and `size` bytes of the vertical blank.

### `sdevkit_dma_copy_vram_column`

```c
void sdevkit_dma_copy_vram_column(const uint8_t *source, uint16_t address, uint16_t size);
```

Like `sdevkit_dma_copy_vram`, but the VRAM address steps by 32 words after each word, so the
words go down a column of a 32 x 32 map. A game that scrolls sideways writes the column coming
into view this way. A column of a 32 x 32 map is at most 64 bytes. Cost: 116 cycles, and `size`
bytes of the vertical blank.

### `sdevkit_dma_copy_cgram`

```c
void sdevkit_dma_copy_cgram(const uint8_t *source, uint16_t address, uint16_t size);
```

Copies `size` bytes (two per colour) from `source` to CGRAM, from colour `address` on. Cost: 100
cycles, and `size` bytes of the vertical blank.

### `sdevkit_dma_clear_vram`

```c
void sdevkit_dma_clear_vram(void);
```

Fills all of VRAM, 64 KB, with zeros. That is far more than a vertical blank takes, so call it
with the screen off. Cost: 111 cycles.

### `sdevkit_dma_fits`

```c
uint8_t   sdevkit_dma_fits(uint16_t bytes);
```

Returns 1 if a copy of `bytes` bytes to VRAM or CGRAM started now still ends inside the current
vertical blank, and 0 otherwise, also outside a vertical blank. A game asks it in `game_vblank`
before each copy that may not fit, and leaves what does not fit for the next vertical blank
instead of losing it. On the SNES it reads the line counter, so the code around the copies
counts too: it allows 160 bytes for each line left and keeps a margin of 8 lines for the setup
of the copy. Rachael counts the bytes copied against the budget of the vertical blank instead.
With the screen off there is nothing to ask. Cost: 81 to 86 cycles.

### `sdevkit_hdma_set`

```c
void sdevkit_hdma_set(uint8_t channel, uint8_t reg, uint8_t twoRegs, const uint8_t *table);
```

Sets up [HDMA](/glossary#hdma) channel `channel`, a number from 1 to 7, in direct mode: once
`sdevkit_hdma_enable` switches it on, from the next frame on, the channel writes data from
`table` into the PPU register `reg` (an `SDEVKIT_PPU_*` name) at the start of every line.
Channel 0 is not for HDMA: general DMA uses it, and every copy sets the channel up anew for
itself, over the HDMA settings. `twoRegs` is the transfer mode:

| Mode | Writes per line |
| --- | --- |
| `SDEVKIT_HDMA_ONE` | one byte to `reg` |
| `SDEVKIT_HDMA_TWO_REGS` | two bytes, to `reg` and `reg + 1` |
| `SDEVKIT_HDMA_TWICE` | two bytes to `reg`, for the registers written twice such as the scroll registers |

The table has the format of the SNES: a line count byte, then the data. A count of 1 to 127
writes the data once and holds it for that many lines; a count of 129 to 255 is followed by new
data for each of (count − 128) lines; a count of 0 ends the table. The channel reads the table
while the frame is drawn, so it must stay valid: a `static` or ROM array, never a local
variable.

The program below fills BG1 with vertical stripes, four pixels white and four black, and shifts
a band of them 4 pixels to the side, from line 32 to line 63, so that in the band the white and
the black change places. Its table has three entries: 32 lines with scroll 0, 32 lines with
scroll 4, and one line with 0 again. The last one is needed because the 0 that ends the table
ends the channel's writes for that frame, and the scroll keeps the value written last: without
it, everything from line 64 down would stay shifted. The VRAM is cleared before `game_init`, so
the map is all zeros and shows tile 0 everywhere.

```c [wave.c]
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

#define VRAM_TILES 0x0000      /* BG1's tiles, in 16-bit words */
#define VRAM_MAP   0x1000      /* BG1's map: all zeros, so tile 0 everywhere */

/* Tile 0: four pixels of colour 1, then four of colour 0, on each of its 8 rows. 16 colours
 * take 4 bit planes; the bytes left out are 0. */
static const uint8_t stripes[32] = {
    0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0
};

/* BG1's horizontal scroll down the screen: 0 for 32 lines, 4 for the next 32, then 0 again */
static const uint8_t wave[] = {
    32, 0, 0,
    32, 4, 0,
    1, 0, 0,
    0
};

void game_init(uint16_t seed)
{
    (void)seed;
    sdevkit_set_mode(SDEVKIT_BG_MODE1, 0);
    sdevkit_bg_set_disable(1);
    sdevkit_bg_set_disable(2);
    sdevkit_dma_copy_vram(stripes, VRAM_TILES, sizeof stripes);
    sdevkit_bg_set_gfx_ptr(0, VRAM_TILES);
    sdevkit_bg_set_map_ptr(0, VRAM_MAP, SDEVKIT_SC_32x32);
    sdevkit_set_palette_color(1, SDEVKIT_RGB5(31, 31, 31));    /* colour 1 white; 0 stays black */
    sdevkit_hdma_set(3, SDEVKIT_PPU_BG1HOFS, SDEVKIT_HDMA_TWICE, wave);
    sdevkit_hdma_enable(SDEVKIT_HDMA_CHANNEL3);
    sdevkit_set_brightness(15);
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    (void)pad;
}

void game_vblank(void)
{
}
```

Cost: 87 cycles.

### `sdevkit_hdma_enable`

```c
void sdevkit_hdma_enable(uint8_t channels);
```

Says which HDMA channels run (`HDMAEN`). Where `sdevkit_hdma_set` takes the channel's number,
this function takes a mask, an `|` of `SDEVKIT_HDMA_CHANNEL1` to `SDEVKIT_HDMA_CHANNEL7`: channel
3 is `sdevkit_hdma_set(3, ...)` and `SDEVKIT_HDMA_CHANNEL3`. `SDEVKIT_HDMA_CHANNEL0` exists too,
but channel 0 belongs to general DMA. Each call replaces the mask, so it names every channel
that is to run, and 0 stops them all. Cost: 21 cycles.

## Colour math and registers

Colour math adds the sub screen or a fixed colour to the main screen, or subtracts it, for
shadows, tints and fades. Two registers set it up, and the constants are their bits. Some of
them name the colour window, an area of the screen set with the window registers through
`sdevkit_reg_write`.

For the first value of `sdevkit_set_color_effect` (register `CGWSEL`):

| Constant | Meaning |
| --- | --- |
| `SDEVKIT_CM_SUBBGOBJ_ENABLE` | add or subtract the sub screen, not the fixed colour |
| `SDEVKIT_CM_ALWAYS_ENABLE` | colour math everywhere |
| `SDEVKIT_CM_MATWIN_ENABLE` | only inside the colour window |
| `SDEVKIT_CM_NOMATWIN_ENABLE` | only outside the colour window |
| `SDEVKIT_CM_NEVER_ENABLE` | never |
| `SDEVKIT_CM_SCBLK_ALWAYS_ENABLE` | main screen black: never |
| `SDEVKIT_CM_SCBLK_MATWIN_ENABLE` | main screen black outside the colour window |
| `SDEVKIT_CM_SCBLK_NOMATWIN_ENABLE` | main screen black inside the colour window |
| `SDEVKIT_CM_SCBLK_NEVER_ENABLE` | main screen black always |

The names are PVSnesLib's and read against their meaning in the `SCBLK` rows: `MATWIN` is the
colour window and `NOMATWIN` the rest of the screen, and each `SCBLK` name says where the main
screen stays enabled. So `SDEVKIT_CM_SCBLK_ALWAYS_ENABLE` never makes it black, and
`SDEVKIT_CM_SCBLK_MATWIN_ENABLE` makes it black outside the colour window.

For the second value (register `CGADSUB`):

| Constant | Meaning |
| --- | --- |
| `SDEVKIT_CM_SUB_MAINSUB` | subtract instead of add |
| `SDEVKIT_CM_DIV2_DIVIDE` | halve the result |
| `SDEVKIT_CM_MSCR_BACK` | colour math on the [backdrop](/glossary#backdrop) |
| `SDEVKIT_CM_MSCR_PAL47` | on sprites with palettes 4 to 7 |
| `SDEVKIT_CM_MSCR_BG1` to `SDEVKIT_CM_MSCR_BG4` | on that layer |

The channels of the fixed colour, for `sdevkit_set_color_intensity`, are `SDEVKIT_CM_APPLY_RED`,
`SDEVKIT_CM_APPLY_GREEN` and `SDEVKIT_CM_APPLY_BLUE`. These two calls add a dark red to BG1 and
the backdrop:

```c
sdevkit_set_color_effect(SDEVKIT_CM_ALWAYS_ENABLE, SDEVKIT_CM_MSCR_BG1 | SDEVKIT_CM_MSCR_BACK);
sdevkit_set_color_intensity(SDEVKIT_CM_APPLY_RED, 8);
```

### `sdevkit_set_color_effect`

```c
void sdevkit_set_color_effect(uint8_t colorMathA, uint8_t colorMathB);
```

Writes `colorMathA` to `CGWSEL` and `colorMathB` to `CGADSUB`, each an `|` of the constants
above. Cost: 36 cycles.

### `sdevkit_set_color_intensity`

```c
void sdevkit_set_color_intensity(uint8_t colorApply, uint8_t intensity);
```

Sets the fixed colour (`COLDATA`): the channels named in `colorApply` get `intensity`, 0 to 31.
Cost: 28 cycles.

### `sdevkit_reg_write`

```c
void sdevkit_reg_write(uint8_t reg, uint8_t value);
```

Writes `value` to the PPU register `$21xx` whose low byte is `reg`, for the registers the API has
no function for, such as the windows. PVSnesLib programs write its `REG_*` variables for this.
Rachael does not draw everything a register can switch on: a mode other than 1 and 7, mosaic,
`SETINI`, direct colour and the ports of VRAM, CGRAM and OAM, which would go around her memories.
She counts such a write, and the desktop check fails on it, because the SNES would show
something else there. Cost: 25 cycles.

The names of the registers, with the address each stands for; the value of a name is the low
byte of its address:

| Name | Address | Name | Address |
| --- | --- | --- | --- |
| `SDEVKIT_PPU_INIDISP` | `$2100` | `SDEVKIT_PPU_W12SEL` | `$2123` |
| `SDEVKIT_PPU_OBSEL` | `$2101` | `SDEVKIT_PPU_W34SEL` | `$2124` |
| `SDEVKIT_PPU_BGMODE` | `$2105` | `SDEVKIT_PPU_WOBJSEL` | `$2125` |
| `SDEVKIT_PPU_BG1SC` | `$2107` | `SDEVKIT_PPU_WH0` (window 1 left) | `$2126` |
| `SDEVKIT_PPU_BG2SC` | `$2108` | `SDEVKIT_PPU_WH1` (window 1 right) | `$2127` |
| `SDEVKIT_PPU_BG3SC` | `$2109` | `SDEVKIT_PPU_WH2` (window 2 left) | `$2128` |
| `SDEVKIT_PPU_BG4SC` | `$210A` | `SDEVKIT_PPU_WH3` (window 2 right) | `$2129` |
| `SDEVKIT_PPU_BG12NBA` | `$210B` | `SDEVKIT_PPU_WBGLOG` | `$212A` |
| `SDEVKIT_PPU_BG34NBA` | `$210C` | `SDEVKIT_PPU_WOBJLOG` | `$212B` |
| `SDEVKIT_PPU_BG1HOFS` | `$210D` | `SDEVKIT_PPU_TM` | `$212C` |
| `SDEVKIT_PPU_BG1VOFS` | `$210E` | `SDEVKIT_PPU_TS` | `$212D` |
| `SDEVKIT_PPU_M7SEL` | `$211A` | `SDEVKIT_PPU_TMW` | `$212E` |
| `SDEVKIT_PPU_M7A` to `SDEVKIT_PPU_M7D` | `$211B` to `$211E` | `SDEVKIT_PPU_TSW` | `$212F` |
| `SDEVKIT_PPU_M7X` | `$211F` | `SDEVKIT_PPU_CGWSEL` | `$2130` |
| `SDEVKIT_PPU_M7Y` | `$2120` | `SDEVKIT_PPU_CGADSUB` | `$2131` |
| | | `SDEVKIT_PPU_COLDATA` | `$2132` |

The scroll registers of the other layers have no names of their own: BG*n*HOFS is at
`$210D + 2(n − 1)` and BG*n*VOFS at `$210E + 2(n − 1)`. They and the mode 7 registers are
written twice, low byte then high byte.

## Mode 7

After `sdevkit_set_mode(SDEVKIT_BG_MODE7, 0)` the screen has one background, BG1, which is
rotated, scaled and sheared by a matrix. Its map is 128 x 128 tiles (1024 x 1024 pixels, one
byte a tile), and its 256 tiles are 8 x 8 pixels with one byte a pixel, an index into all 256
colours of CGRAM, 0 transparent. Map and tiles share the first 32 KB of VRAM, the map in the low
byte of each word and the tiles in the high byte, and nothing else of a game may be there.
Sprites stay as in mode 1: those of priority 1 to 3 are in front of the layer, those of priority
0 behind it.

A screen pixel (x, y) shows the map pixel

```
(a b; c d) * (x + hscroll - cx, y + vscroll - cy) + (cx, cy)
```

with `a`, `b`, `c` and `d` in 8.8 fixed point (`0x0100` is 1.0), and the centre (`cx`, `cy`) and
the scroll in pixels, 13 bits with sign. The scroll is BG1's, set with
`sdevkit_bg_set_scroll(0, ...)`. [Graphics and sound](/guide/graphics-and-sound) shows how a
picture becomes a mode 7 map and its tiles.

### `sdevkit_mode7_load_map`

```c
void sdevkit_mode7_load_map(const uint8_t *map, uint16_t address, uint16_t size);
```

Copies `size` map bytes, one per tile, from map entry `address` on; the entry of a tile is
`row * 128 + column`. It is a DMA copy: in a vertical blank or with the screen off. Cost: 116
cycles, and `size` bytes of the vertical blank.

### `sdevkit_mode7_load_tiles`

```c
void sdevkit_mode7_load_tiles(const uint8_t *tiles, uint16_t address, uint16_t size);
```

Copies `size` tile bytes, one per pixel, from byte `address` of the tiles on; pixel (column,
row) of tile *t* is byte `t * 64 + row * 8 + column`. A DMA copy: in a vertical blank or with the
screen off. Cost: 116 cycles, and `size` bytes of the vertical blank.

### `sdevkit_mode7_set_matrix`

```c
void sdevkit_mode7_set_matrix(int16_t a, int16_t b, int16_t c, int16_t d);
```

Sets the matrix (`M7A` to `M7D`), in 8.8 fixed point. Since the matrix takes a screen pixel to a
map pixel, `a` = `d` = `0x0200` with `b` = `c` = 0 shows the map at half its size, and `0x0080`
at twice its size. Cost: 117 cycles.

### `sdevkit_mode7_set_center`

```c
void sdevkit_mode7_set_center(int16_t x, int16_t y);
```

Sets the centre of the rotation and scaling (`M7X`, `M7Y`), in map pixels. Cost: 65 cycles.

### `sdevkit_mode7_set_select`

```c
void sdevkit_mode7_set_select(uint8_t select);
```

Says what the screen shows outside the map, and whether it is mirrored (`M7SEL`): one of
`SDEVKIT_M7_WRAP` (the map repeats), `SDEVKIT_M7_TRANSPARENT` (nothing, the backdrop) and
`SDEVKIT_M7_TILE0` (tile 0 everywhere), with `SDEVKIT_M7_FLIPX` or `SDEVKIT_M7_FLIPY` added by
`|` to mirror the whole screen. Cost: 21 cycles.

## Sound effects

A game lists its effects in `assets/sfx/sfx.json` and names that file in its
[project file](/glossary#project-file) (`[sound] sfx = "assets/sfx/sfx.json"`). Before it
compiles, `superdevkit build` converts the file into `build/gen/sfx.h` and `sfx.c`: a name per
effect, `SFX_` and its id in capitals, whose value is the effect's place in the list, then
`SFX_COUNT`, and the bank `sfx_bank`, which holds the samples and the tables of the effects.
How the file is made and what its settings do is in
[Graphics and sound](/guide/graphics-and-sound#sound-effects).

The bank's type is declared in `sdevkit/snes.h`:

```c
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
```

The build fills in every field; a game only passes the bank on. An effect whose file lists no
active sample has `SDEVKIT_SFX_OFF` as its sample and plays nothing, but its name stays, so the
game builds unchanged.

### `sdevkit_sfx_init`

```c
void sdevkit_sfx_init(const sdevkit_sfx_bank *bank);
```

Loads the bank's samples into the audio RAM of the sound chip and starts the sound. Call it
once, in `game_init`; a second call does nothing, and so does a null `bank`. On the SNES it waits
for the boot code of the SPC700, the sound CPU, and sends every byte through it, about 350 bytes
a frame: Schach's 13.8 KB take 40 frames after the splash screen. The CPU does nothing else
meanwhile, so the pads are not read, but the PPU keeps showing its picture. A game that draws its
title and switches the screen on before this call, as the last thing in `game_init`, shows the
title during the upload instead of a black screen. On Rachael the same bytes go straight into her
audio RAM. Cost: 51 cycles to unbounded, since the wait for the sound CPU has no bound the timing
report can see.

### `sdevkit_sfx_play`

```c
void sdevkit_sfx_play(uint8_t id);
```

Asks for the effect `id`, one of the `SFX_*` names. It starts after the tick, once the main loop
has run `game_vblank`: a free voice takes it; when all of the bank's voices are busy, it takes
the voice of the lowest priority that is not above its own, of two equal ones the older; when
every playing effect has a higher priority, it is dropped. A voice is free again when its
effect's length in frames has counted down. All effects of one tick start together, and more
than 8 requests in one tick are dropped. An effect without an active sample, an `id` past the
last effect, or a call before `sdevkit_sfx_init` does nothing. This choice is the same C code on
the SNES and on Rachael, so the same voice plays the same effect on both, frame by frame. Cost:
39 to 158 cycles.

## Music

A game lists its tracks in its project file:

```toml
[sound]
music = ["assets/music/title.ogg", { file = "assets/music/boss-2.xm", name = "boss" }]
```

The build writes `build/gen/music.h` with a number per track, its place in the list:
`MUSIC_TITLE` is 0, `MUSIC_BOSS` 1 and `MUSIC_COUNT` 2. A track's name is its file's name
without the extension, unless `name` gives one. The game names a track by its number, never by a
file name. On the desktop and the web the track plays from its file, which may be OGG, MP3, WAV,
QOA, XM or MOD, over and over, until another track or `sdevkit_music_stop`, and it pauses with
the run. On the SNES music is not built yet: both functions do nothing, and dampf removes the
calls, so a game can call them already and keeps them in its SNES build
([Limits](/reference/limits)). The checks play no music.

### `sdevkit_music_play`

```c
void sdevkit_music_play(uint8_t track);
```

Plays the track `track`, a `MUSIC_*` number, in place of the one playing. The track that is
already playing goes on playing; it does not start again. Cost: 6 cycles for the empty function
on the SNES; in a game dampf removes the call.

### `sdevkit_music_stop`

```c
void sdevkit_music_stop(void);
```

Stops the music. Cost: 6 cycles for the empty function on the SNES; in a game dampf removes the
call.

## Save RAM

Save RAM is battery-backed RAM on the cartridge, kept while the console is off: for high scores,
settings or a saved game. A game asks for it with `sram` in its project file, the number of
bytes, 0 to 32,768 ([The superdevkit command](/reference/superdevkit#sram)). For the SNES,
`superdevkit build` passes it to dampf as `--sram`, which writes into the ROM's header that the
cartridge has RAM and a battery, the size rounded up to between 2 and 32 KB; the desktop and the
web builds get the same size. In each case the compiler sees the size as `SDEVKIT_SRAM_SIZE`;
without `sram` it is 0.

### `sdevkit_sram`

```c
uint8_t *sdevkit_sram(void);
```

Returns the save RAM, `SDEVKIT_SRAM_SIZE` bytes, or 0 when the game has none. The game reads and
writes the block like any memory, whenever it likes, and the kit keeps it: on the SNES it is the
cartridge's RAM at `$70:0000`; on the desktop it is the file `<game>.srm` next to the program,
read before the game starts and written when it has changed and when the window closes; on the
web it is `/save/<game>.srm`, kept in the browser's IndexedDB. The first time, the block holds
zeros on the desktop and in the checks, but on a cartridge whatever the RAM held. So a game marks
its data as valid, with a magic number or a checksum, and clears the block when the mark is
missing. A recording starts with the save RAM of its start, so that a replay plays the same
game. Cost: 11 cycles.

The program below keeps a high score in five bytes, so its project file has `sram = 5`. The
mark is the letters `HS` and a check byte, the sum of the four bytes before it:

```c [src/best.c]
#include <stdint.h>
#include "sdevkit/snes.h"

static uint16_t best;      /* the high score */

static uint8_t save_sum(const uint8_t *s)
{
    return (uint8_t)(s[0] + s[1] + s[2] + s[3]);
}

void best_save(void)
{
    uint8_t *s = sdevkit_sram();

    s[0] = 'H';
    s[1] = 'S';
    s[2] = (uint8_t)best;
    s[3] = (uint8_t)(best >> 8);
    s[4] = save_sum(s);
}

/* In game_init: the high score saved before, or 0 when there is none. */
void best_load(void)
{
    const uint8_t *s = sdevkit_sram();

    if (s[0] == 'H' && s[1] == 'S' && s[4] == save_sum(s)) {
        best = (uint16_t)(s[2] | (s[3] << 8));
    } else {
        best = 0;
        best_save();
    }
}
```

## Debug calls

The `sdevkit_dbg_*` functions exist on every platform and never change what the game computes.
`sdevkit_dbg_vram` and `sdevkit_dbg_hash` serve the debug checks on the desktop and on the SNES.

The other nine feed the debug view of the desktop, which `F1` opens beside the picture; on the
web it is only in a build with `--debug`. How the view is used is in
[The desktop](/guide/the-desktop#debugging-on-the-pc). A game registers once, in `game_init`,
what it wants to change or read while it runs, and the view lists it; in each tick it can draw
boxes and arrows over the picture. On the SNES these nine functions are empty and dampf removes
the calls, so the game needs no `#ifdef`. A `name` is a string constant, since the view keeps the
pointer. A game has at most 32 registrations; a variable registered again replaces its entry. A
change made in the view is not part of a recording: from then on, a replay plays another game,
and the window says so.

To make a setting part of the recording, start a desktop build with `--set name=value`, which
sets a registration before the first tick; the recording keeps it. So a bot needs no support of
its own: a toggle named `autoplay`, which `game_tick` reads to make its own pad instead of
`pad[0]`, is switched on with `--set autoplay=1`.

### `sdevkit_dbg_vram`

```c
const uint16_t *sdevkit_dbg_vram(void);
```

On Rachael, returns VRAM as 32,768 words, for a debug check of the tile maps. The SNES cannot
read VRAM like memory, so there it returns a single zero word, and a check that uses it runs only
on the desktop. Cost: 14 cycles.

### `sdevkit_dbg_hash`

```c
void sdevkit_dbg_hash(const void *data, uint16_t bytes);
```

Names memory that makes up the game's state, for the checks that play a game on the SNES and on
the PC with the same input and compare a checksum after every tick. The bytes named during a
tick are hashed (FNV-1a, 32 bits) in the order named, followed by the sprite table, which the
main loop names itself after every `game_tick`. Call it in `game_tick`, on data whose bytes are
the same on both platforms: arrays of `uint8_t` and `uint16_t` (both platforms are little
endian), structs only when they have no padding on the PC, and nothing that holds pointers.

```c
static uint8_t lives[2];
static uint16_t score[2];

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    if (pad[0] & SDEVKIT_KEY_A) {
        score[0]++;
    }
    sdevkit_dbg_hash(lives, sizeof lives);
    sdevkit_dbg_hash(score, sizeof score);
}
```

On the SNES it writes the address and the size to port `$21FC`, which no hardware uses, and the
checker reads and hashes the memory itself; on Rachael it hashes. Cost: 79 cycles.

### `sdevkit_dbg_toggle`

```c
void sdevkit_dbg_toggle(const char *name, uint8_t *flag);
```

Registers `*flag` as a switch in the debug view, 0 or 1. Cost: 6 cycles for the empty function
on the SNES; in a game dampf removes the call.

### `sdevkit_dbg_range`

```c
void sdevkit_dbg_range(const char *name, int16_t *value, int16_t lo, int16_t hi);
```

Registers `*value` as a number the view changes between `lo` and `hi`. Cost: 6 cycles for the
empty function on the SNES; in a game dampf removes the call.

### `sdevkit_dbg_color`

```c
void sdevkit_dbg_color(const char *name, uint16_t *colour);
```

Registers `*colour` as a colour the view changes, in the format of `SDEVKIT_RGB5`: 5 bits each
of red, green and blue, blue in the high bits. Cost: 6 cycles for the empty function on the
SNES; in a game dampf removes the call.

### `sdevkit_dbg_action`

```c
void sdevkit_dbg_action(const char *name, uint8_t *pressed);
```

Registers a button. The view sets `*pressed` to 1; the game does what the button means in its
next `game_tick` and sets it back to 0. The view cannot call a function of the game, since
dampf has no function pointers, and the game's state changes only inside a tick. Cost: 6 cycles
for the empty function on the SNES; in a game dampf removes the call.

### `sdevkit_dbg_text`

```c
void sdevkit_dbg_text(const char *name, const char *text);
```

Registers a line of text that the view shows and reads again every frame, such as why a life was
lost. The game keeps `text` up to date: a string ending in a zero byte, in memory that stays, so
not a local variable. The line changes nothing in the game, so a recording stays valid. Cost: 6
cycles for the empty function on the SNES; in a game dampf removes the call.

### `sdevkit_dbg_value`

```c
void sdevkit_dbg_value(const char *name, const uint16_t *value);
```

Registers a value that the view shows in decimal and in hexadecimal and reads again every frame.
Like a line of `sdevkit_dbg_text`, it changes nothing in the game, so a recording stays valid.
Cost: 6 cycles for the empty function on the SNES; in a game dampf removes the call.

### `sdevkit_dbg_box`

```c
void sdevkit_dbg_box(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind);
```

Draws a box of `w` by `h` pixels at screen position (`x`, `y`) over the picture in the debug
view, a collision box or a target, in the colour of `kind`, 0 to 7. The box is for this tick
only: the list is cleared before each `game_tick`, and it holds at most 256 shapes a tick, boxes,
filled boxes and arrows together. Cost: 6 cycles for the empty function on the SNES; in a game
dampf removes the call, and the loops left empty by it.

### `sdevkit_dbg_fill`

```c
void sdevkit_dbg_fill(int16_t x, int16_t y, uint8_t w, uint8_t h, uint8_t kind);
```

Draws the same box as `sdevkit_dbg_box`, filled, with the picture showing through it, for a
danger zone for example. It is for this tick only and counts among the tick's 256 shapes. Cost:
6 cycles for the empty function on the SNES; in a game dampf removes the call, and the loops
left empty by it.

### `sdevkit_dbg_arrow`

```c
void sdevkit_dbg_arrow(int16_t x, int16_t y, int8_t dx, int8_t dy, uint8_t kind);
```

Draws an arrow from screen position (`x`, `y`) by (`dx`, `dy`) pixels over the picture in the
debug view, in the colour of `kind`, 0 to 7, to show a direction or an air current. It is for
this tick only and counts among the tick's 256 shapes. Cost: 6 cycles for the empty function on
the SNES; in a game dampf removes the call, and the loops left empty by it.

## The old names

Games written before the rename use PVSnesLib's names (`setMode`, `oamSet`), the `snesXxx`
extensions (`snesRegWrite`), `snes_vram`, the macros without prefix (`BG_MODE1`), the pad bits
`KEY_*`, `SNES_PADS` and the typedefs `u8`, `u16`, `s16` and `u32`. The kit has no header for
these names any more, so such a game is converted once. The functions and constants added after
the rename (`sdevkit_dma_copy_vram_column`, the mode 7 calls and constants, `sdevkit_dbg_hash`,
the HDMA modes, the sound effects, music, save RAM and the debug view's calls) have no old name.

The script `tools/sdevkit_rename.py`, also in `<home>/tools`, converts a game. It changes only
identifiers: strings, character constants and comments stay as they are, unless `--comments` is
given. It turns the includes of `"snes/snes.h"` and `"snes/loop.h"` into those of the
`sdevkit/` headers and the typedefs into the `<stdint.h>` types. Run it on files or folders; it
rewrites the `.c` and `.h` files in place and prints each file it changed, with the number of
names replaced. A file that includes `raylib.h` is a desktop frontend, not game code, and is
left as it is, with a warning. With `--dry-run` it only prints:

```sh
python3 -I <home>/tools/sdevkit_rename.py --dry-run src
```

```
src/player.c: 6 names
```

for this file:

```c [src/player.c]
#include "snes/snes.h"
#include "snes/loop.h"

static u16 player_x;

void move_player(u16 pad)
{
    if (pad & KEY_RIGHT) {
        player_x++;
    }
    oamSetXY(0, player_x, 100);   /* oamSetXY: sprite 0 */
}
```

Without `--dry-run` the file becomes:

```c [src/player.c]
#include "sdevkit/snes.h"
#include "sdevkit/loop.h"

static uint16_t player_x;

void move_player(uint16_t pad)
{
    if (pad & SDEVKIT_KEY_RIGHT) {
        player_x++;
    }
    sdevkit_oam_set_xy(0, player_x, 100);   /* oamSetXY: sprite 0 */
}
```

Mostly the new name of a function is `sdevkit_` and its old name in snake case (`setMode` is
`sdevkit_set_mode`), and a macro gets `SDEVKIT_` in front (`KEY_A` is `SDEVKIT_KEY_A`). The
`snesXxx` extensions and a few others lose their old prefix instead: `snesRegWrite` is
`sdevkit_reg_write`, `snes_vram` is `sdevkit_dbg_vram` and `SNES_PADS` is `SDEVKIT_PADS`. The
table in the script is the full list.
