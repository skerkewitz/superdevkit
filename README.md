# dampf

A compiler for a subset of C that makes fast 65816 code for the Super Nintendo. Its first user is
Blubber Blasen, whose game code is C that also builds on the PC with gcc and
clang. Why it exists and how it is meant to work is in [kickoff.md](kickoff.md).

Status: milestone M1 (end to end) is done, including stepping through the C source in Mesen
(checked 2026-11-17). Milestone M2 (naive but complete) is in progress. The naive code generator handles the C
the game uses: all integer types and operators with gcc's exact semantics, control flow, enums,
arrays, structs, pointers, strings, initializers and constant data in ROM. The execution tests run
programs in Mesen and compare every result with gcc on the PC; random programs check the
arithmetic. 15 of the game's 16 files pass the front end (`dampf check`); the last one needs a
small fix in the game. Not done yet: ROMs larger than 32 KB, the SNES platform layer, and building
the whole game.

## Documents

- [kickoff.md](kickoff.md): goals, design, milestones, risks.
- [docs/subset.md](docs/subset.md): the C subset, derived from what the game uses.
- [docs/optimizations.md](docs/optimizations.md): the baseline measurements and the optimization catalogue.
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
