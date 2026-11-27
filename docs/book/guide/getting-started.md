<!-- GENERATED from doc-specs/guide/getting-started.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Getting started

A [superdevkit home](/glossary#superdevkit-home) is one folder that holds everything the kit has:
dampf, the `superdevkit` command, the kit's library, the tools, the examples and this book. A
game lives in a folder of its own, anywhere on the disk, with a
[project file](/glossary#project-file), `superdevkit.toml`, that names the kit's version; the
home's `superdevkit` command builds it.

## What you need

The download brings its own Java and raylib, the library behind the desktop's window. What else
you need depends on the [target](/glossary#target):

| Target | You need |
| --- | --- |
| SNES (`superdevkit build snes`) | nothing else |
| Windows (`superdevkit build win64`) | Visual Studio with "Desktop development with C++" |
| Linux (`superdevkit build linux`) | a C compiler and the OpenGL and X11 development libraries |
| Web (`superdevkit build web`) | [Emscripten](/glossary#emscripten)'s `emcc`, on the `PATH` |

To look at the result you need [Mesen](/glossary#mesen) (MesenCE), which runs the ROM and steps
through the C source.

dampf preprocesses, assembles and links the ROM with its own preprocessor, assembler and linker,
so the SNES build needs no other tool. Its preprocessor handles a subset of C99's directives
and macros, with dampf's own `<stdint.h>`; [The C subset](/reference/c-subset#preprocessor) says
which.

Only to develop the kit do you need [ca65 and ld65](/glossary#ca65-and-ld65) from cc65 2.19, and
clang; the [README of the repository](https://github.com/skerkewitz/superdevkit#prerequisites)
has the details.

## Get superdevkit

Download the zip for your system from the
[releases](https://github.com/skerkewitz/superdevkit/releases):
`superdevkit-<version>-linux-x64.zip` for Linux and [WSL](/glossary#wsl),
`superdevkit-<version>-windows-x64.zip` for Windows. Unpack it anywhere. The zip holds one
folder, `superdevkit-<version>`, and that folder is the superdevkit home.

This page writes `<home>` for that folder. Put `<home>/bin` on your `PATH`. On Linux, for a home
in `~/superdevkit-0.10.1`, add this line to `~/.bashrc` (or your shell's start-up file):

```sh
export PATH="$HOME/superdevkit-0.10.1/bin:$PATH"
```

On Windows, for a home in your user folder, run this line once in PowerShell. It adds the folder
to your user's `PATH`, which every new window then has:

```powershell
[Environment]::SetEnvironmentVariable("Path", "$HOME\superdevkit-0.10.1\bin;" + [Environment]::GetEnvironmentVariable("Path", "User"), "User")
```

The page writes `superdevkit` and `dampf` without their folder from here on. Check that a new
shell finds the right one:

```sh
superdevkit --version
```

```
superdevkit 0.10.1
```

From a checkout of the repository, with JDK 25 or newer, `./gradlew installDist` writes a home
without Java and raylib to `build/install/superdevkit`.

The tests run with `./gradlew build`, which you need only when you work on the kit itself;
[Testing](/inside/testing) describes them.

## What is in the home

| Folder | What it holds |
| --- | --- |
| `bin/` | the launchers: `dampf`, the compiler, and `superdevkit`, the kit's command (with `.bat` files for Windows) |
| `jre/` | the Java runtime the launchers start, cut down to the modules the kit uses |
| `java/` | the jars of the two programs and of what they depend on |
| `raylib/` | raylib for this system and for the web, ready-built |
| `include/` | dampf's own headers: `<stdint.h>`, `<string.h>` and `<dampf.h>` |
| `lib/` | the kit's library: the headers of the [console API](/glossary#console-api) (`include/`), the SNES side (`snes/`), the desktop and web side (`rachael/`, `desktop/`, `web/`), the optional layers' logic (`layers/`), the sound effects (`sound/`), the splash screen, the setup of the headers' HTML reference (`doxygen/`); `C_STYLE.md` and `SNES_HARDWARE.md` |
| `tools/` | tools for game developers: `brr_lab.html`, the editor of a game's sound effects, and `web_check` |
| `examples/` | the kit's examples to read and copy, each with a `README.md` and a `superdevkit.toml` |
| `dev/` | `replay.sh`, which plays a desktop recording on the SNES side |
| `docs/` | this book, as Markdown files that link to each other |

The two include folders are easy to mix up: `<home>/include` holds dampf's own headers, which
take the place of the PC's, and `<home>/lib/include` the console API a game calls:
`sdevkit/snes.h`, `sdevkit/loop.h` and the optional layers above them, such as
`sdevkit/easy.h`.

The `README.md` at the top of the home points to this page, and the file `VERSION` holds the
kit's version. A home written by `./gradlew installDist` has no `jre/`, `raylib/` or `VERSION`:
it runs on the JDK of your system and fetches raylib on its first desktop build.

Two guides in `lib/` are for writing a game: `C_STYLE.md` says how to write a game's C for dampf,
and `SNES_HARDWARE.md` what the SNES allows and which of it the kit has.

## A first game

The smallest example, `hello`, puts text on the screen and a ball that the
[joypad](/glossary#joypad) moves. `superdevkit init` makes a new game from it, in a folder of the
name you give, below the current folder:

```sh
cd ~
superdevkit init mygame
```

```
superdevkit init: ~/mygame, from the example hello
  next: cd mygame && superdevkit run
```

It prints the full path, shown here with `~`. `--from` takes another example instead of `hello`,
such as `superdevkit init mygame --from catchme`. The folder is a copy of the example without its
`build/`, with two changes: `superdevkit.toml` gets the new name and the kit's version, and a new
`CLAUDE.md` points an AI assistant working on the game to the kit's guides `C_STYLE.md` and
`SNES_HARDWARE.md`. The project file then reads:

```toml [superdevkit.toml]
# hello's project file (superdevkit.toml, ADR-6F3CJZBY): superdevkit build snes makes the ROM,
# superdevkit run plays it on the desktop.
name = "mygame"
superdevkit = "0.10"                # the kit's version this game is built with
sources = ["src"]

[graphics]
tiles = ["assets/font_gfx.png", "assets/ball_gfx.png"]
```

In the project file, `name` names the ROM and the programs, `sources` the game's C files and
folders, and `[graphics]` the PNG files the build converts into C. The line
`superdevkit = "0.10"` says which kit builds the game: the `superdevkit` command takes its own
home, and refuses a game that asks for another version. In the home, the examples have
`kit = "../.."` there instead, the kit two folders up; a plain copy of `<home>/examples/hello`
works as well once that line is replaced by `superdevkit = "0.10"`.

Build the ROM, in the game's folder:

```sh
cd ~/mygame
superdevkit build snes
```

```
built build/snes/mygame.sfc
```

The build converts the two PNG files of `assets/` into C in `build/gen`, then dampf compiles the
game, its converted graphics and the kit's library for the SNES (`<home>/lib/snes`) together, as
one [whole program](/glossary#whole-program).

The [entry function](/glossary#entry-function), `main`, is not in your code: it is the library's
main loop in `lib/snes/main.c`. It calls three functions that every superdevkit game defines, and
`src/hello.c` is built around them:

```c
void game_init(uint16_t seed)                    /* once, with the screen off */
{
    /* ... registers in sdevkit_ppu, graphics into VRAM and CGRAM, the text, the screen on */
}

void game_tick(const uint16_t pad[SDEVKIT_PADS]) /* once per frame */
{
    /* ... move the ball by pad[0], write its position, queue the map, place the sprite */
}

void game_vblank(void)                           /* in the vertical blank */
{
    sdevkit_commit();
}
```

A [tick](/glossary#tick), one call of `game_tick`, runs while the picture is drawn, and
`game_vblank` runs in the [vertical blank](/glossary#vertical-blank), the only time the SNES
takes new data for the picture. The example uses the kit's optional layers (`sdevkit/easy.h`), so
it needs no rule of the vertical blank: the tick prepares the next picture in RAM (the
[PPU](/glossary#ppu)'s registers in `sdevkit_ppu`, the sprite, the copy of the changed map it
queues), and `sdevkit_commit` writes all of it into the PPU. A game that wants every cycle writes
the hardware itself in `game_vblank`. [The console API](/reference/console-api) describes these
functions and the calls the game makes in them.

## What you get

After the build, `~/mygame/build/snes/` holds, next to `mygame.sfc`:

- **`mygame.s`**: the generated 65816 assembly, with the C source line and a comment for each
  instruction.
- **`mygame.dbg`**: the debug file, in ld65's format, which lets Mesen show the C source while
  you step.
- **`mygame.lbl`**: a label file.
- **`mygame.timing.txt`**: the [timing report](/glossary#timing-report), the best and worst
  case of each function in CPU cycles, from entry to return.
- **`dampf_runtime.s`**: the assembly of the [runtime](/glossary#runtime), the start-up code
  dampf links into every ROM.
- **`mygame.args`**: what the build compiled, as an [args file](/glossary#args-file) for dampf,
  with absolute paths. `dampf check @build/snes/mygame.args` and `dampf build` take it.

Two more things are in `~/mygame/build/`:

- **`gen/`**: the graphics as C, `font_gfx.c` and `ball_gfx.c` with their headers.
- **`compile_commands.json`**: each of the game's C files with the options dampf's preprocessor
  gets, for editors.

[The generated files](/reference/output-files) describes them all.

## On the desktop

The same game also runs in a window on the PC, on [Rachael](/glossary#rachael), the SNES rebuilt
in portable C. Build it for the [target](/glossary#target) `linux`, on Windows with
`superdevkit build win64`:

```sh
superdevkit build linux
```

```
built build/linux/mygame
```

The build compiles the game's C with the PC's C compiler (`cc`, or the one `CC` names; on
Windows MSVC, the compiler of Visual Studio), without the PC's headers, checks that the game
calls nothing but the console API, and links it with Rachael, the desktop's frontend and the
home's raylib.

The program is named after the game. To build it and play it in a window, run:

```sh
superdevkit run
```

Without a target, `run` builds and starts the desktop program of this machine, `linux` (also
under [WSL](/glossary#wsl), whose window WSLg shows) or `win64` on Windows, and waits until you
close it. On Windows this needs Visual Studio; without it, `superdevkit build snes` still works.
The keyboard is the joypad: the arrows, Z (B), X (A), A (Y), S (X), Q and W (L and R), Enter
(Start). The arrows move the ball, and the picture is the one the SNES shows, pixel for pixel.
[The desktop](/guide/the-desktop) has the rest: the retro TV filter, the debug view, recordings
and the check that plays a game without a window.

The game runs in the browser as well. `superdevkit build web` builds it with Emscripten's `emcc`
into `build/web/index.html`, and `superdevkit run web` serves the page on this machine and opens
it in the browser; [The web](/guide/the-web) describes it.

## Look at it in Mesen

Open `build/snes/mygame.sfc` in Mesen with `mygame.dbg` next to it. In the debugger's integration
settings, let Mesen load `.dbg` files automatically; it then shows `hello.c` in the debugger and
you can step through `game_tick` line by line in the C source. The debug file names the C
sources, the kit's library in the home among them, by paths relative to itself. So the game and
the home must keep their places relative to each other: move one of them, and Mesen finds the
sources again only after the next build.

From WSL, the Windows Mesen works. Open the ROM through the `\\wsl.localhost\...` path of the
game's folder, so that Mesen also finds the debug file and the sources.

To build the ROM and start Mesen with it in one step, run `superdevkit run snes`. It takes the
Mesen program that the environment variable `MESEN` names, else `.mesen/Mesen` or
`.mesen/Mesen.exe` in the home. The home comes without a Mesen, so set `MESEN` to the Linux
build of MesenCE that you unpacked:

```sh
export MESEN=~/mesen/Mesen
superdevkit run snes
```

Since `run snes` hands Mesen a Linux path, open the ROM in the Windows Mesen from WSL through
the `\\wsl.localhost\...` path instead, as above.

[Debugging in Mesen](/guide/debugging) has more on stepping through C code.

## When something goes wrong

**Not in the game's folder.** The commands `build` and `run` take the game in the current folder,
or in a folder given after the target: `superdevkit build snes ~/mygame`. A folder alone,
`superdevkit run ~/mygame`, builds and runs the desktop program. Where there is no
`superdevkit.toml`, they stop:

```
error: no superdevkit.toml in ~ (a project of the kit has one: superdevkit init makes it)
```

**A header not found.** dampf's preprocessor sees dampf's own headers, the console API in
`<home>/lib/include` and the game's folders, never the headers of the PC: the game has to build
the same for the SNES and every other target. A header such as `<stdio.h>`, added to
`src/hello.c` after `<stdint.h>`, stops the build:

```
error: dampf: ~/mygame/src/hello.c:20:10: error: stdio.h: No such file or directory
```

The desktop build compiles the game without the PC's headers too, so `cc` reports the same.

**A construct outside the subset.** dampf accepts a [subset](/glossary#subset) of C, and it
rejects anything outside it with the file, line and column and a short reason. Add a variable of
type `float` to `src/hello.c`, after `ball_y`:

```c
static float ball_speed = 2.0f;
```

Now `superdevkit build snes` stops with the first error:

```
error: dampf: ~/mygame/src/hello.c:36:1: error: 'float' is not supported: dampf has no floating point
```

To see every error of every file at once, run `dampf check`, which runs only the
[front end](/glossary#front-end). Give it the args file the build wrote, from `~/mygame`:

```sh
dampf check @build/snes/mygame.args
```

```
FAILS   ~/mygame/src/hello.c:36:1: error: 'float' is not supported: dampf has no floating point
ok      ~/mygame/build/gen/ball_gfx.c
ok      ~/mygame/build/gen/font_gfx.c
ok      <home>/lib/snes/irq.c
ok      <home>/lib/snes/layers.c
ok      <home>/lib/snes/main.c
ok      <home>/lib/snes/sfx.c
ok      <home>/lib/snes/snes.c
7 of 8 file(s) pass the front end
FAILS   <home>/lib/snes/main.c:42:5: error: function 'game_vblank' is called but never defined   (the 7 file(s) together)
```

It checks every file alone, then the files that passed together, as one program. The last line
follows from the first: `hello.c` failed, so the files checked together have no `game_vblank`.
Fix the first error of a file first; errors that follow from it then go away.

[The C subset](/reference/c-subset) lists what dampf accepts,
[The superdevkit command](/reference/superdevkit) describes `init`, `build` and `run` with all
their options, and [Command line](/reference/command-line) describes `check` and dampf's other
commands.
