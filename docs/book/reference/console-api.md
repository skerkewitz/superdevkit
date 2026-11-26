<!-- GENERATED from doc-specs/reference/console-api.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# The console API

A game reaches the console only through the console API of the
[dev kit](/glossary#dev-kit), and the API exists on every platform: on the SNES, `lib/snes` (the
[platform layer](/glossary#platform-layer), compiled by dampf with the game) implements every
function, and on the desktop and the web [Rachael](/glossary#rachael) implements the same
functions. Every function exists on both, so a game needs no `#ifdef` for the platform; the few
that do nothing on the SNES yet (music, the debug view) say so in their entries. The desktop and
the web builds check that a game calls nothing else, since Rachael cannot see a write that goes
around her. A game includes `sdevkit/loop.h` for the frame rhythm and `sdevkit/snes.h` for the
core, and `sdevkit/easy.h` or one of the layers' headers for a layer:

```c
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
```

The headers are in `<home>/lib/include`, where `<home>` is the
[superdevkit home](/glossary#superdevkit-home). `superdevkit build` passes
`-I <home>/lib/include` to the compiler of every [target](/glossary#target) and compiles the
target's library together with the game (see
[The superdevkit command](/reference/superdevkit#build)).
Every name a game can use starts with `sdevkit_` or `SDEVKIT_`; the functions a game defines
start with `game_`, and no name of the kit does. The headers, `sdevkit/snes.h`, `loop.h`,
`shadow.h`, `sprites.h`, `queue.h`, `input.h`, `easy.h`, `irq.h` and `publish.h`, are the final
word on every declaration below, and they explain the hardware at the names that reach it.

The API has two levels. The core, `sdevkit/snes.h`, writes the hardware at once: a register
write is one store on the SNES, a copy one DMA, and the kit does nothing in the
[vertical blank](/glossary#vertical-blank) that a game did not ask for. It is for a game that
wants every cycle and knows the vertical blank's rules. Above it are optional layers in portable
C: the registers kept in memory (`sdevkit/shadow.h`), a sprite list (`sprites.h`), copies queued
in the tick (`queue.h`), the buttons pressed since the last tick (`input.h`), and all of them
with one commit (`easy.h`). They are for a game that wants none of the vertical blank's rules. A
game uses a layer by calling it, and a layer it does not call costs it nothing, since "dampf
leaves out the functions a program cannot reach" (ADR-6FNWEJAA). Why the API has this shape,
and the shadow state that was tried before it and measured, is in the decision record "The
console API is the hardware at once, with optional layers above it" (ADR-6FNPPCSX).

The cost of each function is given in CPU cycles on the SNES, with the calls it makes, from the
[timing report](/glossary#timing-report) of a program that calls every function once with zero
for each argument. Where an entry gives a range, such as 21 to 133, it is the best and the worst
path the timing report finds through the function's code, not a cost for particular arguments;
"unbounded" means a wait whose end the timing report cannot see. A call with a constant is often
cheaper, since dampf inlines the small functions: a register write with a constant register is
one store. dampf compiles the library together with the game as a
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

Before `game_init` every game shows the kit's splash screen, on the SNES and on Rachael alike:
the logo fades in from black, stays three seconds and fades out. A button pressed while it fades
in or stays ends it as soon as it has faded in. The main loop then resets the
[PPU](/glossary#ppu): the screen off, every register 0, [VRAM](/glossary#vram) and
[CGRAM](/glossary#cgram) zero, every sprite hidden. So `game_init` finds what it would find
without the splash.

The main loop calls `game_init` once, with the screen off. VRAM, CGRAM, [OAM](/glossary#oam)
and the registers take writes at any time here, so this is the place for the large copies:
tiles, maps and palettes. The game switches the screen on when it is ready, last
(`sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15)`, or `sdevkit_ppu.inidisp = 15` with the layers).
The value of `seed` is 0 on the SNES; a desktop build started with `--seed N` passes N
([The desktop](/guide/the-desktop#debugging-on-the-pc)).

Once per [frame](/glossary#frame), while the picture is drawn, the main loop calls `game_tick`:
the game logic of one [tick](/glossary#tick). It changes only the game's own memory: positions,
maps and sprite tables in RAM, what the next vertical blank is to write. Before each tick the
main loop reads both [joypads](/glossary#joypad), by the console's automatic read at the start
of the vertical blank, and hands them over in `pad`, `pad[0]` for port 1 and `pad[1]` for port 2
(`SDEVKIT_PADS` is 2).

In the vertical blank after each tick the main loop calls `game_vblank`, which does the writes to
the hardware: the registers and the copies the tick prepared, or one call of `sdevkit_commit` for
a game on the layers. Between the two the kit does only what it must. It waits for the start of
the next vertical blank (a tick that ends inside one waits for the next, so `game_vblank` always
gets the whole of it), tells the checks where the tick ended, and after `game_vblank` starts the
sound effects the tick asked for, so that they take none of the vertical blank. It copies nothing
and writes no register of its own: the whole vertical blank, about 49,000
[master cycles](/glossary#master-clock), is the game's.

A tick that takes longer than a frame makes the loop wait for the next vertical blank after it:
the game then runs at 30 frames a second, never wrong, and the picture shows the last one
`game_vblank` wrote. On Rachael the frontend gives the same rhythm, and a tick takes as long as it
takes on the PC.

When a write may happen follows from how the PPU draws. While a line is drawn, VRAM, CGRAM and
OAM take no writes: the PPU drops them or puts them at a wrong address. A register written then
changes the picture from that line on, so a scroll written in the middle of the picture tears it.

| Write | `game_init`, or the screen off | `game_tick` | `game_vblank` |
| --- | --- | --- | --- |
| VRAM, CGRAM, OAM | any time | lost | yes, as much as the vertical blank takes |
| a register, an HDMA channel | any time | changes the picture from that line on | yes |
| the screen off (`SDEVKIT_FORCED_BLANK`) | any time | any time | any time |

Switching the screen off at once is the one write a game may want while the picture is drawn: the
rest of that frame is black, and VRAM opens. The desktop checks the rule: a byte for VRAM, CGRAM
or OAM written while the picture is drawn is lost there as on the SNES, and a register or an HDMA
channel written then is counted (`drawing`). The desktop's check fails on either. The rules of the
hardware behind this are in [The SNES hardware](/guide/snes-hardware#video-memory).

The pads are in the SNES's bit layout; a bit is set while its button is held.

| Constant | Value | Button |
| --- | --- | --- |
| `SDEVKIT_KEY_B` | `0x8000u` | B |
| `SDEVKIT_KEY_Y` | `0x4000u` | Y |
| `SDEVKIT_KEY_SELECT` | `0x2000u` | Select |
| `SDEVKIT_KEY_START` | `0x1000u` | Start |
| `SDEVKIT_KEY_UP` | `0x0800u` | up on the cross |
| `SDEVKIT_KEY_DOWN` | `0x0400u` | down |
| `SDEVKIT_KEY_LEFT` | `0x0200u` | left |
| `SDEVKIT_KEY_RIGHT` | `0x0100u` | right |
| `SDEVKIT_KEY_A` | `0x0080u` | A |
| `SDEVKIT_KEY_X` | `0x0040u` | X |
| `SDEVKIT_KEY_L` | `0x0020u` | the left shoulder button |
| `SDEVKIT_KEY_R` | `0x0010u` | the right shoulder button |

The two programs below are complete games, one on each level. The first, cut down from
`examples/mode7`, uses the core: a checkerboard in mode 7 that zooms in and out with the joypad.
`game_init` writes everything at once, `game_tick` changes only `zoom`, and `game_vblank` writes
it into mode 7's matrix.

```c
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

#define CENTER 512                     /* the middle of the map, in pixels */

static uint8_t tiles[2 * 64];          /* two tiles of 8 x 8 pixels, a byte a pixel */
static uint8_t map[128 * 128];         /* 128 x 128 tiles, a byte each */
static int16_t zoom = 0x100;           /* map pixels per screen pixel, 8.8 fixed point */

void game_init(uint16_t seed)
{
    uint16_t i, ty, tx;

    (void)seed;
    for (i = 0; i < 64; i++) {
        tiles[i] = 1;                  /* tile 0 in colour 1, tile 1 in colour 2 */
        tiles[64 + i] = 2;
    }
    for (ty = 0; ty < 128; ty++) {
        for (tx = 0; tx < 128; tx++) {
            map[ty * 128 + tx] = (uint8_t)(((tx >> 3) ^ (ty >> 3)) & 1);   /* squares of 8 x 8 tiles */
        }
    }
    /* the screen is off: everything at once */
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE7);
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE);
    sdevkit_mode7_tiles_copy(0, tiles, sizeof tiles);
    sdevkit_mode7_map_copy(0, map, sizeof map);
    sdevkit_cgram_set(1, SDEVKIT_RGB5(6, 9, 14));
    sdevkit_cgram_set(2, SDEVKIT_RGB5(20, 24, 28));
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7X, CENTER);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7Y, CENTER);
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, CENTER - 128);   /* the centre in the middle of the screen */
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, CENTER - 112);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7A, 0x100);              /* the identity */
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7D, 0x100);
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);                   /* the screen on, at full brightness */
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    if ((pad[0] & SDEVKIT_KEY_UP) && zoom > 0x40) {
        zoom -= 4;                     /* nearer: fewer map pixels per screen pixel */
    }
    if ((pad[0] & SDEVKIT_KEY_DOWN) && zoom < 0x600) {
        zoom += 4;
    }
}

void game_vblank(void)
{
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7A, (uint16_t)zoom);
    sdevkit_reg_write_twice(SDEVKIT_PPU_M7D, (uint16_t)zoom);
}
```

The second, cut down from `examples/hello`, uses the layers: text in a background map, and a
ball that moves left and right with the joypad and has its x position printed under it.
`game_tick` writes the next picture into the register shadow, the sprite list and the queue, and
`game_vblank` is one call. Since `print_number` writes the number every tick, the map is queued
every tick here.

```c
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/easy.h"
#include "font_gfx.h"
#include "ball_gfx.h"

#define VRAM_FONT    0x0000    /* background tiles, in 16-bit words */
#define VRAM_MAP     0x1000    /* the background map: 32 x 32 entries */
#define VRAM_SPRITES 0x4000    /* sprite tiles */
#define TILE_FONT    32        /* the first character of assets/font_gfx.png */

static const char FONT[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-:.";

static uint16_t map[32 * 32];  /* the background map in RAM; queued for the vertical blank */
static uint8_t map_changed;
static int16_t ball_x = 120;

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
    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;                     /* mode 1: two layers of 16 colours, one of 4 */
    sdevkit_ppu.tm = SDEVKIT_BG1_ENABLE | SDEVKIT_OBJ_ENABLE;  /* the first layer and the sprites */
    sdevkit_ppu.bgnba[0] = SDEVKIT_BGNBA(VRAM_FONT, 0);
    sdevkit_ppu.bgsc[0] = SDEVKIT_BGSC(VRAM_MAP, SDEVKIT_SC_32x32);
    sdevkit_ppu.vofs[0] = 0x3FF;                               /* the map's first line on the screen's first line */
    sdevkit_ppu.obsel = SDEVKIT_OBSEL(VRAM_SPRITES, SDEVKIT_OBJ_SIZE16_L32);
    sdevkit_vram_copy(VRAM_FONT, font_gfx_tiles, FONT_GFX_TILES_SIZE);   /* the screen is off: copy at once */
    sdevkit_cgram_copy(0, font_gfx_pal, FONT_GFX_PAL_SIZE);
    sdevkit_vram_copy(VRAM_SPRITES, ball_gfx_tiles, BALL_GFX_TILES_SIZE);
    sdevkit_cgram_copy(128, ball_gfx_pal, BALL_GFX_PAL_SIZE);   /* sprite palette 0 */
    print(10, 6, "HELLO SNES!");
    print(10, 24, "X:");
    sdevkit_vram_copy(VRAM_MAP, map, sizeof map);
    map_changed = 0;
    sdevkit_ppu.inidisp = 15;                                  /* the screen on at the first commit, at full brightness */
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
    if (map_changed && sdevkit_queue_vram(VRAM_MAP, map, sizeof map)) {
        map_changed = 0;
    }
    /* the ball: at (ball_x, 104), tile 0, palette 0, priority 3, no flips; small (16 x 16) */
    sdevkit_sprite(ball_x, 104, 0, SDEVKIT_OAM_ATTR(0, 3, 0, 0), 0);
}

void game_vblank(void)
{
    sdevkit_commit();
}
```

The headers `font_gfx.h` and `ball_gfx.h` are written by the converter from the example's PNG
files; [Graphics and sound](/guide/graphics-and-sound) shows how. The whole example, with its
[project file](/glossary#project-file), is in `examples/hello`; it also moves the ball up and
down, prints its y position and names the ball's sprite to the checks.

A game that publishes its data for interrupt code defines `game_tick` and `game_vblank` through
the macro `SDEVKIT_PUBLISH` instead, and the kit's NMI runs its vertical blank
([Interrupts](#interrupts)).

## Registers

The PPU's registers are write-only: a game keeps its own copy of what it wrote, or uses the
[register shadow](#the-register-shadow). A write changes the picture from the line being drawn
on, so a game writes them in `game_vblank` or with the screen off.

### `sdevkit_reg_write`

```c
void sdevkit_reg_write(uint8_t reg, uint8_t value);
```

Writes `value` to the PPU register `$21xx` whose low byte is `reg`, an `SDEVKIT_PPU_*` name from
`$00` to `$33`. It is the core: the write goes to the hardware at once, and with a constant `reg`
it is one store on the SNES. Rachael does not draw everything a register can switch on: a mode
other than 1 and 7, mosaic, `SETINI`, direct colour and `VMAIN`'s address remapping. She counts
such a write (`undrawn`), and the desktop check fails on it, because the SNES would show
something else there. Cost: 25 cycles.

### `sdevkit_reg_write_twice`

```c
void sdevkit_reg_write_twice(uint8_t reg, uint16_t value);
```

Writes a register that takes two bytes in a row, low byte then high byte: the scroll registers
and mode 7's, marked "twice" in the table below. A single write would set only half of the value.
Like `sdevkit_reg_write`, it writes at once, in `game_vblank` or with the screen off. Cost: 43
cycles.

The names of the registers, with the address each stands for; the value of a name is the low
byte of its address.

| Name | Address | What | Twice |
| --- | --- | --- | --- |
| `SDEVKIT_PPU_INIDISP` | `$2100` | the screen: bit 7 forced blank, bits 0-3 brightness 0 to 15 | |
| `SDEVKIT_PPU_OBSEL` | `$2101` | the sprites' tiles and their two sizes, `SDEVKIT_OBSEL()` | |
| `SDEVKIT_PPU_OAMADDL` | `$2102` | OAM port: the word address, low byte | |
| `SDEVKIT_PPU_OAMADDH` | `$2103` | OAM port: bit 0 the address's bit 8; bit 7 priority rotation, leave 0 | |
| `SDEVKIT_PPU_OAMDATA` | `$2104` | OAM port: data | |
| `SDEVKIT_PPU_BGMODE` | `$2105` | the mode, BG3 in front in mode 1, 16 x 16 tiles per layer | |
| `SDEVKIT_PPU_MOSAIC` | `$2106` | mosaic: block size and layers; not drawn on Rachael | |
| `SDEVKIT_PPU_BG1SC` to `SDEVKIT_PPU_BG4SC` | `$2107` to `$210A` | a layer's map, `SDEVKIT_BGSC()` | |
| `SDEVKIT_PPU_BG12NBA` | `$210B` | the tiles of BG1 and BG2, `SDEVKIT_BGNBA()` | |
| `SDEVKIT_PPU_BG34NBA` | `$210C` | the tiles of BG3 and BG4 | |
| `SDEVKIT_PPU_BG1HOFS` | `$210D` | BG1's horizontal scroll, 10 bits; in mode 7 13 bits with sign | yes |
| `SDEVKIT_PPU_BG1VOFS` | `$210E` | BG1's vertical scroll | yes |
| `SDEVKIT_PPU_BG2HOFS`, `_BG2VOFS` | `$210F`, `$2110` | BG2's scroll | yes |
| `SDEVKIT_PPU_BG3HOFS`, `_BG3VOFS` | `$2111`, `$2112` | BG3's scroll | yes |
| `SDEVKIT_PPU_BG4HOFS`, `_BG4VOFS` | `$2113`, `$2114` | BG4's scroll | yes |
| `SDEVKIT_PPU_VMAIN` | `$2115` | VRAM port: when and by how much the address steps | |
| `SDEVKIT_PPU_VMADDL`, `_VMADDH` | `$2116`, `$2117` | VRAM port: the word address | |
| `SDEVKIT_PPU_VMDATAL`, `_VMDATAH` | `$2118`, `$2119` | VRAM port: the low and the high byte of the word | |
| `SDEVKIT_PPU_M7SEL` | `$211A` | mode 7: what lies outside the map, mirroring | |
| `SDEVKIT_PPU_M7A` to `SDEVKIT_PPU_M7D` | `$211B` to `$211E` | mode 7's matrix, 8.8 fixed point with sign | yes |
| `SDEVKIT_PPU_M7X`, `_M7Y` | `$211F`, `$2120` | mode 7's centre, 13 bits with sign | yes |
| `SDEVKIT_PPU_CGADD` | `$2121` | CGRAM port: the colour index | |
| `SDEVKIT_PPU_CGDATA` | `$2122` | CGRAM port: the colour, low byte then high byte | |
| `SDEVKIT_PPU_W12SEL` | `$2123` | the windows of BG1 and BG2, `SDEVKIT_WSEL()` | |
| `SDEVKIT_PPU_W34SEL` | `$2124` | the windows of BG3 and BG4 | |
| `SDEVKIT_PPU_WOBJSEL` | `$2125` | the windows of the sprites and of the colour window | |
| `SDEVKIT_PPU_WH0`, `_WH1` | `$2126`, `$2127` | window 1's left and right edge, 0 to 255 | |
| `SDEVKIT_PPU_WH2`, `_WH3` | `$2128`, `$2129` | window 2's left and right edge | |
| `SDEVKIT_PPU_WBGLOG` | `$212A` | how each layer's two windows combine, `SDEVKIT_WLOG_*` | |
| `SDEVKIT_PPU_WOBJLOG` | `$212B` | the same for the sprites and the colour window | |
| `SDEVKIT_PPU_TM` | `$212C` | the layers on the main screen, the picture | |
| `SDEVKIT_PPU_TS` | `$212D` | the layers on the sub screen, for colour math | |
| `SDEVKIT_PPU_TMW` | `$212E` | the main screen's layers that their windows mask | |
| `SDEVKIT_PPU_TSW` | `$212F` | the same for the sub screen | |
| `SDEVKIT_PPU_CGWSEL` | `$2130` | colour math, where and with what; bit 0 direct colour, not drawn on Rachael | |
| `SDEVKIT_PPU_CGADSUB` | `$2131` | colour math, how and on which layers | |
| `SDEVKIT_PPU_COLDATA` | `$2132` | colour math's fixed colour, one write per channel | |
| `SDEVKIT_PPU_SETINI` | `$2133` | interlace, high resolution, overscan: keep 0, not drawn on Rachael | |

The copy functions set the ports of VRAM, CGRAM and OAM themselves; a game writes them only for
[`sdevkit_dma`](#sdevkit-dma). The vertical scroll counts one line more than it seems: screen
line y shows map line y + `VOFS` + 1, so `0x3FF` puts map line 0 on screen line 0.

The macros below build the values of the registers.

| Macro | Register | Value |
| --- | --- | --- |
| `SDEVKIT_FORCED_BLANK` | `INIDISP` | `0x80`: the screen off, black, and VRAM, CGRAM and OAM take writes at any time |
| `SDEVKIT_BG_MODE1` | `BGMODE` | 1: BG1 and BG2 with 16 colours a tile, BG3 with 4, and the sprites |
| `SDEVKIT_BG_MODE7` | `BGMODE` | 7: one layer, BG1, rotated and scaled ([mode 7](#colour-math-and-mode-7)) |
| `SDEVKIT_BG3_MODE1_PRIORITY_HIGH` | `BGMODE` | BG3's tiles with the priority bit in front of everything (a status line) |
| `SDEVKIT_BG1_TILES_16` to `SDEVKIT_BG4_TILES_16` | `BGMODE` | that layer with tiles of 16 x 16 pixels: map entry tile t draws t, t + 1, t + 16, t + 17 |
| `SDEVKIT_BG1_ENABLE` to `SDEVKIT_BG4_ENABLE`, `SDEVKIT_OBJ_ENABLE` | `TM`, `TS`, `TMW`, `TSW` | the bits of the layers and of the sprites, joined with a bitwise or |
| `SDEVKIT_BGSC(address, size)` | `BGnSC` | the map at a VRAM word address, a multiple of `0x400`, and its size |
| `SDEVKIT_SC_32x32`, `_64x32`, `_32x64`, `_64x64` | `BGnSC` | 0 to 3: one map, two side by side, two one below the other, four |
| `SDEVKIT_BGNBA(first, second)` | `BG12NBA`, `BG34NBA` | the tiles of two layers, each at a VRAM word address, a multiple of `0x1000` |
| `SDEVKIT_OBSEL(address, sizes)` | `OBSEL` | the sprites' tiles at a VRAM word address, a multiple of `0x2000`, and their sizes |
| `SDEVKIT_OBJ_SIZE8_L16`, `_SIZE8_L32`, `_SIZE8_L64`, `_SIZE16_L32`, `_SIZE16_L64`, `_SIZE32_L64` | `OBSEL` | the small and the large sprite size: `SDEVKIT_OBJ_SIZE16_L32` is 16 x 16 small and 32 x 32 large |
| `SDEVKIT_WIN1_ON`, `SDEVKIT_WIN2_ON` | `W12SEL`, `W34SEL`, `WOBJSEL` | that window counts for this layer |
| `SDEVKIT_WIN1_INVERT`, `SDEVKIT_WIN2_INVERT` | `W12SEL`, `W34SEL`, `WOBJSEL` | ... as the columns outside it |
| `SDEVKIT_WSEL(first, second)` | `W12SEL`, `W34SEL`, `WOBJSEL` | the 4 bits of the register's two layers |
| `SDEVKIT_WLOG_OR`, `_AND`, `_XOR`, `_XNOR` | `WBGLOG`, `WOBJLOG` | with both windows on, the area is in either, both, exactly one, both or neither |

A map is 32 x 32 entries of one word each, row by row; entry (column c, row r) of a 32 x 32 map
is the word at `map + r * 32 + c`. A larger map is two or four of them one after the other in
VRAM. A layer's tiles start at their address: tile n of a 16-colour layer (32 bytes, 16 words a
tile) is at `address + 16n`, of a 4-colour layer (16 bytes) at `address + 8n`. A map entry is
built from these macros, joined with `|`:

| Macro | Bits |
| --- | --- |
| `SDEVKIT_BG_TIL_NUM(n)` | the tile, 0 to 1023 |
| `SDEVKIT_BG_TIL_PAL(n)` | the palette, 0 to 7 |
| `SDEVKIT_BG_TIL_PRIO` | the priority bit |
| `SDEVKIT_BG_TIL_FLIPX` | mirrored left to right |
| `SDEVKIT_BG_TIL_FLIPY` | mirrored top to bottom |

In mode 1 the layers lie in front of each other in this order, front to back (P is a map entry
with the priority bit, and a sprite's priority 0 to 3 is in its attributes): sprites 3, BG1 P,
BG2 P, sprites 2, BG1, BG2, sprites 1, BG3 P, sprites 0, BG3, the
[backdrop](/glossary#backdrop).

A colour is 15 bits, 5 each of red, green and blue, blue in the high bits. Two macros build one:
`SDEVKIT_RGB5(r, g, b)` from values 0 to 31, and `SDEVKIT_RGB8(r, g, b)` from values 0 to 255, of
which it keeps the top 5 bits. In mode 1 a background palette p of 16 colours is CGRAM colours
16p to 16p + 15, a palette of 4 colours (BG3) colours 4p to 4p + 3, and sprite palette p colours
128 + 16p to 128 + 16p + 15. Colour 0 of every palette is transparent; CGRAM colour 0 itself is
the backdrop. Which modes and features the kit draws is in
[The SNES hardware](/guide/snes-hardware#what-the-dev-kit-has).

The program below sets up mode 1 with one layer, puts one tile on it, and scrolls it with the
joypad in `game_vblank`. VRAM starts as zeros, so every other map entry shows tile 0, which is
transparent, and the backdrop shows through.

```c
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

#define VRAM_TILES 0x0000      /* BG1's tiles */
#define VRAM_MAP   0x1000      /* BG1's map: 32 x 32 entries */

static const uint8_t TILE[32] = {                  /* a 4-bit tile: colour 1 everywhere */
    0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0,
};
static const uint16_t ENTRY = SDEVKIT_BG_TIL_NUM(1) | SDEVKIT_BG_TIL_PAL(0);
static uint16_t scroll_x;

void game_init(uint16_t seed)
{
    (void)seed;
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(VRAM_MAP, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG12NBA, SDEVKIT_BGNBA(VRAM_TILES, VRAM_TILES));
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE);
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, 0x3FF);           /* map line 0 on screen line 0 */
    sdevkit_vram_copy(VRAM_TILES + 16, TILE, sizeof TILE);         /* tile 1 (16 words a 4-bit tile) */
    sdevkit_vram_copy(VRAM_MAP + 5 * 32 + 3, &ENTRY, 2);           /* map row 5, column 3 */
    sdevkit_cgram_set(0, SDEVKIT_RGB5(0, 0, 8));                   /* the backdrop: dark blue */
    sdevkit_cgram_set(1, SDEVKIT_RGB5(31, 31, 0));                 /* palette 0, colour 1: yellow */
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);                    /* the screen on, full brightness */
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    if (pad[0] & SDEVKIT_KEY_RIGHT) {
        scroll_x++;                    /* only the game's memory */
    }
    if (pad[0] & SDEVKIT_KEY_LEFT) {
        scroll_x--;
    }
}

void game_vblank(void)
{
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, scroll_x);        /* the hardware */
}
```

## Colour math and mode 7

Colour math adds the fixed colour (`COLDATA`) or the sub screen (`TS`) to the main screen's
pixel, or subtracts it, per channel, clamped to 0 to 31: for shadows, fades, light and
transparency. `CGWSEL` says where and with what, `CGADSUB` how and on which layers; both are
written with [`sdevkit_reg_write`](#sdevkit-reg-write). Some of the constants name the colour
window, an area of the screen set with the window registers.

For `CGWSEL`:

| Constant | Meaning |
| --- | --- |
| `SDEVKIT_CM_SUBBGOBJ_ENABLE` | add or subtract the sub screen, not the fixed colour; where the sub screen is transparent, the fixed colour |
| `SDEVKIT_CM_ALWAYS_ENABLE` | colour math everywhere |
| `SDEVKIT_CM_MATWIN_ENABLE` | only inside the colour window |
| `SDEVKIT_CM_NOMATWIN_ENABLE` | only outside the colour window |
| `SDEVKIT_CM_NEVER_ENABLE` | never |
| `SDEVKIT_CM_SCBLK_ALWAYS_ENABLE` | main screen black: never |
| `SDEVKIT_CM_SCBLK_MATWIN_ENABLE` | main screen black outside the colour window |
| `SDEVKIT_CM_SCBLK_NOMATWIN_ENABLE` | main screen black inside the colour window |
| `SDEVKIT_CM_SCBLK_NEVER_ENABLE` | main screen black everywhere, before colour math: the fixed colour or the sub screen alone |

The names are [PVSnesLib](/glossary#pvsneslib)'s and read against their meaning in the `SCBLK`
rows: `MATWIN` is the colour window and `NOMATWIN` the rest of the screen, and each `SCBLK` name
says where the main screen stays enabled. So `SDEVKIT_CM_SCBLK_ALWAYS_ENABLE` never makes it
black, and `SDEVKIT_CM_SCBLK_MATWIN_ENABLE` makes it black outside the colour window.

For `CGADSUB`:

| Constant | Meaning |
| --- | --- |
| `SDEVKIT_CM_SUB_MAINSUB` | subtract instead of add |
| `SDEVKIT_CM_DIV2_DIVIDE` | halve the result, not where the main screen is made black |
| `SDEVKIT_CM_MSCR_BACK` | colour math on the backdrop |
| `SDEVKIT_CM_MSCR_PAL47` | on sprites with palettes 4 to 7, never 0 to 3 |
| `SDEVKIT_CM_MSCR_BG1` to `SDEVKIT_CM_MSCR_BG4` | on that layer |

`COLDATA` takes one write per channel: `SDEVKIT_CM_APPLY_RED`, `SDEVKIT_CM_APPLY_GREEN` or
`SDEVKIT_CM_APPLY_BLUE` joined with an intensity of 0 to 31, and one write may set several
channels. These three writes, in `game_vblank` or with the screen off, add a dark red to BG1 and
the backdrop:

```c
sdevkit_reg_write(SDEVKIT_PPU_CGWSEL, SDEVKIT_CM_ALWAYS_ENABLE);
sdevkit_reg_write(SDEVKIT_PPU_CGADSUB, SDEVKIT_CM_MSCR_BG1 | SDEVKIT_CM_MSCR_BACK);
sdevkit_reg_write(SDEVKIT_PPU_COLDATA, SDEVKIT_CM_APPLY_RED | 8);
```

With `SDEVKIT_BG_MODE7` the screen has one background, BG1, which a matrix rotates, scales and
shears. Its map is 128 x 128 tiles (1024 x 1024 pixels, one byte a tile, row by row), and its 256
tiles are 8 x 8 pixels with one byte a pixel, an index into all 256 colours of CGRAM, 0
transparent, row by row and without bit planes. Map and tiles fill the first 32 KB of VRAM,
interleaved: the map in the low byte of words `0x0000` to `0x3FFF`, the tiles in the high byte
([`sdevkit_mode7_map_copy`](#sdevkit-mode7-map-copy),
[`sdevkit_mode7_tiles_copy`](#sdevkit-mode7-tiles-copy)). Nothing else of a game may be there, so
its sprite tiles go to `0x4000` or above. In front of the layer are the sprites of priority 1 to
3, behind it those of priority 0.

A screen pixel (x, y) shows the map pixel

```
(a b; c d) * (x + hscroll - cx, y + vscroll - cy) + (cx, cy)
```

with a, b, c and d in 8.8 fixed point (`0x0100` is 1.0), the centre (cx, cy) and the scroll in
pixels, 13 bits with sign. To turn the picture by an angle t and zoom it by z (2.0 shows the map
at half its size), set a = d = cos(t) · z and b = sin(t) · z, c = −b, each times 256. To put
map pixel (mx, my) in the middle of the screen, set the centre to (mx, my) and the scroll to
(mx − 128, my − 112).

| Part | Register | Written with |
| --- | --- | --- |
| a, b, c, d | `M7A` to `M7D` | `sdevkit_reg_write_twice` |
| the centre cx, cy | `M7X`, `M7Y` | `sdevkit_reg_write_twice` |
| the scroll | `BG1HOFS`, `BG1VOFS` | `sdevkit_reg_write_twice` |
| outside the map, mirroring | `M7SEL` | `sdevkit_reg_write` |

`M7SEL` takes one of three values for what lies outside the map, joined with `|` to the
mirroring:

| Constant | Value | Meaning |
| --- | --- | --- |
| `SDEVKIT_M7_WRAP` | `0x00` | the map repeats |
| `SDEVKIT_M7_TRANSPARENT` | `0x80` | nothing: the backdrop |
| `SDEVKIT_M7_TILE0` | `0xC0` | tile 0 everywhere |
| `SDEVKIT_M7_FLIPX` | `0x01` | the whole screen mirrored left to right |
| `SDEVKIT_M7_FLIPY` | `0x02` | the whole screen mirrored top to bottom |

A tick that turns the picture computes the matrix, and `game_vblank` writes it:

```c
// in game_vblank, from a matrix the tick computed
sdevkit_reg_write_twice(SDEVKIT_PPU_M7A, (uint16_t)m7a);
sdevkit_reg_write_twice(SDEVKIT_PPU_M7B, (uint16_t)m7b);
sdevkit_reg_write_twice(SDEVKIT_PPU_M7C, (uint16_t)-m7b);
sdevkit_reg_write_twice(SDEVKIT_PPU_M7D, (uint16_t)m7a);
```

The first program under [The frame rhythm](#the-frame-rhythm) is a whole game in mode 7.
[Graphics and sound](/guide/graphics-and-sound) shows how a picture becomes a mode 7 map and its
tiles.

## Copies

Each copy is one [DMA](/glossary#dma) on channel 0, made at once: in `game_vblank`, or in
`game_init` and whenever the screen is off. The addresses count in the units of each memory:
VRAM addresses count 16-bit words (`0x0000` to `0x7FFF`), CGRAM addresses count colours (0 to
255), OAM addresses count bytes (0 to 543). Sizes count bytes, and a size of 0 copies 64 KB, so
never pass 0 by accident.

DMA moves a byte every 8 master cycles while the CPU waits. A vertical blank has room for about
6,300 bytes when the game does nothing else in it, and the setup of each copy costs about 100
cycles more. Rachael counts every byte the SNES would lose, written outside a vertical blank or
over a budget of 6,244 bytes a vertical blank, and the desktop check fails on it. The rules
behind this are in [The SNES hardware](/guide/snes-hardware#video-memory).

### `sdevkit_dma`

```c
void sdevkit_dma(uint8_t dmap, uint8_t bbad, const void *src, uint16_t bytes);
```

The general DMA: `bytes` bytes from `src`, in any [bank](/glossary#bank), to the PPU register
whose low byte is `bbad` (`SDEVKIT_PPU_VMDATAL`, `_CGDATA` or `_OAMDATA`). The game sets the
port's address first with `sdevkit_reg_write` (`VMAIN`, `VMADDL` and `VMADDH`, `CGADD`, or
`OAMADDL` and `OAMADDH`); the functions below do that for the usual copies. `dmap` is the
transfer mode: `0x00` sends every byte to `bbad`, `0x01` to `bbad` and `bbad + 1` in turn (VRAM's
two ports), `0x02` every byte to `bbad` for a register written twice, and `0x08` joined to one
of them keeps the source from stepping, for a fill. A DMA from the PPU to memory (bit 7) Rachael
does not model; she counts it as undrawn. Cost: 94 cycles, and `bytes` bytes of the vertical
blank.

### `sdevkit_vram_copy`

```c
void sdevkit_vram_copy(uint16_t a, const void *src, uint16_t bytes);
```

Copies words to VRAM from word address `a` on, one after the other: tiles or a map. `src` holds
the words low byte first, as C keeps a `uint16_t`, and `bytes` is even. Cost: 116 cycles, and
`bytes` bytes of the vertical blank.

### `sdevkit_vram_column_copy`

```c
void sdevkit_vram_column_copy(uint16_t a, const void *src, uint16_t bytes);
```

Like `sdevkit_vram_copy`, but the VRAM address steps by 32 words after each word, so the words go
down a column of a map 32 entries wide, from its first entry at `a`. A game that scrolls sideways
brings in one column of a map per step this way. `bytes` is 2 per entry, at most 64 for a 32 x 32
map. Cost: 116 cycles, and `bytes` bytes of the vertical blank.

### `sdevkit_vram_fill`

```c
void sdevkit_vram_fill(uint16_t a, uint8_t value, uint16_t bytes);
```

Fills VRAM from word address `a` with `value`, in both bytes of each word; 0 clears. With
`bytes` 0 it fills 64 KB, all of VRAM, which takes far more than a vertical blank: with the
screen off. Cost: 120 cycles, and `bytes` bytes of the vertical blank.

### `sdevkit_cgram_copy`

```c
void sdevkit_cgram_copy(uint8_t i, const void *src, uint16_t bytes);
```

Copies colours to CGRAM from colour `i` on. `src` holds colours made with `SDEVKIT_RGB5`, two
bytes each, so a palette of 16 colours is 32 bytes. Cost: 100 cycles, and `bytes` bytes of the
vertical blank.

### `sdevkit_cgram_set`

```c
void sdevkit_cgram_set(uint8_t i, uint16_t bgr);
```

Writes one colour into CGRAM, colour `i`, by the CPU through the port (`CGADD`, `CGDATA`), which
is cheaper than a DMA for one or two colours. Like a copy, it belongs in `game_vblank` or into a
time when the screen is off. Cost: 47 cycles.

### `sdevkit_oam_copy`

```c
void sdevkit_oam_copy(uint16_t o, const void *src, uint16_t bytes);
```

Copies bytes of a sprite table, in the hardware's format ([Sprites](#sprites)), into OAM from byte
`o` on. `o` is even, 0 to 542, since OAM's address counts words. A game that uses the first n
sprites copies `4 * n` bytes from 0 and the `(n + 3) / 4` bytes of their high table from 512, not
all 544. Cost: 104 cycles, and `bytes` bytes of the vertical blank.

### `sdevkit_mode7_map_copy`

```c
void sdevkit_mode7_map_copy(uint16_t e, const void *src, uint16_t bytes);
```

Copies mode 7 map entries, one byte each, the tile 0 to 255, into the low byte of VRAM's words
from entry `e` on; the entry of a tile is `row * 128 + column`. `bytes` is the number of entries.
Cost: 116 cycles, and `bytes` bytes of the vertical blank.

### `sdevkit_mode7_tiles_copy`

```c
void sdevkit_mode7_tiles_copy(uint16_t b, const void *src, uint16_t bytes);
```

Copies mode 7 tile pixels, one byte each, the colour 0 to 255, into the high byte of VRAM's words
from pixel `b` on; pixel (column, row) of tile t is `t * 64 + row * 8 + column`, so a tile is 64
bytes. Cost: 116 cycles, and `bytes` bytes of the vertical blank.

### `sdevkit_dma_fits`

```c
uint8_t sdevkit_dma_fits(uint16_t bytes);
```

Returns 1 if a copy of `bytes` bytes started now still fits into the current vertical blank, and
0 if not, also while the picture is drawn. A game asks it in `game_vblank` before each copy that
may not fit, and leaves what does not fit for the next vertical blank instead of losing it. The
two platforms answer from different things. On the SNES it reads the line the picture is at, so
the code around the copies counts too: it allows 160 bytes for each line left and keeps a margin
of 8 lines for the setup of the copy. Rachael counts the bytes copied in this vertical blank
against her budget instead. The two can answer differently for the same game, so a game whose
state depends on the answer gets other checksums on each; the
[queue](#the-sprite-list-and-the-queue) decides the same on both. With the screen off there is
nothing to ask. Cost: 81 to 86 cycles.

## Sprites

The core has no sprite functions: a game keeps a sprite table in the hardware's format, wherever
it likes, and copies it into OAM with [`sdevkit_oam_copy`](#sdevkit-oam-copy) in `game_vblank`.
The table is 544 bytes in two parts.

The low table, bytes 0 to 511, has 128 entries of 4 bytes; entry n starts at byte `4n`:

| Byte | Holds |
| --- | --- |
| 0 | x, its low 8 bits |
| 1 | y |
| 2 | the tile, its low 8 bits |
| 3 | the attributes: bit 0 the tile's bit 8, bits 1-3 the palette (CGRAM 128 + 16 · palette), bits 4-5 the priority 0 to 3, bit 6 mirrored left to right, bit 7 top to bottom |

The high table, bytes 512 to 543, has 2 bits a sprite: sprite n's are in byte `512 + n / 4`,
from bit `2 * (n % 4)`. The lower one is bit 8 of x, which has 9 bits (−256 to 255, so a sprite
can go off the left edge); the upper one picks the large size of `OBSEL`.

| Name | Value | What |
| --- | --- | --- |
| `SDEVKIT_OAM_BYTES` | 544 | the bytes of a sprite table |
| `SDEVKIT_OAM_HIDE_Y` | `0xE8` | the y of a hidden sprite: below the picture |
| `SDEVKIT_OAM_ATTR(palette, priority, hflip, vflip)` | | byte 3 of an entry; join it with the tile's bit 8 |

The SNES draws a sprite one line lower than its y, so for its top on screen line y a game writes
y − 1. A hidden sprite has y `SDEVKIT_OAM_HIDE_Y` and its two high bits 0, since a large one
would reach into the top of the picture. A lower entry is in front of a higher one, whatever
their priorities. The sprites' tiles are 4-bit tiles laid out as a sheet 16 tiles wide from the
address in `OBSEL`: a sprite larger than 8 x 8 at tile t takes t, t + 1, ... to the right and
t + 16, t + 32, ... below. A line shows at most 32 sprites and 34 of their 8-pixel-wide tile
columns; beyond that the SNES leaves sprites out, and the desktop counts the lines (`sprites`).
What the PPU draws per line is in [The SNES hardware](/guide/snes-hardware#sprites). The kit's
reset hides all 128 sprites, so a game copies only the entries it uses.

The program below keeps a table of three 8 x 8 sprites and copies only those three entries and
their byte of the high table in each vertical blank. Down on the joypad moves the first one down.

```c
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

#define VRAM_SPRITES 0x4000
#define SPRITES      3         /* the entries in use */

static const uint8_t BLOCK[32] = {                 /* sprite tile 0: colour 1 everywhere */
    0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0,
};
static uint8_t oam[SDEVKIT_OAM_BYTES];             /* the game's sprite table */

void game_init(uint16_t seed)
{
    uint8_t i;

    (void)seed;
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1);
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_OBJ_ENABLE);
    sdevkit_reg_write(SDEVKIT_PPU_OBSEL, SDEVKIT_OBSEL(VRAM_SPRITES, SDEVKIT_OBJ_SIZE8_L16));
    sdevkit_vram_copy(VRAM_SPRITES, BLOCK, sizeof BLOCK);
    sdevkit_cgram_set(128 + 1, SDEVKIT_RGB5(31, 16, 0));          /* sprite palette 0, colour 1 */
    for (i = 0; i < SPRITES; i++) {
        oam[i * 4] = (uint8_t)(40 + i * 64);                     /* x */
        oam[i * 4 + 1] = 100 - 1;                                /* the top on screen row 100 */
        oam[i * 4 + 2] = 0;                                      /* tile 0 */
        oam[i * 4 + 3] = SDEVKIT_OAM_ATTR(0, 2, 0, 0);           /* palette 0, priority 2 */
    }
    oam[512] = 0;                      /* sprites 0 to 3: small, x below 256 */
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    if (pad[0] & SDEVKIT_KEY_DOWN) {
        oam[1]++;                      /* sprite 0 moves down */
    }
}

void game_vblank(void)
{
    sdevkit_oam_copy(0, oam, SPRITES * 4);                       /* the three entries */
    sdevkit_oam_copy(512, oam + 512, (SPRITES + 3) / 4);         /* their byte of the high table */
}
```

A game that draws its sprites anew every tick can use the
[sprite list](#the-sprite-list-and-the-queue) instead, which copies only the entries in use and
hides the ones of the tick before.

## HDMA

An [HDMA](/glossary#hdma) channel writes data from a table into a PPU register at the start of
every line, from the next frame on, while its bit is set: a scroll per line (a wave, a floor in
perspective), windows per line (a circle), a colour per line (a gradient), a mode per band.
Channel 0 is the copies': every copy sets it up anew for itself, so a game has channels 1 to 7.

The table has the format of the SNES: entries of a line count byte and data, ended by a byte 0.
A count of 1 to 128 writes its data once and holds it for that many lines; a count of 129 to 255
writes new data on each of (count − 128) lines, so the entry has that many data. The data is one
byte per write with `SDEVKIT_HDMA_ONE` and two with the other modes. The first entry applies to
the first line of the picture, and the register keeps the value written last when the table ends.
The channel reads the table while the frame is drawn, so it must stay valid: a `static` or ROM
array, never a local variable. Two tables and switching between them in `game_vblank` change the
effect without tearing.

| Name | Value | What |
| --- | --- | --- |
| `SDEVKIT_HDMA_ONE` | 0 | one byte to the register |
| `SDEVKIT_HDMA_TWO_REGS` | 1 | two bytes, to the register and the next (`WH0` and `WH1`: window 1 per line) |
| `SDEVKIT_HDMA_TWICE` | 2 | two bytes to the register, for one written twice (a scroll register) |
| `SDEVKIT_HDMA_CHANNEL1` to `SDEVKIT_HDMA_CHANNEL7` | `1 << n` | channel n's bit for `sdevkit_hdma_enable` |

### `sdevkit_hdma_set`

```c
void sdevkit_hdma_set(uint8_t channel, uint8_t reg, uint8_t mode, const void *table);
```

Sets up channel `channel`, a number from 1 to 7, to write from `table` into the register `reg`, an
`SDEVKIT_PPU_*` name, in the transfer mode `mode`. It runs once
[`sdevkit_hdma_enable`](#sdevkit-hdma-enable) switches it on. Like a register write, it belongs in
`game_vblank` or into a time when the screen is off; the desktop counts it while the picture is
drawn. Rachael draws the three direct modes above; another mode, or indirect HDMA, she counts as a
write she does not draw. Cost: 84 cycles.

### `sdevkit_hdma_enable`

```c
void sdevkit_hdma_enable(uint8_t channels);
```

Says which channels run from the next frame on, writing `HDMAEN` at once, in `game_vblank` or
with the screen off. Where `sdevkit_hdma_set` takes the
channel's number, this function takes a mask, an `|` of `SDEVKIT_HDMA_CHANNEL1` to
`SDEVKIT_HDMA_CHANNEL7`: channel 3 is `sdevkit_hdma_set(3, ...)` and `SDEVKIT_HDMA_CHANNEL3`. Each
call replaces the mask, so it names every channel that is to run, and 0 stops them all. Cost: 21
cycles.

The program below fills BG1 with vertical stripes, four pixels white and four black, and shifts
a band of them 4 pixels to the side, from line 32 to line 63, so that in the band the white and
the black change places. Its table has three entries: 32 lines with scroll 0, 32 lines with
scroll 4, and one line with 0 again. The last one is needed because the scroll keeps the value
written last: without it, everything from line 64 down would stay shifted. VRAM is cleared
before `game_init`, so the map is all zeros and shows tile 0 everywhere.

```c
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

#define VRAM_TILES 0x0000      /* BG1's tiles */
#define VRAM_MAP   0x1000      /* BG1's map: all zeros, so tile 0 everywhere */

/* Tile 0: four pixels of colour 1, then four of colour 0, on each of its 8 rows. 16 colours
 * take 4 bit planes; the bytes left out are 0. */
static const uint8_t STRIPES[32] = {
    0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0, 0xF0, 0
};

/* BG1's horizontal scroll down the screen: 0 for 32 lines, 4 for the next 32, then 0 again */
static const uint8_t WAVE[] = {
    32, 0, 0,
    32, 4, 0,
    1, 0, 0,
    0
};

void game_init(uint16_t seed)
{
    (void)seed;
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(VRAM_MAP, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_BG12NBA, SDEVKIT_BGNBA(VRAM_TILES, VRAM_TILES));
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE);
    sdevkit_vram_copy(VRAM_TILES, STRIPES, sizeof STRIPES);
    sdevkit_cgram_set(1, SDEVKIT_RGB5(31, 31, 31));               /* colour 1 white; 0 stays black */
    sdevkit_hdma_set(3, SDEVKIT_PPU_BG1HOFS, SDEVKIT_HDMA_TWICE, WAVE);
    sdevkit_hdma_enable(SDEVKIT_HDMA_CHANNEL3);
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    (void)pad;
}

void game_vblank(void)
{
}
```

## The register shadow

The register shadow, `sdevkit/shadow.h`, is an optional layer: the PPU's registers and the HDMA
channels kept in memory, laid out as the hardware has them. `game_tick` writes and reads them
whenever it likes, and one call in `game_vblank` writes them into the hardware. A register's
meaning, its bits and the macros for its values are at its `SDEVKIT_PPU_*` name under
[Registers](#registers).

```c
extern sdevkit_ppu_regs sdevkit_ppu;
extern sdevkit_hdma_channel sdevkit_hdma[8];
extern uint8_t sdevkit_marks;
```

`sdevkit_ppu` holds the registers the next commit writes. It starts as the console after the
kit's reset, but in mode 1 and with the screen off. Since the vertical blank is short and most
registers change seldom, the commit writes `inidisp` every time and the other fields only when
the tick marks their group in `sdevkit_marks`, which the commit clears. The fields, by their
marks:

| Mark | Value | Fields | Registers |
| --- | --- | --- | --- |
| (every commit) | | `inidisp` | `INIDISP`: brightness 0 to 15, or `SDEVKIT_FORCED_BLANK` |
| `SDEVKIT_MARK_SCROLL` | `0x10` | `hofs[4]`, `vofs[4]` | `BG1HOFS` to `BG4VOFS` |
| `SDEVKIT_MARK_COLOR` | `0x20` | `coldata`, `cgwsel`, `cgadsub` | `COLDATA`, `CGWSEL`, `CGADSUB` |
| `SDEVKIT_MARK_MODE7` | `0x04` | `m7a`, `m7b`, `m7c`, `m7d`, `m7x`, `m7y`, `m7sel` | `M7A` to `M7Y`, `M7SEL` |
| `SDEVKIT_MARK_SETUP` | `0x01` | `bgmode`, `obsel`, `mosaic`, `bgsc[4]`, `bgnba[2]`, `setini`, `tm`, `ts` | `BGMODE`, `OBSEL`, `MOSAIC`, `BG1SC` to `BG4SC`, `BG12NBA`, `BG34NBA`, `SETINI`, `TM`, `TS` |
| `SDEVKIT_MARK_WINDOW` | `0x02` | `w12sel`, `w34sel`, `wobjsel`, `wh[4]`, `wbglog`, `wobjlog`, `tmw`, `tsw` | `W12SEL` to `WOBJSEL`, `WH0` to `WH3`, `WBGLOG`, `WOBJLOG`, `TMW`, `TSW` |
| `SDEVKIT_MARK_HDMA` | `0x08` | the channels of `sdevkit_hdma` that `hdmaen` switches on, `hdmaen` | the channels' registers, `HDMAEN` |
| `SDEVKIT_MARK_ALL` | `0x3F` | every group | |

Three fields differ from their register. `coldata` is one colour made with `SDEVKIT_RGB5`, which
the commit writes as `COLDATA`'s three channels. The mode 7 fields are `int16_t`. `mosaic` and
`setini` are not drawn on Rachael: keep them 0. The struct has no padding, 16-bit fields first,
so it is the same on the SNES and the PC and a game may name it to
[`sdevkit_dbg_hash`](#sdevkit-dbg-hash).

`sdevkit_hdma` holds channels 1 to 7 (channel 0 is the copies'), each a struct of three fields:

| Field | What |
| --- | --- |
| `table` | the table, `static` or in ROM |
| `dmap` | the mode: `SDEVKIT_HDMA_ONE`, `_TWO_REGS` or `_TWICE` |
| `bbad` | the register, an `SDEVKIT_PPU_*` name |

Every group starts marked, so the first commit writes all of them. A change to a group without
its mark is not committed: on the SNES the register keeps its old value. On the desktop the
commit finds such a change and counts it (`unmarked`), and the check fails on it, so a forgotten
mark shows there and not only on the console. A game may mix the shadow with
[`sdevkit_reg_write`](#sdevkit-reg-write): what it writes itself stays until a commit of its
group writes the register again.

### `sdevkit_shadow_commit`

```c
void sdevkit_shadow_commit(void);
```

Writes the shadow into the hardware, in `game_vblank`, or in `game_init` with the screen off. A
screen to be switched off goes off first; then the registers of the groups marked; then
`INIDISP`, which may switch the screen on. Cost: 135 to 2,143 cycles, depending on the groups
marked; the worst case, every group and seven HDMA channels, is 2,143 cycles in the timing
report of a program that asks for it in every tick.

The program below scrolls BG1 with the joypad through the shadow:

```c
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/shadow.h"

void game_init(uint16_t seed)
{
    (void)seed;
    sdevkit_ppu.bgmode = SDEVKIT_BG_MODE1;
    sdevkit_ppu.tm = SDEVKIT_BG1_ENABLE;
    sdevkit_ppu.bgsc[0] = SDEVKIT_BGSC(0x1000, SDEVKIT_SC_32x32);
    sdevkit_ppu.inidisp = 15;          /* on at the first commit; no mark needed at the start */
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    if (pad[0] & SDEVKIT_KEY_RIGHT) {
        sdevkit_ppu.hofs[0]++;
        sdevkit_marks |= SDEVKIT_MARK_SCROLL;
    }
}

void game_vblank(void)
{
    sdevkit_shadow_commit();
}
```

## The sprite list and the queue

The sprite list (`sdevkit/sprites.h`) is for a game that draws its sprites anew every tick: it
appends them in `game_tick`, and one call in `game_vblank` copies into OAM what the list filled
and hides what it filled the tick before and not in this one. Only the entries in use are copied.
The sprites' tiles and sizes are those of `OBSEL` ([Sprites](#sprites)).

```c
extern uint8_t sdevkit_oam[SDEVKIT_OAM_BYTES];
```

`sdevkit_oam` is the table the list fills, in the hardware's format. The entries after the list's
are not kept up to date there: the commit hides them in OAM, not in this table.

### `sdevkit_sprite`

```c
void sdevkit_sprite(int16_t x, int16_t y, uint16_t tile, uint8_t attr, uint8_t large);
```

Appends one sprite to this tick's list, in `game_tick`; it reaches OAM at the next commit.
Sprites go in the order of their importance: the first is in front, and the last is the one the
SNES leaves out when a line has more than 32. `x` (−64 to 255) and `y` (−64 to 223) are the left
and the top edge, where a background scrolled by (0, 0) shows its pixel, so a sprite at its
world position minus the scroll lines up with the background it moves on. The list writes y − 1
itself, since the SNES draws a sprite one line lower. `tile` is the sprite's top left tile, 0 to
511, `attr` is made with `SDEVKIT_OAM_ATTR`, and `large` is 0 for `OBSEL`'s small size and 1 for
its large one. A sprite out of the picture is left out (for sprites up to 64 pixels), as is the
129th. Cost: 21 to 133 cycles.

### `sdevkit_sprites_commit`

```c
void sdevkit_sprites_commit(void);
```

In `game_vblank`: copies the list into OAM and empties it for the next tick. It makes up to four
DMAs, 544 bytes at most: the entries filled (4 bytes each) and their bytes of the high table,
then the entries of the tick before to hide and theirs, zero. A tick that appends nothing hides
what the tick before showed. Cost: 60 to 560 cycles besides the DMAs.

The upload queue (`sdevkit/queue.h`) is for copies into VRAM and CGRAM that a tick asks for
where it knows what changed. The commit makes them in `game_vblank`, in the order queued. Each
function returns 1 when the copy fits into that vertical blank and 0, with nothing queued, when
it does not: the game then asks again in the next tick. What fits is decided when the game asks,
in bytes, the same on the SNES and the PC, so the two take and refuse the same copies and the
checks compare them. The source is read at the commit, so it must still hold the data then: a
buffer reused for the next copy in the same tick needs a copy of its own.

| Name | Value | What |
| --- | --- | --- |
| `SDEVKIT_QUEUE_BYTES` | 3,072 | the bytes a vertical blank takes, setups counted |
| `SDEVKIT_QUEUE_COST` | 136 | what each copy's setup counts, in bytes |
| `SDEVKIT_QUEUE_ENTRIES` | 24 | the copies a vertical blank takes at most |

The budget comes from the worst case of the layers' commit. The vertical blank is 37 lines of
1,364 master cycles, less 40 a line for the RAM's refresh: 48,988. The commit's worst case without
the queue takes 18,865 of them, the sprites' DMA 4,352: 25,771 are left, 3,221 bytes of DMA. A
copy's setup of 153 cycles is as long as 134 bytes. Hence the 3,072 bytes and 136 a copy, with
149 bytes to spare. The numbers hold when the commit starts `game_vblank`, after at most the other
layers' commits; a game that does its own copies before it has less.

### `sdevkit_queue_dma`

```c
uint8_t  sdevkit_queue_dma(uint8_t port, uint8_t dmap, uint8_t vmain, uint16_t dest, const void *src, uint16_t bytes);
```

Queues a copy in its raw form; the functions below fill it in with half the parameters. `port`
is the register it writes, the low byte of `$21xx`: `0x18` or `0x19` for VRAM, `0x22` for CGRAM.
`dmap` is the transfer mode of [`sdevkit_dma`](#sdevkit-dma), `vmain` how the VRAM address steps
(0 for CGRAM), `dest` a VRAM word address or a CGRAM colour, and `bytes` 1 to
`SDEVKIT_QUEUE_BYTES`; 0 is refused. Returns 1 if queued, 0 if it does not fit. Cost: 59 to 198
cycles.

### `sdevkit_queue_vram`

```c
uint8_t  sdevkit_queue_vram(uint16_t a, const void *src, uint16_t bytes);
```

Queues words to VRAM from word address `a`, as [`sdevkit_vram_copy`](#sdevkit-vram-copy) copies
them. Returns 1 if queued, 0 if refused. Cost: 29 cycles.

### `sdevkit_queue_vram_column`

```c
uint8_t  sdevkit_queue_vram_column(uint16_t a, const void *src, uint16_t bytes);
```

Queues words down a column of a map 32 entries wide, as
[`sdevkit_vram_column_copy`](#sdevkit-vram-column-copy) copies them; `bytes` is 2 per entry, at
most 64. Returns 1 if queued, 0 if refused. Cost: 29 cycles.

### `sdevkit_queue_cgram`

```c
uint8_t  sdevkit_queue_cgram(uint8_t i, const void *src, uint16_t bytes);
```

Queues colours to CGRAM from colour `i`, as [`sdevkit_cgram_copy`](#sdevkit-cgram-copy) copies
them. Returns 1 if queued, 0 if refused. Cost: 29 cycles.

### `sdevkit_queue_color`

```c
uint8_t  sdevkit_queue_color(uint8_t i, uint16_t bgr);
```

Queues one colour, `bgr` for colour `i`. The queue keeps the value, so it needs no source that
stays. Returns 1 if queued, 0 if refused. Cost: 79 to 246 cycles.

### `sdevkit_queue_mode7_map`

```c
uint8_t  sdevkit_queue_mode7_map(uint16_t e, const void *src, uint16_t bytes);
```

Queues mode 7 map entries from entry `e`, as
[`sdevkit_mode7_map_copy`](#sdevkit-mode7-map-copy) copies them. Returns 1 if queued, 0 if
refused. Cost: 29 cycles.

### `sdevkit_queue_mode7_tiles`

```c
uint8_t  sdevkit_queue_mode7_tiles(uint16_t b, const void *src, uint16_t bytes);
```

Queues mode 7 tile pixels from pixel `b`, as
[`sdevkit_mode7_tiles_copy`](#sdevkit-mode7-tiles-copy) copies them. Returns 1 if queued, 0 if
refused. Cost: 29 cycles.

### `sdevkit_queue_room`

```c
uint16_t sdevkit_queue_room(void);
```

Returns how many bytes one more copy may have now, or 0 when none fits. Cost: 42 to 53 cycles.

### `sdevkit_queue_commit`

```c
void sdevkit_queue_commit(void);
```

In `game_vblank`: makes the queued copies, in the order queued, and empties the queue for the
next tick. On the SNES it writes the registers of channel 0 itself rather than through the core's
copies, since the vertical blank pays for every cycle of the setup. A copy's setup takes about
150 cycles besides the DMA itself (153 in the timing report of `dev/commit_stress`). Cost: 48 to
3,672 cycles besides the DMAs.

The second program under [The frame rhythm](#the-frame-rhythm) queues its map: `map_changed`
stays set when the queue refuses the copy, so the copy is asked for again in the next tick.

## The buttons pressed and the one commit

`game_tick` gets the buttons held; a menu or a jump wants the ones pressed now and not in the
tick before. The input layer, `sdevkit/input.h`, keeps both:

```c
typedef struct {
    uint16_t pad[SDEVKIT_PADS];        /**< The buttons held (SDEVKIT_KEY_*). */
    uint16_t pressed[SDEVKIT_PADS];    /**< The buttons held now and not at the call before. */
} sdevkit_input_state;

extern sdevkit_input_state sdevkit_input;
```

### `sdevkit_input_update`

```c
void sdevkit_input_update(const uint16_t pad[SDEVKIT_PADS]);
```

Takes `game_tick`'s `pad`, at the start of `game_tick`, and fills `sdevkit_input`: the buttons
held, and the ones pressed since the last call. Cost: 86 cycles.

```c
void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    sdevkit_input_update(pad);
    if (sdevkit_input.pressed[0] & SDEVKIT_KEY_A) {
        jump();                          /* once a press, not once a frame */
    }
}
```

The header `sdevkit/easy.h` brings all the layers, the register shadow, the sprite list, the
queue and the input, and one commit for them. With it a game writes its next picture in
`game_tick`, and its `game_vblank` is one line, as in the second program under
[The frame rhythm](#the-frame-rhythm).

### `sdevkit_commit`

```c
void sdevkit_commit(void);
```

Commits all layers, in `game_vblank` as its first call. A screen to be switched off goes off
first; then the sprite list, the registers of the groups marked, the queue; then `INIDISP`, which
may switch the screen on. It is also allowed in `game_init`, where the screen is off, for a
picture before a long job such as [`sdevkit_sfx_init`](#sdevkit-sfx-init). Cost: 249 to 6,381
cycles.

The commit is the one piece of the kit that runs in the vertical blank when a game calls it. In
CPU cycles on the SNES, from the timing report of `dev/commit_stress` (2026-11-25):

| Part | Cycles |
| --- | ---: |
| `sdevkit_commit`, nothing marked, no sprite, nothing queued | 238 |
| `sdevkit_shadow_commit`, every group and seven HDMA channels | 2,143 |
| `sdevkit_sprites_commit`, at most | 560 |
| `sdevkit_queue_commit`, a copy's setup | 153 |
| `sdevkit_commit`'s worst case without the queue | 2,695 |

A program that asks for the worst case in every tick, every group, the seven channels, the queue
filled to its budget and the sprite list full, played 600 ticks in [Mesen](/glossary#mesen) on
2026-11-25 without a byte reaching VRAM or OAM outside the vertical blank.

A game mixes the layers with the core where it needs to. Each layer costs only what its functions
cost, and a game that outgrows one replaces it by the core where it matters and keeps the rest.
Registers the game writes with `sdevkit_reg_write` stay until a commit of their group writes them
again. Copies the game makes itself in `game_vblank` take time from the queue: its budget holds
when the commit starts the vertical blank, so a game that copies before the commit has less.

`examples/catchme` is such a game. Its registers, its copies and the buttons pressed go through
the layers, but its six sprites (the mouse, the four cats and a bonus) keep fixed slots from tick
to tick in a sprite table of its own, which `game_tick` changes where a sprite changed. It never
calls `sdevkit_sprite`, so the sprite list stays empty and its commit does nothing. `game_vblank`
copies just the six slots and their two bytes of the high table with the core, 26 bytes instead
of 544, and then commits the layers. The two functions below are cut from `actors.c` and
`game.c`; `SLOTS` is 6:

```c
/* In game_vblank: the six slots and their two bytes of the high table, 26 bytes, not 544. */
void sprites_copy(void)
{
    sdevkit_oam_copy(0, sprite_table, SLOTS * 4);
    sdevkit_oam_copy(512, sprite_table + 512, (SLOTS + 3) / 4);
    sdevkit_dbg_hash(sprite_table, SLOTS * 4);  /* the checks: the sprites (with the next tick's state) */
}

void game_vblank(void)
{
    sprites_copy();
    sdevkit_commit();
}
```

The 26 bytes come before the commit, which leaves the queue that much less of the vertical blank
than its budget assumes.

## Sound effects

A game lists its effects in `assets/sfx/sfx.json` and names that file in its
[project file](/glossary#project-file) (`[sound] sfx = "assets/sfx/sfx.json"`). Before it
compiles, `superdevkit build` converts the file into `build/gen/sfx.h` and `sfx.c`: a name per
effect, `SFX_` and its id in capitals, whose value is the effect's place in the list, then
`SFX_COUNT`, and the bank `sfx_bank`, which holds the samples and the tables of the effects.
How the file is made and what its settings do is in
[Graphics and sound](/guide/graphics-and-sound#sound).

The SNES plays sound on a CPU of its own, the SPC700, with a DSP of 8 voices, from samples in its
own 64 KB of audio RAM. The bank's type is declared in `sdevkit/snes.h`:

```c
#define SDEVKIT_SFX_OFF 0xFF              /**< The sample of an effect without an active one. */

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
```

The build fills in every field; a game only passes the bank on. An effect whose file lists no
active sample has `SDEVKIT_SFX_OFF` as its sample and plays nothing, but its name stays, so the
game builds unchanged.

### `sdevkit_sfx_init`

```c
void sdevkit_sfx_init(const sdevkit_sfx_bank *bank);
```

Loads the bank's samples into the audio RAM and starts the sound. Call it once, in `game_init`; a
second call does nothing, and so does a null `bank`. On the SNES it waits for the boot code of
the SPC700 and sends every byte through it, about 350 bytes a frame: Schach's 13.8 KB take 40
frames after the splash screen. The CPU does nothing else meanwhile, so the pads are not read,
but the PPU keeps showing its picture. A game that draws its title and switches the screen on
before this call, as the last thing in `game_init`, shows the title during the upload instead of
a black screen. On Rachael the same bytes go straight into her audio RAM. Cost: 51 cycles to
unbounded, since the wait for the sound CPU has no bound the timing report can see.

### `sdevkit_sfx_play`

```c
void sdevkit_sfx_play(uint8_t id);
```

Asks for the effect `id`, one of the `SFX_*` names, in `game_tick`. It starts after the tick,
once the main loop has run `game_vblank`: a free voice takes it; when all of the bank's voices are
busy, it takes the voice of the lowest priority that is not above its own, of two equal ones the
older; when every playing effect has a higher priority, it is dropped. A voice is free again when
its effect's length in frames has counted down. All effects of one tick start together, and more
than 8 requests in one tick are dropped. An effect without an active sample, or a call before
`sdevkit_sfx_init`, does nothing. This choice is the same C code on the SNES and on Rachael, so
the same voice plays the same effect on both, frame by frame. Cost: 39 to 158 cycles.

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

On Rachael, returns VRAM as 32,768 words, for a debug check of the maps. The SNES cannot read
VRAM like memory, so there it returns a single zero word, and a check that uses it runs only on
the desktop. Cost: 14 cycles.

### `sdevkit_dbg_hash`

```c
void sdevkit_dbg_hash(const void *data, uint16_t bytes);
```

Names memory that makes up the game's state, for the checks that play a game on the SNES and on
the PC with the same input and compare a checksum after every tick. The bytes named between the
end of one `game_tick` and the end of the next, `game_vblank` included, are hashed (FNV-1a, 32
bits) in the order named. The kit names nothing itself: a game names what its next picture
depends on, such as its sprite table and its positions. Name data whose bytes are the same on
both platforms: arrays of `uint8_t` and `uint16_t` (both platforms are little endian), structs
only when they have no padding on the PC, and nothing that holds pointers.

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
next `game_tick` and sets it back to 0. Cost: 6 cycles for the empty function on the SNES; in a
game dampf removes the call.

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

## Interrupts

Two kinds of interrupt code reach the hardware while a tick runs, both optional: the game's IRQ
(`sdevkit/irq.h`), which runs at a row of the picture while it is drawn, and the vertical blank
of a game that publishes its data (`sdevkit/publish.h`), which the kit's NMI runs. Interrupt code
runs in the middle of the tick, at any instruction. If it read the game's variables there, it
could find a 16-bit value with its low byte new and its high byte old, or half of a palette. So
the tick publishes what interrupt code needs: one struct, which the tick writes as an ordinary
variable, and a copy of it, which interrupt code only reads. Interrupt code always finds the data
of the tick whose vertical blank came last, the tick the picture on the screen was made from.
dampf gives each handler a context of its own, so its C code does not overwrite the
[frames](/glossary#frame) of the code it interrupted, and interrupt code multiplies in software.

### `SDEVKIT_PUBLISH`

```c
#define SDEVKIT_PUBLISH(type, name, tick, vblank)
```

Declares the data the tick publishes, and defines `game_tick`, `game_vblank` and the kit's NMI
handler. A game writes it once in a program, at file scope in one of its C files, and defines no
`game_tick`, no `game_vblank` and no NMI handler of its own. `type` is a struct (an array goes
into one), `tick` a function `static void tick(const uint16_t pad[SDEVKIT_PADS])` and `vblank` a
function `static void vblank(void)` of the same file, after the macro. The macro defines:

- **the struct** `name` of type `type`, the tick's own, an ordinary variable;
- **its two published copies** and two variables, `sdevkit_publish_side` (which copy interrupt
  code reads) and `sdevkit_publish_ready`, which a game does not touch;
- **`game_tick`**, which calls `tick(pad)`, copies `name` into the copy interrupt code does not
  read, and marks it ready;
- **the kit's NMI handler**, which makes a ready copy the current one with one write, and then
  calls `vblank()`;
- **`game_vblank`**, which the kit's loop calls after each tick at the start of the vertical
  blank. On the SNES it switches the NMI on the first time (`sdevkit_publish_nmi_on`, which only
  the macro calls) and waits until the NMI has taken the ready copy, so the next tick begins only
  after the switch. On the PC, which has no NMI, it switches and calls `vblank` itself.

The same C runs on every platform; dampf leaves out the PC's branch (`DAMPF_HAS_INTERRUPTS` in
`<dampf.h>`), and the checksums of the ticks are the same on the SNES, the desktop and the web.

```c
#include "sdevkit/publish.h"

struct split { uint16_t scroll_x; uint16_t colors[16]; };

SDEVKIT_PUBLISH(struct split, split, tick, vblank)   // defines split, its copies, game_tick, game_vblank

static void tick(const uint16_t pad[SDEVKIT_PADS])
{
    split.scroll_x += 1;                     // an ordinary variable, all of the tick
}

static void vblank(void)                     // in the kit's NMI: published data only
{
    const struct split *s = SDEVKIT_PUBLISHED(split);
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, s->scroll_x);
}
```

A game that publishes gets its `vblank` run by the kit's NMI at the start of every vertical
blank, on the SNES. A tick that takes longer than a frame no longer delays it: the NMI comes in
the middle of the tick, and `vblank` writes the picture of the last tick that ended once more,
so scrolls, splits and fades stay in place while the game runs at 30 frames a second or slower.
A game that does not publish keeps `game_vblank` as [The frame rhythm](#the-frame-rhythm)
describes it. `vblank` is interrupt code then, and keeps these rules:

- **Published data only.** It reads the game's data only through `SDEVKIT_PUBLISHED`. The tick's
  own variables, also the struct `name`, may be half written when it runs.
- **The same writes again.** What it writes depends only on the published data, so running it
  again on the same data writes the same: on the SNES it runs once a frame, also while a tick
  overruns, on the desktop and the web once a tick. The desktop's check runs it twice after each
  tick and counts every register, VRAM, CGRAM or OAM byte the second run changes (`repeated`),
  since such a byte would make the SNES's picture differ from the desktop's after an overrun.
- **Nothing of the main code.** It writes no variable that the main code (`game_init`, the tick)
  writes too, and uses none of the optional layers: their state is the tick's. A game on the
  layers keeps `game_vblank`.
- **The hardware as `game_vblank` would.** It writes registers, copies into VRAM, CGRAM and OAM,
  HDMA, and calls `sdevkit_irq_line`. Sound effects stay in the tick.

The cost on the SNES comes after every tick and in every vertical blank. The copy after each
tick takes about 75 cycles and 10.5 a byte, from the timing report of a tick that changes one
word, against the same game without the header (2026-11-26):

| Struct | Cycles after the tick |
| --- | ---: |
| 32 bytes | 411 |
| 128 bytes | 1,419 |
| 512 bytes | 5,451 |

A frame has about 50,000. In every vertical blank the NMI takes 118 to 140 cycles beyond
`vblank`: the entry stub's 102, the switch, 16 cycles when there is nothing to switch and 38 when
there is, and the call. The RAM is two copies of the struct and three bytes, and a page for the
NMI's [direct page](/glossary#direct-page) and frames, which the main code's frames lose. A game
that does not include the header pays nothing.

### `SDEVKIT_PUBLISH_DECLARE`

```c
#define SDEVKIT_PUBLISH_DECLARE(type, name)
```

Declares the published data for the game's other C files, in a header: the struct `name` and its
copies, as `SDEVKIT_PUBLISH` defines them.

### `SDEVKIT_PUBLISHED`

```c
#define SDEVKIT_PUBLISHED(name)
```

Gives a pointer to the current copy, the data of the tick whose vertical blank came last, for
interrupt code: the game's IRQ and `vblank`. Read it once at the start of the handler and keep
the pointer; the copy does not change while the handler runs. In the tick it gives the data of
the tick before. Finding the current copy takes about 15 cycles, and each access through the
pointer a cycle more than to a global.

The game's IRQ runs C code at the rows of the picture the game chooses: a status bar below a
scrolling playfield, a second scroll for the lower half, a fade that starts in the middle of the
screen. Where [HDMA](#hdma) changes a register at every row from a table, the IRQ can read the
published data and decide. The PPU takes a register for a row only in the horizontal blank
before it; a write while it draws the row shows in the row's middle, half old and half new. So
the IRQ comes in two steps. [`sdevkit_irq_line`](#sdevkit-irq-line) makes it come in the
scanline before picture row `row` (row y of the picture is scanline y + 1), early enough for the
handler to read its data and decide; then the handler calls
[`sdevkit_irq_wait_hblank`](#sdevkit-irq-wait-hblank), which returns when the horizontal blank
before the row has begun, and writes. A register it writes then shows from that row on, to the
end of the picture or until something writes it again.

### `game_irq`

```c
void game_irq(void);
```

The game's IRQ handler. A game that has one includes `<dampf.h>` and defines it as
`DAMPF_INTERRUPT(irq) void game_irq(void)`. The hardware calls it at the row `sdevkit_irq_line`
sets; C code never does. It runs in a context of its own: it and every function it calls have
their own frames. The name is the game's, so it keeps the `game_` prefix. The IRQ costs the entry
stub's 97 cycles and the handler's, each time it comes, and the main code's frames have a page
less RAM. A game that does not include `sdevkit/irq.h` and defines no `game_irq` pays nothing.

The time the handler has, measured in Mesen, in CPU cycles as the timing report counts them:

- **Before the wait**: about 53 cycles from the handler's first instruction to the start of the
  blank. Reading a value from the published data takes about 33. What the handler does before
  the wait that it does not need for its writes belongs after them.
- **After the wait**: its register writes must end within about 25 cycles of the wait's return,
  less about 6 for each HDMA channel of one byte on that line, more for more bytes. The wait
  returns up to 12 cycles after the blank has begun, a round of its loop. A scroll register
  written with `sdevkit_reg_write_twice` takes 17 cycles, a register of one byte about 10: one
  scroll register, or two registers of a byte. More goes in HDMA, or in an IRQ at another row.
- **After the last write**: the handler may go on, up to the next IRQ. The timing report has a
  line for the IRQ, `dampf_irq`, with the entry stub's 97 cycles. While the handler waits, the
  tick does not run: the IRQ takes about 60 cycles of the tick's time beyond its code.
- **Too long before the wait**: the handler reaches the wait inside the blank, and the wait
  returns at once; its writes come late and may tear the row's left edge. Later still, after the
  blank, the wait waits for the next row's blank, and the writes show a row lower. The wait cannot
  tell either from a handler in time, and Rachael, who has no time, cannot either. Keep the code
  before the wait short and look at the timing report.

The handler may call `sdevkit_irq_line` itself, for the next split of the same frame: a row below
the one it runs at comes in this frame, a row at or above it in the next one. So a handler with
two splits sets the second row in the first and the first row in the second. Since it runs in
the middle of the tick, the handler keeps these rules:

- **Published data only.** It reads the game's data only through `SDEVKIT_PUBLISHED`, which is
  complete and the data of the picture being drawn; a global the tick writes may be half
  written.
- **No shared variable.** It writes no variable that the main code (`game_init`, `game_tick`,
  `game_vblank`) writes too, since one of the writes could be lost. A variable only the handler
  writes is fine.
- **Registers only, after the wait.** It writes registers with `sdevkit_reg_write` and
  `sdevkit_reg_write_twice`, after the wait, and nothing into VRAM, CGRAM or OAM: the PPU takes
  nothing there while it draws, and the desktop counts it as lost. It makes no HDMA call: Rachael
  does not draw an HDMA channel changed in the middle of the picture, and the desktop counts it
  as a write while the picture is drawn.
- **The vertical blank first.** `game_vblank`, or the `vblank` the kit's NMI runs, ends before
  the first row the IRQ runs at. A register written there would show in the middle of the
  picture, and in `game_vblank` the IRQ could even come between the two bytes of a scroll
  register.

On the desktop and the web Rachael calls `game_irq` while she draws the picture, before the row
it is set for, after that row's HDMA, as the SNES does. A register it writes before
`sdevkit_irq_wait_hblank` counts as written while the picture is drawn (`drawing`), so a
forgotten wait fails the desktop check; the writes after it are free. The picture is the SNES's,
pixel for pixel, as long as the handler keeps the times above, which the desktop does not see.

### `sdevkit_irq_line`

```c
void sdevkit_irq_line(uint8_t row);
```

Runs `game_irq` right before picture row `row`, 0 to 223, in every frame from now on. A row from
`SDEVKIT_IRQ_ROWS` (224) on switches the IRQ off, as `sdevkit_irq_off` does. It may be called
anywhere: in `game_init`, where the IRQ then comes from the first frame on, in `game_vblank`, and
in `game_irq` itself for the next row. On the SNES it writes the CPU's timer registers (`VTIME`,
`HTIME`) and switches the timer's IRQ on in `NMITIMEN`, which is the kit's: a game does not write
`$4200` to `$420A` itself. Cost: 57 cycles.

### `sdevkit_irq_off`

```c
void sdevkit_irq_off(void);
```

Switches the IRQ off: `game_irq` runs no more until the next `sdevkit_irq_line`. Cost: 35
cycles.

### `sdevkit_irq_wait_hblank`

```c
void sdevkit_irq_wait_hblank(void);
```

In `game_irq`, once, before its register writes: waits for the horizontal blank before the IRQ's
row. On the SNES it waits until the blank begins (bit 6 of `HVBJOY`); the writes after it then
land in the blank, within the time given above. Call it once in each call of the handler: a
second call waits for the next row's blank. On Rachael it does nothing but note that the handler
waited. Cost: 17 cycles to unbounded.

The program below, cut down from the repository's `dev/irq_check`, scrolls a field of stripes
with the joypad and keeps a status bar under it still. The tick publishes the scroll, `vblank`
writes it for the top of the picture, and the IRQ before row 192 sets the scroll back to 0 for
the bar, whose rows are in another palette. The handler reads nothing, so it waits at once.

```c
#include <stdint.h>
#include <dampf.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"
#include "sdevkit/irq.h"
#include "sdevkit/publish.h"

#define BAR_ROW  192           /* the status bar: rows 192 to 223, map rows 24 to 27 */
#define VRAM_MAP 0x1000

struct view {
    uint16_t scroll_x;         /* the playfield's scroll */
};

SDEVKIT_PUBLISH(struct view, view, tick, vblank)

static uint8_t tile[32];       /* tile 1: four pixels of colour 1, then four of colour 0 */
static uint16_t map[32 * 32];

void game_init(uint16_t seed)
{
    uint16_t i;

    (void)seed;
    for (i = 0; i < 8; i++) {
        tile[i * 2] = 0xF0;
    }
    for (i = 0; i < 32 * 32; i++) {   /* tile 1 everywhere, the bar's rows in palette 1 */
        map[i] = (uint16_t)(SDEVKIT_BG_TIL_NUM(1) | SDEVKIT_BG_TIL_PAL(i >= 24 * 32 ? 1 : 0));
    }
    sdevkit_vram_copy(16, tile, sizeof tile);
    sdevkit_vram_copy(VRAM_MAP, map, sizeof map);
    sdevkit_cgram_set(1, SDEVKIT_RGB5(31, 31, 31));               /* palette 0: white stripes */
    sdevkit_cgram_set(16 + 1, SDEVKIT_RGB5(31, 24, 0));           /* palette 1: yellow */
    sdevkit_reg_write(SDEVKIT_PPU_BGMODE, SDEVKIT_BG_MODE1);
    sdevkit_reg_write(SDEVKIT_PPU_BG1SC, SDEVKIT_BGSC(VRAM_MAP, SDEVKIT_SC_32x32));
    sdevkit_reg_write(SDEVKIT_PPU_TM, SDEVKIT_BG1_ENABLE);
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1VOFS, 0x3FF);
    sdevkit_irq_line(BAR_ROW);                                    /* every frame from now on */
    sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15);
}

static void tick(const uint16_t pad[SDEVKIT_PADS])
{
    if (pad[0] & SDEVKIT_KEY_RIGHT) {
        view.scroll_x++;       /* an ordinary variable, all of the tick */
    }
    if (pad[0] & SDEVKIT_KEY_LEFT) {
        view.scroll_x--;
    }
    sdevkit_dbg_hash(&view, sizeof view);
}

static void vblank(void)       /* in the kit's NMI on the SNES: published data only */
{
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, SDEVKIT_PUBLISHED(view)->scroll_x);
}

DAMPF_INTERRUPT(irq) void game_irq(void)
{
    sdevkit_irq_wait_hblank();                                    /* first the blank, */
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, 0);              /* then the write: the bar stands still */
}
```

A handler that needs the published data reads it before the wait and writes after it. With
`struct split { uint16_t top_x; uint16_t bar_x; }` published as `split`:

```c
DAMPF_INTERRUPT(irq) void game_irq(void)
{
    uint16_t x = SDEVKIT_PUBLISHED(split)->bar_x;   /* read and decide first, */
    sdevkit_irq_wait_hblank();                       /* then wait for the blank, */
    sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS, x); /* then write */
}
```
