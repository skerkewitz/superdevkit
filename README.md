# dampf

![dampf](assets/Dampf.jpg)

A compiler for a subset of C that produces fast code for the WDC 65816, with exactly one target:
the Super Nintendo. Its first user is Blubber Blasen (the game's stage 3, the SNES port), whose
game logic is C that also builds and is tested on the PC with gcc and clang. dampf runs as a
separate project in parallel with the game.

The name: in German, *Dampf machen* ("make steam") means to get things moving fast. It also fits
the steam factory of Blubber Blasen.

Status: milestones M0 to M2 are done, M3 (the optimizer) is in progress, and M6 (dampf's own
assembler and linker) is done ahead of M5; the state of each
milestone is in [docs/roadmap.md](docs/roadmap.md). dampf compiles all of Blubber Blasen and
builds a playable ROM of it, with the game's console API written in C in
[platform/snes](platform/snes/README.md) (sound and save RAM are not built yet). During play the
game takes 0.91 frames per tick, and dampf's code is 1.81 times as fast as Calypsi's on the same
scene; the current figures are in
[docs/optimizations.md](docs/optimizations.md#where-things-stand). dampf computes a best and a
worst case in CPU cycles for every function, and a function over its cycle budget
(`DAMPF_BUDGET`) fails the build ([docs/timing.md](docs/timing.md)). The naive code generator,
straight from the AST, stays as an independent reference (`--backend naive`): every test program
is built with both and compared with gcc on the PC.

## Why

The game's stage 3 needs a C compiler for the 65816, and the existing ones are not good enough:

| Compiler | Result |
| --- | --- |
| PVSnesLib with 816-tcc | Very slow code. The data layout needed workarounds because the compiler throws errors on ordinary code. |
| Calypsi | Works as intended, but the code is still too slow: 2.72 frames per tick during play on an older version of the game. The comparison with dampf on today's version is in [docs/optimizations.md](docs/optimizations.md#where-things-stand). |
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
6. In the end, nothing to install but Java. dampf assembles and links with its own assembler and
   linker (milestone M6); gcc is still needed for preprocessing until dampf has its own
   preprocessor (M5).

Non-goals:

- Full C, or C standard conformance beyond the subset.
- Other CPUs or targets. The code keeps the 65816 core and the SNES target apart, but nothing is
  built for a second target.
- An own debugger. dampf uses Mesen.
- Floating point.
- Fast compilation. A game this size compiles in seconds either way.

## Documents

The user book is in [docs/book](docs/book) (`npm install`, then `npm run docs:dev`); its pages
are generated from the specs in [doc-specs](doc-specs/README.md). The documents below are the
working documents it is written from.

- [docs/architecture.md](docs/architecture.md): the pipeline, the three IR levels, core and target, the assembly output.
- [docs/subset.md](docs/subset.md): the C subset, derived from what the game uses, and the decisions about it.
- [docs/hints.md](docs/hints.md): hints that tell dampf what a human assembly programmer would know.
- [docs/subrange-types.md](docs/subrange-types.md): design proposal for subrange types (`DAMPF_RANGED`) and inferred value ranges; what is built of it is in optimizations.md.
- [docs/optimizations.md](docs/optimizations.md): where things stand, the optimization catalogue, the gap to hand-written assembly.
- [docs/optimizations-log.md](docs/optimizations-log.md): dated measurements of each optimization step.
- [docs/timing.md](docs/timing.md): exact timing, worst-case bounds and cycle budgets.
- [docs/transition-to-lir.md](docs/transition-to-lir.md): the way from the old code generator to LIR and to machine code without ca65 and ld65.
- [docs/memory-map.md](docs/memory-map.md): where code, data, variables and pointers live.
- [docs/testing.md](docs/testing.md): the test layers and how they work.
- [docs/roadmap.md](docs/roadmap.md): milestones and their state, risks, open questions, the relation to Blubber Blasen.
- [docs/game-findings.md](docs/game-findings.md): things in Blubber Blasen's code worth changing, for whoever works on the game.
- [tools/README.md](tools/README.md): the scripts in `tools/`.
- [examples/blubberblasen/SNAPSHOT.md](examples/blubberblasen/SNAPSHOT.md): the snapshot of the game dampf is developed against.

## Prerequisites

To use dampf: JDK 25 or newer and gcc. To develop it, also ca65 and ld65 from cc65 2.19
(`BackendOutputTest` builds the execution tests, 20 random programs and the game with them as
well and compares the ROMs) and Python 3. Mesen 2 is
optional (the tests then also run there), clang is needed only for `tools/subset_scan.py`,
Node.js only for the book. Gradle comes with the wrapper. The book's
[getting started](docs/book/guide/getting-started.md) page has what a user needs.

On Arch Linux, cc65 is not in the official repositories; build it from source:

```bash
git clone --depth 1 --branch V2.19 https://github.com/cc65/cc65.git
cd cc65 && make bin && sudo make install PREFIX=/usr/local
```

## Building and testing

```bash
./gradlew build                          # compile and run the tests
./gradlew installDist                    # the launcher: build/install/dampf/bin/dampf
./gradlew test -Preplays                 # also play the game's replays
./gradlew test -Prandom=500 -PrandomSeed=1   # more random programs, from a given seed
./gradlew test -Pcoverage                # JaCoCo: build/reports/jacoco/test/html/index.html
./gradlew pmdMain pmdTest                # PMD: build/reports/pmd/ (not a gate)
./gradlew cpd                            # copied code: build/reports/cpd/main.txt
tools/bench_game.sh                      # the game's benchmark: checksums and cycles
tools/build_game_rom.sh                  # the playable ROM: build/game/blubberblasen.sfc
```

The tests run in dampf's own emulator (`dampf.emu`) and, when `.mesen/Mesen.exe` exists (not in
git), headless in Mesen 2 as well: unpack Mesen 2.1.1 there and allow Lua scripts I/O access
(in `settings.json`: `"Debug": { "ScriptWindow": { "AllowIoOsAccess": true } }`). From WSL, the
Windows `Mesen.exe` works. All Gradle switches are listed in the book's
[testing](docs/book/inside/testing.md) page, the scripts in [tools/README.md](tools/README.md).

## Using it

```bash
dampf build --entry test_main -o build/examples/m1/calls.sfc examples/m1/calls.c
dampf parse -I $BB/src -I $BB/build/generated $BB/src/game/*.c
dampf check -I $BB/src -I $BB/build/generated $BB/src/game/*.c
```

The launcher is `build/install/dampf/bin/dampf` (`./gradlew installDist`).

`build` writes the ROM and next to it the generated assembly (`.s`, with the C source line and a
comment for each instruction; the naive code also shows the processor state), the debug file
(`.dbg`, in ld65's format, which Mesen reads) and a label file. `--assembler ca65` assembles and
links with ca65 and ld65 instead of dampf's own assembler and linker (the same ROM; ld65 also
writes a map file). The runtime calls the entry function after start-up (`void name(void)`, default
`main`) and writes to $21FB when it returns. With the code generator from MIR it also writes the
timing report (`.timing.txt`). `--backend naive` picks the naive code generator, `--slowrom`
builds for a SlowROM cartridge (FastROM is the default), `--replace fn=file.s` links a
hand-written function ([reference](reference/README.md)) and `--block-counts` lays out the
blocks by a profile. `parse` only preprocesses and parses. `check` runs the front end on every
file alone, then on all of them together (recursion, functions defined nowhere or twice).
`dampf mir`, `dampf profile` and `dampf bench` are the tools behind the scripts in `tools/`.

## Debugging in Mesen

Open the ROM in Mesen 2 with the `.dbg` file next to it. In the debugger's integration settings,
let Mesen load `.dbg` files automatically; it then shows the C source. The debug file names the C
sources relative to itself, so keep the repository's layout. From WSL, open the ROM through
`\\wsl.localhost\...`. The book's [debugging](docs/book/guide/debugging.md) page shows how to
step through C.

## Repository layout

```
src/main/antlr/dampf/parser/   C grammar from grammars-v4 (see its README.md)
src/main/java/dampf/           Main (the command line) and Compiler (the pipeline)
src/main/java/dampf/parser/    support classes for the grammar (symbol table for typedef names)
src/main/java/dampf/frontend/  preprocessor call, source positions, parsing, AST builder
src/main/java/dampf/ast/       typed AST of the whole program
src/main/java/dampf/check/     subset checks (call graph, no recursion)
src/main/java/dampf/mir/       MIR: lowering from the AST, the passes, the interpreter
src/main/java/dampf/backend/   code generators (from MIR, and the naive one from the AST), timing
src/main/java/dampf/lir/       LIR: 65816 code in basic blocks, its text (ca65 source), verifier, passes
src/main/java/dampf/asm/       dampf's assembler and linker, and the debug file
src/main/java/dampf/target/    the 65816's cycle table
src/main/java/dampf/snes/      ROM build (memory map, bank packing, header; with dampf's linker or ld65)
src/main/java/dampf/emu/       dampf's own 65816 and SNES emulator
src/main/java/dampf/tools/     dampf mir, dampf bench, dampf profile
src/main/java/dampf/util/      running external programs
src/main/resources/dampf/include/  dampf's own <stdint.h> and <dampf.h>
src/main/resources/dampf/snes/     SNES runtime (start-up, header, vectors)
src/test/java/dampf/           unit, execution, random, replay and emulator tests; book source checks
src/test/resources/exec/       execution test programs
examples/m1/                   example for stepping through C in Mesen
examples/blubberblasen/        snapshot of the game's code (do not edit; refresh with tools/update_game_snapshot.sh)
platform/snes/                 the game's console API in C, for the playable ROM
reference/                     hand-written assembly of hot functions, the yardstick for dampf's code
tools/                         scripts: benchmark, profile, playable ROM, snapshot, subset scan (see its README.md)
tools/replay/                  the driver that plays the game's recordings (ReplayTest)
tools/subset_scan.py           M0 scanner: which C constructs a code base uses
config/pmd/                    PMD rules (./gradlew pmdMain pmdTest)
assets/                        the logo
docs/                          the working documents (see Documents)
docs/book/                     the user book (VitePress), mostly generated from doc-specs/
doc-specs/                     what each page of the book covers, and its sources
```

## Licence

dampf is under the Apache License v2.0 with LLVM Exceptions, the same licence as LLVM
(SPDX: `Apache-2.0 WITH LLVM-exception`); see [LICENSE](LICENSE) and [NOTICE](NOTICE). The
exception means that code dampf puts into a compiled program carries no licence conditions: games
built with dampf can use any licence. Third-party parts (the C grammar from grammars-v4) keep their
own licence, listed in NOTICE.
