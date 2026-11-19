<!-- GENERATED from doc-specs/guide/getting-started.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Getting started

A superdevkit home is one folder that holds everything the kit has: dampf, the kit's library,
the tools, the examples and this book. A game lives in a folder of its own, anywhere on the
disk, and names the home it is built with.

## What you need

| Tool | Used for |
| --- | --- |
| JDK 25 or newer | building the home and running dampf (the code targets Java 25) |
| gcc | the preprocessor (`gcc -E`) |
| Python 3 | the converters in `tools/`, which turn a game's PNG files into C |
| a C compiler (`cc`) | the desktop build, with curl and tar to fetch raylib on its first run |
| [Mesen](/glossary#mesen) (MesenCE) | running the ROM and stepping through the C source |

dampf runs gcc only to preprocess your source: `#include` and `#define` work as on the PC, with
dampf's own `<stdint.h>`. dampf assembles and links the ROM with its own assembler and linker.
[ca65 and ld65](/glossary#ca65-and-ld65) from cc65 2.19 are needed only to develop dampf, since
its tests build every program with them as well and compare the ROMs; the README of the
repository says how to install them.

The desktop build links with raylib, OpenGL and X11, so the system needs their libraries.

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

This page writes `<home>` for the folder you copied. Put `<home>/bin` on your `PATH` if you like;
the page names the launcher in full, `<home>/bin/dampf`.

`./gradlew build` also runs the tests, which you need only when you work on the kit itself;
[Testing](/inside/testing) describes them.

## What is in the home

| Folder | What it holds |
| --- | --- |
| `bin/` | the launchers: `dampf`, the compiler, and `superdevkit`, the kit's command (with `.bat` files for Windows) |
| `java/` | the jars of the two programs |
| `include/` | dampf's own headers: `<stdint.h>`, `<string.h>` and `<dampf.h>` |
| `lib/` | the kit's library: the headers of the console API (`include/`), the SNES side (`snes/`), the desktop side (`rachael/`, `desktop/`), the splash screen; `C_STYLE.md` and `SNES_HARDWARE.md` |
| `tools/` | tools for game developers: `png2snes.py`, `brr_lab.html`, `sdevkit_rename.py` |
| `examples/` | the kit's examples to read and copy, most with a `README.md` and a `build.sh` |
| `dev/` | the desktop build (`build_desktop.sh`, `build_desktop_windows.sh`) and its helpers, and `replay.sh` |
| `docs/` | this book, as Markdown files that link to each other |

The commands of `superdevkit` (`init`, `build`, `run`) are not built yet, so a game is built with
its own `build.sh` for now; [the roadmap](/inside/roadmap) has them as milestone M7.

`lib/C_STYLE.md` says how to write a game's C for dampf, and `lib/SNES_HARDWARE.md` what the SNES
allows and which of it the kit has.

## A first game

The smallest example, `hello`, puts text on the screen and a ball that the
[joypad](/glossary#joypad) moves. Copy it to a folder of your own:

```sh
cp -r <home>/examples/hello ~/mygame
cd ~/mygame
```

In the home the example finds the kit two folders up, as `../..`. In your copy, two files have
to name the home instead: `build.sh` and the [args file](/glossary#args-file) `hello.args`.
Write the absolute path of your home where the files below show `<home>`. `build.sh` then
reads:

```sh [build.sh]
#!/bin/sh
# Builds hello for the SNES: converts the PNG files of assets/ into C (tools/png2snes.py), then
# compiles everything with dampf. Until the superdevkit command comes (roadmap M7), this script is
# the build.
#   examples/hello/build.sh            -> examples/hello/build/hello.sfc
#   CONVERT_ONLY=1 examples/hello/build.sh   only the assets into build/gen
set -e
cd "$(dirname "$0")"
KIT=<home>
GEN=build/gen
mkdir -p "$GEN"
python3 -I "$KIT/tools/png2snes.py" tiles assets/font_gfx.png font_gfx "$GEN"
python3 -I "$KIT/tools/png2snes.py" tiles assets/ball_gfx.png ball_gfx "$GEN"
[ "${CONVERT_ONLY:-0}" = 1 ] && exit 0      # the tests (ExamplesTest) and the desktop build convert only
# dampf: in a superdevkit home bin/dampf, in the kit's repository its installation
if [ -z "$DAMPF" ] && [ -x "$KIT/bin/dampf" ]; then DAMPF=$KIT/bin/dampf; fi
DAMPF=${DAMPF:-$KIT/build/install/superdevkit/bin/dampf}
[ -x "$DAMPF" ] || (cd "$KIT" && ./gradlew -q installDist)
"$DAMPF" build @hello.args -o build/hello.sfc "$@"
```

Only the line `KIT=<home>` changed. The script converts the two PNG files of `assets/` into C in
`build/gen`, then calls `<home>/bin/dampf` with the args file. `hello.args` holds the options
of that call, one per line, with paths relative to the file:

```text [hello.args]
# hello for the SNES: the game, its converted graphics (build/gen, written by build.sh) and the
# dev kit's library. Paths are relative to this file (ADR record 28).
-I <home>/lib/include
-I src
-I build/gen
--entry main
src/
build/gen/
<home>/lib/snes/
```

The two lines that named `../../lib` now name the home. A folder stands for the `.c` files in it,
so dampf compiles the game, its converted graphics and the kit's library for the SNES
(`lib/snes`) together, as one [whole program](/glossary#whole-program). Build the ROM:

```sh
./build.sh
```

The converters print nothing; dampf answers with one line naming the ROM and the debug file. It
prints full paths, shown here with `~`:

```
wrote ~/mygame/build/hello.sfc and ~/mygame/build/hello.dbg
```

The `main` that `--entry main` names as the [entry function](/glossary#entry-function) is not in
your code: it is the library's main loop in `lib/snes/main.c`. It calls three functions that
every superdevkit game defines, and `src/hello.c` is built around them:

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

`~/mygame/build/` now holds, next to `hello.sfc`:

- **`hello.s`**: the generated 65816 assembly, with the C source line and a comment for each
  instruction.
- **`hello.dbg`**: the debug file, in ld65's format, which lets Mesen show the C source while
  you step.
- **`hello.lbl`**: a label file.
- **`hello.timing.txt`**: the best and worst case of each function in CPU cycles, from entry to
  return.
- **`dampf_runtime.s`**: the assembly of the [runtime](/glossary#runtime), the start-up code
  dampf links into every ROM.
- **`gen/`**: the graphics as C, `font_gfx.c` and `ball_gfx.c` with their headers, written by
  `png2snes.py`.

[The generated files](/reference/output-files) describes them all.

## On the desktop

The same game also runs in a window on the PC, on [Rachael](/glossary#rachael), the SNES rebuilt
in portable C. Build it with the script in the home, naming the game's folder by an absolute
path (the script changes to the home before it starts):

```sh
<home>/dev/build_desktop.sh ~/mygame
```

The script runs the game's `build.sh` with `CONVERT_ONLY=1`, which converts the graphics and
stops before dampf. It then compiles the game's C with the PC's C compiler, checks that the game
calls nothing but the console API, and links it with Rachael, the desktop's frontend and raylib.
On its first run it fetches raylib into `<home>/build/raylib`:

```
raylib 5.5 for linux in build/raylib/linux
built ~/mygame/build/linux/mygame
```

The program is named after the game's folder. Start it to play in a window:

```sh
~/mygame/build/linux/mygame
```

The keyboard is the joypad: the arrows, Z (B), X (A), A (Y), S (X), Q and W (L and R), Enter
(Start). The arrows move the ball, and the picture is the one the SNES shows, pixel for pixel.
[The desktop](/guide/the-desktop) has the rest: the retro TV filter, recordings and the check
that plays a game without a window.

## Look at it in Mesen

Open `hello.sfc` in Mesen with `hello.dbg` next to it. In the debugger's integration settings,
let Mesen load `.dbg` files automatically; it then shows `hello.c` in the debugger and you can
step through `game_tick` line by line in the C source. The debug file names the C sources
relative to itself, the kit's library in the home among them, so Mesen finds them as long as the
game and the home stay where they were when you built the ROM.

From WSL, the Windows Mesen works. Open the ROM through the `\\wsl.localhost\...` path of the
game's folder, so that Mesen also finds the debug file and the sources.

[Debugging in Mesen](/guide/debugging) has more on stepping through C code.

## When something goes wrong

**gcc not found.** Every command that reads C runs gcc as the preprocessor first, so without
gcc nothing compiles. dampf names each source file it could not preprocess and stops:

```
src/hello.c:1:1: error: cannot run gcc, the preprocessor dampf needs: is it installed and on the PATH?
build/gen/ball_gfx.c:1:1: error: cannot run gcc, the preprocessor dampf needs: is it installed and on the PATH?
build/gen/font_gfx.c:1:1: error: cannot run gcc, the preprocessor dampf needs: is it installed and on the PATH?
<home>/lib/snes/main.c:1:1: error: cannot run gcc, the preprocessor dampf needs: is it installed and on the PATH?
<home>/lib/snes/snes.c:1:1: error: cannot run gcc, the preprocessor dampf needs: is it installed and on the PATH?
```

**A wrong path to the home in the args file.** When `hello.args` names a home that is not
there, because the home was moved or the path is mistyped, dampf checks every file and folder the
args file names as a source before it runs gcc, and stops at the first that does not exist. Here
the home was moved away from `<home>`:

```
dampf: no such file or folder: <home>/lib/snes
```

Correct both lines that name the home. dampf does not check the folders of `-I` lines, so when
only `-I <home>/lib/include` is wrong, gcc reports the header it cannot find instead, such as
`src/hello.c:14:10: error: sdevkit/loop.h: No such file or directory`. When `build.sh` names the
wrong home, `python3` finds no `png2snes.py` and the build stops there.

**A construct outside the subset.** dampf accepts a [subset](/glossary#subset) of C, and it
rejects anything outside it with the file, line and column and a short reason. `dampf check`
runs only the [front end](/glossary#front-end) and is the quickest way to find such places. Add
a variable of type `float` to `src/hello.c`, after `ball_y`:

```c
static float ball_speed = 2.0f;
```

`./build.sh` now stops with the first error. Check every file of the game, from `~/mygame`:

```sh
<home>/bin/dampf check @hello.args
```

```
FAILS   src/hello.c:30:1: error: 'float' is not supported: dampf has no floating point
ok      build/gen/ball_gfx.c
ok      build/gen/font_gfx.c
ok      <home>/lib/snes/main.c
ok      <home>/lib/snes/snes.c
4 of 5 file(s) pass the front end
FAILS   <home>/lib/snes/main.c:42:5: error: function 'game_vblank' is called but never defined   (the 4 file(s) together)
```

`dampf check` checks every file alone, then the files that passed together, as one program. The
last line follows from the first: `hello.c` failed, so the files checked together have no
`game_vblank`. Fix the first error of a file first; errors that follow from it then go away.

[The C subset](/reference/c-subset) lists what dampf accepts, and
[Command line](/reference/command-line) describes `check` and the other commands.
