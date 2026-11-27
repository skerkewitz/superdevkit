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
       superdevkit gen [FOLDER]                  only the conversions, into build/gen
       superdevkit png tiles|tiles2|tiles8|palette|mode7 picture.png NAME out_dir [FIRST]
       superdevkit sfx assets/sfx/sfx.json NAME out_dir
       superdevkit --version
```

Use `superdevkit --version`, or `superdevkit version`, to see which kit you have. It prints one
line on the standard output and exits with status 0:

```
superdevkit 0.10.0
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

The name may have letters, digits, `_` and `-` only. Without `--from` the example is `hello`;
with it, any folder of the home's `examples/` that has a `superdevkit.toml`. The copy leaves out
the example's `build/` folder and changes two things:

- **The project file.** `name` in `superdevkit.toml` becomes `NAME`, and the examples' `kit`
  line (the path to the kit's folder, which the examples are part of) becomes
  `superdevkit = "0.10"`, the version of the kit that made the copy.
- **`CLAUDE.md`.** A new file that tells an AI working on the game to read the kit's guides
  first, `lib/C_STYLE.md` (C for dampf) and `lib/SNES_HARDWARE.md` (the hardware and what the
  kit has of it), with their full paths in the home.

It then prints the new folder's full path and the next step:

```
superdevkit init: <folder>/mygame, from the example hello
  next: cd mygame && superdevkit run
```

With `--plan` or `--dry-run`, `init` prints what it would copy and change and writes nothing.
It stops with status 1 when the name has other characters, when the folder is there already,
when the example does not exist, and when `superdevkit` was not started through the home's
launcher and so does not know the home:

```
error: a project's name has letters, digits, _ and - only: my game
```

## build

Use `build` to build the game in a folder for one [target](/glossary#target):

```sh
superdevkit build snes            # the game in the current folder
superdevkit build linux ../chess  # the game in the folder ../chess
```

The folder defaults to the current one and must hold a `superdevkit.toml`, the
[project file](/glossary#project-file). Every build first runs the commands the project file
names under [`[build] before`](#build-before), then converts the pictures and sounds it lists
into `build/gen/` (with the same converters as [png](#png) and [sfx](#sfx)), and writes
`build/compile_commands.json` last: each of the game's C files with the options dampf's
preprocessor gets, for editors and their code completion. A build that succeeds prints what it
made:

```
built build/snes/mygame.sfc
```

The targets differ in the compiler and in what they fetch when it is not there:

| Target | Builds with | Fetched when missing | Writes |
|---|---|---|---|
| `snes` | dampf, in the same Java | nothing | `build/snes/NAME.sfc` |
| `linux` | the system's C compiler (`CC`, default `cc`) | raylib 5.5 for Linux | `build/linux/NAME` |
| `win64` | MSVC (Visual Studio's C++ tools for x64) | raylib 5.5 for MSVC | `build/win64/NAME.exe` |
| `web` | [Emscripten](/glossary#emscripten)'s `emcc` (`EMCC`) | raylib 5.5 for WebAssembly | `build/web/index.html` |

raylib draws the window and plays the sound of the desktop and web builds. A release zip holds
it for its own system and the web, in `raylib/<system>/`. When it is not there, as for `win64`
from WSL with the Linux zip, the command fetches the ready-built package from raylib's releases
on GitHub and unpacks it into `raylib/<system>/` of the kit's folder (`build/raylib/<system>/`
in a checkout of the repository), once for each of `linux`, `windows` and `web`; after that a
build needs no network.

### snes

The game's C files, the files in `build/gen/` and the kit's `lib/snes/` are compiled by dampf
as one [whole program](/glossary#whole-program), with `--entry main`, the
[code generator from MIR](/glossary#code-generator-from-mir), `-I` for the kit's `lib/include`,
the game's include folders and `build/gen`, `-D` for the macros of the project file's
[`[snes]`](#snes-desktop-and-web) table, and `--sram` when the project file asks for save RAM.
The kit's `lib/snes/` goes to dampf with [`--lib`](/reference/command-line#lib), which keeps the
macros of `[snes]` out of the kit's code. Next to the ROM dampf writes its other files (the
assembly, the debug file for [Mesen](/glossary#mesen), the label file, the timing report), which
[The generated files](/reference/output-files) describes.

Every SNES build also writes `build/snes/NAME.args`, an [args file](/glossary#args-file) of what
it compiled: the include folders, the macros, `--entry`, `--sram` and every C file, one per
line, with absolute paths and `--lib` in front of each of the kit's files. Use it to call dampf
on the game with options of your own:

```sh
dampf check @build/snes/mygame.args
dampf build @build/snes/mygame.args -o out.sfc --backend naive
```

### linux

The desktop program is built on [Rachael](/glossary#rachael) in three steps. Each of the game's
C files is compiled alone, as on the SNES, without the host's headers:
`-nostdinc -ffreestanding`, dampf's `include/` and the kit's `lib/include`, with
`-std=c99 -O2 -g -Wall -Wextra` and the macros of the project file's
[`[desktop]`](#snes-desktop-and-web) table. A check then reads the game's object files and stops
the build when one of them calls a function that is not part of the kit's
[console API](/glossary#console-api) (`lib/include/sdevkit`):

```
error: check: src_game.o: uses printf, which is not part of superdevkit's console API (lib/include/sdevkit, ADR-6F3CK2AR)
```

The check also writes `game_interrupts.c` beside the objects, which tells the frontend whether
the game defines `game_irq`, its handler of the interrupt at a picture row, and
`sdevkit_publish_nmi`, the handler of the [vertical blank](/glossary#vertical-blank)'s
interrupt that `SDEVKIT_PUBLISH` defines. C has no optional symbol that gcc, MSVC and `emcc` all
take, so the build looks for them in the objects. Last, the objects and that file are linked
with `lib/rachael`, the desktop's frontend `lib/desktop` and raylib into `build/linux/NAME`. The
objects go to `build/linux/obj/`, which each build empties first, and the project's music to
`build/linux/music/`, next to the program. The target builds on Linux and under
[WSL](/glossary#wsl), not on Windows.

### win64

The same three steps with Microsoft's C compiler `cl` (`/std:c11 /O2 /Z7 /W3`), into
`build/win64/NAME.exe`. The command finds Visual Studio with its `vswhere` and runs `cl` in a
batch file after `vcvars64.bat`, which sets up its environment. The target builds on Windows
and from WSL; elsewhere it stops with

```
error: the target win64 builds on Windows or WSL, with MSVC (ADR-6F60Z709)
```

A machine without Visual Studio stops the build with the first message below, and one whose
Visual Studio lacks the C++ tools for x64 with the second; `<vswhere>` stands for the path of
`vswhere.exe` in Visual Studio's installer folder:

```
error: vswhere: no Visual Studio (<vswhere>); win64 builds with MSVC's C++ tools for x64. Install Visual Studio with the workload "Desktop development with C++", or build for the SNES, which needs nothing else: superdevkit build snes
error: vswhere: no Visual Studio with the C++ tools for x64 (vswhere found none). Install Visual Studio with the workload "Desktop development with C++", or build for the SNES, which needs nothing else: superdevkit build snes
```

### web

The same three steps with `emcc`, with WebAssembly objects and the macros of the
[`[web]`](#snes-desktop-and-web) table, and a link that adds the web's part of the frontend,
`lib/web`. The page is `build/web/index.html`, with `index.js` and
`index.wasm` beside it, and `index.data` holding the music when the project has any. A browser
loads the page only from a web server, not from a file: [run](#run) serves it.
[The web](/guide/the-web) has the rest.

Without `emcc` the build stops and says what to install; `<reason>` is the system's own error:

```
error: emcc: no Emscripten (emcc): the target web builds with emcc; install the Emscripten SDK and put emcc on the PATH, or name it with EMCC=path. The SNES needs nothing else: superdevkit build snes (<reason>)
```

### Options

The options may stand anywhere after the command. An option the command does not know stops it
with `error: unknown option --name`, the usage and status 2.

- **`--verbose`** prints a line before the steps, `superdevkit build snes: mygame (kit <home>)`,
  and each step as it runs.
- **`--plan`** prints the steps without running them, then checks like `--dry-run`.
- **`--dry-run`** runs nothing and checks that every file the steps need is there: the sources,
  the pictures, `sfx.json`, the music and, for `web`, the kit's page `lib/web/shell.html`. Each
  missing one is a line `missing: <path> (for <step>)` on the standard error stream. It ends
  with `everything the build needs is there` and status 0, or with the number missing and
  status 1. It does not check that a C compiler is installed. The commands of
  [`[build] before`](#build-before) do not run, and since they may write what the build needs,
  a project that has them gets no `missing:` line at all.
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
  dampf  build -I <home>/lib/include -I src -I build/gen --entry main -o build/snes/mygame.sfc src build/gen/ --lib <home>/lib/snes/
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
superdevkit run ../chess         # the desktop program of the game in ../chess
```

Without a target it builds for the machine's own desktop: `win64` on Windows, `linux` on Linux
and under WSL, and `linux` on every other system too. macOS is not a target of the kit
([Limits](/reference/limits)). A single word that is not a target is taken as the folder. `run`
takes the same options as `build`; `--plan` shows the step that starts the game as the last
one.

- **`linux` and `win64`** start the program in the game's folder and wait until it ends. A
  program that ends with a status other than 0 makes `run` fail:
  `error: start: mygame ended with status 3`. [The desktop](/guide/the-desktop) describes the
  window, its keys and its options.
- **`snes`** opens the ROM in [Mesen](/glossary#mesen): the program `MESEN` names, else
  `.mesen/Mesen` or `.mesen/Mesen.exe` in the kit's folder. Without one it stops with

  ```
  error: start: no Mesen at <path> (install MesenCE and name it with MESEN=path; in the kit's repository, dev/fetch_mesen.sh)
  ```

- **`web`** serves `build/web` on this machine, at `http://127.0.0.1:8080/` or, when that port
  is taken, the next free one, and opens the page in the browser. It prints the address and
  serves until you press Ctrl+C. Nothing is cached, so after a new build a reload of the page
  shows it.

## gen

Use `gen` when a game has a build system of its own and needs only what the kit generates. It
runs the commands of [`[build] before`](#build-before) and the conversions of a build, and
compiles nothing:

```sh
superdevkit gen            # the game in the current folder
superdevkit gen ../chess   # the game in the folder ../chess
```

The files in `build/gen/` are the ones a build writes there. A project with music also gets its
tracks in `build/gen/music/`, with `music_files.c`, the list of their paths for the desktop's
frontend. The list has a folder of its own because a build compiles the `.c` files directly in
`build/gen/` and must not take it.

It ends with `generated build/gen`, or with `nothing to generate` for a project without pictures
and sounds. The options `--verbose`, `--plan` and `--dry-run` work as for [build](#options);
`--debug` changes nothing.

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
| `tiles8` | at most 256 colours | `NAME_tiles` (8 bits a pixel), `NAME_pal` (256 colours) |
| `palette` | the first row of pixels, at most 16 colours | `NAME_pal` only |
| `mode7` | an indexed PNG 1024 wide, up to 1024 high | `NAME_map`, `NAME_tiles`, `NAME_pal` |

The header also defines the sizes and counts, with the name in capitals:
`BOARD_GFX_TILE_COUNT`, `BOARD_GFX_PAL_SIZE` and so on. Only `mode7` takes `FIRST`, the first
[CGRAM](/glossary#cgram) colour its palette goes to (1 to 255, default 1). A kind it does not
know, or `FIRST` for another kind, is a usage error with status 2; a picture it cannot convert
is an error with status 1.
[Graphics and sound](/guide/graphics-and-sound) shows how a game uses the data.

## sfx

Use `sfx` to convert a game's sound effects into C, as the BRR Lab saves them in `sfx.json`.
The BRR Lab is the kit's editor of sound effects, `tools/brr_lab.html` in the home. In the same
game:

```sh
superdevkit sfx assets/sfx/sfx.json sfx out
```

It writes `NAME.c` (the samples) and `NAME.h` (`SFX_` and the id in capitals for each effect,
`SFX_COUNT`, and the bank `NAME_bank` for `sdevkit_sfx_init`) into `out_dir`. `NAME` is a C name
in small letters. It prints a line for each effect and how much of the audio RAM the samples
take; here with the lines of four of Schach's five effects left out:

```
superdevkit sfx: out/sfx.c and .h
  put          put.wav             585 bytes  16.00 kHz    5 frames
  ...
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
on one line. Anything else, and a key given twice, stops the build with the number of the line,
here for a second `name` in line 6:

```
error: superdevkit.toml:6: the key 'name' is there twice
```

A key the kit does not know is an error too, at the top as in a table, and names the keys it
knows:

```
error: superdevkit.toml: unknown key 'colour' (known: build, desktop, graphics, include, kit, name, snes, sound, sources, sram, superdevkit, web)
```

Every path in the file is relative to the folder of `superdevkit.toml`. A copy made with
[init](#init) has `superdevkit = "0.10"` in place of the `kit` line.

### name

A string, required: the program's name, of letters, digits, `_` and `-`. It names what the build
writes: `build/snes/catchme.sfc`, `build/linux/catchme`, `build/win64/catchme.exe`.

### superdevkit

A string: the version of the kit the game needs, of two or three numbers. Two numbers
(`"0.9"`) accept every kit of that version, `0.9.0` and `0.9.1` alike; three (`"0.9.0"`) only
that one. The command checks it against its own version and stops when they differ:

```
error: the project needs superdevkit 0.8, this is 0.10.0 (<home>); install that version, or name a kit folder in superdevkit.toml: kit = "path"
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

A table of the pictures the build converts with [png](#png), one array for each kind:
`tiles`, `tiles2`, `tiles8`, `palette` and `mode7`. An entry is the path of a PNG file, or an
inline table:

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
`tiles8`, `palette`, `mode7`. A file name that is no C name needs a `name`, and two pictures
with the same name are an error, as is a picture called `sfx` when the project has sound effects.

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

### [build] before {#build-before}

A game with converters of its own names them here, so that every build has their output. The
table `[build]` has one key, `before`, an array of strings, by default empty. Each string is a
command:

```toml
[build]
before = ["./make_levels.sh"]
```

The commands run in their order, in the project's folder, through the system's shell (`sh -c`,
on Windows `cmd /c`), for every target and for [gen](#gen). They run before the build looks at
the sources, so they may write files that `sources` lists; a build then checks that every entry
of `sources` is there. What a command prints appears indented by four spaces, and `--plan` and
`--dry-run` list the commands without running them. A command that ends with a status other
than 0 stops the build with status 1, followed by what it printed:

```
error: before: './make_levels.sh' ended with status 3:
no levels
```

### [snes], [desktop] and [web] {#snes-desktop-and-web}

These three optional tables set macros per [target](/glossary#target): `[snes]` for `snes`,
`[desktop]` for `linux` and `win64`, `[web]` for `web`. Each has one key, `defines`, an array of
strings, by default empty. An entry is `NAME`, which defines the macro as 1, or `NAME=VALUE`,
and has no spaces or double quotes:

```toml
[snes]
defines = ["LEVELS=3"]

[desktop]
defines = ["DEV_TOOLS", "LEVELS=8"]
```

With this file, code inside `#ifdef DEV_TOOLS` is built for `linux` and `win64` only, `LEVELS`
is 3 on the SNES and 8 on the desktop, and the web build defines neither.

The macros reach the game's own files, those in `build/gen` included, and not the kit's
library. On the desktop and the web the link step compiles the library. On the SNES dampf
compiles the game and the kit's `lib/snes` as one [whole program](/glossary#whole-program), but
gets `lib/snes` with [`--lib`](/reference/command-line#lib), whose files get no `-D` macros, so a
macro with the name of something there does not change the kit's code. The file
`build/compile_commands.json` shows the game's files as dampf sees them and so holds the macros
of `[snes]`, whatever the target.

An entry of another form stops the build:

```
error: superdevkit.toml: [desktop] defines: 'TWO WORDS' is not NAME or NAME=VALUE without spaces
```

## Environment

| Variable | Read by | Default |
|---|---|---|
| `CC` | `build linux`: the C compiler | `cc` |
| `EMCC` | `build web`: Emscripten's compiler | `emcc`, found on the `PATH` |
| `MESEN` | `run snes`: the Mesen program | `.mesen/Mesen`, then `.mesen/Mesen.exe` in the kit's folder |
| `BROWSER` | `run web`: a command that takes the page's address | the system's default browser |

The default browser is opened with `xdg-open` on Linux and Windows' `start` on Windows and from
WSL; the command also has `open` for macOS, which is not a target of the kit. Set
`BROWSER=true` to serve the page without opening a browser. The `win64` target reads no
variable: it finds Visual Studio in its standard folder.

## Exit status

| Status | When |
|---|---|
| 0 | the command did its work, or `--plan` and `--dry-run` found every file there |
| 1 | an error in the project, the build or a conversion |
| 2 | a usage error |

Status 1 comes from:

- an error in the project file, or a version or kit that does not fit;
- a step of the build that failed, or a file missing under `--plan` or `--dry-run`;
- `init` with a name it does not take, or that cannot copy the example;
- a picture or a sound file that `png` or `sfx` cannot convert;
- a program started by `run` that ended with a status other than 0.

Status 2 comes from no command, a command or option it does not know, too many or too few
arguments, a target it does not know, and a `png` kind it does not know.

Errors go to the standard error stream and start with `error:`; a failed build step names its
tool after it, as in `error: check: ...`. The `missing:` lines of `--dry-run` go there too,
without `error:`.
