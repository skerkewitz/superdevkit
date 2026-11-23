<!-- GENERATED from doc-specs/guide/compiling-a-game.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Compiling a game

A game is not one file but many, with headers in several directories, graphics converted into C
and a library it calls: Er-Tüp, the side-scrolling shooter in `<home>/examples/ertuep` and the
largest example of the [dev kit](/glossary#dev-kit), compiles 29 `.c` files into its ROM. This
page compiles Er-Tüp with dampf, finds what does not fit the [subset](/glossary#subset), and
measures where the cycles go. When you compile your own game, pass its paths instead.

A ROM of a game needs a [platform layer](/glossary#platform-layer) for the library the game
calls, compiled together with the game; Er-Tüp calls the dev kit's
[console API](/glossary#console-api), and its build compiles the platform layer in `lib/snes`
with it.

The page writes `dampf` and `superdevkit` for the launchers in `<home>/bin`, in the
[superdevkit home](/glossary#superdevkit-home) of [Getting started](/guide/getting-started),
with `<home>/bin` on your `PATH`. The home has the examples, but not what a build writes into
their `build/` folders, so the commands run in a copy of Er-Tüp of your own, which
`superdevkit init` makes in the folder `ertuep`:

```sh
superdevkit init ertuep --from ertuep
cd ertuep
```

## Many files, one call

dampf compiles the [whole program](/glossary#whole-program) at once, never one file at a time:
there are no object files to link afterwards. Put every `.c` file of the game on one command
line. Use `-I` for each directory the `#include`s are searched in and `-D` for each macro, as
with gcc (`-Dname` or `-Dname=value`). Use `--entry` to name the
[entry function](/glossary#entry-function), the function the [runtime](/glossary#runtime) calls
after start-up; without it, that is `main`.

A game written against the console API has no `main` of its own: the entry function is the
main loop of the platform layer, in `<home>/lib/snes/main.c`, which calls the game's functions.
Its call therefore names the home's headers with `-I <home>/lib/include` and compiles the `.c`
files of `<home>/lib/snes` with the game's. For a game of your own with its `.c` files and
headers in `src`, run in the game's folder, the shortest call is:

```sh
dampf build -I <home>/lib/include -I src -o build/mygame.sfc src/*.c <home>/lib/snes/*.c
```

It prints one line, with the full paths of the ROM and of the debug file for
[Mesen](/glossary#mesen); a copy of the Mandelbrot example, made with
`superdevkit init mygame --from mandelbrot`, builds with it (path cut):

```
wrote .../mygame/build/mygame.sfc and .../mygame/build/mygame.dbg
```

You rarely need to type this: `superdevkit build snes` puts the same options into the argument
file it writes, below. A program that does not use the kit, such as the execution test of
[Getting started](/guide/getting-started), leaves out the home's include directory and
`lib/snes`, and names its entry function with `--entry`.

Er-Tüp is built with `superdevkit build snes`, from its [project file](/glossary#project-file)
`superdevkit.toml`. The build first converts the PNG files the project file lists, from
`assets`, into C in `build/gen`, then compiles the game with dampf. With `--plan` it shows these
steps without running them, with the paths in the game's folder relative to it (the home's
written here as `<home>`, and most steps cut):

```sh
superdevkit build snes --plan
```

```
superdevkit build snes: ertuep (kit <home>)
  png    tiles assets/stage_bar.png stage_bar build/gen
  ...
  png    mode7 assets/karussell.png karussell build/gen 33
  dampf  build -I <home>/lib/include -I src -I build/gen --entry main -o build/snes/ertuep.sfc src build/gen/ <home>/lib/snes/
  write  build/compile_commands.json
everything the build needs is there
```

Run it once without `--plan`. It prints only `built build/snes/ertuep.sfc`, and leaves the
graphics converted into C in `build/gen` and the ROM in `build/snes`:

```sh
superdevkit build snes
```

Er-Tüp has three include directories: `<home>/lib/include` with the headers of the console API,
`src` with the game's own, and `build/gen` with the graphics converted into C. Its `.c` files
are in the last two and in `<home>/lib/snes`; it needs no `-D`. Once a build has converted the
graphics, the same compile on one command line is:

```sh
dampf build \
    -I <home>/lib/include -I src -I build/gen \
    --entry main -o build/ertuep/ertuep.sfc \
    src/*.c build/gen/*.c <home>/lib/snes/*.c
```

It prints its line as well (path cut):

```
wrote .../ertuep/build/ertuep/ertuep.sfc and .../ertuep/build/ertuep/ertuep.dbg
```

A call this long belongs in a file. dampf reads its arguments from an
[argument file](/glossary#args-file) as well, and every `superdevkit build snes` writes one for
the game: `build/snes/<name>.args`, which names what the build compiled. It names every file on
its own line, with absolute paths, with a `-D` for each macro the project file defines for the
SNES, and with `--sram` when the project file asks for save RAM.
Er-Tüp's, with the game's paths cut to `.../ertuep/`, the home's written as `<home>` and most of
its files left out:

```text [build/snes/ertuep.args]
# What superdevkit build snes compiles, for dampf (written by every build):
#   dampf build @build/snes/ertuep.args -o out.sfc [more options]
-I <home>/lib/include
-I .../ertuep/src
-I .../ertuep/build/gen
--entry main
.../ertuep/src/boss.c
...
.../ertuep/build/gen/wall_kitchen.c
<home>/lib/snes/main.c
<home>/lib/snes/sfx.c
<home>/lib/snes/snes.c
```

The command line adds to the file, so the file leaves out the output and each call names its
own. The call below builds the same ROM as `superdevkit build snes` and prints dampf's single
line (path cut):

```sh
dampf build @build/snes/ertuep.args -o build/ertuep.sfc
```

```
wrote .../ertuep/build/ertuep.sfc and .../ertuep/build/ertuep.dbg
```

Options added after the file, such as another code generator or `--replace`, give a variant of
the same build.

You can also write an argument file by hand. `build`, `check` and `parse` read it when you name
it with `@file` or `--config file`, one file per call. The file holds one option with its value
per line, or one source file or folder; `#` starts a comment. Relative paths in it are taken
from the file's folder, so the file works from any folder, and a folder stands for the `.c`
files directly in it, not those in its subfolders.

A function can be given a [budget](/reference/command-line#budgets) in CPU cycles with
`DAMPF_BUDGET` from `<dampf.h>`. When its worst case comes above 90% of the budget, a warning
line comes before dampf's `wrote` line; when it is over the budget, or has no worst case, the
build fails.

Next to the ROM, dampf writes the generated assembly (`ertuep_1.s`, `ertuep_2.s` and the
runtime's `dampf_runtime.s`), the label file (`ertuep.lbl`, the address of every label), and
the [timing report](/glossary#timing-report) `ertuep.timing.txt` with the best and worst case of
each function in CPU cycles. dampf assembles and links with its own assembler and linker; with
`--assembler ca65` it uses [ca65 and ld65](/glossary#ca65-and-ld65) instead, which build the
same ROM and also write a map file. A function without a worst case is marked `unbounded`, with
the loop or call that has no bound, such as `(loop at objects.c:877)`; a function that never
returns, such as the `main` of the platform layer, has `never` as its best case. Every option of
`build` is on [Command line](/reference/command-line).

## Find what does not fit first

A game written for gcc on the PC uses some C that dampf does not accept. Before the first build,
run [`dampf check`](/reference/command-line#check) over all the files, with the same `-I` and
`-D` options. It runs the [front end](/glossary#front-end) on each file on its own and reports
every problem of each file, at most one per top-level declaration, so one unsupported construct
does not hide the others. Then it takes the files that passed
together and looks for what only the whole program shows: a recursion, a function that is called
but defined in none of the files, a function defined in two files.

With the kit, use the argument file instead of typing the options again.
`superdevkit build snes` writes it before dampf compiles, so even a first build that dampf's
errors stop leaves it behind, and `check` reads it as `build` does. For Er-Tüp every file
passes, alone and together (paths cut as above, files left out):

```sh
dampf check @build/snes/ertuep.args
```

```
ok      .../ertuep/src/boss.c
ok      .../ertuep/src/bossmap.c
...
ok      <home>/lib/snes/sfx.c
ok      <home>/lib/snes/snes.c
29 of 29 file(s) pass the front end
the 29 file(s) together: ok
```

A file that does not fit is marked `FAILS`, with the position and the message, and `check`
exits with 1. Here a game of your own has, in `src`, one file that uses `float`, and one,
`countdown.c`, whose function calls itself:

```c [src/physics.c]
#include <stdint.h>

float player_speed;

void move_player(int16_t *player_x)
{
    *player_x = (int16_t)(*player_x + player_speed);
}
```

```sh
dampf check -I src src/*.c
```

```
ok      src/countdown.c
FAILS   src/physics.c:3:1: error: 'float' is not supported: dampf has no floating point
        src/physics.c:7:39: error: 'player_speed' is not declared
ok      src/score.c
2 of 3 file(s) pass the front end
FAILS   src/countdown.c:5:25: error: recursion is not supported: countdown → countdown   (the 2 file(s) together)
```

The indented line under `physics.c` follows from the one above it: the declaration of
`player_speed` is rejected, so its use in `move_player` finds no variable. It goes away with the
first.

For each report, change the game's code so that it fits the subset, in a way that the
[PC build](/glossary#pc-build) still compiles and computes the same results: the same source
is meant to build with gcc and with dampf, and dampf's code must match gcc's bit for bit. Here
that means integer or fixed-point arithmetic instead of `float`. Run `check` again until every
file passes. [The C subset](/reference/c-subset) lists what dampf accepts.

The last line comes from the files taken together. On its own `countdown.c` passes, but its
function calls itself, which only the call graph of the whole program shows; `build` rejects it
with the same message:

```c [src/countdown.c]
#include <stdint.h>

uint8_t countdown(uint8_t n)
{
    return n == 0 ? 0 : countdown(n - 1);
}
```

```
src/countdown.c:5:25: error: recursion is not supported: countdown → countdown
```

Only the files that pass alone go into this second round, so fix those first: a function
defined in a failing file can show up here as called but never defined.

## Where the time goes

Once the game builds, the next question is which functions take the cycles. The examples answer
it in dampf's own emulator, without Mesen: `dampf.emu.ExampleProfile` plays the ROM with the
joypad input of the example's `check.txt`, the file its checks play, and charges the
[master clocks](/glossary#master-clock) of every instruction between two
[ticks](/glossary#tick) to the function the instruction belongs to. It finds the functions by
the label file and the timing report next to the ROM. A call is charged to the function called,
not to the caller, and the wait for the [vertical blank](/glossary#vertical-blank) to the
function that waits. When the debug file lies next to the ROM, it also charges the cycles to C
lines, which shows where inside a function the time goes, also inside the functions dampf
inlined.

There is no launcher for it; run it with the classes of the home, giving the ROM, its label
file, the input, the first tick and the tick after the last. Er-Tüp's README profiles ticks
5,700 to 5,731, the fight against the brew kettle of stage 2. The ROM and its label file are
the ones `superdevkit build snes` wrote into `build/snes` above; the copy has no `build/` folder
before that build:

```sh
java -cp '<home>/java/*' dampf.emu.ExampleProfile \
    build/snes/ertuep.sfc build/snes/ertuep.lbl check.txt 5700 5732
```

A number after the ticks sets how many rows each table has (25 without it). The output
starts like this (each table cut, the path of the home's file cut to `.../lib/`):

```
ticks 5700 to 5732: 1.000 frames per tick
  64.6 %      231004  main
   5.7 %       20488  kettle_update
   4.8 %       17100  collide
   3.1 %       11021  effects
   3.0 %       10644  play
   2.3 %        8315  draw_player
   2.2 %        7777  player_update
...
C lines:
  63.7 %      227464  .../lib/snes/snes.c:420
   2.5 %        8848  src/boss.c:332
   1.2 %        4300  src/stage.c:338
   1.1 %        3801  src/game.c:324
   0.7 %        2534  src/draw.c:283
...
```

The first line repeats the two numbers of the call, so its `5732` is the tick after the last
measured, and gives the [frames](/glossary#frame) the ticks took on average. A tick ends by
waiting for the next vertical blank, so a tick whose work fits in a frame takes exactly one.
Each row then gives the share of the measured ticks and the master clocks per tick, the most
expensive first. Here the time in `main` is almost all line 420 of `<home>/lib/snes/snes.c`, the
loop that waits for the vertical blank: nearly two thirds of each frame are left. Of the game's
own work, the kettle's `kettle_update` takes the most, and line 332 of `src/boss.c`, with lines
330 to 335 further down the list, is the loop that moves the kettle's tentacles.

## Replacing a function with assembly

To find out how far dampf's code is from what a person writes by hand, you can link a
hand-written version of one function in place of the one dampf compiles, and run the same
input with it. The folder `reference/` of superdevkit's repository holds such versions for
functions of the examples, such as Er-Tüp's random generator `random16` in
`reference/ertuep_random16.s`; the home has no `reference/`, so this needs a checkout of the
repository. Use [`--replace`](/reference/command-line#replace) with the C name of the function
and the assembly file, added to the argument file of the build. In the copy of Er-Tüp, with
`<repo>` for the folder of the checkout:

```sh
dampf build @build/snes/ertuep.args --backend naive \
    --replace random16=<repo>/reference/ertuep_random16.s -o build/ref.sfc
```

The function must exist in the C source, and the hand-written code has to keep dampf's calling
convention, which says where the parameters and the result are and how the function is called
and returns. `reference/README.md` in the repository lists it, and how the examples' tests check
that each reference computes what the C does.

That convention is the one of the [naive code generator](/glossary#naive-code-generator), so
`--replace` switches to it unless `--backend` names a code generator. Asking for the
[code generator from MIR](/glossary#code-generator-from-mir) together with `--replace` fails
with this message, at the entry function:

```
<home>/lib/snes/main.c:78:5: error: --replace works with the naive code generator only
```

The comparison is therefore between the naive code generator's version and the hand-written
one: the code generator from MIR inlines `random16` into its callers, while the naive one calls
it. Budgets and the timing file come from the code generator from MIR: the naive one computes
no best or worst case, so a build with `--replace` writes no `.timing.txt` and checks no
`DAMPF_BUDGET`. Without the timing report, `ExampleProfile` counts every C label as a function;
run it on the build with the reference and on a naive build without it, with the same input,
which makes the same calls, so the ratio of the totals is the ratio per call.

How the examples check dampf against the PC is on [Testing](/inside/testing).
