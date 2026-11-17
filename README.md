# dampf

A compiler for a subset of C that makes fast 65816 code for the Super Nintendo. Its first user is
Blubber Blasen, whose game code is C that also builds on the PC with gcc and
clang. Why it exists and how it is meant to work is in [kickoff.md](kickoff.md).

Status: milestone M0 (define the subset). The front end preprocesses and parses all of Blubber
Blasen's `src/game`; there is no code generator yet.

## Documents

- [kickoff.md](kickoff.md): goals, design, milestones, risks.
- [docs/subset.md](docs/subset.md): the C subset, derived from what the game uses.
- [docs/optimizations.md](docs/optimizations.md): the baseline measurements and the optimization catalogue.

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
./gradlew test -Pblubberblasen=/path/to/BlubberBlasen        # also parse every file of the game
./gradlew installDist                                        # build/install/dampf/bin/dampf
```

The game test needs the game's generated sources (`build/generated/gfx_data.h`), so build the
game once first.

## Using it

```bash
dampf parse -I $BB/src -I $BB/build/generated $BB/src/game/*.c
```

This is all the command line does so far: preprocess and parse, and report syntax errors at
their original file and line.

## Repository layout

```
src/main/antlr/dampf/parser/   C grammar from grammars-v4 (see its README.md)
src/main/java/dampf/parser/    support classes for the grammar (symbol table for typedef names)
src/main/java/dampf/frontend/  preprocessor call, source positions, parsing
src/main/resources/dampf/include/  dampf's own <stdint.h>
tools/subset_scan.py           M0 scanner: which C constructs a code base uses
docs/                          subset and optimization documents
```
