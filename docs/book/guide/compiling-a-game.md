<!-- GENERATED from doc-specs/guide/compiling-a-game.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Compiling a game

A game is not one file but many, with headers in several directories and macros set on the
command line: the snapshot below has 17 `.c` files. This page compiles the snapshot of
[Blubber Blasen](/glossary#blubber-blasen) in `snapshots/blubberblasen` with dampf, finds what
does not fit the [subset](/glossary#subset), and measures where the cycles go. The snapshot is
a copy of the game's code that is never edited; when you compile your own game, pass its paths
instead.

The game's own build and its [PVSnesLib](/glossary#pvsneslib) calls are not part of this page.
The examples build the game's compiler benchmark (`snapshots/blubberblasen/bench/bench.c`),
which replaces every PVSnesLib call with an empty stub, so what runs is the game logic alone. A
ROM that plays needs a [platform layer](/glossary#platform-layer) for the library the game
calls, compiled together with the game; Blubber Blasen's is in `lib/snes`, the library of the
[dev kit](/glossary#dev-kit) on the SNES.

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

For the snapshot, the include directories are `snapshots/blubberblasen/src` and
`snapshots/blubberblasen/generated`. The benchmark's build adds one `-D` per PVSnesLib call,
which renames it (`setMode` becomes `bb_setMode`) to one of the stubs in `bench.c`. This is
the call `dev/bench_game.sh` makes, with the options written out; the script also passes
`--backend mir`, which is the default:

```sh
build/install/dampf/bin/dampf build \
    -I snapshots/blubberblasen/src -I snapshots/blubberblasen/generated \
    -DsetMode=bb_setMode -DsetBrightness=bb_setBrightness -DsetScreenOn=bb_setScreenOn \
    -DsetScreenOff=bb_setScreenOff -DsetColorEffect=bb_setColorEffect \
    -DsetColorIntensity=bb_setColorIntensity -DsetPaletteColor=bb_setPaletteColor \
    -DdmaCopyVram=bb_dmaCopyVram -DdmaCopyCGram=bb_dmaCopyCGram -DdmaClearVram=bb_dmaClearVram \
    -DbgSetGfxPtr=bb_bgSetGfxPtr -DbgSetMapPtr=bb_bgSetMapPtr -DbgSetScroll=bb_bgSetScroll \
    -DbgSetEnable=bb_bgSetEnable -DbgSetDisable=bb_bgSetDisable \
    -DbgSetEnableSub=bb_bgSetEnableSub -DbgSetDisableSub=bb_bgSetDisableSub \
    -DbgInitTileSet=bb_bgInitTileSet -DbgInitMapSet=bb_bgInitMapSet \
    -DoamInit=bb_oamInit -DoamInitGfxSet=bb_oamInitGfxSet -DoamInitGfxAttr=bb_oamInitGfxAttr \
    -DoamSet=bb_oamSet -DoamSetXY=bb_oamSetXY -DoamSetEx=bb_oamSetEx \
    -DoamSetVisible=bb_oamSetVisible -DoamClear=bb_oamClear -DsnesRegWrite=bb_snesRegWrite \
    -DsnesHdmaSet=bb_snesHdmaSet -DsnesHdmaEnable=bb_snesHdmaEnable \
    -DsnesDmaFits=bb_snesDmaFits -DoamMemory=bb_oamMemory \
    --entry main -o build/bench/dampf.sfc \
    snapshots/blubberblasen/src/game/*.c snapshots/blubberblasen/generated/*.c \
    snapshots/blubberblasen/bench/bench.c
```

It compiles in seconds and prints one line, with the full paths of the ROM and of the debug
file for [Mesen](/glossary#mesen) (cut here):

```
wrote .../build/bench/dampf.sfc and .../build/bench/dampf.dbg
```

Nobody types a call like this twice. Keep it in an argument file instead, which `build`, `check`
and `parse` read when you name it with `@file` or `--config file`, one file per call. The file
holds one option with its value per line, or one source file or folder; `#` starts a comment.
Relative paths in it are taken from the file's folder, so the file works from any folder, and
a folder stands for the `.c` files directly in it. The same build, kept in `bench.args` at the
top of the repository:

```text [bench.args]
# Blubber Blasen's compiler benchmark, as dev/bench_game.sh builds it
-I snapshots/blubberblasen/src
-I snapshots/blubberblasen/generated
-DsetMode=bb_setMode
-DsetBrightness=bb_setBrightness
-DsetScreenOn=bb_setScreenOn
-DsetScreenOff=bb_setScreenOff
-DsetColorEffect=bb_setColorEffect
-DsetColorIntensity=bb_setColorIntensity
-DsetPaletteColor=bb_setPaletteColor
-DdmaCopyVram=bb_dmaCopyVram
-DdmaCopyCGram=bb_dmaCopyCGram
-DdmaClearVram=bb_dmaClearVram
-DbgSetGfxPtr=bb_bgSetGfxPtr
-DbgSetMapPtr=bb_bgSetMapPtr
-DbgSetScroll=bb_bgSetScroll
-DbgSetEnable=bb_bgSetEnable
-DbgSetDisable=bb_bgSetDisable
-DbgSetEnableSub=bb_bgSetEnableSub
-DbgSetDisableSub=bb_bgSetDisableSub
-DbgInitTileSet=bb_bgInitTileSet
-DbgInitMapSet=bb_bgInitMapSet
-DoamInit=bb_oamInit
-DoamInitGfxSet=bb_oamInitGfxSet
-DoamInitGfxAttr=bb_oamInitGfxAttr
-DoamSet=bb_oamSet
-DoamSetXY=bb_oamSetXY
-DoamSetEx=bb_oamSetEx
-DoamSetVisible=bb_oamSetVisible
-DoamClear=bb_oamClear
-DsnesRegWrite=bb_snesRegWrite
-DsnesHdmaSet=bb_snesHdmaSet
-DsnesHdmaEnable=bb_snesHdmaEnable
-DsnesDmaFits=bb_snesDmaFits
-DoamMemory=bb_oamMemory
--entry main
snapshots/blubberblasen/src/game/
snapshots/blubberblasen/generated/
snapshots/blubberblasen/bench/bench.c
```

The command line adds to the file, so the file leaves out the output and each call names its own:

```sh
build/install/dampf/bin/dampf build @bench.args -o build/bench/dampf.sfc
```

This builds the same ROM as the long call. The ROM that plays is built the same way, from the
argument file that comes with the snapshot; it compiles the game with the platform layer in
`lib/snes` instead of the benchmark's stubs:

```sh
build/install/dampf/bin/dampf build @snapshots/blubberblasen.args -o build/game/blubberblasen.sfc
```

A function can be given a [budget](/reference/command-line#budgets) in CPU cycles with
`DAMPF_BUDGET` from `<dampf.h>`. When its worst case comes above 90% of the budget, a warning
line comes before this one; when it is over the budget, or has no worst case, the build fails.

Next to the ROM, dampf writes the generated assembly (one `.s` file per module, plus the
runtime's), the label file (`dampf.lbl`, the address of every label), and `dampf.timing.txt`
with the best and worst case of each function in CPU cycles. dampf assembles and links with its
own assembler and linker; with `--assembler ca65` it uses
[ca65 and ld65](/glossary#ca65-and-ld65) instead, which build the same ROM and also write a map
file. A function without a worst case is marked `unbounded`, with the loop or call that has no
bound, such as `(loop at game.c:1217)`; a function that never returns, such as the benchmark's
`main`, has `never` as its best case. Every option of `build` is on
[Command line](/reference/command-line).

## Find what does not fit first

A game written for gcc on the PC uses some C that dampf does not accept. Before the first build,
run [`dampf check`](/reference/command-line#check) over all the files, with the same `-I` and
`-D` options, or with the argument file: `dampf check @bench.args`. It runs the front end on
each file on its own and reports every problem of each file, at most one per top-level
declaration, so one unsupported construct does not hide the others. Then it takes the files that
passed together and looks for what only the whole program shows: a recursion, a function that
is called but defined in none of the files, a function defined in two files. For the snapshot
every file passes, alone and together (cut):

```sh
build/install/dampf/bin/dampf check -I snapshots/blubberblasen/src \
    -I snapshots/blubberblasen/generated snapshots/blubberblasen/src/game/*.c \
    snapshots/blubberblasen/generated/*.c snapshots/blubberblasen/bench/bench.c
```

```
ok      snapshots/blubberblasen/src/game/boss.c
ok      snapshots/blubberblasen/src/game/bubble.c
...
ok      snapshots/blubberblasen/bench/bench.c
17 of 17 file(s) pass the front end
the 17 file(s) together: ok
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

Once the game builds, the next question is which functions take the cycles.
[`dampf profile`](/reference/command-line#profile) answers it from a run of the ROM. It reads
three things: the ROM, a file of execution counts, which says how often the instruction at each
ROM offset ran and optionally how many [master clocks](/glossary#master-clock) the measured
stretch took, and the label file and `.s` files that `build` wrote next to the ROM, which
say where each function starts. Then it adds up the cycles of every instruction per function.
A call is counted at the caller: each `JSL` that ran adds one call to the function it targets.

For the snapshot, `dev/profile_game.sh` does the whole round. It builds the benchmark as
above, runs [ticks](/glossary#tick) 900 to 1700, a stretch in which the game is played, in
dampf's own emulator, which writes the counts, and prints the report:

```sh
dev/profile_game.sh bench
```

The script builds into `build/profile` and writes the counts to
`build/profile/bench.profile.txt`; its last step is this call, where `40` is the number of rows
and `--skip bench.c` leaves the functions of the benchmark harness out of the table and the
shares (`--blocks` is explained below):

```sh
build/install/dampf/bin/dampf profile build/profile/bench.sfc \
    build/profile/bench.profile.txt 40 --skip bench.c --blocks build/profile/bench.blocks.txt
```

The report starts like this (cut after twelve rows):

```
9,780,436 instructions, 33,553,205 CPU cycles (measured stretch: 7,164,989,812 master clocks, 20049.4 frames)
left out (bench.c): 96.9% of all CPU cycles
  share      calls  instructions      CPU cycles   cyc/call  function
  16.0%        680     1,627,151       5,359,747      7,881  separate
  13.4%        800     1,273,700       4,491,428      5,614  build_sprites
   8.4%        680       808,355       2,820,903      4,148  bubbles_update
   7.4%        800       678,525       2,477,256      3,096  bubbles_draw
   5.0%      1,501       484,912       1,681,578      1,120  think_walker
   4.9%        800       480,719       1,650,717      2,063  update_play
   4.5%      3,434       473,441       1,505,552        438  level_on_ground
   4.3%        800       403,177       1,429,353      1,786  enemies_draw
   4.2%      1,600       396,256       1,396,562        872  player_update
   3.5%        680       358,415       1,188,671      1,748  items_update
   3.4%        800       359,832       1,141,359      1,426  items_draw
   2.6%        800       236,767         856,413      1,070  build_oam
```

The first line sums up all functions that are not left out. The measured stretch in brackets
covers everything that ran, the harness included, also counted in [frames](/glossary#frame);
the second line says how much of it the harness took. The 20,049.4 frames for 800 ticks are
therefore not the game's frames per tick. Each row then gives the function's share of the
cycles, how often it was called, its instructions, its CPU cycles and the cycles per call, the
most expensive function first. The cycles per call tell a function that is cheap but called
often, such as `level_on_ground` with 438 CPU cycles per call over 3,434 calls, from one that
is expensive each time, such as `separate` with 7,881. The runtime's routines, such as the
multiplication, appear as `(runtime) dampf_mul16`.

To see which kinds of instruction the cycles go to, add `--instructions` to `dampf profile`,
or set `INSTRUCTIONS=1` for the script:

```sh
INSTRUCTIONS=1 dev/profile_game.sh bench
```

A second table then follows, with the cycles per instruction and addressing mode in the
functions that are not left out (cut after six rows):

```
  share      executed      CPU cycles  instruction
  13.3%     1,115,206       4,460,122  sta dp
  11.5%       972,720       3,849,241  lda dp
   9.4%       527,536       3,154,608  lda abs,x
   7.7%       858,098       2,574,294  cmp #imm
   6.8%       759,273       2,277,819  and #imm
   4.3%       557,814       1,446,852  bne
```

Here about a quarter of the time goes to loading and storing values in the
[direct page](/glossary#direct-page).

The profile also says how often each block of the generated code ran. The script writes that to
`build/profile/bench.blocks.txt` (`dampf profile --blocks`), and a build with it lays the code
out so that what runs often stays together
([`--block-counts`](/reference/command-line#block-counts)):

```sh
BLOCKS=build/profile/bench.blocks.txt dev/bench_game.sh
```

Only the order of the blocks changes, so the program gives the same results with or without
it.

## Replacing a function with assembly

To find out how far dampf's code is from what a person writes by hand, you can link a
hand-written version of one function in place of the one dampf compiles, and run the same
benchmark with it. The folder `reference/` holds such versions. Use
[`--replace`](/reference/command-line#replace) with the C name of the function and the assembly
file:

```sh
build/install/dampf/bin/dampf build ... \
    --replace level_solid_px=reference/level_solid_px.s -o build/bench/dampf_ref.sfc ...
```

The other options and files are those of the build above. The function must exist in the C
source, and the hand-written code keeps dampf's calling convention: the parameters are in the
function's static frame, fixed places in memory named `_<name>__<param>`; the result goes back
in A (and X for 32 bits), the call is `JSL`/`RTL`, A, X and Y are 16 bit, and the
[DBR](/glossary#dbr) is `$7E`.

That convention is the one of the [naive code generator](/glossary#naive-code-generator), so
`--replace` switches to it unless `--backend` names a code generator. Asking for the
[code generator from MIR](/glossary#code-generator-from-mir) together with `--replace` fails
with

```
snapshots/blubberblasen/bench/bench.c:215:5: error: --replace works with the naive code generator only
```

Budgets and the timing file come from the code generator from MIR: the naive one computes no
best or worst case, so a build with `--replace` writes no `.timing.txt` and checks no
`DAMPF_BUDGET`.

The benchmark script takes the replacements in `REPLACE`, builds `build/bench/dampf_ref.sfc`
and compares its run with the PC's checksums and, if there is one, with the last run of the
naive code generator:

```sh
REPLACE="level_solid_px=reference/level_solid_px.s" dev/bench_game.sh
```

How the benchmark and the replays check dampf against the PC is on [Testing](/inside/testing).
