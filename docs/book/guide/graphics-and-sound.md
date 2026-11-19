<!-- GENERATED from doc-specs/guide/graphics-and-sound.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Graphics and sound

A picture drawn on the PC reaches the SNES screen as C. The converter `tools/png2snes.py` reads
a PNG file and writes a `.c` file with the picture's tiles and colours as constant arrays, plus a
`.h` file that declares them, into the game's `build/gen/` folder. The game's
[args file](/glossary#args-file) adds that folder to the include path and to the program, so
dampf compiles the arrays into the ROM with the rest of the game, and the game copies them into
[VRAM](/glossary#vram), the video memory, with calls of the
[console API](/reference/console-api). The converter needs only Python 3, no image library. The
commands below run in the game's folder; write the path of your
[superdevkit home](/glossary#superdevkit-home) where they show `<home>`.

## Tiles and palettes

The SNES draws backgrounds and sprites from tiles of 8 x 8 pixels, each pixel an index into a
palette of colours. Use `tiles` to cut a picture into such tiles:

```sh
python3 -I <home>/tools/png2snes.py tiles assets/ball_gfx.png ball_gfx build/gen
```

The arguments are the PNG file, a name and the folder to write to. The picture's width and height
must be multiples of 8, and it may have at most 16 colours, since a tile has 4 bits a pixel.
Colour 0 is the transparent colour of sprites and the [backdrop](/glossary#backdrop) of
backgrounds. The converter takes two kinds of PNG:

- **Indexed**: the PNG's own palette is used as it is, so palette index 0 is colour 0. A pixel
  with an index above 15 is an error.
- **RGB or RGBA**: the colours are numbered in the order they first appear, reading the picture
  from the top left. Transparent pixels (alpha 0) become colour 0, which is black in the palette;
  the other colours start at 1, so at most 15 of them are allowed besides the transparent one.

Only 8-bit PNG files without interlacing are read: grey, RGB, indexed, grey with alpha and RGBA.

The tiles come out in reading order, left to right and then down. That order suits sprites when
the sheet is 128 pixels wide, 16 tiles a row: a 16 x 16 sprite whose first tile is `n` takes the
tiles `n`, `n + 1`, `n + 16` and `n + 17` in VRAM, which in such a sheet are the four quarters of
the 16 x 16 square you drew. Draw each sprite where it looks right, and its tiles land where the
SNES looks for them.

For the name `ball_gfx`, the converter writes `ball_gfx.c` and `ball_gfx.h`. The header holds:

| Name | What it is |
|---|---|
| `ball_gfx_tiles[]` | the tiles in the SNES's planar 4-bit format, 32 bytes a tile |
| `ball_gfx_pal[]` | the 16 colours in BGR 5:5:5, 2 bytes each, low byte first |
| `BALL_GFX_TILE_COUNT` | the number of tiles |
| `BALL_GFX_TILES_SIZE` | the size of `ball_gfx_tiles` in bytes |
| `BALL_GFX_PAL_SIZE` | the size of `ball_gfx_pal` in bytes, 32 |

In the planar format a byte holds one bit of each of the eight pixels of a tile's row, so a
pixel's 4 bits lie in four bytes. BGR 5:5:5 is the colour format of
[CGRAM](/glossary#cgram), the SNES's colour memory: 5 bits each of blue, green and red. The
arrays are `const uint8_t`, so they stay in ROM.

A layer with fewer colours takes `tiles2`, which writes tiles of 2 bits a pixel, 16 bytes a tile,
and a palette of 4 colours (8 bytes). The picture may then use only the colours 0 to 3. Mode 1
has two background layers of 16 colours, BG1 and BG2, and a third, BG3, of 4; Er-Tüp
(`examples/ertuep`) draws its status line on BG3:

```sh
python3 -I <home>/tools/png2snes.py tiles2 assets/hud.png hud build/gen
```

The same tiles can be shown in other colours, a character in another shirt or an enemy that
flashes when hit. Use `palette` to convert only the colours, from a strip whose first row of
pixels holds up to 16 colours, left to right:

```sh
python3 -I <home>/tools/png2snes.py palette assets/pal_ginger.png pal_ginger build/gen
```

It writes `pal_ginger_pal[]` and `PAL_GINGER_PAL_SIZE`, no tiles. CatchMe (`examples/catchme`)
converts six such strips, one per palette of its sprites.

The example `examples/hello` converts a font for the background and a sprite sheet for the ball:

```sh
python3 -I "$KIT/tools/png2snes.py" tiles assets/font_gfx.png font_gfx "$GEN"
python3 -I "$KIT/tools/png2snes.py" tiles assets/ball_gfx.png ball_gfx "$GEN"
```

The game includes the two headers and hands the arrays to the console API in `game_init`, which
runs with the screen off, so the copies into VRAM can happen at once. This is cut from
`examples/hello/src/hello.c`, with the lines that do not concern the graphics and some comments
left out:

```c
#include "font_gfx.h"
#include "ball_gfx.h"

#define VRAM_FONT    0x0000    /* background tiles, in 16-bit words */
#define VRAM_MAP     0x1000    /* the background map: 32 x 32 entries */
#define VRAM_SPRITES 0x4000    /* sprite tiles */

static uint16_t map[32 * 32];  /* the background map in RAM; copied in the vertical blank */

void game_init(uint16_t seed)
{
    (void)seed;
    sdevkit_set_mode(SDEVKIT_BG_MODE1, 0);
    sdevkit_bg_init_tile_set(0, font_gfx_tiles, font_gfx_pal, 0, FONT_GFX_TILES_SIZE,
                             FONT_GFX_PAL_SIZE, SDEVKIT_BG_16COLORS, VRAM_FONT);
    sdevkit_bg_set_map_ptr(0, VRAM_MAP, SDEVKIT_SC_32x32);
    sdevkit_oam_init_gfx_set(ball_gfx_tiles, BALL_GFX_TILES_SIZE, ball_gfx_pal, BALL_GFX_PAL_SIZE,
                             0, VRAM_SPRITES, SDEVKIT_OBJ_SIZE16_L32);
    print(10, 6, "HELLO SNES!");
    sdevkit_dma_copy_vram((const uint8_t *)map, VRAM_MAP, sizeof map);
    sdevkit_set_brightness(15);
}
```

The call [`sdevkit_bg_init_tile_set`](/reference/console-api#sdevkit-bg-init-tile-set) copies
tiles and palette of a background layer; its first `0` is the layer, numbered 0 to 3 for BG1 to
BG4, and its second the number of the palette the colours go to.
[`sdevkit_oam_init_gfx_set`](/reference/console-api#sdevkit-oam-init-gfx-set) copies those of
the sprites, and its `0` is the number of the sprites' palette. A palette of its own goes to
CGRAM with `sdevkit_dma_copy_cgram(pal_ginger_pal, address, PAL_GINGER_PAL_SIZE)`, where the
address counts colours. After `game_init`, copy into VRAM and CGRAM only in the
[vertical blank](/glossary#vertical-blank), in `game_vblank` (see
[the frame rhythm](/reference/console-api#the-frame-rhythm)). Where the tiles and palettes go in
VRAM, and how many there may be, is on [The SNES hardware](/guide/snes-hardware).

`tiles` writes tiles and a palette, but no map. A background layer also needs one: a table of
16-bit entries in VRAM, one for each tile of the layer, that says which tile is drawn there. The
game makes the map itself. `hello.c` keeps its map of 32 x 32 entries in RAM, and its function
`print` writes text into it in code, each character as the number of its tile in the font.
`sdevkit_bg_set_map_ptr` tells the layer where in VRAM its map lies, and
`sdevkit_dma_copy_vram` copies the map there; whenever the text changes, `game_vblank` copies it
again. An entry of hello's map is a tile number alone, so every character takes palette 0. The
map entry macros of the [console API](/reference/console-api#backgrounds) build an entry that
names a palette as well, or mirrors the tile:

```c
map[row * 32 + column] = SDEVKIT_BG_TIL_NUM(tile) | SDEVKIT_BG_TIL_PAL(1);
```

## Mode 7 pictures

Mode 7 shows one background layer that the SNES can rotate and scale: a map of 128 x 128 tiles,
1024 x 1024 pixels, drawn from at most 256 tiles. Its tiles have one byte a pixel, an index into
all 256 colours of CGRAM. Use `mode7` to convert such a picture:

```sh
python3 -I <home>/tools/png2snes.py mode7 assets/festzelt.png festzelt build/gen 33
```

The PNG must be indexed, 1024 pixels wide and up to 1024 high, in whole tiles of 8 x 8.

The last argument, `FIRST` (1 when left out), says where the picture's colours go in CGRAM:
palette index `i` becomes colour `FIRST - 1 + i`, and index 0 stays 0, transparent. You choose
it because mode 7 has no palettes of its own: it shares CGRAM with the sprites and, when the
screen switches modes during the picture, with the other layers. Er-Tüp's stage 3 is in mode
7, and its two pictures, the Festzelt (a beer tent) and the Karussell, take their colours from
CGRAM 33 on, which leaves the colours below to the rest of the game.
A picture whose highest index would go past colour 255 is an error.

The converter cuts the picture into 8 x 8 blocks and stores identical blocks once. More than 256
different blocks is an error, because mode 7 has 256 tiles and cannot flip one to reuse it. The
converter names the first tile that does not fit, by column and row:

```
assets/arena.png: more than 256 different tiles of 8 x 8 (at tile 0, 2); mode 7 has 256 and cannot flip them
```

For the name `festzelt` it writes:

| Name | What it is |
|---|---|
| `festzelt_map[]` | one byte a tile, 128 a row; `FESTZELT_MAP_SIZE` bytes |
| `festzelt_tiles[]` | the tiles, 64 bytes each |
| `festzelt_pal[]` | the colours from index 1 on, BGR 5:5:5; `FESTZELT_PAL_SIZE` bytes |
| `FESTZELT_PAL_FIRST` | the `FIRST` it was converted with |

The size of `festzelt_tiles` is `FESTZELT_TILES_SIZE` bytes, and `FESTZELT_TILE_COUNT` counts its
tiles. Er-Tüp's Festzelt uses 249 tiles and 72 colours. A game loads such a picture with the
`sdevkit_mode7_*` calls and copies the colours to where `FIRST` put them:

```c
sdevkit_set_mode(SDEVKIT_BG_MODE7, 0);
sdevkit_mode7_load_tiles(festzelt_tiles, 0, FESTZELT_TILES_SIZE);
sdevkit_mode7_load_map(festzelt_map, 0, FESTZELT_MAP_SIZE);
sdevkit_dma_copy_cgram(festzelt_pal, FESTZELT_PAL_FIRST, FESTZELT_PAL_SIZE);
```

These are copies into VRAM and CGRAM too: with the screen off, or in the vertical blank.
`sdevkit_mode7_set_matrix`, `sdevkit_mode7_set_center` and `sdevkit_mode7_set_select` then turn,
scale and place the layer; they are on [The console API](/reference/console-api#mode-7).

## Converting in the build

A game lives in a folder of its own, outside the superdevkit home, and its `build.sh` converts
the pictures before it compiles. This is `examples/hello/build.sh` with the path of the home in
`KIT`, and with its comments and the lines that look for dampf inside the repository left out:

```sh [build.sh]
#!/bin/sh
set -e
cd "$(dirname "$0")"
KIT=<home>
GEN=build/gen
mkdir -p "$GEN"
python3 -I "$KIT/tools/png2snes.py" tiles assets/font_gfx.png font_gfx "$GEN"
python3 -I "$KIT/tools/png2snes.py" tiles assets/ball_gfx.png ball_gfx "$GEN"
[ "${CONVERT_ONLY:-0}" = 1 ] && exit 0
"$KIT/bin/dampf" build @hello.args -o build/hello.sfc "$@"
```

The conversion comes first, so that `build/gen/` holds the C before dampf reads it. Started with
`CONVERT_ONLY=1`, the script stops after the conversion; the desktop build starts it that way to
get the same converted pictures and then compiles the game for the PC (see
[The desktop](/guide/the-desktop)).

The args file adds the converted C twice: `-I build/gen` lets the game include `ball_gfx.h`, and
`build/gen/` compiles `ball_gfx.c` and the others as part of the program. This is
`examples/hello/hello.args` with the paths of the home instead of `../..`, and without its
comment:

```text [hello.args]
-I <home>/lib/include
-I src
-I build/gen
--entry main
src/
build/gen/
<home>/lib/snes/
```

Paths in the args file are relative to the file itself. The options of the args file are on
[Command line](/reference/command-line).

## Sound

The kit cannot play sound yet: a game has no call to start a sample (see
[Limits](/reference/limits)).

What `tools/brr_lab.html` is for today is trying out a sound effect before the kit plays sound.
Open the page in a browser, drop a WAV file on it, and hear how the effect would sound on the
SNES after the compression to BRR, the SNES's sample format, and how much of the 64 KB of audio
RAM it would take. The page needs no network and no libraries, and saves the `.brr` file on
request.
