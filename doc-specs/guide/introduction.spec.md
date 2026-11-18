---
title: What is dampf?
sources:
  - docs/ADR.md
  - docs/adr/*.md
  - README.md
  - docs/roadmap.md
  - docs/optimizations.md
  - platform/snes/README.md
audience: Game programmers deciding whether dampf fits their SNES project
---

## Purpose

Tell a reader in one page what dampf is, what problem it solves, how far it is, and whether it
is for them. A reader who stops after this page knows whether to read on.

## Sections

The page opens, before the first H2, with two or three sentences: a compiler for a subset of C
that produces fast code for the 65816, with the Super Nintendo as its only target, and the same
source compiles and behaves bit for bit the same with gcc on the PC.

1. **Why another C compiler** – the existing options for C on the SNES and why they were not
   enough (the README's table, in prose or as a table), and the narrower problem dampf solves
   instead: fast SNES game code, not general C.
2. **What dampf assumes** – the assumptions no general compiler can make: one CPU, a subset it
   defines and checks, the whole program at once, hints in the source, C as the contract with
   the PC build. For each, one sentence on what it buys.
3. **The PC build is the reference** – why the same source compiling with gcc matters: the game
   logic is tested on the PC, and dampf's result must match it bit for bit. Link
   [Writing C for dampf](/guide/writing-c).
4. **Goals and non-goals** – both lists from the README, the goals with a sentence each.
5. **Where it stands** – what is done and what is not, from the README's status and the
   roadmap's milestones, with the latest frames-per-tick figure for Blubber Blasen against the
   naive code and Calypsi. Link [Roadmap](/inside/roadmap) and [Limits](/reference/limits).
6. **Where to go next** – one line each to [Getting started](/guide/getting-started), the
   [reference](/reference/command-line) and the [inside guide](/inside/how-a-compile-works).

## Pay attention to

- The name: say in one sentence what *Dampf machen* means, as the README does.
- Blubber Blasen is the first user; name it, and say it is a game whose logic is C that also
  builds on the PC. Do not describe the game beyond that.
- dampf builds a playable ROM of the game (the SNES platform layer); say so in *Where it
  stands*, with what is not built there (sound, save RAM).
- Whether dampf fits the reader's own game: its logic must also build with gcc on the PC, and
  the calls it makes into PVSnesLib or another library need a
  [platform layer](/glossary#platform-layer) written in C, as Blubber Blasen's is. Say both
  plainly, in *What dampf assumes* or *Where it stands*.
- Give the unit with the first figure (frames per tick), before or in the table.

## Out of scope

- How to install or run it: [Getting started](/guide/getting-started).
- The subset in detail, the optimizations in detail.
