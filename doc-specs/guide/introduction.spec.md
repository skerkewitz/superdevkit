---
title: What is dampf?
sources:
  - lib/README.md
  - docs/ADR.md
  - docs/adr/*.md
  - README.md
  - docs/roadmap.md
  - docs/history.md
  - docs/optimizations.md
  - lib/snes/README.md
  - tools/README.md
audience: Game programmers deciding whether dampf fits their SNES project
---

## Purpose

Tell a reader in one page what dampf is, what problem it solves, how far it is, and whether it
is for them. A reader who stops after this page knows whether to read on.

## Sections

The page opens, before the first H2, with two or three sentences: a compiler for a subset of C
that produces fast code for the 65816, with the Super Nintendo as its only target, and the same
source compiles and behaves bit for bit the same with gcc on the PC. Then a sentence or two on
superdevkit, the kit dampf is the compiler of: a game is written once against its console API and
built for the SNES with dampf, and for Linux, Windows and the web with the PC's C compiler, on
Rachael. Link [The superdevkit command](/reference/superdevkit) and [The web](/guide/the-web).

1. **Why another C compiler** – the existing options for C on the SNES and why they were not
   enough: the README's sentence on the compilers (Calypsi makes faster code than 816-tcc,
   PVSnesLib's compiler, and dampf's is faster than Calypsi's) and its table of the other ways,
   in prose or as a table; then the narrower problem dampf solves instead: fast SNES game code,
   not general C.
2. **What dampf assumes** – the assumptions no general compiler can make: one CPU, a subset it
   defines and checks, the whole program at once, hints in the source, C as the contract with
   the PC build. For each, one sentence on what it buys.
3. **The PC build is the reference** – why the same source compiling with gcc matters: the game
   logic is tested on the PC, and dampf's result must match it bit for bit. Link
   [Writing C for dampf](/guide/writing-c).
4. **Goals and non-goals** – both lists from the README, the goals with a sentence each.
5. **Where it stands** – what is done and what is not, from the README's status and the
   roadmap's milestones, with the latest frames-per-tick figure for Blubber Blasen against the
   naive code. Link [Roadmap](/inside/roadmap) and [Limits](/reference/limits).
6. **Where to go next** – one line each to [Getting started](/guide/getting-started), the
   [reference](/reference/command-line) and the [inside guide](/inside/how-a-compile-works).

## Pay attention to

- The name: say in one sentence what *Dampf machen* means, as the README does.
- Blubber Blasen is the first user; name it, and say it is a game whose logic is C that also
  builds on the PC. Do not describe the game beyond that.
- dampf builds a playable ROM of the game (the SNES platform layer); say so in *Where it
  stands*, with what the kit's library has besides (sound effects and save RAM on every target,
  music on the desktop and the web only).
- Whether dampf fits the reader's own game: its logic must also build with gcc on the PC, and
  it reaches the console only through the kit's console API (`lib/include/sdevkit/snes.h`,
  PVSnesLib's calls renamed), which `lib/snes` implements on the SNES as the
  [platform layer](/glossary#platform-layer) and Rachael on the desktop and the web. A game
  written against PVSnesLib converts its names with `tools/sdevkit_rename.py`; one that calls
  another library needs a platform layer of its own. Say both plainly, in *What dampf assumes*
  or *Where it stands*, and link [The console API](/reference/console-api).
- Give the unit with the first figure (frames per tick), before or in the table.
- Calypsi and 816-tcc get that one sentence and nothing more: no figure of theirs (frames per
  tick, cycles, "times as fast"), even where the sources still have one. The kit no longer uses
  or measures them.

## Out of scope

- How to install or run it: [Getting started](/guide/getting-started).
- The subset in detail, the optimizations in detail.
