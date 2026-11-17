# dampf

A compiler for a subset of C that produces fast code for the WDC 65816, with exactly one target:
the Super Nintendo. Its first user is Blubber Blasen (the game's stage 3, the SNES port), whose
game logic is C that also builds and is tested on the PC with gcc and clang. dampf runs as a
separate project in parallel with the game.

The name: in German, *Dampf machen* ("make steam") means to get things moving fast. It also fits
the steam factory of Blubber Blasen.

Status: milestone M1 (end to end) is done, including stepping through the C source in Mesen
(checked 2026-11-17). Milestone M2 (naive but complete) is in progress. The naive code generator
handles the C the game uses: all integer types and operators with gcc's exact semantics, control
flow, enums, arrays, structs, pointers, strings, initializers and constant data in ROM. The
execution tests run programs in Mesen and compare every result with gcc on the PC; random
programs check the arithmetic. All 16 files of the game pass the front end (`dampf check`). ROMs can have up to 4 MB: code and data are packed into
32 KB LoROM banks. Not done yet: building the whole game, the SNES platform layer, and the parts
of the design that come with the optimizer (IR levels, cycle counting, hints). The next step is
running the game's own benchmark on dampf; the plan is in [docs/roadmap.md](docs/roadmap.md).

## Why

The game's stage 3 needs a C compiler for the 65816, and the existing ones are not good enough:

| Compiler | Result |
| --- | --- |
| PVSnesLib with 816-tcc | Very slow code. The data layout needed workarounds because the compiler throws errors on ordinary code. |
| Calypsi | Works as intended, but the code is still too slow (2.72 frames per tick during play, see [docs/optimizations.md](docs/optimizations.md)). Why is being investigated; the results feed into this project. |
| llvm-mos | Good 6502 code, but no native 16-bit 65816 code generation. LLVM is a poor fit: it assumes many uniform registers and fixed-width values, while on the 65816 the operand width (8 or 16 bit) is processor state set by `REP`/`SEP`. |
| Hand-written assembly for the whole game | Tried years ago and failed. Hand-written assembly for a few hot functions stays possible later, but should not be the plan. |

We do not need a general C compiler. We need a compiler that makes SNES games fast. That is a much
smaller and more focused problem, and it lets dampf make assumptions no general compiler can:

- One CPU, one target. No portability layers.
- A subset of C we define ourselves, and a checker that enforces it, so every assumption the
  optimizer makes is guaranteed.
- The whole program is always compiled at once, so every optimization sees everything (call
  graph, all uses of every variable).
- Hints in the source (as macros) give the compiler knowledge it cannot infer.
- C is only the contract that keeps the same source compiling on the PC with gcc or clang. That is
  what makes the code testable.

## Goals and non-goals

Goals:

1. Fast 65816 code for SNES game logic: the game logic of Blubber Blasen's busiest scene fits in
   at most 60 % of a frame.
2. The same source compiles on the PC with gcc/clang and behaves **bit-identically** on both sides.
3. Source-level debugging of the C code in Mesen.
4. Readable output: the generated assembly is commented so a human can follow what each part is
   meant to do.
5. Correctness first: every optimization is checked automatically against a reference.

Non-goals:

- Full C, or C standard conformance beyond the subset.
- Other CPUs or targets. The code keeps the 65816 core and the SNES target apart, but nothing is
  built for a second target.
- An own assembler, linker or debugger. dampf uses ca65, ld65 and Mesen.
- Floating point.
- Fast compilation. A game this size compiles in seconds either way.

## Documents

- [docs/architecture.md](docs/architecture.md): the pipeline, the three IR levels, core and target, the assembly output.
- [docs/subset.md](docs/subset.md): the C subset, derived from what the game uses, and the decisions about it.
- [docs/hints.md](docs/hints.md): hints that tell dampf what a human assembly programmer would know.
- [docs/optimizations.md](docs/optimizations.md): the baseline measurements, the optimization catalogue, the gap to hand-written assembly, and what knowing the machine allows.
- [docs/timing.md](docs/timing.md): exact timing, worst-case bounds and cycle budgets.
- [docs/memory-map.md](docs/memory-map.md): where code, data, variables and pointers live.
- [docs/testing.md](docs/testing.md): the test layers and how they work.
- [docs/roadmap.md](docs/roadmap.md): milestones and their state, risks, open questions, the relation to Blubber Blasen.
- [docs/game-findings.md](docs/game-findings.md): things in Blubber Blasen's code worth changing, for whoever works on the game.
- [examples/blubberblasen/SNAPSHOT.md](examples/blubberblasen/SNAPSHOT.md): the snapshot of the game dampf is developed against.

## Prerequisites

| Tool | Used for |
| --- | --- |
| JDK 25 or newer | building and running dampf (the code targets Java 25, the current LTS release) |
| gcc | the preprocessor (`gcc -E`) and the PC side of the execution tests |
| clang | `tools/subset_scan.py` |
| ca65, ld65 (cc65 2.19) | assembling and linking the generated code (from M1 on) |
| Python 3 | `tools/subset_scan.py` |

On Arch Linux, cc65 is not in the official repositories; build it from source:

```bash
git clone --depth 1 --branch V2.19 https://github.com/cc65/cc65.git
cd cc65 && make bin && sudo make install PREFIX=/usr/local
```

Gradle comes with the wrapper; nothing to install.

## Building and testing

```bash
./gradlew build                                              # compile and run the unit tests
./gradlew test -Pmesen=/path/to/Mesen.exe                    # execution tests with another Mesen
./gradlew test -Pblubberblasen=/path/to/BlubberBlasen        # parse the live game instead of the snapshot
./gradlew installDist                                        # build/install/dampf/bin/dampf
```

The execution tests compile each program in `src/test/resources/exec/` twice: with gcc on the PC
(with the undefined behaviour sanitizer, so a test with undefined behaviour fails) and with dampf
into a ROM that runs headless in Mesen 2 (`--testrunner`). All globals must have the same values
afterwards. From WSL, the Windows `Mesen.exe` works; paths are converted with `wslpath`.

The tests use `.mesen/Mesen.exe` if it exists (not in git): unpack Mesen 2.1.1 there and allow
Lua scripts I/O access (in `settings.json`: `"Debug": { "ScriptWindow": { "AllowIoOsAccess": true } }`).
Without a Mesen the execution tests are skipped.

The game parse test uses the snapshot in `examples/blubberblasen`. To refresh the snapshot, build
the game once (so its `build/generated` is current) and run
`tools/update_game_snapshot.sh /path/to/BlubberBlasen`.

## Using it

```bash
dampf build --entry test_main -o build/examples/m1/calls.sfc examples/m1/calls.c
dampf parse -I $BB/src -I $BB/build/generated $BB/src/game/*.c
```

`build` writes the ROM and next to it the generated assembly (`.s`, with the C source line, the
processor state and a comment for each instruction), ld65's debug file (`.dbg`), a label file
and a map file. The runtime calls the entry function after start-up (`void name(void)`, default
`main`) and writes to $21FB when it returns. `parse` only preprocesses and parses.

## Debugging in Mesen

Open the ROM in Mesen 2 with the `.dbg` file next to it. In the debugger's integration settings,
let Mesen load `.dbg` files automatically. The debug file names the C sources relative to itself,
so Mesen finds them as long as the repository keeps its layout. From WSL, open the ROM through
`\\wsl.localhost\...`.

## Repository layout

```
src/main/antlr/dampf/parser/   C grammar from grammars-v4 (see its README.md)
src/main/java/dampf/parser/    support classes for the grammar (symbol table for typedef names)
src/main/java/dampf/frontend/  preprocessor call, source positions, parsing, AST builder
src/main/java/dampf/ast/       typed AST of the whole program
src/main/java/dampf/check/     subset checks (call graph, no recursion)
src/main/java/dampf/backend/   naive code generator (ca65 assembly)
src/main/java/dampf/snes/      ROM build with ca65/ld65
src/main/resources/dampf/include/  dampf's own <stdint.h>
src/main/resources/dampf/snes/     SNES runtime (start-up, header, vectors) and ld65 memory map
src/test/resources/exec/       execution test programs
examples/m1/                   example for stepping through C in Mesen
examples/blubberblasen/        snapshot of the game's code (do not edit; refresh with tools/update_game_snapshot.sh)
tools/subset_scan.py           M0 scanner: which C constructs a code base uses
docs/                          subset and optimization documents
```
