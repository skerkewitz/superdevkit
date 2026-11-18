<!-- GENERATED from doc-specs/guide/getting-started.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Getting started

This page takes you from a fresh checkout of dampf to a ROM built from `examples/m1/calls.c`,
open in [Mesen](/glossary#mesen). On the way you use every tool dampf needs once: the JDK and
gcc.

All commands run from the top of the repository. The launcher dampf builds is
`build/install/dampf/bin/dampf`; put that directory on your `PATH` if you like. The page writes
`dampf` for it.

## What you need

| Tool | Used for |
| --- | --- |
| JDK 25 or newer | building and running dampf (the code targets Java 25) |
| gcc | the preprocessor (`gcc -E`), and the PC side of the tests |
| Mesen (MesenCE) | running the ROM and stepping through the C source |

dampf runs gcc only to preprocess your source: `#include` and `#define` work as on the PC, with
dampf's own `<stdint.h>`. dampf assembles and links the ROM with its own assembler and linker.
[ca65 and ld65](/glossary#ca65-and-ld65) from cc65 2.19 are needed only to develop dampf, since
its tests build every program with them as well and compare the ROMs; the README of the
repository says how to install them.

Gradle comes with the wrapper in the repository, so there is nothing to install for it.

## Build dampf

```sh
./gradlew installDist
```

This compiles dampf and writes the launcher to `build/install/dampf/bin/dampf`. It runs no
tests. The first `./gradlew build` compiles dampf and runs its tests as well. The tests need no
Mesen: they run the generated programs in dampf's own emulator. When `.mesen/` in the repository
holds a Mesen (it is not in git), or you name one with `-Pmesen=/path/to/Mesen`, the execution
tests run each program in Mesen too. `dev/fetch_mesen.sh` puts MesenCE there, the Linux build
`Mesen` and the Windows build `Mesen.exe`; the Linux build is taken when it is there.
[Testing](/inside/testing) says how to set that up.

Next to `bin/`, `build/install/dampf/include` holds dampf's headers, `<dampf.h>` among them.
They are there for the PC build of a game, which finds them with
`gcc -idirafter build/install/dampf/include`.

## Compile a first program

The first program has three small functions and one that calls them and stores the results in
global variables:

```c [examples/m1/calls.c]
/* M1 example: open build/examples/m1/calls.sfc in Mesen and step through test_main() in the C source. */
#include <stdint.h>

uint16_t result;
uint16_t nested;
uint8_t small_result;
int8_t signed_result;

static uint16_t add3(uint16_t a, uint16_t b, uint16_t c)
{
    uint16_t s = a + b;
    s = s + c;
    return s;
}

static uint8_t inc8(int8_t x)
{
    return x + 1;               /* int arithmetic, then truncated to uint8_t */
}

static int8_t neg8(uint8_t x)
{
    return -x;                  /* -(int)x, then converted to int8_t */
}

void test_main(void)
{
    result = add3(1000, 2000, 3);
    nested = add3(add3(1, 2, 3), add3(10, 20, 30), add3(100, 200, 300));
    small_result = inc8(-1);
    signed_result = neg8(inc8(126));
}
```

Build it into a ROM:

```sh
dampf build --entry test_main -o build/examples/m1/calls.sfc examples/m1/calls.c
```

dampf answers with one line naming the ROM and the debug file. It prints full paths; here the
top of the repository is shown as `/path/to/dampf`:

```
wrote /path/to/dampf/build/examples/m1/calls.sfc and /path/to/dampf/build/examples/m1/calls.dbg
```

A ROM built by dampf has no `main` of the usual kind. The [runtime](/glossary#runtime), the
start-up code dampf links into every ROM, sets up the processor and memory and then calls one C
function, the [entry function](/glossary#entry-function). When that function returns, the
runtime writes to `$21FB` and waits in a loop. Use `--entry` to name the entry function; without
it dampf looks for `main`. The example calls its function `test_main`, so it needs the option.
Leave it out and dampf stops with:

```
examples/m1/calls.c:1:1: error: entry function 'main' is not defined
```

The entry function takes no arguments and returns nothing: `void test_main(void)`.

## What you get

`build/examples/m1/` now holds, next to `calls.sfc`:

- **`calls.s`**: the generated 65816 assembly, with the C source line and a comment for each
  instruction.
- **`calls.dbg`**: the debug file, in ld65's format, which lets Mesen show the C source while
  you step.
- **`calls.lbl`**: a label file.
- **`calls.timing.txt`**: the best and worst case of each function in CPU cycles, from entry to
  return.

dampf also leaves the runtime's assembly (`dampf_runtime.s`) there.
[The generated files](/reference/output-files) describes them all.

## Look at it in Mesen

Open `calls.sfc` in Mesen with `calls.dbg` next to it. In the debugger's
integration settings, let Mesen load `.dbg` files automatically; it then shows `calls.c` in the
debugger and you can step through `test_main` line by line in the C source. The debug file
names the C sources relative to itself, so Mesen finds them as long as the repository keeps its
layout.

When `test_main` has returned, the globals hold what gcc on the PC computes for the same code:
`result` is 3003, `nested` 666, `small_result` 0 and `signed_result` -127.

From WSL, the Windows Mesen works. Open the ROM through the `\\wsl.localhost\...` path of the
repository, so that Mesen also finds the debug file and the sources.

[Debugging in Mesen](/guide/debugging) has more on stepping through C code.

## When something goes wrong

**gcc not found.** Every command that reads C runs gcc as the preprocessor first, so without
gcc nothing compiles:

```
Exception in thread "main" java.io.IOException: Cannot run program "gcc": Exec failed, error: 2 (No such file or directory)
```

**A construct outside the subset.** dampf accepts a [subset](/glossary#subset) of C, and it
rejects anything outside it with the file, line and column and a short reason. `dampf check`
runs only the front end and is the quickest way to find such places. For a file that uses
floating point:

```c [speed.c]
float player_speed;

void test_main(void)
{
    player_speed = 1.5f;
}
```

```sh
dampf check speed.c
```

```
FAILS   speed.c:1:1: error: 'float' is not supported: dampf has no floating point
        speed.c:5:5: error: 'player_speed' is not declared
0 of 1 file(s) pass the front end
```

dampf reports every error of a run, not only the first. The second line follows from the first:
the declaration of `player_speed` is rejected, so the use in `test_main` finds no variable.
Fix the first error of a file first; errors that follow from it then go away.

[The C subset](/reference/c-subset) lists what dampf accepts, and
[Command line](/reference/command-line) describes `check` and the other commands.
