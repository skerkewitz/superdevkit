<!-- GENERATED from doc-specs/reference/command-line.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Command line

Run dampf without arguments, or with a command it does not know, and it prints its usage and
exits with status 2:

```
usage: dampf --version
       dampf parse|build|check @file ...   (or --config file: options from an argument file; a folder means its .c files)
       dampf parse [-I dir] [-D name[=value]] file.c ...
       dampf build [-I dir] [-D name[=value]] [--entry name] [--backend mir|naive] [--replace fn=file.s] [--block-counts file] [--slowrom] [--sram bytes] [--assembler dampf|ca65] [--opt-limit n] -o out.sfc file.c ...
       dampf check [-I dir] [-D name[=value]] file.c ...
       dampf profile rom.sfc profile.txt [top] [--skip file.c ...] [--instructions] [--blocks file]
```

Two more commands, `mir` and `bench`, are not in this list; they are described below. The
launcher is `bin/dampf` in the [superdevkit home](/glossary#superdevkit-home); after
`./gradlew installDist` in the repository it is `build/install/superdevkit/bin/dampf`.

This page is about dampf alone. To build a whole game, with its pictures, sounds and the kit's
library, use the `superdevkit` command, which calls dampf for you:
[The superdevkit command](/reference/superdevkit).

Use `dampf --version`, or `dampf version`, to see which dampf you have. It prints one line on
the standard output and exits with status 0:

```
dampf 0.9.1
```

The commands `build`, `check` and `parse` print everything on the standard error stream;
`profile`, `mir` and `bench` print their results on the standard output.

## build

Use `build` to compile C files into a ROM. dampf compiles the
[whole program](/glossary#whole-program) at once, so name every C file of the program in one
call:

```sh
dampf build --entry test_main -o build/calls.sfc compiler/src/test/resources/exec/calls.c
```

A folder among the arguments stands for the `.c` files directly in it, in sorted order; files
in its subfolders are not included, so a folder such as `src/game/debug/` does not end up in the
ROM unasked. The same holds for `check` and `parse`.

Every file or folder named as a source must exist. dampf checks that before it compiles
anything, and otherwise prints one line and exits with status 2:

```
dampf: no such file or folder: src
```

At least one C file and `-o` are required; without them dampf prints the usage and exits with
status 2. So it does, for `build`, `check` and `parse`, when `-I`, `-D`, `--entry` or `-o` is
the last argument and has no value.

On success it prints one line naming the ROM and the debug file, both as absolute paths, after
any [warnings about budgets](#budgets), and exits with status 0:

```
wrote /…/build/calls.sfc and /…/build/calls.dbg
```

With the [code generator from MIR](/glossary#code-generator-from-mir), the default, dampf also
writes `calls.timing.txt` next to the ROM, with the best and worst case of every function in CPU
cycles; [Exact timing](/inside/timing) explains how they are computed. The other files dampf
writes next to the ROM are described in [The generated files](/reference/output-files). If the
program has errors, dampf prints a [message](#messages) for each and exits with status 1.

### -I {#option-i}

Use `-I dir` to add a directory to the search path of `#include`. The option may be given more
than once and also written joined, as `-Idir`. dampf preprocesses with a preprocessor of its
own, so no gcc is needed, and it never looks at the host's headers, so the program cannot
depend on them. An `#include "file.h"` is looked for first in the directory of the file that
includes it; then both forms, `"file.h"` and `<file.h>`, are looked for in dampf's own include
directory, which provides `<stdint.h>`, `<string.h>` and `<dampf.h>`, and then in the
directories given with `-I`, in their order. dampf does not check that a directory given with
`-I` exists.

### -D {#option-d}

Use `-D name` or `-D name=value` to define a macro for the preprocessor, as gcc's `-D` does:
without a value the macro is `1`. It may be given more than once and also written joined, as
`-DFACTOR=4`. dampf itself always defines `__DAMPF__` as `1`, so a header can tell the dampf
build from the [PC build](/glossary#pc-build). It also defines C99's `__STDC__` (`1`),
`__STDC_VERSION__` (`199901L`) and `__STDC_HOSTED__` (`1`), but none of the host compiler's
macros, such as `__GNUC__`.

### --entry {#entry}

Use `--entry name` to name the [entry function](/glossary#entry-function), the function the
[runtime](/glossary#runtime) calls after start-up. The default is `main`. If the program does
not define it, the build stops with:

```
compiler/src/test/resources/exec/calls.c:1:1: error: entry function 'main' is not defined
```

The position is always the first line of the first C file.

### --backend {#backend}

Use `--backend mir` or `--backend naive` to choose the code generator: `mir` is the
[code generator from MIR](/glossary#code-generator-from-mir), `naive` the
[naive code generator](/glossary#naive-code-generator). Without `--backend`, dampf uses `mir`,
unless `--replace` is given: then the default is `naive`, because only the naive code generator
can link replaced functions. For any other value, dampf prints this line, then its usage, and
exits with status 2:

```
unknown backend 'fast' (mir or naive)
```

### --replace {#replace}

Use `--replace fn=file.s` to link the function `fn` from hand-written
[ca65](/glossary#ca65-and-ld65) assembly in `file.s` instead of compiling it from C. The option
may be given more than once, once per function. It is meant for reference versions of single
functions, written by hand to compare with dampf's code. The replacement keeps dampf's calling
convention: parameters in the function's static frame (`_<name>__<param>`), the result in A
(and X for 32 bits), `JSL`/`RTL`, A, X and Y 16 bits wide, [DBR](/glossary#dbr) at `$7E`.

The function must be defined in the C files, or the build stops:

```
add.c:1:1: error: --replace missing: there is no function 'missing' to replace
```

`--replace` works only with the naive code generator. Given together with `--backend mir`, the
build stops with this message, at the position of the entry function:

```
add.c:5:6: error: --replace works with the naive code generator only
```

### --slowrom {#slowrom}

Use `--slowrom` for a cartridge whose ROM is too slow for 3.58 MHz. Without it, the start-up
switches the ROM in banks `$80` and up to FastROM speed (`MEMSEL` at `$420D`) and the header says
FastROM; all of dampf's code and constant data are in those banks. With it, the ROM stays at
2.68 MHz and the header says SlowROM. Flash cartridges and emulators run FastROM.

### --sram {#sram}

Use `--sram bytes` to give the cartridge battery-backed save RAM of that many bytes, from 0 to
32,768; the default is 0, no save RAM. The ROM's header then announces it, rounded up to 2, 4,
8, 16 or 32 KB, at `$70:0000`, and the preprocessor gets the macro `SDEVKIT_SRAM_SIZE` with the
exact number, which the kit's `sdevkit_sram` uses. For a value outside that range, or one that
is not a number, dampf prints one line and exits with status 2:

```
dampf: --sram takes the bytes of save RAM, 0 to 32768: 40000
```

A game built with the `superdevkit` command gets the option from `sram` in its
[project file](/reference/superdevkit#sram).

### --block-counts {#block-counts}

Use `--block-counts file` to lay the code out by a profile: the file says how often each block
of the generated code ran, as [`profile --blocks`](#profile) writes it, one line per block with
the function, the block and the count. Where a block ends in a branch, the side that ran more
often follows it, and blocks that never ran go to the end of their function, so the code that
runs stays together and its branches are short. Only the order of the blocks changes: counts
from an older build or from other code make the program slower at most, never wrong. Without
the option the order does not depend on any profile. Only the
[code generator from MIR](/glossary#code-generator-from-mir) uses it.

### --assembler {#assembler}

Use `--assembler dampf` or `--assembler ca65` to choose what assembles and links the program.
The default, `dampf`, is dampf's own assembler and linker. With `ca65`, dampf runs
[ca65 and ld65](/glossary#ca65-and-ld65) from the cc65 suite instead, which must then be
installed; the ROM is the same, and ld65 also writes a map file next to it. For any other value,
dampf prints this line, then its usage, and exits with status 2:

```
unknown assembler 'gas' (dampf or ca65)
```

### --opt-limit {#opt-limit}

Use `--opt-limit n` to let at most `n` applications of the optimizer's
[passes](/glossary#pass) run, such as one pass on one function; once the limit is reached, no
pass runs any more. It is a tool for working on dampf: a program that is wrong with every pass
and right with none is built with ever smaller limits until the one application that broke it
is found. When the limit is reached, dampf names the last application on the standard error
stream, before the `wrote` line:

```
dampf: pass limit 3 reached in calls; the last application: Narrow on _inc8
```

Without the option the environment variable `DAMPF_OPT_LIMIT` sets the limit; without either
there is none. Under a limit, a [cycle budget](#budgets) that is not kept is a warning instead
of an error, because the passes that prove a loop's bound may not have run. Give the option on
the command line: an argument file does not take it. For a negative value or one that is not a
number, dampf prints this line, then its usage, and exits with status 2:

```
--opt-limit -3: a number of pass applications, 0 or more
```

### -o {#output}

Use `-o out.sfc` to name the ROM; it is required. All files of the build go into the directory
of the ROM, which dampf creates if it does not exist, and take its name without `.sfc` as their
base name.

### Argument file {#argument-file}

A build of a whole game needs several include directories, an entry and many source files. To
keep them in a file, name an argument file with `@file` or `--config file`. `build`, `check`
and `parse` take it, once per call. `superdevkit build snes` writes one for every game,
`build/snes/NAME.args`, with what it compiled and absolute paths
([The superdevkit command](/reference/superdevkit#snes)), so dampf's commands can be run on the
same files:

```sh
superdevkit build snes examples/ertuep
dampf check @examples/ertuep/build/snes/ertuep.args
```

A file you write yourself and check in uses relative paths. This one, saved as
`examples/ertuep/ertuep.args` in the repository, builds Er-Tüp, the example game, from its
sources, its graphics converted to C and the kit's library:

```text
# Er-Tüp for the SNES: the game, its converted graphics (build/gen, written by
# superdevkit build snes) and the kit's library. Paths are relative to this file.
-I ../../lib/include
-I src
-I build/gen
--entry main
src/
build/gen/
../../lib/snes/
```

The folder `build/gen` holds the graphics converted to C, so `superdevkit build snes` or
`superdevkit gen` has to have written it before this build, which gives the same ROM:

```sh
dampf build @examples/ertuep/ertuep.args -o build/ertuep.sfc
```

Each line holds one option with its value, or one source file or folder. A `#` at the start of
a word starts a comment that runs to the end of the line, blank lines are ignored, and a value
with blanks in it can be put in double quotes. Relative paths in the file, those of `-I`, `-o`,
`--block-counts`, the file of `--replace` and the source files, count from the file's folder,
not from where dampf runs, so the file works from any folder.

The file's arguments go in front of those of the command line, so the command line adds to
them: more `-I`, `-D` or source files, or an option the file does not have. For `-o` and
`--entry`, which take one value, the command line wins. A file may not name another argument
file.

A mistake in the file or in how it is named stops dampf before it compiles anything. It prints
one line starting with `dampf:`, the file and the line number, and exits with status 2:

```
dampf: bb.args:3: -I needs one value
```

The other messages are `an argument file may not name another`,
`one option with its value per line, or one file or folder`, `a quote is not closed`, and,
without a line number, `no argument file bb.args`,
`only one argument file per call (a.args and b.args)` and `--config needs a file`.

### Cycle budgets {#budgets}

With the code generator from MIR, dampf checks the cycle budgets that functions declare with the
[hint](/glossary#hint) `DAMPF_BUDGET` from `<dampf.h>`, against the worst case in the
`.timing.txt` file, calls included. A function whose worst case is above 90 % of its budget gets
a warning, printed before the `wrote` line; the build still succeeds. For this file:

```c [budget.c]
#include <stdint.h>
#include <dampf.h>

uint16_t score;

DAMPF_BUDGET(24)
void add_points(uint16_t points)
{
    score += points;
}

void main(void)
{
    add_points(10);
}
```

`dampf build -o build/budget.sfc budget.c` prints:

```
budget.c:7:6: warning: 'add_points' takes up to 22 CPU cycles, 92 % of its budget of 24 (DAMPF_BUDGET)
wrote /…/build/budget.sfc and /…/build/budget.dbg
```

A function whose worst case is above its budget stops the build with an error at its definition,
and dampf exits with status 1. With `DAMPF_BUDGET(20)` in the file above:

```
budget.c:7:6: error: 'add_points' takes up to 22 CPU cycles, over its budget of 20 (DAMPF_BUDGET); best case 22, see the .timing.txt file
```

So does a function with a budget but no worst case, such as one with a loop that has no known
bound: the message says it `has no worst case` and names the loop, as the `.timing.txt` file
does. The naive code generator computes no timing, so it checks no budgets.

## check

Use `check` to find out which files of a code base dampf accepts, before you try to build all
of them:

```sh
dampf check @examples/ertuep/build/snes/ertuep.args
```

It runs only the front end (preprocessor, parser and the checks of the subset) and handles
every file on its own, so one construct outside the [subset](/glossary#subset) does not hide the
problems of other files. For each file it prints one line: `ok` and the file name, or `FAILS`
and the first problem of that file, with the file's other problems indented on the lines below
it, one at most per top-level declaration. A last line counts the files that pass:

```
FAILS   score.c:12:5: error: 'float' is not supported: dampf has no floating point
0 of 1 file(s) pass the front end
```

For the file `scores.c` in [Messages](#messages), which has three errors:

```
FAILS   scores.c:7:5: error: 'float' is not supported: dampf has no floating point
        scores.c:13:5: error: 'double' is not supported: dampf has no floating point
        scores.c:19:5: error: 'long' is not supported: use int32_t or uint32_t (long is 64 bit on the PC)
0 of 1 file(s) pass the front end
```

A file that the preprocessor rejects (a missing header, for example) fails like any other, with
the preprocessor's [message](#messages) at its position, and `check` goes on with the next
file.

Some problems only show in the whole program: recursion, a function that is called but defined
in none of the files, and a function defined in two files. So `check` then takes the files that
passed together and prints one more line: `the N file(s) together: ok`, or a `FAILS` line with
the first such problem, ending in `(the N file(s) together)`. If no file passed, this line is
missing. For this file:

```c [rec.c]
#include <stdint.h>

void count(uint16_t n)
{
    if (n > 0) count(n - 1);
}
```

`dampf check rec.c` prints:

```
ok      rec.c
1 of 1 file(s) pass the front end
FAILS   rec.c:5:16: error: recursion is not supported: count → count   (the 1 file(s) together)
```

The exit status is 0 if every file passes and the files pass together, and 1 otherwise. `check`
does not look for the entry function; `build` does. `check` takes `-I`, `-D`, folders and an
[argument file](#argument-file) as [build](#build) does.

## parse

Use `parse` to preprocess and parse files without building the program. It takes `-I`, `-D`,
folders and an [argument file](#argument-file) as [build](#build) does. For each file it prints
the messages of the parser, such as syntax errors, and then one line with the number of files
and errors:

```
2 file(s), 0 error(s)
```

The exit status is 0 without errors and 1 with at least one. `parse` does not check the subset:
a file that `parse` accepts can still fail `check` and `build`.

## profile

Use `profile` to see how many CPU cycles each function of a ROM took while it ran. Its
arguments:

```
usage: dampf profile <rom.sfc> <profile.txt> [top N] [--skip file.c ...] [--instructions] [--blocks file]
```

- **`rom.sfc`** is a ROM built by dampf. The label file (`.lbl`) and the assembly (`.s`) that
  `build` wrote next to it must still be there: dampf takes the start of each function from
  them.
- **`profile.txt`** is the profile: how often each instruction in the ROM ran, one line per
  instruction with its offset in the ROM file and its count, and optionally a line
  `clocks N` with the master clocks (the SNES's base clock, 357,368 per frame) of the measured
  stretch. [`dampf bench --profile`](#bench-profile) writes it from dampf's own emulator; a
  Lua script for [Mesen](/glossary#mesen) can write the same format.
- **`top`** is a number: how many functions the table shows, the most expensive first. The
  default is 40. It also limits the rows of the table that `--instructions` adds.
- **`--skip file.c`** leaves the functions defined in that C file out of the table and the
  shares, such as a test harness that calls the game code. It may be given more than once; a
  line above the table says how much of all cycles was left out.
- **`--instructions`** adds a second table: the CPU cycles per instruction and addressing mode,
  such as `lda dp` or `sbc long,x`, with how often each ran, the most expensive first. It shows
  which kinds of instruction the time goes to, and leaves out the files given with `--skip`.
- **`--blocks file`** also writes how often each block of the generated code ran, for
  [`build --block-counts`](#block-counts). dampf takes the blocks from the label file.

dampf knows what each instruction costs and multiplies it by the count. Calls are counted at the
callers: every `JSL` adds its count to the function it calls. The runtime's routines appear as
`(runtime) name`. With fewer than two arguments, `profile` prints its usage and exits with
status 2; otherwise it exits with 0.

## mir

Use `mir` to print the program's [MIR](/glossary#mir) or to run it in the MIR interpreter,
without building a ROM. It is a tool for working on dampf, not for building a game. It has no
usage message; its forms are:

```
dampf mir [-O] [-I dir] [-D def] file.c ...
dampf mir [-O] [--check-ranges] [-I dir] [-D def] --bench name file.c ...
```

Without `--bench`, it prints the MIR of the whole program on the standard output. `-I` and
`-D` work as for [build](#build). If the front end finds an error, `mir` prints the messages and
exits with status 1.

### -O {#mir-optimize}

Prints or runs the MIR after the optimizer's [passes](/glossary#pass) instead of the MIR as it
comes from the [AST](/glossary#ast).

### --bench {#mir-bench}

Use `--bench name` to call the function `name` in the MIR interpreter instead of printing the
MIR. It is meant for the benchmark of [Blubber Blasen](/glossary#blubber-blasen): `mir` prints
one line per [tick](/glossary#tick) in the format of [bench](#bench), with `0` for the clocks,
because the interpreter does not count cycles. The run ends when the function returns or when
the program writes to `$21FB`. If the program does not define the function, `mir` prints this
line and exits with status 2:

```
function 'nothere' is not defined
```

### --check-ranges {#check-ranges}

With `--bench`, checks every value the interpreter computes against the
[value range](/glossary#value-range) dampf inferred for it.

## bench

Use `bench` to run a benchmark ROM in dampf's own emulator: a ROM built with `build` that
reports its ticks and measurements through the ports below, as the benchmark of
[Blubber Blasen](/glossary#blubber-blasen), built from the game's `bench.c`, does. It is a tool
for working on dampf and on the benchmark:

```
usage: dampf bench <bench.sfc> [--profile counts.txt fromTick toTick]
```

It prints what the game's Mesen script `bench.lua` writes: one line per tick with the tick
number, the state, the master clocks between the start and end markers (writes to `$21FC` and
`$21FF`) and the checksums (pairs of writes to `$21FD` and `$21FE`); a write to `$21FA` ends
the tick's line. The output can go straight into the game's `report.py`.

The emulator stops after 200,000 [frames](/glossary#frame), far more than the benchmark needs,
so a program that hangs fails: `bench` then prints `the benchmark did not finish` and exits
with status 1. With other arguments than the two forms above, it prints its usage and exits
with status 2.

### --profile {#bench-profile}

Use `--profile counts.txt fromTick toTick`, right after the ROM, to record a profile for
[profile](#profile): how often each instruction in the ROM ran from tick `fromTick` to tick
`toTick`. dampf writes it to `counts.txt` with the master clocks of that stretch, and prints the
ticks as without the option.

## Messages

dampf prints a message about the source code in the form `file:line:column: severity: text`.
The file is named as on the command line, line and column count from 1, and the severity is
`error`, `warning` or `note`. Positions refer to the original source file, not to the
preprocessed text. For this file:

```c [score.c]
#include <stdint.h>

uint16_t score;

void add_points(uint16_t points)
{
    score += points;
}

void main(void)
{
    float bonus = 1.5;
    add_points(10);
}
```

`dampf build -o build/score.sfc score.c` prints:

```
score.c:12:5: error: 'float' is not supported: dampf has no floating point
```

dampf reports every error of a run, in all files, so that one error does not hide the next.
Within a top-level declaration, such as a function, the first error ends it, so there is at
most one message per declaration. For this file:

```c [scores.c]
#include <stdint.h>

uint16_t score;

void add_points(uint16_t points)
{
    float factor = 1.5;
    score += points;
}

void reset(void)
{
    double zero = 0;
    score = 0;
}

void main(void)
{
    long double big = 0;
    add_points(10);
}
```

`dampf build -o build/scores.sfc scores.c` prints:

```
scores.c:7:5: error: 'float' is not supported: dampf has no floating point
scores.c:13:5: error: 'double' is not supported: dampf has no floating point
scores.c:19:5: error: 'long' is not supported: use int32_t or uint32_t (long is 64 bit on the PC)
```

Recursion is only looked for once the front end has found no error, so `build` reports it
only for a program without other errors.

Errors of the preprocessor are messages of the same form, at the place of the error, and the
first one ends the file. For this file:

```c [hdr.c]
#include <missing.h>
void main(void) {}
```

`dampf build -o build/hdr.sfc hdr.c` prints:

```
hdr.c:1:10: error: missing.h: No such file or directory
```

An `#error` line becomes a message with its text, such as `#error no save RAM`. dampf's
preprocessor has what C99's has, as far as the subset needs it; a directive or a predefined
macro it does not have is an error that says so:

```
line.c:1:2: error: #line is not supported by dampf's preprocessor
```

## Exit status

| Command | 0 | 1 | 2 |
|---|---|---|---|
| `--version` | version printed | — | further arguments |
| `build` | ROM written | error in the program, or a budget not kept | usage error |
| `check` | every file passes, also together | a file or the files together fail | usage error |
| `parse` | no errors | at least one error | usage error |
| `profile` | table printed | — | fewer than two arguments |
| `mir` | MIR printed or run | error in the program | `--bench` function not defined, or no such source file |
| `bench` | benchmark finished | benchmark did not finish | wrong arguments |

A usage error is a missing command or one dampf does not know, an option without its value at
the end of the command line, a mistake in an [argument file](#argument-file), a source file or
folder that does not exist, and for `build` also a missing `-o`, no C file, an unknown
`--backend` or `--assembler` value or a wrong `--sram` or `--opt-limit` value. A missing header
is an error in the program. Any other unexpected failure, such as a missing `.s`
file for `--replace` or a missing profile file, ends dampf with a Java exception and status 1.
