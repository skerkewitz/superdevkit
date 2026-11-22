<!-- GENERATED from doc-specs/guide/getting-started.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Getting started

A superdevkit home is one folder that holds everything the kit has: dampf, the `superdevkit`
command, the kit's library, the tools, the examples and this book. A game lives in a folder of its
own, anywhere on the disk, with a [project file](/glossary#project-file), `superdevkit.toml`,
that names the kit's version; the home's `superdevkit` command builds it.

## What you need

| Tool | Used for |
| --- | --- |
| JDK 25 or newer | building the home and running dampf and `superdevkit` (the code targets Java 25) |
| a C compiler (`cc`, gcc or clang) | the desktop build |
| [Mesen](/glossary#mesen) (MesenCE) | running the ROM and stepping through the C source |
| Emscripten (`emcc`) | only the build for the browser |

dampf preprocesses, assembles and links the ROM with its own preprocessor, assembler and linker,
so the SNES build needs nothing but Java. The preprocessor is a subset of C99's: `#include` and
`#define` work as on the PC, with dampf's own `<stdint.h>`.
[ca65 and ld65](/glossary#ca65-and-ld65) from cc65 2.19 and clang are needed only to develop the
kit, since its tests build every program with ca65 and ld65 as well and compare the ROMs, and
take clang as the reference on the PC; the README of the repository says how to install them.

The desktop build links with raylib, OpenGL and X11, so the system needs their libraries. The kit
fetches raylib itself on the first desktop build.

## Get superdevkit

Today the home is built from the repository. In a checkout, run the command below; Gradle comes
with the wrapper in the repository, so there is nothing to install for it:

```sh
./gradlew installDist
```

This writes the home to `build/install/superdevkit`. It runs no tests. The folder is
self-contained: copy it anywhere you like, for example to `~/superdevkit`. Copy it before you put
anything of your own into it, since the next `installDist` empties `build/install/superdevkit`
first. Release downloads come later ([roadmap](/inside/roadmap)).

This page writes `<home>` for the folder you copied. Put `<home>/bin` on your `PATH`; the page
writes `superdevkit` and `dampf` without their folder from here on. Check that the shell finds
the right one:

```sh
superdevkit --version
```

```
superdevkit 0.8.0
```

`./gradlew build` also runs the tests, which you need only when you work on the kit itself;
[Testing](/inside/testing) describes them.

## What is in the home

| Folder | What it holds |
| --- | --- |
| `bin/` | the launchers: `dampf`, the compiler, and `superdevkit`, the kit's command (with `.bat` files for Windows) |
| `java/` | the jars of the two programs |
| `include/` | dampf's own headers: `<stdint.h>`, `<string.h>` and `<dampf.h>` |
| `lib/` | the kit's library: the headers of the console API (`include/`), the SNES side (`snes/`), the desktop and web side (`rachael/`, `desktop/`, `web/`), the sound effects (`sound/`), the splash screen; `C_STYLE.md` and `SNES_HARDWARE.md` |
| `tools/` | tools for game developers: `brr_lab.html`, the editor of a game's sound effects, `sdevkit_rename.py` and `web_check` |
| `examples/` | the kit's examples to read and copy, each with a `README.md` and a `superdevkit.toml` |
| `dev/` | `replay.sh`, which plays a desktop recording on the SNES side |
| `docs/` | this book, as Markdown files that link to each other |

The `README.md` at the top of the home points to this page. The first desktop build adds
`raylib/`, the raylib it fetched.

`lib/C_STYLE.md` says how to write a game's C for dampf, and `lib/SNES_HARDWARE.md` what the SNES
allows and which of it the kit has.

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
superdevkit = "0.8"                # the kit's version this game is built with
sources = ["src"]

[graphics]
tiles = ["assets/font_gfx.png", "assets/ball_gfx.png"]
```

`name` names the ROM and the programs, `sources` the game's C files and folders, and `[graphics]`
the PNG files the build converts into C. The line `superdevkit = "0.8"` says which kit builds the
game: the `superdevkit` command takes its own home, and refuses a game that asks for another
version. In the home, the examples have `kit = "../.."` there instead, the kit two folders up; a
plain copy of `<home>/examples/hello` works as well once that line is replaced by
`superdevkit = "0.8"`.

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
    /* ... graphics into VRAM, palettes into CGRAM, the text, the screen on */
}

void game_tick(const uint16_t pad[SDEVKIT_PADS]) /* once per frame */
{
    /* ... move the ball by pad[0], write its position, place the sprite */
}

void game_vblank(void)                           /* in the vertical blank */
{
    /* ... copy the changed background map into VRAM */
}
```

A [tick](/glossary#tick), one call of `game_tick`, runs while the picture is drawn, and
`game_vblank` runs in the [vertical blank](/glossary#vertical-blank), the only time the SNES
takes new data for the picture. [The console API](/reference/console-api) describes these
functions and the calls the game makes in them.

## What you get

`~/mygame/build/snes/` now holds, next to `mygame.sfc`:

- **`mygame.s`**: the generated 65816 assembly, with the C source line and a comment for each
  instruction.
- **`mygame.dbg`**: the debug file, in ld65's format, which lets Mesen show the C source while
  you step.
- **`mygame.lbl`**: a label file.
- **`mygame.timing.txt`**: the best and worst case of each function in CPU cycles, from entry to
  return.
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
in portable C. Build it for the [target](/glossary#target) `linux`:

```sh
superdevkit build linux
```

The build compiles the game's C with the PC's C compiler (`cc`, or the one `CC` names), without
the PC's headers, checks that the game calls nothing but the console API, and links it with
Rachael, the desktop's frontend and raylib. On its first run it fetches raylib from GitHub into
`<home>/raylib/linux`:

```
    fetching https://github.com/raysan5/raylib/releases/download/5.5/raylib-5.5_linux_amd64.tar.gz
built build/linux/mygame
```

The program is named after the game. To build it and play it in a window, run:

```sh
superdevkit run
```

`run` without a target builds and starts the desktop program of this machine, `linux` (also
under WSL, whose window WSLg shows), and waits until you close it. The keyboard is the joypad:
the arrows, Z (B), X (A), A (Y), S (X), Q and W (L and R), Enter (Start). The arrows move the
ball, and the picture is the one the SNES shows, pixel for pixel.
[The desktop](/guide/the-desktop) has the rest: the retro TV filter, the debug view, recordings
and the check that plays a game without a window.

The game runs in the browser as well. `superdevkit build web` builds it with Emscripten's `emcc`
into `build/web/index.html`, and `superdevkit run web` serves the page on this machine and opens
it in the browser; [The web](/guide/the-web) describes it.

## Look at it in Mesen

Open `build/snes/mygame.sfc` in Mesen with `mygame.dbg` next to it. In the debugger's integration
settings, let Mesen load `.dbg` files automatically; it then shows `hello.c` in the debugger and
you can step through `game_tick` line by line in the C source. The debug file names the C sources
relative to itself, the kit's library in the home among them, so Mesen finds them as long as the
game and the home stay where they were when you built the ROM.

`superdevkit run snes` builds the ROM and starts Mesen with it, once the environment variable
`MESEN` holds the path of the Mesen program.

From WSL, the Windows Mesen works. Open the ROM through the `\\wsl.localhost\...` path of the
game's folder, so that Mesen also finds the debug file and the sources.

[Debugging in Mesen](/guide/debugging) has more on stepping through C code.

## When something goes wrong

**Not in the game's folder.** `superdevkit build` and `run` take the game in the current folder,
or in the folder named after the target (`superdevkit build snes ~/mygame`). Where there is no
`superdevkit.toml`, they stop:

```
error: no superdevkit.toml in ~ (a project of the kit has one: superdevkit init makes it)
```

**A header not found.** dampf's preprocessor sees dampf's own headers, the console API in
`<home>/lib/include` and the game's folders, never the headers of the PC: the game has to build
the same for the SNES and every other target. A header such as `<stdio.h>`, added to
`src/hello.c` after `<stdint.h>`, stops the build:

```
error: dampf: ~/mygame/src/hello.c:14:10: error: stdio.h: No such file or directory
```

The desktop build compiles the game without the PC's headers too, so `cc` reports the same.

**A construct outside the subset.** dampf accepts a [subset](/glossary#subset) of C, and it
rejects anything outside it with the file, line and column and a short reason. Add a variable of
type `float` to `src/hello.c`, after `ball_y`:

```c
static float ball_speed = 2.0f;
```

`superdevkit build snes` now stops with the first error:

```
error: dampf: ~/mygame/src/hello.c:30:1: error: 'float' is not supported: dampf has no floating point
```

`dampf check` runs only the [front end](/glossary#front-end) and lists every error of every file.
Give it the args file the build wrote, from `~/mygame`:

```sh
dampf check @build/snes/mygame.args
```

```
FAILS   ~/mygame/src/hello.c:30:1: error: 'float' is not supported: dampf has no floating point
ok      ~/mygame/build/gen/ball_gfx.c
ok      ~/mygame/build/gen/font_gfx.c
ok      <home>/lib/snes/main.c
ok      <home>/lib/snes/sfx.c
ok      <home>/lib/snes/snes.c
5 of 6 file(s) pass the front end
FAILS   <home>/lib/snes/main.c:44:5: error: function 'game_vblank' is called but never defined   (the 5 file(s) together)
```

`dampf check` checks every file alone, then the files that passed together, as one program. The
last line follows from the first: `hello.c` failed, so the files checked together have no
`game_vblank`. Fix the first error of a file first; errors that follow from it then go away.

[The C subset](/reference/c-subset) lists what dampf accepts,
[The superdevkit command](/reference/superdevkit) describes `init`, `build` and `run` with all
their options, and [Command line](/reference/command-line) describes `check` and dampf's other
commands.
