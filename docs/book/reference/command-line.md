<!-- GENERATED from doc-specs/reference/command-line.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Command line

Run dampf without arguments, or with a command it does not know, and it prints its usage and
exits with status 2:

```
usage: dampf parse [-I dir] [-D name[=value]] file.c ...
       dampf build [-I dir] [-D name[=value]] [--entry name] [--backend mir|naive] [--replace fn=file.s] -o out.sfc file.c ...
       dampf check [-I dir] [-D name[=value]] file.c ...
       dampf profile rom.sfc profile.txt [top]
```

Two more commands, `mir` and `bench`, are not in this list; they are described below. After
`./gradlew installDist` the launcher is `build/install/dampf/bin/dampf`.

The commands `build`, `check` and `parse` print everything on the standard error stream;
`profile`, `mir` and `bench` print their results on the standard output.

## build

Use `build` to compile C files into a ROM. dampf compiles the
[whole program](/glossary#whole-program) at once, so name every C file of the program in one
call:

```sh
dampf build --entry test_main -o build/examples/m1/calls.sfc examples/m1/calls.c
```

At least one C file and `-o` are required; without them dampf prints the usage and exits with
status 2. So it does, for `build`, `check` and `parse`, when `-I`, `-D`, `--entry` or `-o` is
the last argument and has no value.

On success it prints one line naming the ROM and the debug file, both as absolute paths, and
exits with status 0:

```
wrote /…/build/examples/m1/calls.sfc and /…/build/examples/m1/calls.dbg
```

With the [code generator from MIR](/glossary#code-generator-from-mir), the default, dampf also
writes `calls.timing.txt` next to the ROM, with the best and worst case of every function in CPU
cycles. The other files dampf writes next to the ROM are described in
[The generated files](/reference/output-files). If the program has an error, dampf prints its
[messages](#messages) and exits with status 1.

### -I {#option-i}

Use `-I dir` to add a directory to the search path of `#include`. The option may be given more
than once and also written joined, as `-Idir`. Each directory is passed on to the preprocessor,
`gcc -E`, which dampf runs with `-std=c99 -nostdinc -undef -D__DAMPF__=1`: the host's headers
and predefined macros stay out, so the program cannot depend on them. dampf's own include
directory, which provides `<stdint.h>`, comes before the directories you give.

### -D {#option-d}

Use `-D name` or `-D name=value` to define a macro for the preprocessor, as gcc's `-D` does. It
may be given more than once and also written joined, as `-DFACTOR=4`. dampf itself always
defines `__DAMPF__` as `1`, so a header can tell the dampf build from the
[PC build](/glossary#pc-build).

### --entry {#entry}

Use `--entry name` to name the [entry function](/glossary#entry-function), the function the
[runtime](/glossary#runtime) calls after start-up. The default is `main`. If the program does
not define it, the build stops with:

```
examples/m1/calls.c:1:1: error: entry function 'main' is not defined
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

### -o {#output}

Use `-o out.sfc` to name the ROM; it is required. All files of the build go into the directory
of the ROM, which dampf creates if it does not exist, and take its name without `.sfc` as their
base name.

## check

Use `check` to find out which files of a code base dampf accepts, before you try to build all
of them:

```sh
dampf check -I examples/blubberblasen/src -I examples/blubberblasen/generated examples/blubberblasen/src/game/*.c
```

It runs only the front end (preprocessor, parser and the checks of the subset) and handles
every file on its own, so one construct outside the [subset](/glossary#subset) does not hide the
problems of other files. For each file it prints one line: `ok` and the file name, or `FAILS`
and the first problem of that file. A last line counts the files that pass:

```
FAILS   score.c:12:5: error: 'float' is not supported: dampf has no floating point
0 of 1 file(s) pass the front end
```

A file that the preprocessor rejects (a missing header, for example) fails like any other, with
gcc's message at gcc's position, and `check` goes on with the next file.

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
does not look for the entry function; `build` does. `check` takes `-I` and `-D` as
[build](#build) does.

## parse

Use `parse` to preprocess and parse files without building the program. It takes `-I` and `-D`
as [build](#build) does. For each file it prints the messages of the parser, such as syntax
errors, and then one line with the number of files and errors:

```
2 file(s), 0 error(s)
```

The exit status is 0 without errors and 1 with at least one. `parse` does not check the subset:
a file that `parse` accepts can still fail `check` and `build`.

## profile

Use `profile` to see how many CPU cycles each function of a ROM took while it ran. Its
arguments:

```
usage: dampf profile <rom.sfc> <profile.txt> [top N] [--skip file.c ...]
```

- **`rom.sfc`** is a ROM built by dampf. The label file (`.lbl`) and the assembly (`.s`) that
  `build` wrote next to it must still be there: dampf takes the start of each function from
  them.
- **`profile.txt`** is the profile: how often each instruction in the ROM ran, one line per
  instruction with its offset in the ROM file and its count, and optionally a line
  `clocks N` with the master clocks (the SNES's base clock, 357,366 per frame) of the measured
  stretch. [Mesen](/glossary#mesen) writes it with the Lua
  script `tools/profile.lua`; [`dampf bench --profile`](#bench-profile) writes the same format
  from dampf's own emulator.
- **`top`** is a number: how many functions the table shows, the most expensive first. The
  default is 40.
- **`--skip file.c`** leaves the functions defined in that C file out of the table and the
  shares, such as a test harness that calls the game code. It may be given more than once; a
  line above the table says how much of all cycles was left out.

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

Use `bench` to run the benchmark ROM of [Blubber Blasen](/glossary#blubber-blasen), built from
the game's `bench.c` with `build`, in dampf's own emulator. It is a tool for working on dampf
and on the benchmark:

```
usage: dampf bench <bench.sfc> [--profile counts.txt fromTick toTick]
```

It prints what the game's Mesen script `bench.lua` writes: one line per tick with the tick
number, the state, the master clocks between the start and end markers (writes to `$21FC` and
`$21FF`) and the checksums (pairs of writes to `$21FD` and `$21FE`). The output can go straight
into the game's `report.py`.

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

Errors of the preprocessor are messages of the same form: dampf takes gcc's first error and its
position, as for a missing header:

```
hdr.c:1:10: error: missing.h: No such file or directory
```

Where gcc gives no position, as for a C file that does not exist, the message is at line 1,
column 1 of the file, and its text starts with `preprocessing failed:` and gcc's first line.

## Exit status

| Command | 0 | 1 | 2 |
|---|---|---|---|
| `build` | ROM written | error in the program | usage error |
| `check` | every file passes, also together | a file or the files together fail | usage error |
| `parse` | no errors | at least one error | usage error |
| `profile` | table printed | — | fewer than two arguments |
| `mir` | MIR printed or run | error in the program | `--bench` function not defined |
| `bench` | benchmark finished | benchmark did not finish | wrong arguments |

A usage error is a missing command or one dampf does not know, an option without its value at
the end of the command line, and for `build` also a missing `-o`, no C file or an unknown
`--backend` value. A missing C file or header is an error in the program. Any other unexpected
failure, such as a missing `.s` file for `--replace` or a missing profile file, ends dampf with
a Java exception and status 1.
