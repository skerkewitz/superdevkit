<!-- GENERATED from doc-specs/guide/the-desktop.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# The desktop

The desktop build is your game as an ordinary program for the PC: the same C code, compiled by
the PC's C compiler against [Rachael](/glossary#rachael), the SNES rebuilt in portable C. The
picture is the SNES's, pixel for pixel, and Rachael counts what the console would lose, so most
of a game can be written, played and tested here. What it does not give is the console's time:
the game's code runs at the PC's speed, however long a tick would take on the SNES.

## Building for the desktop

Use `dev/build_desktop.sh` with the game's folder to build it for Linux:

```sh
dev/build_desktop.sh examples/hello
```

It writes the program to `examples/hello/build/linux/hello`, named after the folder. The game's
folder needs a `build.sh` that converts its graphics into C when `CONVERT_ONLY=1` is set (see
[Graphics and sound](/guide/graphics-and-sound)); the script runs it first, then compiles the
`.c` files of `src/` and of `build/gen/`. It needs a C compiler (`cc`, or the one `CC` names),
Python 3, `nm`, the X11 and OpenGL libraries the program links with, and on the first run curl
and tar: raylib, the library behind the window, is fetched ready-built in version 5.5 into
`build/raylib/linux` and then reused.

The build has three steps, and the first two are there to keep the game honest:

1. **The game alone.** The game's sources are compiled as on the SNES, without the host's
   headers (`-nostdinc`, `-ffreestanding`). The only include folders are dampf's (`<stdint.h>`,
   `<string.h>`), the kit's public headers in `lib/include` and the game's own `src/` and
   `build/gen/`. A game that includes `<stdio.h>` fails here.
2. **The symbol check.** `dev/check_game_symbols.py` reads with `nm` what the game's object
   files leave undefined and accepts only the functions and variables declared in
   `lib/include/sdevkit`, what the game defines itself, and a few that the C compiler calls on
   its own (`memcpy`, `memset`, `memmove` and the stack and buffer checks).
3. **The link** with Rachael (`lib/rachael`), the desktop frontend (`lib/desktop`) and raylib.

The symbol check exists because Rachael can only count what goes through the console API. A
function of raylib or the C library declared by hand, or a call into Rachael's internals,
would work on the PC and have no counterpart on the SNES, so the check rejects it and names
the symbol and the object. No header the game can include declares `rand`, so a game that
declares it itself and calls it in `src/main.c` gets:

```
/path/to/mygame/build/linux/obj/src_main.o: uses rand, which is not part of superdevkit's console API (lib/include/sdevkit, ADR record 34)
```

