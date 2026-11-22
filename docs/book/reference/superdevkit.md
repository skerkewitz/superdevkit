<!-- GENERATED from doc-specs/reference/superdevkit.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# The superdevkit command

Run `superdevkit` without arguments, or with a command it does not know, and it prints its usage
on the standard error stream and exits with status 2:

```
usage: superdevkit init NAME [--from EXAMPLE]   a new project in ./NAME, from an example (hello)
       superdevkit build TARGET [FOLDER]         snes, linux, win64 or web; the project in FOLDER (.)
       superdevkit run [TARGET] [FOLDER]         builds and starts it (the desktop; snes in Mesen;
                                                 web in the browser, served from this machine)
           --verbose  every step   --plan  the steps only   --dry-run  checks only   --debug  -O0 (web: and F1's view)
       superdevkit png tiles|tiles2|palette|mode7 picture.png NAME out_dir [FIRST]
       superdevkit sfx assets/sfx/sfx.json NAME out_dir
       superdevkit --version
```

Use `superdevkit --version`, or `superdevkit version`, to see which kit you have. It prints one
line on the standard output and exits with status 0:

```
superdevkit 0.8.0
```

The command is the one in `bin/` of the [superdevkit home](/glossary#superdevkit-home), next to
`bin/dampf`. It runs the converters and dampf in its own Java process and calls a C compiler
only for the desktop and the web. The compiler's own command, `dampf`, and its options are on
[Command line](/reference/command-line).

## init

Use `init` to start a new game. It copies one of the kit's examples into a new folder `NAME` in
the current folder:

```sh
superdevkit init mygame                  # a copy of examples/hello
superdevkit init mygame --from catchme   # a copy of examples/catchme
```

`NAME` has letters, digits, `_` and `-` only. Without `--from` the example is `hello`; with it,
any folder of the home's `examples/` that has a `superdevkit.toml`. The copy leaves out the
example's `build/` folder and changes two things:

- **The project file.** `name` in `superdevkit.toml` becomes `NAME`, and the examples' `kit`
  line (the path to the kit's repository, which the examples are part of) becomes
  `superdevkit = "0.8"`, the version of the kit that made the copy.
- **`CLAUDE.md`.** A new file that tells an AI working on the game to read the kit's guides
  first, `lib/C_STYLE.md` (C for dampf) and `lib/SNES_HARDWARE.md` (the hardware and what the
  kit has of it), with their full paths in the home.

It then prints the new folder's full path and the next step:

```
superdevkit init: <folder>/mygame, from the example hello
  next: cd mygame && superdevkit run
```

With `--plan` or `--dry-run`, `init` prints what it would copy and change and writes nothing.
It stops with status 1 when the folder is there already, when the example does not exist, and
when `superdevkit` was not started through the home's launcher and so does not know the home.

## build

Use `build` to build the game in a folder for one [target](/glossary#target):

```sh
superdevkit build snes                    # the game in the current folder
superdevkit build linux examples/ertuep   # the game in examples/ertuep
```

The folder defaults to the current one and must hold a `superdevkit.toml`, the
[project file](/glossary#project-file). Every build first converts the pictures and sounds the
project file lists into `build/gen/` (with the same converters as [png](#png) and [sfx](#sfx)),
and writes `build/compile_commands.json` last: each of the game's C files with the options
dampf's preprocessor gets, for editors and their code completion. A build that succeeds prints
what it made:

```
built build/snes/mygame.sfc
```

The targets differ in the compiler and in what they need the first time:

| Target | Builds with | Fetched the first time | Writes |
|---|---|---|---|
| `snes` | dampf, in the same Java | nothing | `build/snes/NAME.sfc` |
| `linux` | the system's C compiler (`CC`, default `cc`) | raylib 5.5 for Linux | `build/linux/NAME` |
| `win64` | MSVC (Visual Studio's C++ tools for x64) | raylib 5.5 for MSVC | `build/win64/NAME.exe` |
| `web` | Emscripten's `emcc` (`EMCC`) | raylib 5.5 for WebAssembly | `build/web/index.html` |

raylib draws the window and plays the sound of the desktop and web builds. The command fetches
the ready-built package from raylib's releases on GitHub and unpacks it into the home's
`raylib/` folder, once per system; after that a build needs no network.

### snes

The game's C files, the files in `build/gen/` and the kit's `lib/snes/` are compiled by dampf
as one [whole program](/glossary#whole-program), with `--entry main`, the
[code generator from MIR](/glossary#code-generator-from-mir), `-I` for the kit's `lib/include`,
the game's include folders and `build/gen`, and `--sram` when the project file asks for save
RAM. Next to the ROM dampf writes its other files (the assembly, the debug file for
[Mesen](/glossary#mesen), the label file, the timing report), which
[The generated files](/reference/output-files) describes.

Every SNES build also writes `build/snes/NAME.args`, an [args file](/glossary#args-file) of what
it compiled: the include folders, `--entry`, `--sram` and every C file, one per line, with
absolute paths. Use it to call dampf on the game with options of your own:

```sh
dampf check @build/snes/mygame.args
dampf build @build/snes/mygame.args -o out.sfc --backend naive
```

### linux

The desktop program is built on [Rachael](/glossary#rachael) in three steps. Each of the game's
C files is compiled alone, as on the SNES, without the host's headers: `-nostdinc
-ffreestanding`, dampf's `include/` and the kit's `lib/include`, with `-std=c99 -O2 -g -Wall
-Wextra`. A check then reads the game's object files and stops the build when one of them calls
a function that is not part of the kit's console API (`lib/include/sdevkit`):

```
error: check: src_game.o: uses printf, which is not part of superdevkit's console API (lib/include/sdevkit, ADR-6F3CK2AR)
```

Last, the objects are linked with `lib/rachael`, the desktop's frontend `lib/desktop` and raylib
into `build/linux/NAME`. The objects go to `build/linux/obj/`, which each build empties first,
and the project's music to `build/linux/music/`, next to the program. The target builds on
Linux and under WSL, not on Windows.

### win64

The same three steps with Microsoft's C compiler `cl` (`/std:c11 /O2 /Z7 /W3`), into
`build/win64/NAME.exe`. The command finds Visual Studio with its `vswhere` and runs `cl` in a
batch file after `vcvars64.bat`, which sets up its environment. The target builds on Windows
and from WSL; elsewhere it stops with

```
error: the target win64 builds on Windows or WSL, with MSVC (ADR-6F60Z709)
```

### web

The same three steps with `emcc`, with WebAssembly objects, and a link that adds the web's part
of the frontend, `lib/web`. The page is `build/web/index.html`, with `index.js` and
`index.wasm` beside it, and `index.data` holding the music when the project has any. A browser
loads the page only from a web server, not from a file: [run](#run) serves it.
[The web](/guide/the-web) has the rest.

### Options

The options may stand anywhere after the command. An option the command does not know stops it
with `error: unknown option --name`, the usage and status 2.

- **`--verbose`** prints a line before the steps, `superdevkit build snes: mygame (kit <home>)`,
  and each step as it runs.
- **`--plan`** prints the steps without running them, then checks like `--dry-run`.
- **`--dry-run`** runs nothing and checks that every file the steps need is there: the sources,
  the pictures, `sfx.json` and the music. Each missing one is a line
  `missing: <path> (for <step>)`. It ends with `everything the build needs is there` and
  status 0, or with the number missing and status 1. It does not check that a C compiler is
  installed.
- **`--debug`** builds without optimization: `-O0` instead of `-O2` for `linux` and `web`,
  `/Od` instead of `/O2` for `win64`. For `web` it also adds `-g` and defines
  `SDEVKIT_WEB_DEBUG`, for the kit and the game's own files: only such a page has the debug view
  (F1) and its keys F5 to F8. The desktop has the debug view in every build. For `snes` the
  option changes nothing.

This is the plan of a game made with `superdevkit init mygame`, run in its folder; `<home>`
stands for the path of the superdevkit home:

```sh
superdevkit build snes --plan
```

```
superdevkit build snes: mygame (kit <home>)
  png    tiles assets/font_gfx.png font_gfx build/gen
  png    tiles assets/ball_gfx.png ball_gfx build/gen
  dampf  build -I <home>/lib/include -I src -I build/gen --entry main -o build/snes/mygame.sfc src build/gen/ <home>/lib/snes/
  write  build/compile_commands.json
everything the build needs is there
```

The first column names the tool of each step. A plan for `linux` or `web` shows every call of
the C compiler in full; one for `win64` shows the calls of `cl` in short.

## run

Use `run` to build the game and start it:

```sh
superdevkit run                  # the desktop program of this machine
superdevkit run snes             # the ROM in Mesen
superdevkit run web              # the page in the browser
superdevkit run examples/hello   # the desktop program of the game in examples/hello
```

Without a target it builds for the machine's own desktop: `win64` on Windows, `linux` on Linux
and under WSL. A single word that is not a target is taken as the folder. `run` takes the same
options as `build`; `--plan` shows the step that starts the game as the last one.

- **`linux` and `win64`** start the program in the game's folder and wait until it ends. A
  program that ends with a status other than 0 makes `run` fail:
  `error: start: mygame ended with status 3`. [The desktop](/guide/the-desktop) describes the
  window, its keys and its options.
- **`snes`** opens the ROM in [Mesen](/glossary#mesen): the program `MESEN` names, else
  `.mesen/Mesen` or `.mesen/Mesen.exe` in the kit's folder. Without one it stops with
  `error: start: no Mesen at <path> (MESEN=path, or dev/fetch_mesen.sh in the kit)`.
- **`web`** serves `build/web` on this machine, at `http://127.0.0.1:8080/` or, when that port
  is taken, the next free one, and opens the page in the browser. It prints the address and
  serves until you press Ctrl+C. Nothing is cached, so after a new build a reload of the page
  shows it.

## png

Use `png` to convert one PNG picture into SNES graphics data as C, without a build. In a game
made from the example Schach (`superdevkit init chess --from schach`):

```sh
superdevkit png tiles assets/board_gfx.png board_gfx out
```

It writes `NAME.c` and `NAME.h` into `out_dir`, which it creates if needed; `NAME` must be a C
name. A build runs the same conversion for every picture of the project file's
[`[graphics]`](#graphics) into `build/gen/`. The kinds:

| Kind | Takes | Writes |
|---|---|---|
| `tiles` | at most 16 colours, cut into 8 × 8 tiles | `NAME_tiles` (4 bits a pixel), `NAME_pal` (16 colours) |
| `tiles2` | at most 4 colours | `NAME_tiles` (2 bits a pixel), `NAME_pal` (4 colours) |
| `palette` | the first row of pixels, at most 16 colours | `NAME_pal` only |
| `mode7` | an indexed PNG 1024 wide, up to 1024 high | `NAME_map`, `NAME_tiles`, `NAME_pal` |

The header also defines the sizes and counts, with the name in capitals:
`BOARD_GFX_TILE_COUNT`, `BOARD_GFX_PAL_SIZE` and so on. Only `mode7` takes `FIRST`, the first
[CGRAM](/glossary#cgram) colour its palette goes to (1 to 255, default 1). A kind it does not
know, or `FIRST` for another kind, is a usage error with status 2; a picture it cannot convert
is an error with status 1.
[Graphics and sound](/guide/graphics-and-sound) shows how a game uses the data.

## sfx

Use `sfx` to convert a game's sound effects, as the BRR Lab saves them in `sfx.json`, into C.
In the same game:

```sh
superdevkit sfx assets/sfx/sfx.json sfx out
```

It writes `NAME.c` (the samples) and `NAME.h` (`SFX_` and the id in capitals for each effect,
`SFX_COUNT`, and the bank `NAME_bank` for `sdevkit_sfx_init`) into `out_dir`. `NAME` is a C name
in small letters. It prints a line for each effect and how much of the audio RAM the samples
take:

```
superdevkit sfx: out/sfx.c and .h
  put          put.wav             585 bytes  16.00 kHz    5 frames
  take         take.wav           1080 bytes  16.00 kHz    9 frames
  your_move    your_move.wav      3123 bytes  16.00 kHz   22 frames
  check        check.wav          2070 bytes  16.00 kHz   15 frames
  mate         mate.wav           6948 bytes  16.00 kHz   48 frames
  13806 bytes of 64448 in the audio RAM (21.4 %)
```

A mistake in the file, an effect with two active samples, a sound file it cannot read, or
samples that do not fit into the audio RAM stop it with status 1, and each message names
`sfx.json` and, where there is one, the line. A build runs the same conversion with the name
`sfx` for the project file's [`[sound]`](#sound) `sfx` into `build/gen/`.
[Graphics and sound](/guide/graphics-and-sound) describes the effects and the BRR Lab.

## The project file

A game is a folder with a `superdevkit.toml`. This is CatchMe's, one of the examples:

```toml [examples/catchme/superdevkit.toml]
# CatchMe's project file (superdevkit.toml, ADR-6F3CJZBY): superdevkit build snes makes the ROM,
# superdevkit run plays it on the desktop.
name = "catchme"
kit = "../.."                 # the kit's repository, which this example is part of
sram = 8                      # the high score (sdevkit_sram)
sources = ["src"]

[graphics]
tiles = ["assets/maze_gfx.png", "assets/sprites_gfx.png"]
palette = [
    "assets/pal_ginger.png", "assets/pal_black.png", "assets/pal_white.png",
    "assets/pal_brown.png", "assets/pal_scared.png", "assets/pal_hot.png",
]

[sound]
music = ["assets/music/chase.wav"]   # MUSIC_CHASE (dev/art/music.py made it)
```

The kit reads the file with a TOML reader of its own, which takes the part of TOML such a file
needs: `#` comments, `key = value` with bare keys, `[table]` headers one level deep, strings in
double or single quotes, whole numbers (with a sign and `_`), `true` and `false`, arrays (over
several lines, with a comma after the last value allowed) and inline tables `{ key = value }`
on one line. Anything else, and a key given twice, stops the build with the line:

```
error: superdevkit.toml:4: the key 'name' is there twice
```

A key the kit does not know is an error too, at the top as in a table, and names the keys it
knows:

```
error: superdevkit.toml: unknown key 'colour' (known: graphics, include, kit, name, sound, sources, sram, superdevkit)
```

Every path in the file is relative to the folder of `superdevkit.toml`.

### name

A string, required: the program's name, of letters, digits, `_` and `-`. It names what the build
writes: `build/snes/catchme.sfc`, `build/linux/catchme`, `build/win64/catchme.exe`.

### superdevkit

A string: the version of the kit the game needs, of two or three numbers. Two numbers
(`"0.8"`) accept every kit of that version, `0.8.0` and `0.8.1` alike; three (`"0.8.0"`) only
that one. The command checks it against its own version and stops when they differ:

```
error: the project needs superdevkit 0.7, this is 0.8.0 (<home>); install that version, or name a kit folder in superdevkit.toml: kit = "path"
```

Either `superdevkit` or `kit` is required.

### kit

A string: the path of a kit's folder to build with, instead of the home the command comes from.
The examples name the kit's repository this way, which lets you work on the kit and a game at
once. With `kit`, `superdevkit` is not checked. The folder must have the kit's
`lib/include/sdevkit/snes.h`.

### sram

A whole number from 0 to 32768, default 0: the bytes of battery-backed save RAM the game keeps
through `sdevkit_sram`. On the SNES it becomes dampf's `--sram`; on the desktop and the web the
compiler gets `SDEVKIT_SRAM_SIZE` defined.

### sources

An array of strings, required, with at least one entry: the game's C files and folders, for
every target. A folder stands for the `.c` files directly in it, in the order of their names, as
for dampf ([Command line](/reference/command-line)). A source that does not exist stops the
build.

### include

An array of strings: the folders of the game's headers. Without it the include folders are the
sources' folders: a folder of `sources` itself, and for a `.c` file the folder it is in. The
kit's `lib/include` comes before them, and `build/gen` after them when the project converts
pictures or sounds.

### [graphics]

A table of the pictures the build converts with [png](#png), one array for each kind: `tiles`,
`tiles2`, `palette` and `mode7`. An entry is the path of a PNG file, or an inline table:

| Key | Meaning |
|---|---|
| `png` | the PNG file, required |
| `name` | the C name of the data; default the file name without `.png` |
| `first` | `mode7` only: the first CGRAM colour, 1 to 255, default 1 |

```toml
[graphics]
tiles = ["assets/maze_gfx.png", { png = "assets/boss.png", name = "boss_gfx" }]
mode7 = [{ png = "assets/festzelt.png", first = 33 }]
```

Each picture becomes `build/gen/NAME.c` and `NAME.h`, converted in the order `tiles`, `tiles2`,
`palette`, `mode7`. A file name that is no C name needs a `name`, and two pictures with the same
name are an error, as is a picture called `sfx` when the project has sound effects.

### [sound]

A table with two keys, both optional:

- **`sfx`** is the path of the game's `sfx.json`. The build converts it with [sfx](#sfx) into
  `build/gen/sfx.c` and `sfx.h`.
- **`music`** is an array of the music's tracks. An entry is the path of a file, or an inline
  table `{ file = "...", name = "..." }`; the name defaults to the file name without its
  extension and must be a C name. The build writes `build/gen/music.h`, with `MUSIC_` and the
  name in capitals for each track, numbered from 0, and `MUSIC_COUNT`. The desktop and web
  builds take the files along: next to the program in `build/<target>/music/`, on the web in
  `index.data`. So two tracks may not have the same file name, even in different folders, nor
  the same name; a game has at most 255 tracks. On the SNES the music calls do nothing yet
  ([Limits](/reference/limits)).

## Environment

| Variable | Read by | Default |
|---|---|---|
| `CC` | `build linux`: the C compiler | `cc` |
| `EMCC` | `build web`: Emscripten's compiler | `emcc`, found on the `PATH` |
| `MESEN` | `run snes`: the Mesen program | `.mesen/Mesen`, then `.mesen/Mesen.exe` in the kit's folder |
| `BROWSER` | `run web`: a command that takes the page's address | the system's default browser |

The default browser is opened with `xdg-open` on Linux, `open` on macOS, and Windows' `start`
on Windows and from WSL. Set `BROWSER=true` to serve the page without opening a browser. The
`win64` target reads no variable: it finds Visual Studio in its standard folder.

## Exit status

| Status | When |
|---|---|
| 0 | the command did its work; `--plan` and `--dry-run` found every file there; `--version` |
| 1 | an error: in the project file, a version or kit that does not fit, a step of the build that failed, a file missing under `--plan` or `--dry-run`, `init` that cannot copy, a picture or sound file that cannot be converted, a program started by `run` that ended with a status other than 0 |
| 2 | a usage error: no command, a command or option it does not know, too many or too few arguments, a target it does not know, a `png` kind it does not know |

Errors go to the standard error stream and start with `error:`; a failed build step names its
tool after it, as in `error: check: ...`.
