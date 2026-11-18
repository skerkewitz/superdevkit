<!-- GENERATED from doc-specs/inside/roadmap.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->

# Roadmap

dampf is built in milestones, each measured against [Blubber Blasen](/glossary#blubber-blasen),
the game it is developed for. The goal of the whole project is that the game logic of the
game's busiest scene fits in at most 60 % of a [frame](/glossary#frame). This page says where
dampf stands, what comes next and what is still open. Unlike the other pages of the book, it
also describes plans. What is not built yet, listed for users, is on
[Limits](/reference/limits).

## Where dampf stands

dampf is at version 0.5.0, in work; M0 to M4 and M6 were concluded with 0.4.0 on 2026-11-18. It
compiles all of Blubber Blasen, gives the results of the [PC build](/glossary#pc-build) in the
game's benchmark and replays, on the SNES and in a playable ROM, and assembles and links the ROM
itself. While the game is played it takes 0.79 frames per [tick](/glossary#tick) against a goal
of 0.6, and on the same scene dampf's code is 2.14 times as fast as
[Calypsi](/glossary#calypsi)'s.

dampf is the compiler of superdevkit, a development kit for SNES games that this repository
holds until it is published. M5 is in work, as version 0.5.0: Blubber Blasen builds its ROM
with the dev kit, from its own repository.

## Versions

The version is the dev kit's. It is set in one place, `build.gradle.kts`, and `dampf --version`
prints it. Milestone N is released as version 0.N.0, and fixes between two milestones raise the
last number. M6 was done ahead of M5 and is part of 0.4.0, so there is no 0.6.0.

Every version before 1.0 is a pre-release: the [subset](/glossary#subset), the command line and
the files dampf writes may change from one version to the next. Publishing is a step of its
own and not scheduled yet (see [Publishing](#publishing-not-scheduled)), so 0.5.0, the end of
M5, is not a public version. What 1.0 needs is not decided.

Versions follow the milestones rather than what changed, as semantic versioning would have
them, because before 1.0 nearly every milestone breaks something, and such numbers would say
little more than the milestone does. A milestone's version is therefore known before it starts.
If a milestone is split or merged, the numbers of the ones after it change; numbers already
released stay.

## Milestones

| Milestone | Goal | Version | State |
| --- | --- | --- | --- |
| M0 | Define the subset of C from what the game uses | 0.4.0 | Done 2026-11-17 |
| M1 | End to end: a small function compiled, assembled, linked, run in a test, stepped through in [Mesen](/glossary#mesen) | 0.4.0 | Done 2026-11-17 |
| M2 | Naive but complete: all of Blubber Blasen compiles, the benchmark and the replays give the PC's checksums, cycles measured and compared with Calypsi | 0.4.0 | Done 2026-11-17 |
| M3 | The big levers: [MIR](/glossary#mir), [LIR](/glossary#lir) and the [code generator from MIR](/glossary#code-generator-from-mir), static timing and budgets, [value ranges](/glossary#value-range) and [pointer compression](/glossary#pointer-compression); faster than Calypsi | 0.4.0 | Done 2026-11-18 |
| M4 | Fine tuning, started early: the block layout by a profile, peephole rules, loop and branch optimizations | 0.4.0 | Done 2026-11-18 |
| M6 | Standing alone: dampf's own assembler and linker | 0.4.0 | Done 2026-11-18 |
| M5 | Blubber Blasen builds its ROM with the dev kit, from its own repository | 0.5.0 | In work |
| M7 | The optimizer: what was left of M3, register allocation first | 0.7.0 | Planned |
| M8 | Language, platform and usability: C in interrupts, [intrinsics](/glossary#intrinsic), the open subset decisions, dampf's own preprocessor | 0.8.0 | Planned |
| M9 | Timing and debugging: [master clocks](/glossary#master-clock), bounds for more loops, locals and types in Mesen | 0.9.0 | Planned |
| M10 | Fine tuning: what was left of M4, until the frame budget is met | 0.10.0 | Planned |

M3 and M4 were concluded at version 0.4.0 although only part of their goals was reached: dampf
is faster than Calypsi, but the frame budget is not met yet. Too much had stayed open in them
while too little was planned after them, so what was left moved to M7 and M10. The order after
M5 follows the likely gain and what each milestone needs from the one before; it is revised
when M5 is done.

### M5: Blubber Blasen builds with the dev kit

M5 is done when Blubber Blasen builds its SNES ROM from its own repository with a local install
of the dev kit, and that ROM plays the game's recordings with the PC's checksums. No release is
needed for that while the repository is private. Its work falls into five parts:

- **Correctness first**, since the game will rely on the code. The miscompilation described
  under [Risks and open questions](#risks-and-open-questions) is found and fixed, with an
  execution test. The random programs are made to reach the optimizer, with loops over arrays of
  structs, pointers into arrays, byte stores in a row, 8.8 fixed-point values and fewer
  divisions, and more execution tests cover the patterns the optimizer looks for; there are 29
  today. The playable ROM runs on a real console or a flash cartridge, and the margin of 8 lines
  that the [platform layer](/glossary#platform-layer) leaves for the code around a DMA copy is
  measured again, since a copy that starts after the
  [vertical blank](/glossary#vertical-blank) is lost.
- **A compiler a game can call.** dampf reports all errors of a run, not only the first. Its
  options can be kept in an argument file, given by `--config` or `@file`, in which a folder
  stands for the `.c` files directly in it, so that a game checks in one file per build. dampf
  ships a `<string.h>` with `memset` and `memcpy`, written in C for now. The install carries the
  library and the tools as well as the compiler, and the game refers to it by its folder.
- **The dev kit in this repository.** Done: the platform layer has become the dev kit's
  library in `lib/snes`, and the console API the dev kit owns is `lib/include/snes/snes.h`,
  taken over from the game with the same declarations. A table shows per function and platform
  whether it is built or a stub, and a test checks that every function of the API exists in
  `lib/snes` and in the table. The main loop (`lib/snes/main.c`) is the library's, and the frame
  rhythm it calls (`game_init`, `game_tick`, `game_vblank`, the pad bits) is declared in
  `lib/include/snes/loop.h`. Done: the scripts are now in `dev/`, for developing the dev kit.
  The planned split into `tools/`, for game developers, and `dev/` found nothing for other
  games: the check in Mesen plays Blubber Blasen with scripted input, and the replay converter
  reads the game's format, so `tools/` comes back with the first tool for game developers.
  Done: the snapshot of Blubber Blasen has moved to `snapshots/blubberblasen`. Examples become
  projects of their own, each with a README, an argument file, its sources and its own build
  folder; the first is `examples/m1`.
- **The book.** One book serves the whole dev kit. First the existing pages move into folders
  for dampf's part, `reference/compiler/` and `inside/compiler/`; then the user guide becomes
  the dev kit's, with getting started from the first example project, and the reference gets
  the console API with its table of built and stub per platform, in `reference/api/`.
- **The game's side**, done in the game's own repository: a build script for the dev kit next to
  the PVSnesLib one, with an argument file, and the game's data as plain C.

### After M5

**M7, the optimizer**, takes up what was left of M3, in the order of the plan of 2026-11-17,
which estimated the likely gain on play from the shares of the cycles in a profile. The gains
are estimates, not measurements, and several ideas that looked good measured nothing. First
comes register allocation, keeping values in A, X and Y over several instructions instead of a
[direct page](/glossary#direct-page) place each, estimated at 10 to 20 %. It is the largest
lever and the hardest: loads and stores of direct page values are the largest share of the
cycles, and with one accumulator the hard part is choosing what lives where. Today one pass per
pattern does part of it, and a common model of what lives where comes first. Splitting arrays of
structs into one array per field (5 to 10 % in the loops over the bubbles) needs a decision on
the subset or a [hint](/glossary#hint), since the layout in memory then differs from the PC's,
and HIR, a level above MIR that is built only when something needs it. Then come
[8-bit stretches](/glossary#_8-bit-and-16-bit-mode) with the [`M` flag](/glossary#m-and-x-flags)
followed across blocks, 8-bit index registers with arrays aligned to pages, and a calling
convention that passes the first argument in A. 16-bit pointers into RAM and enum objects in one
byte follow once their subset decisions are taken, and constant shifts by a multiple of 8
become byte moves: a 32-bit shift by 16 is a loop of 16 steps today, about 600 cycles. The rest
of the way to the goal has to come from the game doing less work, as the checkpoint after M2
concluded.

**M8, language, platform and usability**, starts with C in interrupt handlers. dampf keeps the
frames of functions at fixed places, so C called from an NMI or IRQ would overwrite the frames
of the code it interrupted, and the platform layer waits for the vertical blank instead. Nearly
every SNES game has an NMI handler, and raster effects need an IRQ handler, so the functions an
interrupt reaches are to get a frame area of their own; how a handler is declared and what it
saves is open. M8 also brings intrinsics for the hardware multipliers, the divider and DMA, the
open decision on the subset whether plain `char` is for text only, function pointers in
constant tables, hand-written assembly with the code generator from MIR (`--replace` works only
with the [naive code generator](/glossary#naive-code-generator) today), and less memory for a
compile, which takes 1.8 GB for the game. dampf gets its own C preprocessor, so that using it
needs nothing but Java; today users on Windows install MinGW or MSYS only for `gcc -E`, which
stays in the tests as the oracle.

**M9, timing and debugging**, counts the timing report, the assembly comments and the budgets
in master clocks instead of CPU cycles, names the costliest path per C line in the report and in
budget errors, and finds bounds for more loops: 37 of the game's functions have no worst case
today. Frame-level timing adds DRAM refresh, HDMA and interrupts. Mesen shows the C source and
the names of global variables from dampf's debug file, and nothing more; dampf's users would
gain from variables shown with their C type, struct fields, and the locals of a function in
their direct page places. dampf first fills what the debug file's format already has room for,
then a small patch teaches Mesen to read it, offered to Mesen's author. The patch waits until
the dev kit is public, because a patch from a compiler with users is easier to argue for. A
debugger of dampf's own is not planned.

**M10, fine tuning**, takes up what was left of M4 until the frame budget is met: the block
layout by a profile by default (it is built, but only on request, because it needs a profile in
the game's build), superoptimization of short hot sequences, `<<8` as a byte store, `switch` as
a jump table, `MVN` for copies, and unrolling by a hint. Inlining is to be guided by the
profile, and a tuning option is to try the parameters chosen today on one game, such as the
inlining limit and the block order, on the user's own code and keep the fastest build, each
candidate checked against the PC's results. The hints that transform code come in M10, as do
variables in bank `$7F`, and subrange types declared in the source, but those only once a
profile shows a hot value whose range dampf cannot infer.

### The dev kit after M5 (not scheduled)

Three steps of the dev kit have no milestone yet. The PC side of the console API, the pseudo
SNES and the generic part of the game's raylib and web frontends, moves from the game into
`lib/pc`, together with the game's own developers, since it changes their daily work. The
compiler moves into a folder `compiler/` once the PC side brings a second build world into the
repository. Converters such as `gfx2c` go into `tools/`.

### Publishing (not scheduled)

The dev kit goes public as superdevkit when it is useful to someone other than Blubber Blasen.
For that, the snapshot of Blubber Blasen, its replays and the hand-written reference function
leave the repository, and dampf's own examples take over their jobs as test case and benchmark.
They are also in the git history, so either the history is cleaned or the public repository
starts without it. The examples become a set of benchmarks in different styles rather than one
busy scene, because every speed decision so far was measured on one game: the optimizations of
2026-11-18 made play of Blubber Blasen 13 % faster, but the execution tests only 0.9 %, and part
of the gain comes from parameters chosen on this one game, such as the inlining limit and the
block order. Each optimization is then measured on all of them, and a tuned parameter is chosen
on the whole set. The repository is renamed to superdevkit, with dampf as its compiler, and
moves to GitHub, with a release there, a build on GitHub Actions and the book on GitHub Pages.
The licence is decided already: dampf is under the Apache License 2.0 with LLVM Exceptions, the
same as LLVM, so code dampf puts into a ROM carries no licence conditions and games built with
dampf can use any licence.

## The checkpoint after M2

Before M3 started, the measurements of M2 had to answer one question: can a better compiler
alone make the game fast enough, or does the game also have to do less work?

The goal of 0.6 frames per tick needed dampf's naive code to get 9 to 11 times faster on average
and 14 to 20 times on the worst tick. The optimizations were expected to bring a factor of 3 to
6 over the naive code, and the first function written by hand in assembly, `level_solid_px`,
agreed: the naive code was 4.5 times slower than it. Even with hand-written quality everywhere,
play would land at about 1.2 to 1.7 frames on average, and the worst tick with the
[CPU partner](/glossary#cpu-partner) at 3 to 4 frames. The compiler alone cannot close the gap.

Three options were on the table: compiler first and decide after M3, compiler and game in
parallel, or relax the requirement, for example game logic at 30 Hz on the heaviest ticks. The
decision was **compiler and game in parallel**. dampf goes on with the compiler, while the game
works on the hot spots no compiler can fix: the CPU partner and the pairwise separation of
bubbles in `separate`, the game's function that pushes bubbles apart (16 % of the cycles of
play). Relaxing the requirement stays as a fallback, decided on measured numbers if rare worst
ticks still do not fit.

The game changes in its own repository. dampf follows by refreshing its snapshot of the game's
code in `snapshots/blubberblasen` and measuring again with the benchmark, the replays and the
profile. Whether the budget is met therefore depends on work outside dampf.

## Risks and open questions

The main risks and how they are met:

- **Miscompilation.** The layers of tests, the naive code generator as an independent
  reference, and the game's replays as the oracle catch wrong code. One miscompilation is known
  and not found yet; it is described below.
- **The optimizer is tested by one game.** Each build runs 29 execution tests and 50 random
  programs, and the random programs barely reach the optimizer's patterns, so in practice the
  optimizer is tested by Blubber Blasen's benchmark and one replay recording. M5 adds random
  programs and execution tests for the patterns.
- **Tuned on one game.** The inlining limit, the block order and the idioms the optimizer looks
  for come from Blubber Blasen. The set of benchmarks that publishing brings is meant to
  correct that.
- **Never run on a console.** Everything is checked in dampf's emulator and in Mesen;
  [FastROM](/glossary#fastrom-and-slowrom), the DMA margin and timing at the edges have not run
  on hardware yet, which M5 changes.
- **Different behaviour on PC and SNES.** The subset allows fixed-width types only, dampf
  follows C's promotion rules exactly, and the tests compare every program with gcc.
- **The frame budget depends on the game.** The checkpoint after M2 concluded that the compiler
  alone cannot reach it; with the CPU partner the worst tick takes 3 frames.
- **Little human knowledge of the code.** About 23,000 lines were written in two days, mostly
  by AI agents. A bug like the open miscompilation needs someone who knows how the code
  generator works, and today that knowledge is mostly in the documents and the decision
  records.
- **Scope creep.** The compiler could become the project and stall the game; the book and M6
  came before M5. The two run in parallel, and every milestone is measured against the game.
- **Gains smaller than hoped.** Each optimization is measured, and the order of the remaining
  ones follows the profile.
- **A wrong hint silently produces wrong code.** Every hint that states a fact is checked as an
  assertion in the PC build, with the replays running against it.

Open questions a user may run into:

- **A miscompilation not yet found.** dampf inlines functions of up to 60 instructions, and
  with that limit the game's benchmark gives the PC's checksums. With a limit of 80 it differs
  from the PC in 56 of 3,610 ticks, from tick 32 on. The fault is in an older part of the code
  generator, on a function that the larger inlining makes: the optimized MIR is right, since its
  interpreter gives the PC's checksums; dampf's assembler and [ca65](/glossary#ca65-and-ld65)
  build the same ROM; and the difference stays with every LIR pass switched off. The next step
  is to find that function and reduce it to an execution test; fixing it is part of M5.
- **The language of the dev kit's tools.** The first tool, the PNG converter for the
  examples, is written in Python for now. Java would keep the dev kit to nothing but Java to
  install and can read PNG files itself; Python is quicker to write but is one more thing to
  install. This is to be looked at before more tools come.
- **What 1.0 needs.** The frame budget met, the subset decisions taken, C in interrupts, or a
  set of users: which of them is open.
- **Calypsi's drift.** Calypsi's results drift away from the PC's from tick 596 on. Whether
  that is a miscompilation, RAM that Calypsi's start-up leaves uncleared, or a step of the
  game's own Calypsi build that dampf's comparison lacks, is open. Until then the comparison
  rests on 102 ticks.
- **Locals in Mesen.** How Mesen shows locals that live in fixed direct page places, several
  values sharing a place (M9).
- **`restrict`.** Whether a no-alias hint of dampf's own is clearer than `restrict`, and how
  `restrict` is checked on the PC.
- **Timing.** How to express path combinations that cannot happen, should the worst-case
  bounds turn out too pessimistic, and how much HDMA the game uses (DMA transfers the SNES
  makes line by line while it draws), which changes the budget of a frame.
- **Reference functions.** Which functions become the hand-written yardsticks for dampf's code.
  `level_solid_px` is still the only one, and it leaves the repository with the game at
  publishing.
