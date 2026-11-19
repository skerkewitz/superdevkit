<!-- GENERATED from doc-specs/guide/compiling-a-game.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Compiling a game

A game is not one file but many, with headers in several directories, graphics converted into C
and a library it calls: Er-Tüp, the side-scrolling shooter in `examples/ertuep` and the largest
example of the [dev kit](/glossary#dev-kit), has 28 `.c` files. This page compiles Er-Tüp with
dampf, finds what does not fit the [subset](/glossary#subset), and measures where the cycles go.
When you compile your own game, pass its paths instead.

A ROM of a game needs a [platform layer](/glossary#platform-layer) for the library the game
calls, compiled together with the game; Er-Tüp calls the dev kit's console API, and its argument
file compiles the platform layer in `lib/snes` with it.

## Many files, one call

dampf compiles the [whole program](/glossary#whole-program) at once, never one file at a time:
there are no object files to link afterwards. Put every `.c` file of the game on one command
line. Use `-I` for each directory the `#include`s are searched in and `-D` for each macro, as
with gcc (`-Dname` or `-Dname=value`). Use `--entry` to name the
[entry function](/glossary#entry-function), the function the [runtime](/glossary#runtime) calls
after start-up; without it, that is `main`.

For a game of your own in `mygame/src`, with its headers next to the `.c` files and a `main`,
the shortest call with the launcher from [Getting started](/guide/getting-started) is:

```sh
build/install/dampf/bin/dampf build -I mygame/src -o build/mygame/mygame.sfc mygame/src/*.c
```

Er-Tüp has three include directories: `lib/include` with the headers of the console API,
`examples/ertuep/src` with the game's own, and `examples/ertuep/build/gen` with the graphics
converted into C. Its `.c` files are in the last two and in `lib/snes`; it needs no `-D`. The
graphics come from the PNG files in `examples/ertuep/assets`, which `examples/ertuep/build.sh`
converts with `tools/png2snes.py` before it compiles (`CONVERT_ONLY=1` stops after the
conversion). With the graphics converted, the whole build on one command line is:

```sh
build/install/dampf/bin/dampf build \
    -I lib/include -I examples/ertuep/src -I examples/ertuep/build/gen \
    --entry main -o build/ertuep/ertuep.sfc \
    examples/ertuep/src/*.c examples/ertuep/build/gen/*.c lib/snes/*.c
```

It compiles in seconds and prints one line, with the full paths of the ROM and of the debug
file for [Mesen](/glossary#mesen) (cut here):

```
wrote .../build/ertuep/ertuep.sfc and .../build/ertuep/ertuep.dbg
```

Nobody types a call like this twice. Keep it in an argument file instead, which `build`, `check`
and `parse` read when you name it with `@file` or `--config file`, one file per call. The file
holds one option with its value per line, or one source file or folder; `#` starts a comment.
Relative paths in it are taken from the file's folder, so the file works from any folder, and
a folder stands for the `.c` files directly in it. Er-Tüp keeps its build in
`examples/ertuep/ertuep.args`:

```text [examples/ertuep/ertuep.args]
# Er-Tüp for the SNES: the game, its converted graphics (build/gen, written by build.sh) and the
# dev kit's library. Paths are relative to this file (ADR record 28).
-I ../../lib/include
-I src
-I build/gen
--entry main
src/
build/gen/
../../lib/snes/
```

The command line adds to the file, so the file leaves out the output and each call names its
own. `build.sh` changes to the example's folder and, after converting the PNG files, makes this
call, where `-o` is taken from the current folder as on any command line:

```sh
cd examples/ertuep
../../build/install/dampf/bin/dampf build @ertuep.args -o build/ertuep.sfc
```

From the top of the repository, the same file is `@examples/ertuep/ertuep.args`; either way the
ROM is the same as the one of the long call. `examples/ertuep/build.sh` does both steps and
prints the same single line:

```sh
examples/ertuep/build.sh
```

```
wrote .../examples/ertuep/build/ertuep.sfc and .../examples/ertuep/build/ertuep.dbg
```

A function can be given a [budget](/reference/command-line#budgets) in CPU cycles with
`DAMPF_BUDGET` from `<dampf.h>`. When its worst case comes above 90% of the budget, a warning
line comes before this one; when it is over the budget, or has no worst case, the build fails.

Next to the ROM, dampf writes the generated assembly (`ertuep_1.s`, `ertuep_2.s` and the
runtime's `dampf_runtime.s`), the label file (`ertuep.lbl`, the address of every label), and
`ertuep.timing.txt` with the best and worst case of each function in CPU cycles. dampf
assembles and links with its own assembler and linker; with `--assembler ca65` it uses
[ca65 and ld65](/glossary#ca65-and-ld65) instead, which build the same ROM and also write a map
file. A function without a worst case is marked `unbounded`, with the loop or call that has no
bound, such as `(loop at objects.c:877)`; a function that never returns, such as the `main` of
the platform layer, has `never` as its best case. Every option of `build` is on
[Command line](/reference/command-line).

## Find what does not fit first

A game written for gcc on the PC uses some C that dampf does not accept. Before the first build,
run [`dampf check`](/reference/command-line#check) over all the files, with the same `-I` and
`-D` options, or with the argument file. It runs the [front end](/glossary#front-end) on each
file on its own and reports every problem of each file, at most one per top-level declaration,
so one unsupported construct does not hide the others. Then it takes the files that passed
together and looks for what only the whole program shows: a recursion, a function that is called
but defined in none of the files, a function defined in two files. Er-Tüp's argument file
names the folder of the converted graphics, so convert them first
(`CONVERT_ONLY=1 examples/ertuep/build.sh`). Then
every file passes, alone and together (cut):

```sh
build/install/dampf/bin/dampf check @examples/ertuep/ertuep.args
```

```
ok      examples/ertuep/src/boss.c
ok      examples/ertuep/src/bossmap.c
...
ok      lib/snes/main.c
ok      lib/snes/snes.c
28 of 28 file(s) pass the front end
the 28 file(s) together: ok
```

A file that does not fit is marked `FAILS`, with the position and the message, and `check`
exits with 1. Here a game in `mygame/src` has one file that uses `float`, and one,
`countdown.c`, whose function calls itself:

```c [mygame/src/physics.c]
#include <stdint.h>

float player_speed;

void move_player(int16_t *player_x)
{
    *player_x = (int16_t)(*player_x + player_speed);
}
```

```sh
build/install/dampf/bin/dampf check -I mygame/src mygame/src/*.c
```

```
ok      mygame/src/countdown.c
FAILS   mygame/src/physics.c:3:1: error: 'float' is not supported: dampf has no floating point
        mygame/src/physics.c:7:39: error: 'player_speed' is not declared
ok      mygame/src/score.c
2 of 3 file(s) pass the front end
FAILS   mygame/src/countdown.c:5:25: error: recursion is not supported: countdown → countdown   (the 2 file(s) together)
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

```c [mygame/src/countdown.c]
#include <stdint.h>

uint8_t countdown(uint8_t n)
{
    return n == 0 ? 0 : countdown(n - 1);
}
```

```
mygame/src/countdown.c:5:25: error: recursion is not supported: countdown → countdown
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

There is no launcher for it; run it with the classes of the installed dampf, giving the ROM,
its label file, the input, the first tick and the tick after the last. Er-Tüp's README profiles
ticks 5,700 to 5,731, the fight against the brew kettle of stage 2:

```sh
java -cp 'build/install/dampf/lib/*' dampf.emu.ExampleProfile \
    examples/ertuep/build/ertuep.sfc examples/ertuep/build/ertuep.lbl \
    examples/ertuep/check.txt 5700 5732
```

A number after the ticks sets how many rows each table has (25 without it). The output
starts like this (each table cut):

```
ticks 5700 to 5732: 1.000 frames per tick
  57.5 %      205660  main
   7.5 %       26941  kettle_update
   5.1 %       18295  collide
   4.1 %       14737  effects
   3.3 %       11636  play
   2.6 %        9114  draw_player
   2.5 %        8831  player_update
...
C lines:
  56.5 %      202083  lib/snes/snes.c:420
   2.5 %        8936  src/boss.c:332
   1.5 %        5291  src/boss.c:335
   1.3 %        4770  src/stage.c:338
   1.3 %        4485  src/draw.c:131
...
```

The first line gives the [frames](/glossary#frame) the ticks took on average. A tick ends by
waiting for the next vertical blank, so a tick whose work fits in a frame takes exactly one.
Each row then gives the share of the measured ticks and the master clocks per tick, the most
expensive first. Here the time in `main` is almost all line 420 of `lib/snes/snes.c`, the loop
that waits for the vertical blank: more than half of each frame is left. Of the game's own work,
the kettle's `kettle_update` takes the most, and its lines 330 to 336 of `src/boss.c` are the
loop that moves the kettle's tentacles.

## Replacing a function with assembly

To find out how far dampf's code is from what a person writes by hand, you can link a
hand-written version of one function in place of the one dampf compiles, and run the same
input with it. The folder `reference/` holds such versions for functions of the examples, such
as Er-Tüp's random generator `random16` in `reference/ertuep_random16.s`. Use
[`--replace`](/reference/command-line#replace) with the C name of the function and the assembly
file:

```sh
cd examples/ertuep
../../build/install/dampf/bin/dampf build @ertuep.args --backend naive \
    --replace random16=../../reference/ertuep_random16.s -o build/ref.sfc
```

The function must exist in the C source, and the hand-written code keeps dampf's calling
convention: the parameters are in the function's static frame, fixed places in memory named
`_<name>__<param>`; the result goes back in A (and X for 32 bits), the call is `JSL`/`RTL`, A,
X and Y are 16 bit, and the [DBR](/glossary#dbr) is `$7E`. `reference/README.md` lists the
rest, and how the examples' tests check that each reference computes what the C does.

That convention is the one of the [naive code generator](/glossary#naive-code-generator), so
`--replace` switches to it unless `--backend` names a code generator. Asking for the
[code generator from MIR](/glossary#code-generator-from-mir) together with `--replace` fails
with

```
../../lib/snes/main.c:59:5: error: --replace works with the naive code generator only
```

The comparison is therefore between the naive code generator's version and the hand-written
one: the code generator from MIR inlines `random16` into its callers, while the naive one calls
it. Budgets and the timing file come from the code generator from MIR: the
naive one computes no best or worst case, so a build with `--replace` writes no `.timing.txt`
and checks no `DAMPF_BUDGET`. Without the timing report, `ExampleProfile` counts every C label
as a function; run it on the build with the reference and on a naive build without it, with
the same input, which makes the same calls, so the ratio of the totals is the ratio per call.

How the examples check dampf against the PC is on [Testing](/inside/testing).
