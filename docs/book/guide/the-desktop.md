<!-- GENERATED from doc-specs/guide/the-desktop.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# The desktop

The desktop build is your game as an ordinary program for the PC: the same C code, compiled by
the PC's C compiler against [Rachael](/glossary#rachael), the SNES rebuilt in portable C. The
picture is the SNES's, pixel for pixel, and Rachael counts what the console would lose, so most
of a game can be written, played and tested here. What it does not give is the console's time:
the game's code runs at the PC's speed, however long a [tick](/glossary#tick) would take on the
SNES.

## Building for the desktop

Use `superdevkit build linux` in the game's folder to build it for Linux, here in a copy of
`examples/hello` made with `superdevkit init hello`:

```sh
superdevkit build linux
```

It writes the program to `build/linux/hello`, named after the `name` in the game's
[project file](/glossary#project-file), and prints `built build/linux/hello`. Before the C
compiler runs, the build converts the game's pictures and sound effects into C in `build/gen`
(see [Graphics and sound](/guide/graphics-and-sound)). It needs a C compiler (`cc`, or the one
`CC` names: gcc or clang) and the X11 and OpenGL libraries the program links with. raylib, the
library behind the window and the sound, comes ready-built in version 5.5: the first build
fetches it from raylib's releases on GitHub into the
[superdevkit home](/glossary#superdevkit-home) (`raylib/linux`), and later builds reuse it.

The build has three steps, and the first two are there to keep the game honest:

1. **The game alone.** The game's sources are compiled as on the SNES, without the host's
   headers (`-nostdinc`, `-ffreestanding`). The only include folders are dampf's (`<stdint.h>`,
   `<string.h>`, `<dampf.h>`), the kit's public headers in `lib/include`, the game's own include
   folders and `build/gen`. A game that includes `<stdio.h>` fails here.
2. **The symbol check.** superdevkit reads what the game's object files leave undefined and
   accepts only the functions and variables declared in `lib/include/sdevkit`, what the game
   defines itself, and a few that the C compiler calls on its own (`memcpy`, `memset`,
   `memmove` and the stack and buffer checks).
3. **The link** with Rachael (`lib/rachael`), the desktop frontend (`lib/desktop`) and raylib.

The symbol check exists because Rachael can only count what goes through the
[console API](/glossary#console-api). A function of raylib or the C library declared by hand,
or a call into Rachael's internals, would work on the PC and have no counterpart on the SNES,
so the check rejects it and names the symbol and the object. No header the game can include
declares `rand`, so a game that declares it itself and calls it in `src/main.c` gets:

```
error: check: src_main.o: uses rand, which is not part of superdevkit's console API (lib/include/sdevkit, ADR-6F3CK2AR)
```

Use `superdevkit run` to build the game and start it in one go. It builds for the
[target](/glossary#target) of the PC it runs on (`linux`, also under [WSL](/glossary#wsl);
`win64` on Windows), starts the program in the game's folder and waits until you close the
window. `superdevkit build linux --plan` lists what the build would run without running it; the
command's options are on [The superdevkit command](/reference/superdevkit).

For Windows, run `superdevkit build win64`, on Windows or from WSL. It goes through the same
three steps with Visual Studio's C compiler (MSVC, found with `vswhere`; it needs the C++ tools
for x64) and raylib's MSVC package, and writes
`build/win64/hello.exe`. Started from Windows, from Explorer for example, the program plays
with the keyboard and with the gamepads Windows knows: WSL sees no gamepad, since Windows keeps
every input device. It can also be started from WSL, with `superdevkit run win64` there. It is
built as a Windows program without a console.

## Playing

Start the program without options to play in a window, or let `superdevkit run` start it:

```sh
build/linux/hello
```

The window opens at three times the SNES's 256 by 224 pixels; `--scale N` picks another
factor. You can resize it: the picture fills it as far as its shape allows, centred, with black
around it. Before the game, the splash screen plays, as on the SNES.

The keyboard and the first gamepad are [joypad](/glossary#joypad) 1, at the same time:

| SNES | Keyboard | Gamepad |
|---|---|---|
| Up, down, left, right | arrow keys | the directional pad |
| B | `Z` | the bottom face button |
| A | `X` | the right face button |
| Y | `A` | the left face button |
| X | `S` | the top face button |
| L | `Q` | the left shoulder button |
| R | `W` | the right shoulder button |
| Start | `Enter` | the right middle button |
| Select | right `Shift` or `Backspace` | the left middle button |

The window shows the picture through a retro TV filter that makes it look like a SNES on a CRT
television. Press `Tab` to turn it off and on, or start with `--no-crt` to start without it.

Press `F12` for a screenshot: `hello_0.png`, `hello_1.png` and so on, in the folder you
started the program from. A screenshot is Rachael's picture without the filter.

The window plays the game's sound. The sound effects go through Rachael's DSP, the SNES's sound
chip rebuilt in C, at 32 kHz in stereo. The music the game asks for with `sdevkit_music_play`
plays from the track's file, which the build copies into `build/linux/music/` next to the
program, over and over until the game stops it or asks for another; it pauses when the game is
paused. On the SNES, music is not built yet (see [Limits](/reference/limits)). How a game gets
its sounds is in [Graphics and sound](/guide/graphics-and-sound).

A game with save RAM (`sdevkit_sram`, sized by `sram` in its project file) keeps it in the file
`<game>.srm` next to the program: `build/linux/catchme.srm` for `examples/catchme`, which
keeps its high score there. The program reads the file before the game starts; without one,
the save RAM holds zeros. It writes the file when the save RAM has changed, which it looks at
every half second, and when the window closes. A check and a replay never touch the file: they
start with the save RAM their file names.

When you close the window, the program prints to the error output what the SNES would have
lost during the session (`hello: N bytes would not have reached the PPU on the SNES`, the
[PPU](/glossary#ppu) being the chip that draws the picture), the register writes Rachael does
not draw and the DSP writes she does not play.

All options, as the program prints them for an option it does not know or one that lacks its
argument:

```
usage: build/linux/hello [--scale N] [--no-crt] [--replay recording.txt]
       build/linux/hello --check check.txt [--out dir] [--record recording.txt] [--wav sound.wav]
                 [--view-shot view.png [--view-tab 0..3]]
```

The web build runs this same frontend in the browser, with the same keys for the game, though it
has the debug view and its keys (`F1`, `F5` to `F8`) only in a build made with `--debug`; see
[The web](/guide/the-web).

## The check without a window

Use `--check` to play a game without a window for a fixed number of ticks, with the input of a
file, and `--out` for the folder its files go to
(by default the current one):

```sh
mkdir -p build/check
build/linux/hello --check check.txt --out build/check
```

The folder must exist. The file has one instruction a line; `#` starts a comment:

| Line | Meaning |
|---|---|
| `ticks N` | play N ticks (600 without the line) |
| `press T1 T2 buttons...` | hold the buttons on joypad 1 from tick T1 to tick T2 - 1 |
| `shot T` | a screenshot after T ticks |
| `splash N` | a screenshot of frame N of the splash screen |
| `sram H` | the save RAM at power-on, its bytes in hex, the rest zero (all zero without the line) |

The buttons are `b`, `y`, `select`, `start`, `up`, `down`, `left`, `right`, `a`, `x`, `l` and
`r`. On the desktop every [frame](/glossary#frame) is one tick; the splash
screen plays before the first tick and gets no input. The check of `examples/hello`:

```[examples/hello/check.txt]
ticks 400
splash 1
splash 16
splash 31
splash 120
splash 230
shot 30
press 60 160 right
press 160 260 down
shot 300
```

The check writes the screenshots into the `--out` folder as `example_shot_30.png`,
`example_splash_16.png` and so on, unfiltered. These are the names the repository's check in
Mesen (`dev/example_check.lua`) gives its screenshots too, so that the pictures of the two can
be compared file by file. It also writes `hashes.txt`: the checksum of every tick,
the tick and the checksum in hex on a line (`0 635e2468`). The checksum covers the sprite
table ([OAM](/glossary#oam)) and the bytes the game names with
[`sdevkit_dbg_hash`](/reference/console-api#sdevkit-dbg-hash) in its tick; a game that names
none is compared by its sprites alone. Two runs that give the same checksums played the same
game.

It also writes the sound there, as `sound.txt`: every write to a register of the DSP, with
`init` (made in `game_init`) or the tick after which it came, the register and the value in
hex; after `game_init`'s writes, the line `aram H` holds a checksum of what the game uploaded
into the sound chip's memory. dampf's emulator writes the same file for the ROM, so the sound
of the two builds can be compared. Add `--wav file` to write the DSP's output as a WAV file as
well. A check plays no music. Then it prints one line:

```
frames 400 ticks 400 lost 0 undrawn 0 unheard 0 sprites 0
```

The exit status is 1 when a byte was lost (written outside the
[vertical blank](/glossary#vertical-blank), or more than one vertical blank can copy), when
the game wrote a register Rachael does not draw, so that the SNES would show another picture,
or when it wrote the DSP in a way Rachael does not play, so that the SNES would sound different.
The line counts all three: `lost` the lost bytes, and each frame that lost some gets a line of
its own before it (`lost frame N: B bytes`); `undrawn` the register writes Rachael does not
draw and `unheard` the DSP writes she does not play. When there are any, a line before the last
names the first of them:

```
hello: N register writes Rachael does not draw, the first $21XX = $YY (the SNES would show something else)
hello: N DSP writes Rachael does not play, the first $XX = $YY (docs/sound.md: what Rachael's DSP plays)
```

The last number, `sprites`, counts the lines of the picture with more sprites or sprite tiles
than the SNES draws; a game may mean that, to make sprites flicker, so it does not fail the
check. What each of these rules counts is explained in
[The SNES hardware](/guide/snes-hardware). A file the program cannot read, an unknown button,
or an `sram` line longer than the game's save RAM ends it with exit status 2.

## Recordings and replays

Every session in the window is recorded: the joypad and the checksum of every tick. When you
see something worth keeping, a bug or a moment to come back to, press `F11`. The program puts a
mark at that tick and saves the session so far as `<game>_rec_<n>.txt` in the folder you started
it from, with the first free number, and says so:

```
hello: tick 1234 flagged, the recording is hello_rec_0.txt
```

Each `F11` writes a new file with the next free number, holding the session from its start and
every mark so far. A recording is a `check.txt`: its `ticks` line, the save RAM the session
started with as an `sram` line (unless it was all zero), the joypad as a `press` line for each
run of ticks with the same buttons held, and two more kinds of line, `mark T` for each mark and
`hash T H` for the checksum of every tick. So one file plays in the window, in a check and on
the SNES side, and from the same save RAM.

Use `--replay` to play a recording in the window:

```sh
build/linux/hello --replay hello_rec_0.txt
```

It pauses at each mark and at the first tick whose checksum is not the recorded one, which
means that from there the program plays another game than the one that recorded. After the
last tick of the recording the keyboard takes over, and the recording goes on, keeping the old
marks. Four keys control the play, also without a replay, and the window's title shows the
tick and the state:

- **`F5`** pauses and goes on.
- **`F6`** steps one tick while paused.
- **`F7`** runs eight ticks a frame, and turns that off again.
- **`F8`** runs a tick every four frames (slow motion), and turns that off again.

A recording also plays without a window: `--check` with a recording compares the checksums too,
prints the first tick that differs and exits with status 1. Add `--record file` to a check to
write the recording of its run.

To play a recording on the SNES side, build the game's ROM with `superdevkit build snes` and
give `dev/replay.sh` of the superdevkit home the ROM and the recording:

```sh
superdevkit build snes
<home>/dev/replay.sh build/snes/hello.sfc hello_rec_0.txt
```

It plays the ROM in dampf's own emulator with the recording's joypad and compares the checksum
of every tick with the recorded one:

```
400 ticks played, 400 checksums the same as the recording
```

Where a tick differs, it names the first one and exits with status 1:

```
tick 200 has checksum 11edb09a on the SNES, the recording deadbeef
```

That tick is where the SNES and the desktop part ways, the place to look for a difference
between the two builds. The ROM must be built from the same source as the program that
recorded the file; with any other change the checksums differ for that reason alone.
`JAVA_HOME` picks the Java it runs with.

## Debugging on the PC

Press `F1` to open the debug view, a panel beside the picture. At its top are the tick and
four buttons for the run, the same as `F5` to `F8`: Pause (Go while paused), Step, Slow and
Fast. Below them are four tabs:

- **Game**: what the game registered to change while it runs, a switch for the game's boxes
  over the picture, and at the bottom the lost bytes, undrawn register writes and unheard DSP
  writes so far.
- **Palettes**: the 256 colours of [CGRAM](/glossary#cgram); a click on a row picks the palette
  the Tiles tab draws in.
- **Tiles**: [VRAM](/glossary#vram) as tiles of 8 by 8 pixels with 4 bits a pixel, 16 a row, in
  that palette; the mouse wheel scrolls.
- **Sprites**: the entries of the sprite table that are on the screen, with position, tile,
  palette, priority and size; the one under the mouse is outlined on the picture.

A game registers in `game_init` what it wants to change while it runs: `sdevkit_dbg_toggle` a
flag of 0 or 1, `sdevkit_dbg_range` a number between two bounds, `sdevkit_dbg_color` a colour,
`sdevkit_dbg_action` a button, which sets its variable to 1. In each tick it can draw boxes over
the picture with `sdevkit_dbg_box`, a collision box for instance, in one of eight colours. On
the SNES these functions are empty and dampf removes the calls, so the game needs no `#ifdef`.
In the following game the player moves left and right, and going past x = 200 sends it back to
the start unless it is invincible. The view changes the player's speed, switches invincibility
on and off and puts the player back to the start with a button; the player's box is green while
it is invincible and red otherwise:

```c
#include <stdint.h>
#include "sdevkit/loop.h"
#include "sdevkit/snes.h"

static int16_t player_x = 120;
static int16_t player_y = 100;
static int16_t player_speed = 2;
static uint8_t invincible;
static uint8_t reset_player;

void game_init(uint16_t seed)
{
    (void)seed;
    sdevkit_dbg_range("player speed", &player_speed, 0, 8);
    sdevkit_dbg_toggle("invincible", &invincible);
    sdevkit_dbg_action("reset player", &reset_player);
}

void game_tick(const uint16_t pad[SDEVKIT_PADS])
{
    if (pad[0] & SDEVKIT_KEY_RIGHT) {
        player_x += player_speed;
    }
    if (pad[0] & SDEVKIT_KEY_LEFT) {
        player_x -= player_speed;
    }
    if (player_x > 200 && !invincible) {
        reset_player = 1;           /* past 200: back to the start */
    }
    if (reset_player) {
        player_x = 120;
        player_y = 100;
        reset_player = 0;
    }
    sdevkit_dbg_box(player_x, player_y, 16, 16, invincible ? 1 : 0);
}

void game_vblank(void)
{
}
```

A change made in the view is not part of the recording, so from that tick on a replay plays
another game. The program says so once on the error output, the window's title adds
`changed in the debug view`, and a recording saved from then on notes it in a comment line:

```
# the debug view changed player speed before tick 812: from there a replay plays another game
```

With `--check`, `--view-shot file.png` draws the view once after the check into a PNG, in a
hidden window, and `--view-tab 0..3` picks the tab; it is there for tests and documentation.

The desktop build also carries debug information: the Linux build compiles with `-g`, and the
Windows build writes `build/win64/<game>.pdb` next to the program. So the game's C runs under
gdb or the Visual Studio debugger like any program of the PC, with breakpoints, steps and
variables in your own source.

The build optimizes (`-O2`, `/O2` on Windows), and at that level gdb shows some variables as
`<optimized out>`. Add `--debug` to build without optimization (`-O0`, `/Od`), so that the
debugger shows every variable; the picture and the checksums stay the same. `superdevkit run`
takes the option as well:

```sh
superdevkit build linux --debug
```

A check is a good way in: it opens no window and plays the same input every time, so a bug
comes back at the same tick. Under gdb, with a breakpoint on the game's tick:

```sh
mkdir -p build/check
gdb --args build/linux/hello --check check.txt --out build/check
```

```
(gdb) break game_tick
(gdb) run
```

A recording works the same way in place of `check.txt`: it plays the session up to the moment
you pressed `F11`.

What the debugger shows is the game compiled by the PC's C compiler. The 65816 code dampf
writes for the SNES is not there; to step through that, see
[Debugging in Mesen](/guide/debugging).

## What the desktop does not tell you

Rachael is no emulator: it runs no 65816 code, and the game's code runs at the PC's speed. A
tick that takes two frames on the SNES takes one on the desktop, and code too slow for the
console is never too slow here. The window keeps 60 frames a second whatever the game does in
a tick; only the check runs as fast as the PC can. The desktop finds what the SNES would lose or
draw differently, but not whether a tick fits in a frame.

For the console's time, look at the [timing report](/glossary#timing-report) that `dampf build`
writes next to the ROM (see [Exact timing](/inside/timing)), and run the ROM in
[Mesen](/glossary#mesen), as [Debugging in Mesen](/guide/debugging) shows.
