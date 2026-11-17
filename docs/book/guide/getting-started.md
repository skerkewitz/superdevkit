<!-- GENERATED from doc-specs/guide/getting-started.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Getting started

This page takes you from a fresh checkout of dampf to a ROM built from `examples/m1/calls.c`,
open in Mesen. On the way you use every tool dampf needs once: the JDK, gcc, ca65 and ld65.

All commands run from the top of the repository. The launcher dampf builds is
`build/install/dampf/bin/dampf`; put that directory on your `PATH` if you like. The page writes
`dampf` for it.

## What you need

| Tool | Used for |
| --- | --- |
| JDK 25 or newer | building and running dampf (the code targets Java 25) |
| gcc | the preprocessor (`gcc -E`), and the PC side of the tests |
| ca65, ld65 (cc65 2.19) | assembling and linking the generated code into a ROM |
| Mesen 2 | running the ROM and stepping through the C source |

dampf runs gcc only to preprocess your source: `#include` and `#define` work as on the PC, with
dampf's own `<stdint.h>`. [ca65 and ld65](/glossary#ca65-and-ld65) come from the cc65 suite. Some
distributions do not package cc65 (Arch Linux has it only outside the official repositories);
build it from source there:

```sh
git clone --depth 1 --branch V2.19 https://github.com/cc65/cc65.git
cd cc65 && make bin && sudo make install PREFIX=/usr/local
```

Gradle comes with the wrapper in the repository, so there is nothing to install for it.

## Build dampf

```sh
./gradlew installDist
```

This compiles dampf and writes the launcher to `build/install/dampf/bin/dampf`. It runs no
tests. The first `./gradlew build` compiles dampf and runs its tests as well. The tests need no
Mesen: they run the generated programs in dampf's own emulator. When `.mesen/Mesen.exe` exists
in the repository (it is not in git), or you name one with `-Pmesen=/path/to/Mesen.exe`, the
execution tests run each program in Mesen too. [Testing](/inside/testing) says how to set that
up.

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

- **`calls.s`**: the generated 65816 assembly, with the C source line, the processor state and a
  comment for each instruction.
- **`calls.dbg`**: ld65's debug file, which lets Mesen show the C source while you step.
- **`calls.lbl`**: a label file.
- **`calls.map`**: the map file ld65 writes when it links the ROM.

dampf leaves a few more intermediate files there, such as the runtime's assembly
(`dampf_runtime.s`) and the object files. [The generated files](/reference/output-files)
describes them all.

## Look at it in Mesen

Open `calls.sfc` in [Mesen](/glossary#mesen) with `calls.dbg` next to it. In the debugger's
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

**ca65 or ld65 not found.** dampf runs both to make the ROM. If one is missing from your `PATH`,
the build stops with a Java exception that starts like this:

```
Exception in thread "main" java.io.IOException: Cannot run program "ca65" (in directory "/path/to/dampf/build/examples/m1"): Exec failed, error: 2 (No such file or directory)
```

Install cc65 as in [What you need](#what-you-need) and check that `ca65` and `ld65` run from the
shell.

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
0 of 1 file(s) pass the front end
```

[The C subset](/reference/c-subset) lists what dampf accepts, and
[Command line](/reference/command-line) describes `check` and the other commands.
