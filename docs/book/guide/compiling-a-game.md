<!-- GENERATED from doc-specs/guide/compiling-a-game.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Compiling a game

A real game is not one file but dozens, with headers in several directories and macros set on
the command line. This page compiles the snapshot of [Blubber Blasen](/glossary#blubber-blasen)
in `examples/blubberblasen` with dampf, finds what does not fit the
[subset](/glossary#subset), and measures where the cycles go. The snapshot is a copy of the
game's code that is never edited; when you compile your own game, pass its paths instead.

The game's own build and its [PVSnesLib](/glossary#pvsneslib) calls are not part of this page.
The examples build the game's compiler benchmark (`examples/blubberblasen/bench/bench.c`),
which replaces every PVSnesLib call with an empty stub, so what runs is the game logic alone.

## Many files, one call

dampf compiles the [whole program](/glossary#whole-program) at once, never one file at a time:
there are no object files to link afterwards. Put every `.c` file of the game on one command
line. Use `-I` for each directory the `#include`s are searched in and `-D` for each macro, as
with gcc (`-Dname` or `-Dname=value`). Use `--entry` to name the
[entry function](/glossary#entry-function), the function the [runtime](/glossary#runtime) calls
after start-up; without it, that is `main`.

For the snapshot, the include directories are `examples/blubberblasen/src` and
`examples/blubberblasen/generated`. The benchmark's build adds one `-D` per PVSnesLib call,
which renames it (`setMode` becomes `bb_setMode`) the way the game's own benchmark build does;
the stubs are in `bench.c`. This is the call `tools/bench_game.sh` makes, with the options
written out:

```sh
build/install/dampf/bin/dampf build \
    -I examples/blubberblasen/src -I examples/blubberblasen/generated \
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
    examples/blubberblasen/src/game/*.c examples/blubberblasen/generated/*.c \
    examples/blubberblasen/bench/bench.c
```

It takes a few seconds and prints one line, with the full paths of the ROM and of the debug
file for [Mesen](/glossary#mesen) (cut here):

```
wrote .../build/bench/dampf.sfc and .../build/bench/dampf.dbg
```

Next to the ROM, dampf writes the generated assembly (one `.s` file per module, plus the
runtime's), the label and map files, and `dampf.timing.txt` with the best and worst case of
each function in CPU cycles. Every option of `build` is on
[Command line](/reference/command-line).

## Find what does not fit first

A game written for gcc on the PC uses some C that dampf does not accept. Before the first build,
run `dampf check` over all the files, with the same `-I` and `-D` options. It runs the front end
on each file on its own and reports the first problem of each file, so one unsupported
construct does not hide the others. For the snapshot every file passes (cut):

```sh
build/install/dampf/bin/dampf check -I examples/blubberblasen/src \
    -I examples/blubberblasen/generated examples/blubberblasen/src/game/*.c \
    examples/blubberblasen/generated/*.c examples/blubberblasen/bench/bench.c
```

```
ok      examples/blubberblasen/src/game/boss.c
ok      examples/blubberblasen/src/game/bubble.c
...
ok      examples/blubberblasen/bench/bench.c
17 of 17 file(s) pass the front end
```

A file that does not fit is marked `FAILS`, with the position and the message, and `check`
exits with 1. Here a game in `mygame/src` has one file that uses `float`:

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
ok      mygame/src/score.c
2 of 3 file(s) pass the front end
```

For each report, change the game's code so that it fits the subset, in a way that the
[PC build](/glossary#pc-build) still compiles and computes the same results: the same source
is meant to build with gcc and with dampf, and dampf's code must match gcc's bit for bit. Here
that means integer or fixed-point arithmetic instead of `float`. Run `check` again until every
file passes. [The C subset](/reference/c-subset) lists what dampf accepts.

`check` looks at each file alone, so it cannot see problems that need the whole program. A
recursion is one: `countdown.c` passes `check`, but its function calls itself, and `build`
rejects it once it sees the call graph:

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

## Where the time goes

Once the game builds, the next question is which functions take the cycles. `dampf profile`
answers it from a run of the ROM. It reads three things: the ROM, a file of execution counts
(how often the instruction at each ROM offset ran, and optionally the master clocks of the
measured stretch), and the label file and `.s` files that `build` wrote next to the ROM, which
say where each function starts. Then it adds up the cycles of every instruction per function.
A call is counted at the caller: each `JSL` that ran adds one call to the function it targets.

For the snapshot, `tools/profile_game.sh` does the whole round. It builds the benchmark as
above, runs [ticks](/glossary#tick) 900 to 1700 (the stretch of play) in dampf's own emulator,
which writes the counts, and prints the report:

```sh
tools/profile_game.sh bench
```

The script builds into `build/profile`; its last step is this call, where `40` is the number of
rows and `--skip bench.c` leaves the functions of the benchmark harness out of the table and
the shares:

```sh
build/install/dampf/bin/dampf profile build/profile/bench.sfc \
    build/profile/bench.profile.txt 40 --skip bench.c
```

The report starts like this (cut after twelve rows):

```
15,578,131 instructions, 53,678,397 CPU cycles (measured stretch: 9,207,852,586 master clocks, 25765.9 frames)
left out (bench.c): 95.5% of all CPU cycles
  share      calls  instructions      CPU cycles   cyc/call  function
  16.4%        680     2,628,836       8,822,817     12,974  separate
  12.9%        800     1,975,611       6,914,291      8,642  build_sprites
   7.7%        680     1,204,224       4,143,064      6,092  bubbles_update
   6.2%        800       930,656       3,342,660      4,178  bubbles_draw
   5.3%     12,008       874,133       2,853,002        237  ground_px
   4.8%      1,600       699,796       2,558,812      1,599  player_update
   4.5%        680       708,652       2,427,266      3,569  items_update
   4.2%        800       650,832       2,257,168      2,821  update_play
   3.6%        800       548,493       1,914,054      2,392  enemies_draw
   3.4%        800       555,129       1,838,131      2,297  items_draw
   2.6%      1,501       384,256       1,399,321        932  think_walker
   2.4%      2,892       409,272       1,307,292        452  level_wall_ahead
```

The first line sums up all functions that are not left out. The measured stretch in brackets
covers everything that ran, the harness included, also counted in [frames](/glossary#frame);
the second line says how much of it the harness took. Each row then gives the function's share of the cycles, how often it was called,
its instructions, its CPU cycles and the cycles per call, the most expensive function first.
The cycles per call tell a function that is cheap but called often, such as `ground_px`, from
one that is expensive each time, such as `separate`. The runtime's routines, such as the
multiplication, appear as `(runtime) dampf_mul16`.

## Replacing a function with assembly

To find out how far dampf's code is from what a person writes by hand, you can link a
hand-written version of one function in place of the one dampf compiles, and run the same
benchmark with it. The folder `reference/` holds such versions. Use `--replace` with the C name
of the function and the assembly file:

```sh
build/install/dampf/bin/dampf build ... \
    --replace level_solid_px=reference/level_solid_px.s -o build/bench/dampf_ref.sfc ...
```

The other options and files are those of the build above. The function must exist in the C
source, and the hand-written code keeps dampf's calling convention: the parameters are in the
function's static frame (`_<name>__<param>`), the result goes back in A (and X for 32 bits),
the call is `JSL`/`RTL`, A, X and Y are 16 bit, and the [DBR](/glossary#dbr) is `$7E`.

That convention is the one of the [naive code generator](/glossary#naive-code-generator), so
`--replace` switches to it unless `--backend` names a code generator. Asking for the
[code generator from MIR](/glossary#code-generator-from-mir) together with `--replace` fails
with

```
examples/blubberblasen/bench/bench.c:215:5: error: --replace works with the naive code generator only
```

The benchmark script takes the replacements in `REPLACE`, builds `build/bench/dampf_ref.sfc`
and compares its run with the PC's checksums and, if there is one, with the last run of the
naive code generator:

```sh
REPLACE="level_solid_px=reference/level_solid_px.s" tools/bench_game.sh
```

How the benchmark and the replays check dampf against the PC is on [Testing](/inside/testing).
