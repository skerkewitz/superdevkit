<!-- GENERATED from doc-specs/reference/console-api.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# The console API

A game reaches the console only through the console API of the
[dev kit](/glossary#dev-kit), and the API exists on every platform: on the SNES, `lib/snes` (the
[platform layer](/glossary#platform-layer), compiled by dampf with the game) implements every
function, and on the desktop [Rachael](/glossary#rachael) implements the same functions. Every
function is built on both, so a game needs no `#ifdef` for the platform. The desktop build checks
that a game calls nothing else, since Rachael cannot see a write that goes around her. The API
is two headers in `<home>/lib/include`, where `<home>` is the
[superdevkit home](/glossary#superdevkit-home): `sdevkit/loop.h` for the frame rhythm and
`sdevkit/snes.h` for everything else. A game puts `-I <home>/lib/include` and `<home>/lib/snes/`
into its [args file](/glossary#args-file) (the options are on
[Command line](/reference/command-line)) and includes both:

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
for each argument. Where an entry gives a range, such as 61 to 173, it is the best and the worst
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
long as they need. It ends by switching the screen on, usually with
`sdevkit_set_brightness(15)`. The main loop passes 0 as `seed` today.

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
prepared, and the scroll registers.

Between `game_tick` and `game_vblank` the main loop waits for the start of the next vertical
blank. A tick that ends inside a vertical blank waits for the next one, so the uploads always
get a whole vertical blank. A tick that takes longer than a frame makes the loop wait for the
next vertical blank after it: the game runs slower, never wrong, and it is never interrupted.
There is no interrupt handler on the SNES, because dampf keeps the
[frame of a function](/glossary#frame) at a fixed place, and C code called from an interrupt in
the middle of a tick would overwrite the frames of the code it interrupted. On Rachael the
frontend gives the same rhythm, and a tick takes as long as it takes on the PC.

Before `game_init` every game shows the kit's splash screen, on the SNES and on the desktop
alike: the logo fades in from black, stays three seconds and fades out. A button pressed while
it fades in or stays ends it as soon as it has faded in. The main loop then puts the console
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
args file, is in `examples/hello`.

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
85 to 195 cycles.

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
Cost: 88 cycles.

### `sdevkit_bg_set_enable`

```c
void sdevkit_bg_set_enable(uint8_t bgNumber);
```

Shows the layer on the main screen (`TM`). Cost: 61 to 173 cycles.

### `sdevkit_bg_set_disable`

```c
void sdevkit_bg_set_disable(uint8_t bgNumber);
```

Hides the layer on the main screen (`TM`). Cost: 64 to 176 cycles.

### `sdevkit_bg_set_enable_sub`

```c
void sdevkit_bg_set_enable_sub(uint8_t bgNumber);
```

Shows the layer on the sub screen (`TS`), which colour math combines with the main screen.
Cost: 61 to 173 cycles.

### `sdevkit_bg_set_disable_sub`

```c
void sdevkit_bg_set_disable_sub(uint8_t bgNumber);
```

Hides the layer on the sub screen (`TS`). Cost: 64 to 176 cycles.

### `sdevkit_bg_init_tile_set`

```c
void sdevkit_bg_init_tile_set(uint8_t bgNumber, const uint8_t *tileSource, const uint8_t *tilePalette, uint8_t paletteEntry, uint16_t tileSize,
                   uint16_t paletteSize, uint16_t colorMode, uint16_t address);
```

Copies `tileSize` bytes of tiles to VRAM word `address`, copies `paletteSize` bytes of palette
to CGRAM from colour `paletteEntry * colorMode`, and sets the layer's tile base to `address`.
`paletteEntry` is the number of the palette, and `colorMode` is `SDEVKIT_BG_16COLORS` or
`SDEVKIT_BG_4COLORS`. It makes two DMA copies, so call it with the screen off or in
`game_vblank`. Cost: 592 to 782 cycles, and `tileSize + paletteSize` bytes of the vertical
blank.

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
Cost: 23 to 104,215 cycles. The upper figure is the timing report's bound for the loop over all
128 sprites, and all 128 are what this call clears.

### `sdevkit_oam_init_gfx_set`

```c
void sdevkit_oam_init_gfx_set(const uint8_t *tileSource, uint16_t tileSize, const uint8_t *tilePalette, uint16_t paletteSize, uint8_t tilePaletteNumber,
                   uint16_t address, uint8_t oamsize);
```

Copies `tileSize` bytes of sprite tiles to VRAM word `address`, copies `paletteSize` bytes of
palette to CGRAM from colour `128 + tilePaletteNumber * 16` (the sprite palettes are the upper
half of CGRAM), and sets the sprite tile base and sizes as `sdevkit_oam_init_gfx_attr` does. It
makes two DMA copies: with the screen off or in `game_vblank`. Cost: 246 to 356 cycles, and
`tileSize + paletteSize` bytes of the vertical blank.

### `sdevkit_oam_init_gfx_attr`

```c
void sdevkit_oam_init_gfx_attr(uint16_t address, uint8_t oamsize);
```

Sets where the sprite tiles start in VRAM, in steps of 8K words, and the pair of sprite sizes,
one of the `SDEVKIT_OBJ_SIZE*` values (`OBSEL`). Cost: 37 to 147 cycles.

### `sdevkit_oam_set`

```c
void sdevkit_oam_set(uint16_t id, uint16_t xspr, uint16_t yspr, uint8_t priority, uint8_t hflip, uint8_t vflip, uint16_t gfxoffset, uint8_t paletteoffset);
```

Sets all of a sprite's entry but its size: the position (`xspr` has 9 bits, `yspr` 8), the
priority 0 to 3 against the background layers, mirroring left to right (`hflip`) and top to
bottom (`vflip`), the tile number `gfxoffset` (0 to 511) and the palette 0 to 7. The SNES draws
a sprite one line lower than its y, so `examples/hello` passes `ball_y - 1`. Cost: 315 to 427
cycles.

### `sdevkit_oam_set_xy`

```c
void sdevkit_oam_set_xy(uint16_t id, uint16_t xspr, uint16_t yspr);
```

Moves a sprite and leaves the rest of its entry alone. Cost: 139 to 251 cycles.

### `sdevkit_oam_set_ex`

```c
void sdevkit_oam_set_ex(uint16_t id, uint8_t size, uint8_t hide);
```

Sets a sprite's size (`SDEVKIT_OBJ_SMALL` or `SDEVKIT_OBJ_LARGE`), then hides or shows it as
`sdevkit_oam_set_visible` does. Cost: 80 to 192 cycles.

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
`numEntries` 0 it clears from `first` to the last sprite. Cost: 23 to 103,703 cycles; the upper
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
With the screen off there is nothing to ask. Cost: 87 to 92 cycles.

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

Cost: 93 cycles.

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

## Debug calls

The two `sdevkit_dbg_*` functions exist on every platform and never change what the game
computes. They serve the debug checks on the desktop and on the SNES.

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

## The old names

Games written before the rename use PVSnesLib's names (`setMode`, `oamSet`), the `snesXxx`
extensions (`snesRegWrite`), `snes_vram`, the macros without prefix (`BG_MODE1`), the pad bits
`KEY_*`, `SNES_PADS` and the typedefs `u8`, `u16`, `s16` and `u32`. Two deprecated headers in
`lib/include/snes/` still map these names to the new ones: `snes/snes.h` includes
`sdevkit/snes.h`, and `snes/loop.h` includes `sdevkit/loop.h`. They are only for code that cannot
be changed yet; new code includes the `sdevkit/` headers. The functions and constants added after
the rename (`sdevkit_dma_copy_vram_column`, the mode 7 calls and constants, `sdevkit_dbg_hash`,
the HDMA modes) have no old name.

The script `tools/sdevkit_rename.py`, also in `<home>/tools`, converts a game. It changes only
identifiers: strings, character constants and comments stay as they are, unless `--comments` is
given. It turns the includes of `"snes/snes.h"` and `"snes/loop.h"` into those of the
`sdevkit/` headers and the typedefs into the `<stdint.h>` types. Run it on files or folders; it
rewrites the `.c` and `.h` files in place and prints each file it changed, with the number of
names replaced. With `--dry-run` it only prints:

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
table in the script is the full list, and the only copy of the mapping: `--write-compat DIR`
writes the two deprecated headers from it, so they must not be edited by hand.