A game of your own lives in a folder outside the
[superdevkit home](/glossary#superdevkit-home). The script changes to the home before it
builds, so give it the game's folder as an absolute path; in the game's folder:

```sh
<home>/dev/build_desktop.sh "$PWD"
```

The program is written into the game's folder, `build/linux/mygame` for a folder named
`mygame`.

For Windows, run `dev/build_desktop_windows.sh` from WSL (the Windows Subsystem for Linux). It
takes the same argument, goes through the same three steps with Visual Studio's C compiler
(MSVC) on the Windows side (found with `vswhere`; it needs the C++ tools for x64) and raylib's
MSVC package, and writes `build/windows/<game>.exe` into the game's folder. Started from
Windows, from Explorer for example, the program plays with the keyboard and with the gamepads
Windows knows, which WSL does not see. It can also be started from WSL, as the checks do. It is
built as a Windows program without a console, so what it prints shows only when it is started
from WSL.

## Playing

Start the program without options to play in a window:

```sh
examples/hello/build/linux/hello
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

The window shows the picture through a retro TV filter, three shaders that make it look like a
SNES on a consumer CRT television fed over composite video. Press `Tab` to turn it off and on,
or start with `--no-crt` to start without it. If a shader fails to compile, the program logs
`CRT: shader failed, drawing without it` and draws the plain picture.

Press `F12` for a screenshot: `hello_0.png`, `hello_1.png` and so on, in the folder you
started the program from. A screenshot is Rachael's picture without the filter.

When you close the window, the program prints to the error output what the SNES would have
lost during the session (`hello: N bytes would not have reached the PPU on the SNES`, the
[PPU](/glossary#ppu) being the chip that draws the picture) and the register writes Rachael
does not draw.

All options, as the program prints them for an option it does not know or one that lacks its
argument:

```
usage: examples/hello/build/linux/hello [--scale N] [--no-crt] [--replay recording.txt]
       examples/hello/build/linux/hello --check check.txt [--out dir] [--record recording.txt]
```

## The check without a window

Use `--check` to play a game without a window for a fixed number of
[ticks](/glossary#tick), with the input of a file, and `--out` for the folder its files go to
(by default the current one):

```sh
mkdir -p build/hello-check
examples/hello/build/linux/hello --check examples/hello/check.txt --out build/hello-check
```

The folder must exist. The file has one instruction a line; `#` starts a comment:

| Line | Meaning |
|---|---|
| `ticks N` | play N ticks (600 without the line) |
| `press T1 T2 buttons...` | hold the buttons on joypad 1 from tick T1 to tick T2 - 1 |
| `shot T` | a screenshot after T ticks |
| `splash N` | a screenshot of frame N of the splash screen |

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
`example_splash_16.png` and so on, unfiltered, and `hashes.txt`: the checksum of every tick,
the tick and the checksum in hex on a line (`0 635e2468`). The checksum covers the sprite
table ([OAM](/glossary#oam)) and the bytes the game names with
[`sdevkit_dbg_hash`](/reference/console-api#sdevkit-dbg-hash) in its tick; a game that names
none is compared by its sprites alone. Two runs that give the same checksums played the same
game. Then it prints one line:

```
frames 400 ticks 400 lost 0 undrawn 0 sprites 0
```

The exit status is 1 when a byte was lost (written outside the
[vertical blank](/glossary#vertical-blank), or more than one vertical blank can copy) or when
the game wrote a register Rachael does not draw: the SNES would show another picture. The line
counts both: `lost` the lost bytes, and each frame that lost some gets a line of its own before
it (`lost frame N: B bytes`); `undrawn` the register writes Rachael does not draw, and when
there are any, a line before the last names the first of them:

```
hello: N register writes Rachael does not draw, the first $21XX = $YY (the SNES would show something else)
```

The last number, `sprites`, counts the lines of the picture with more sprites or sprite tiles
than the SNES draws; a game may mean that, to make sprites flicker, so it does not fail the
check. What each of these rules counts is explained in
[The SNES hardware](/guide/snes-hardware). A file the program cannot read, or an unknown
button, ends it with exit status 2.

## Recordings and replays

Every session in the window is recorded: the joypad and the checksum of every tick. When you
see something worth keeping, a bug or a moment to come back to, press `F11`. The program puts a
mark at that tick and saves the session so far as `<game>_rec_<n>.txt` in the folder you started
it from, with the first free number, and says so:

```
hello: tick 1234 flagged, the recording is hello_rec_0.txt
```

Each `F11` writes a new file with the next free number, holding the session from its start and
every mark so far. A recording is a `check.txt`: its `ticks` line, the joypad as a `press` line
for each run of ticks with the same buttons held, and two more kinds of line, `mark T` for each
mark and `hash T H` for the checksum of every tick. So one file plays in the window, in a check
and on the SNES side.

Use `--replay` to play a recording in the window:

```sh
examples/hello/build/linux/hello --replay hello_rec_0.txt
```

It pauses at each mark and at the first tick whose checksum is not the recorded one, which
means that from there the program plays another game than the one that recorded. After the
last tick of the recording the keyboard takes over, and the recording goes on, keeping the old
marks. Three keys control the play, also without a replay, and the window's title shows the
tick and the state:

- **`F5`** pauses and goes on.
- **`F6`** steps one tick while paused.
- **`F7`** runs eight ticks a frame, and turns that off again.

A recording also plays without a window: `--check` with a recording compares the checksums too,
prints the first tick that differs and exits with status 1. Add `--record file` to a check to
write the recording of its run.

To play a recording on the SNES side, give `dev/replay.sh` the game's ROM and the recording:

```sh
sh examples/hello/build.sh
dev/replay.sh examples/hello/build/hello.sfc hello_rec_0.txt
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

That tick is where the SNES and the desktop part, the place to look for a difference between
the two builds. The ROM must be built from the same source as the program that recorded the
file; with any other change the checksums differ for that reason alone. `JAVA_HOME` picks the
Java it runs with.

## Debugging on the PC

The desktop build carries debug information: the Linux build compiles with `-g`, and the
Windows build writes `build/windows/<game>.pdb` next to the program. So the game's C runs under
gdb or the Visual Studio debugger like any program of the PC, with breakpoints, steps and
variables in your own source.

The build optimizes (`-O2`, `/O2` on Windows), and at that level gdb shows some variables as
`<optimized out>`. Set `DEBUG=1` to build without optimization (`-O0`, `/Od`), so that the
debugger shows every variable; the picture and the checksums stay the same:

```sh
DEBUG=1 dev/build_desktop.sh examples/hello
```

A check is a good way in: it opens no window and plays the same input every time, so a bug
comes back at the same tick. Under gdb, with a breakpoint on the game's tick:

```sh
mkdir -p build/hello-check
gdb --args examples/hello/build/linux/hello --check examples/hello/check.txt --out build/hello-check
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
