<!-- GENERATED from doc-specs/guide/graphics-and-sound.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Graphics and sound

A picture drawn on the PC reaches the SNES screen as C. A game names its pictures in the
`[graphics]` table of its [project file](/glossary#project-file), `superdevkit.toml`, and
`superdevkit build` converts each of them with `superdevkit png`: the converter reads the PNG
file and writes a `.c` file with the picture's tiles and colours as constant arrays, plus a `.h`
file that declares them, into the game's `build/gen/` folder. The build adds that folder to the
include path and to the program, so the arrays are compiled with the rest of the game, into the
ROM or the program for the PC, and the game copies them into [VRAM](/glossary#vram), the video
memory, with calls of the [console API](/glossary#console-api). The converter is part of the
`superdevkit` command and needs nothing but Java, no image library.

## Tiles and palettes

The SNES draws backgrounds and sprites from tiles of 8 x 8 pixels, each pixel an index into a
palette of colours. Use `tiles` to cut a picture into such tiles:

```toml
[graphics]
tiles = ["assets/ball_gfx.png"]
```

The entry names the PNG file, relative to the game's folder; its name without `.png`,
`ball_gfx`, becomes the name of the C it is converted to. The picture's width and height
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

```toml
tiles2 = ["assets/hud.png"]
```

The same tiles can be shown in other colours, a character in another shirt or an enemy that
flashes when hit. Use `palette` to convert only the colours, from a strip whose first row of
pixels holds up to 16 colours, left to right:

```toml
palette = ["assets/pal_ginger.png"]
```

It writes `pal_ginger_pal[]` and `PAL_GINGER_PAL_SIZE`, no tiles. CatchMe (`examples/catchme`)
converts six such strips, one per palette of its sprites.

The example `examples/hello` converts a font for the background and a sprite sheet for the ball.
This is the `[graphics]` table of its `superdevkit.toml`:

```toml
[graphics]
tiles = ["assets/font_gfx.png", "assets/ball_gfx.png"]
```

The game includes the two headers and hands the arrays to the console API in `game_init`, which
runs with the screen off, so the copies into VRAM can happen at once. This is cut from
`examples/hello/src/hello.c` to the uploads, with some comments left out:

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

The arguments of the calls say where everything goes; VRAM addresses count 16-bit words, CGRAM
addresses colours. The call `sdevkit_set_mode(SDEVKIT_BG_MODE1, 0)` switches to mode 1; in place
of its `0`, `SDEVKIT_BG3_MODE1_PRIORITY_HIGH` would put the BG3 tiles of priority 1 above
everything. Then
[`sdevkit_bg_init_tile_set`](/reference/console-api#sdevkit-bg-init-tile-set) copies tiles and
palette of a background layer. Its first `0` is the layer, numbered 0 to 3 for BG1 to BG4, and
the tiles go to VRAM word `VRAM_FONT`. Its second `0` is the number of the palette, and
`SDEVKIT_BG_16COLORS` the colour mode, 16 colours a palette: the palette's colours go to CGRAM
from colour 16 times its number on, here from colour 0. The call `sdevkit_bg_set_map_ptr` puts
the layer's map, 32 x 32 entries, at VRAM word `VRAM_MAP`.

For the sprites,
[`sdevkit_oam_init_gfx_set`](/reference/console-api#sdevkit-oam-init-gfx-set) copies their tiles
to VRAM word `VRAM_SPRITES` and their colours to the sprite palette its `0` names, and
`SDEVKIT_OBJ_SIZE16_L32` makes a small sprite 16 x 16 pixels and a large one 32 x 32. The sprite
palettes are the upper half of CGRAM: sprite palette n starts at colour 128 + 16 n, so hello's
ball takes its colours from colour 128 on.

A palette of its own goes to CGRAM with `sdevkit_dma_copy_cgram`, whose address is the first
colour. CatchMe puts the colours of one of its cats, `pal_ginger`, into sprite palette 1:

```c
sdevkit_dma_copy_cgram(pal_ginger_pal, 128 + 16 * 1, PAL_GINGER_PAL_SIZE);
```

After `game_init`, copy into VRAM and CGRAM only in the
[vertical blank](/glossary#vertical-blank), in `game_vblank` (see
[the frame rhythm](/reference/console-api#the-frame-rhythm)). How VRAM is laid out, and how many
tiles and sprites there may be, is on [The console API](/reference/console-api) and
[The SNES hardware](/guide/snes-hardware).

A conversion with `tiles` writes tiles and a palette, but no map. A background layer also needs
one: a table of 16-bit entries in VRAM, one for each tile of the layer, that says which tile is
drawn there. The game makes the map itself. `hello.c` keeps its map of 32 x 32 entries in RAM,
and its function `print` writes text into it in code, each character as the number of its tile
in the font. Then `sdevkit_dma_copy_vram` copies the map to `VRAM_MAP`, where
`sdevkit_bg_set_map_ptr` told the layer to look for it; whenever the text changes, `game_vblank`
copies it again. An entry of hello's map is a tile number alone, so every character takes
palette 0. The map entry macros of the [console API](/reference/console-api#backgrounds) build
an entry that names a palette as well, or mirrors the tile:

```c
map[row * 32 + column] = SDEVKIT_BG_TIL_NUM(tile) | SDEVKIT_BG_TIL_PAL(1);
```

## Mode 7 pictures

Mode 7 shows one background layer that the SNES can rotate and scale: a map of 128 x 128 tiles,
1024 x 1024 pixels, drawn from at most 256 tiles. Its tiles have one byte a pixel, an index into
all 256 colours of CGRAM. Use `mode7` to convert such a picture:

```toml
mode7 = [{ png = "assets/festzelt.png", first = 33 }]
```

The PNG must be indexed, 1024 pixels wide and up to 1024 high, in whole tiles of 8 x 8.

The key `first` (1 when left out) says where the picture's colours go in CGRAM:
palette index `i` becomes colour `first - 1 + i`, and index 0 stays 0, transparent. You choose
it because mode 7 has no palettes of its own: it shares CGRAM with the sprites and, when the
screen switches modes during the picture, with the other layers. Er-Tüp's stage 3 is in mode
7, and its two pictures, the Festzelt (a beer tent) and the Karussell, take their colours from
CGRAM 33 on, which leaves the colours below to the rest of the game.
A picture whose highest index would go past colour 255 is an error.

The converter cuts the picture into 8 x 8 blocks and stores identical blocks once. More than 256
different blocks is an error, because mode 7 has 256 tiles and cannot flip one to reuse it. The
converter names the first tile that does not fit, by column and row. A race track drawn as a
ring of road with kerbs on grass, 1024 x 1024 pixels in four colours, is such a picture: wherever
the curved edges cross a tile, they make a block of their own. Converted by hand (see
[Converting in the build](#converting-in-the-build)) in the game's folder:

```sh
superdevkit png mode7 assets/track.png track build/gen
```

```
error: assets/track.png: more than 256 different tiles of 8 x 8 (at tile 81, 30); mode 7 has 256 and cannot flip them
```

With `mode7 = ["assets/track.png"]` in the `[graphics]` table, `superdevkit build` stops with the
same message, which then starts with `error: png:` and names the PNG by its full path.

For Er-Tüp's Festzelt, converted with the name `festzelt`, the converter writes:

| Name | What it is |
|---|---|
| `festzelt_map[]` | one byte a tile, 128 a row; `FESTZELT_MAP_SIZE` bytes |
| `festzelt_tiles[]` | the tiles, 64 bytes each |
| `festzelt_pal[]` | the colours from index 1 on, BGR 5:5:5; `FESTZELT_PAL_SIZE` bytes |
| `FESTZELT_PAL_FIRST` | the `first` it was converted with |

The size of `festzelt_tiles` is `FESTZELT_TILES_SIZE` bytes, and `FESTZELT_TILE_COUNT` counts its
tiles. Er-Tüp's Festzelt uses 249 tiles and 72 colours. A game loads such a picture with the
`sdevkit_mode7_*` calls and copies the colours to where `first` put them. The `0` of
`sdevkit_mode7_load_tiles` is the byte address of the tiles to start at, and the `0` of
`sdevkit_mode7_load_map` the map entry to start at (row times 128 plus column), so both fill
from the start:

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

A game lives in a folder of its own, outside the
[superdevkit home](/glossary#superdevkit-home), and names its pictures in the `[graphics]` table
of its `superdevkit.toml`. The table has four keys, `tiles`, `tiles2`, `palette` and `mode7`, one
for each kind of conversion, and each holds an array of entries. An entry is the path of a PNG
file, relative to the folder of `superdevkit.toml`, and the name of its C is the file's name
without `.png`. Where that name does not suit, or is not a valid C name, the entry gives one:
`{ png = "assets/boss.png", name = "boss_gfx" }`. A `mode7` entry may add `first`. Two pictures
with the same name are an error. This is the `[graphics]` table of Er-Tüp's `superdevkit.toml`,
which uses three of the four kinds:

```toml
[graphics]
tiles = [
    "assets/stage_bar.png", "assets/stage_cellar.png", "assets/stage_kitchen.png",
    "assets/stage_brew.png", "assets/stage_mett.png",
    "assets/wall_bar.png", "assets/wall_cellar.png", "assets/wall_kitchen.png", "assets/wall_brew.png",
    "assets/sprites_a.png", "assets/sprites_b.png", "assets/sprites_c.png",
    { png = "assets/boss.png", name = "boss_gfx" },
    { png = "assets/boss2.png", name = "boss2_gfx" },
]
tiles2 = ["assets/hud.png"]   # BG3, 2 bits a pixel
mode7 = [                     # stage 3 in mode 7: its colours from CGRAM 33 on
    { png = "assets/festzelt.png", first = 33 },
    { png = "assets/karussell.png", first = 33 },
]
```

Every `superdevkit build`, whatever its [target](/glossary#target), converts the pictures first,
so that `build/gen/` holds the C before the compiler reads it. Then it hands that folder to the
compiler twice: `-I build/gen` lets the game include `ball_gfx.h`, and `build/gen/` compiles
`ball_gfx.c` and the others as part of the program. Use `--plan` to see the steps without
running them. In a copy of hello, made with `superdevkit init hello`, and with the path of the
home written as `<home>`:

```sh
superdevkit build snes --plan
```

```
superdevkit build snes: hello (kit <home>)
  png    tiles assets/font_gfx.png font_gfx build/gen
  png    tiles assets/ball_gfx.png ball_gfx build/gen
  dampf  build -I <home>/lib/include -I src -I build/gen --entry main -o build/snes/hello.sfc src build/gen/ <home>/lib/snes/
  write  build/compile_commands.json
everything the build needs is there
```

The builds for the desktop and the web convert in the same way and compile the files of
`build/gen/` with the game's own (see [The desktop](/guide/the-desktop) and
[The web](/guide/the-web)).

To convert one picture by hand, outside a build, run `superdevkit png`. Its usage line says
what it takes: the kind, the PNG file, the name, the folder to write to and, for `mode7` only,
the first colour:

```
superdevkit png tiles|tiles2|palette|mode7 picture.png NAME out_dir [FIRST]
```

In a copy of Er-Tüp, this converts the Festzelt as the build does:

```sh
superdevkit png mode7 assets/festzelt.png festzelt build/gen 33
```

Its exit status is on [The superdevkit command](/reference/superdevkit), with every key of
`superdevkit.toml`.

## Sound

The SNES makes its sound with a chip of its own: the SPC700, a small processor with 64 KB of
audio RAM, drives the S-DSP, which plays up to eight voices at once. A voice plays a sample from
the audio RAM, stored in BRR, the SNES's compressed sample format. The kit plays sound effects on
the SNES and on the PC, and music on the PC only.

### Sound effects

A game lists its sound effects in `assets/sfx/sfx.json`, next to their WAV files. This is
Steinschlag's, `examples/steinschlag/assets/sfx/sfx.json`, cut to its first two effects:

```json
{
  "voices": 2,
  "sounds": [
    { "id": "dig", "priority": 0, "samples": [
        { "file": "dig.wav", "active": true,
          "rate": 11000, "treble": 0, "gain": 0, "trim": true, "volume": 70 } ] },
    { "id": "gem", "priority": 2, "samples": [
        { "file": "gem.wav", "active": true,
          "rate": 22000, "treble": 6, "gain": -6, "trim": true, "volume": 90 } ] }
  ]
}
```

The member `voices` says how many of the eight voices the effects use. Each effect has an `id`,
which names it in C, a `priority`, and a list of samples: variants of the effect, of which at
most one is `active`. The priority is a number, 0 when left out, that decides which effect gives
way when the voices are busy: the higher wins. Only the active sample is read, converted and put
into the game; the others keep their settings, so that you can swap the sound and keep the old
one at hand. An effect without an active sample keeps its name and plays nothing, and the game
builds unchanged. The other members of a sample set how it sounds:

| Member | What it does | Default |
|---|---|---|
| `rate` | the rate the S-DSP plays the sample at, in Hz | 16000 |
| `treble` | boosts the highs before encoding, in dB, against the dull sound of the S-DSP's interpolation | 0 |
| `gain` | scales the sound before encoding, in dB, so that it uses the range of BRR | 0 |
| `trim` | when `true`, cuts what is quieter than -50 dB at the start and the end | `true` |
| `volume` | the voice's volume register while it plays, 127 at most | 100 |

The game names the file in the `[sound]` table of its `superdevkit.toml`:

```toml
[sound]
sfx = "assets/sfx/sfx.json"
```

Then `superdevkit build` converts it with `superdevkit sfx`, after the pictures, into
`build/gen/sfx.c`, the BRR of every active sample and the tables of the effects, and
`build/gen/sfx.h`, which names them. It prints what each effect takes, and the total against
the 64,448 bytes of audio RAM that the driver leaves to the samples. This is what
`superdevkit build snes` prints in a copy of Steinschlag, made with
`superdevkit init steinschlag --from steinschlag`:

```
    dig          dig.wav             369 bytes  11.00 kHz    5 frames
    gem          gem.wav            3132 bytes  22.00 kHz   17 frames
    land         land.wav            819 bytes  11.00 kHz    9 frames
    beetle       beetle.wav         1521 bytes  16.00 kHz   12 frames
    crushed      crushed.wav        2916 bytes  11.00 kHz   30 frames
    exit         exit.wav           5463 bytes  16.00 kHz   38 frames
    14220 bytes of 64448 in the audio RAM (22.1 %)
built build/snes/steinschlag.sfc
```

The build stops, with the line in the file, when an effect has two active samples, an active
sample's WAV file is missing or two effects have the same `id`. When the samples do not fit into
the audio RAM, it stops with the total and lists the largest samples, each with its line. The
header gives each effect a number, its place in the list, named `SFX_` and the `id` in capitals;
this is Steinschlag's `sfx.h`, cut to the names:

```c
#define SFX_DIG     0
#define SFX_GEM     1
#define SFX_LAND    2
#define SFX_BEETLE  3
#define SFX_CRUSHED 4
#define SFX_EXIT    5
#define SFX_COUNT   6

/* The effects for sdevkit_sfx_init(&sfx_bank), in game_init. */
extern const sdevkit_sfx_bank sfx_bank;
```

A game that plays an effect the file does not list stops at the compiler, since the name is not
in the header. The game includes `sfx.h`, hands the bank to `sdevkit_sfx_init` once in
`game_init`, while the screen is off, and calls `sdevkit_sfx_play` where the sound happens. Cut
from `examples/steinschlag/src/game.c`, where `play` is what
`game_tick` runs while a cave is played:

```c
static void play(uint16_t pad)
{
    /* ... */
        if (time_left == 0 && !miner_dead) {
            cave[miner_y][miner_x] = K_BLAST;     /* out of time: the miner's lamp goes off */
            cell_changed(miner_x, miner_y);
            miner_dead = 1;
            sdevkit_sfx_play(SFX_CRUSHED);
        }
    /* ... */
}

void game_init(uint16_t seed)
{
    /* ... */
    sdevkit_sfx_init(&sfx_bank);
    /* ... */
}
```

An effect asked for in a [tick](/glossary#tick) starts after the tick, once the main loop has
run `game_vblank`. A free voice takes it. When all of the file's voices are busy, it takes the
voice of the lowest priority that is not above its own, of two equal ones the older; when every
playing effect has a higher priority, the new one is dropped. A voice is free again when its
effect has played to its end, the length in [frames](/glossary#frame) that the build printed.

On the SNES, `sdevkit_sfx_init` sends a small driver and the samples to the SPC700; the upload
takes about a frame per 350 bytes, during `game_init` with the screen off. On the desktop and
the web, [Rachael](/glossary#rachael) plays the same bytes with a DSP of her own, which decodes
the BRR and interpolates between its samples as the S-DSP does. Which voice an effect gets is
the same C code on both, so the choice is the same on the SNES and on the PC, frame by frame.

The BRR Lab, `<home>/tools/brr_lab.html`, is the editor of `sfx.json`. Open it in a browser and
open the game's folder `assets/sfx` with it; a folder without `sfx.json` starts with one effect
per WAV file. The Lab converts every sample as the build does, to the same bytes, plays it as
the original and as the SNES plays it, and keeps the total of the samples against the audio RAM
in view, with the errors the build would stop on. So you hear what a setting does, and see what
it costs, before you build. Chrome and Edge save `sfx.json` back into the folder; other browsers
offer it as a download. The page needs no network and no libraries.

### Music

A game lists its music in the `[sound]` table as well, one file per track. CatchMe has one:

```toml
[sound]
music = ["assets/music/chase.wav"]
```

The build writes `build/gen/music.h` with a number per track, its place in the list. Its name is
the file's without the extension, unless the entry gives one:
`{ file = "assets/music/boss-2.xm", name = "boss" }`. CatchMe's:

```c
#define MUSIC_CHASE 0   /* assets/music/chase.wav */
#define MUSIC_COUNT 1
```

The game plays a track by its number, never by a file name, and stops it. CatchMe starts the
chase at the start of a level and stops it at the game's end, in `examples/catchme/src/game.c`:

```c
    sdevkit_music_play(MUSIC_CHASE);
    /* ... */
    sdevkit_music_stop();
```

On the desktop and the web the track plays from its file, over and over, until another track or
the stop; asking for the same track again lets it play on. A desktop build copies the files into
`build/<target>/music/`, a web build packs them into the page's `index.data`. Any format raylib
reads will do: OGG, MP3, WAV, QOA, and the trackers' XM and MOD. On the SNES music is not built
yet: the calls do nothing, and dampf removes them, so a game can call them already (see
[Limits](/reference/limits)).
