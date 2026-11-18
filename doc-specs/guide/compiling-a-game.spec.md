---
title: Compiling a game
sources:
  - README.md
  - src/main/java/dampf/Main.java
  - src/main/java/dampf/Compiler.java
  - src/main/java/dampf/tools/Profile.java
  - snapshots/blubberblasen/SNAPSHOT.md
  - dev/bench_game.sh
  - dev/profile_game.sh
  - lib/snes/README.md
audience: Users moving from one example file to the many files of a real game
---

## Purpose

Show how a game of many files is compiled with dampf, with the snapshot of Blubber Blasen as the
example: include directories, defines, the entry function, finding what does not fit the subset,
and measuring where the cycles go.

## Sections

1. **Many files, one call** – all `.c` files on one command line, `-I` for include directories
   and `-D` for defines, the entry function. A `dampf build` call for the snapshot's benchmark
   (as `dev/bench_game.sh` builds it), and what it prints. Before it, the shortest call for
   a game of the reader's own (its `.c` files, `-I`, `-o`, the entry function by default
   `main`), with the launcher from [Getting started](/guide/getting-started).
2. **Find what does not fit first** – `dampf check` over the game's files before the first
   build, and what to do with each report (change the code, keeping the PC build working).
3. **Where the time goes** – `dampf profile`: what it reads and what it prints, cycles per
   function, with a short excerpt of real output. Mention that `dev/profile_game.sh` does the
   whole round for the snapshot.
4. **Replacing a function with assembly** – `--replace fn=file.s`, what it is for (comparing a
   hand-written function with dampf's, as `reference/` does), and that it switches to the naive
   code generator unless `--backend` says otherwise.

## Pay attention to

- Commands run from the top of the repository on `snapshots/blubberblasen`; run them and copy
  what they print, cut where long.
- The snapshot is not edited; say that a reader compiling their own game passes its paths.
- The game's own build and its PVSnesLib calls are not part of this page; the benchmark stubs
  them out (`SNAPSHOT.md`). Say in one sentence that a ROM of a game needs a
  [platform layer](/glossary#platform-layer) for the library it calls, compiled with it.
- Budgets and the timing file come from the code generator from MIR; say so where `--replace`
  switches to the naive one.

## Out of scope

- Every option: [Command line](/reference/command-line).
- How the benchmark and the replays check dampf against the PC: [Testing](/inside/testing).
