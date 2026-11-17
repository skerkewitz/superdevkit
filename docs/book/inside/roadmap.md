<!-- GENERATED from doc-specs/inside/roadmap.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->

# Roadmap

dampf is built in milestones, each measured against [Blubber Blasen](/glossary#blubber-blasen),
the game it is developed for. The goal of the whole project is that the game logic of the
game's busiest scene fits in at most 60 % of a [frame](/glossary#frame). This page says where
dampf stands, what comes next and what is still open. Unlike the other pages of the book, it
also describes plans. What is not built yet, listed for users, is on
[Limits](/reference/limits).

## Milestones

| Milestone | Goal | State |
| --- | --- | --- |
| M0 | Define the [subset](/glossary#subset) of C from what the game uses | Done; `goto` and the hint macros still open |
| M1 | End to end: compile, assemble with ca65, link with ld65, run in a test, step through C in [Mesen](/glossary#mesen) | Done |
| M2 | Naive but complete: all of Blubber Blasen compiles, matches the PC, and is measured | Done |
| Checkpoint | Decide whether the plan reaches the budget | Decided: compiler and game in parallel |
| M3 | The big levers: the optimizer on [MIR](/glossary#mir), timing report, inferred value ranges, pointer compression; faster than [Calypsi](/glossary#calypsi) | In progress |
| M4 | Fine tuning until the frame budget is met | Not started |
| M5 | Publishing: own examples instead of Blubber Blasen, an own preprocessor, a hosted repository | Not started |
| M6 | Standing alone: an own assembler and linker, so that using dampf needs nothing but Java | Not started; the very last step |

M2 showed that the whole game works on dampf. All of Blubber Blasen compiles with the
[naive code generator](/glossary#naive-code-generator), the game's benchmark gives the
checksums of the [PC build](/glossary#pc-build) for all 3,610 [ticks](/glossary#tick), and a
recorded replay of 3,000 ticks gives its 50 recorded checksums on the SNES. Cycles are measured
per tick and per function, and one hot function written by hand in assembly, `level_solid_px`,
gave the first comparison: the naive code was 4.5 times slower than the hand-written one.

**M3, in progress.** M3 began with the foundation for the optimizer: MIR, an
[SSA](/glossary#ssa) representation with a text format that parses back, a verifier and an
interpreter; dampf's own 65816 emulator with exact cycle counts, which matches Mesen on the
game's benchmark and runs it in 30 seconds instead of 6 minutes; and cycle counts in the
assembly comments. On top of it, the [code generator from MIR](/glossary#code-generator-from-mir)
and its [passes](/glossary#pass) are built: width narrowing, constant multiplication and
division, the standard optimizations, compare and branch in one,
[value ranges](/glossary#value-range) inferred from constants, operations, conditions and
loops, values in the [direct page](/glossary#direct-page) instead of RAM, register allocation
in the form the 65816 allows, induction variables, and
[pointer compression](/glossary#pointer-compression) for pointers into a single object. With
them, play of Blubber Blasen takes 1.53 frames per tick, against 5.17 with the naive code.
The static timing analysis is built as well: a best and a worst case in CPU cycles for every
function, written to `<rom>.timing.txt` and checked against the emulator on every call. Still
to come in M3: `DAMPF_TRIPS` and parameter ranges from call sites, since 91 of the game's
functions still have no bound, then a cycle budget (`DAMPF_BUDGET`) checked at build time,
timing in master cycles and a report per C line; more hand-written reference functions, and
value ranges from array bounds.

**M4, next.** M4 is fine tuning until the frame budget is met: the remaining optimizations of
the catalogue, arrays aligned to a page so that 8-bit index registers reach them,
optimization guided by the profiles of the replays, superoptimization of short hot sequences,
and the [hints](/glossary#hint) that transform code, such as `DAMPF_TABLE` and `DAMPF_PIN_X`.

## The checkpoint after M2

Before M3 started, the measurements of M2 had to answer one question: can a better compiler
alone make the game fast enough, or does the game also have to do less work? The numbers, in
frames per tick with dampf's naive code:

| | Average | Worst tick |
| --- | --- | --- |
| Play | 5.17 | 8.58 |
| Play with the CPU partner | 6.55 | 12.23 |

The goal of 0.6 frames needed code 9 to 11 times faster on average and 14 to 20 times on the
worst tick. The optimizations were expected to bring a factor of 3 to 6 over the naive code,
and the hand-written `level_solid_px` agreed with 4.5. Even with hand-written quality
everywhere, play would land at about 1.2 to 1.7 frames on average, and the worst tick with the
CPU partner at 3 to 4 frames. A factor of about 2 to 3 on average, more on the worst ticks, has
to come from the game.

Three options were on the table: compiler first and decide after M3, compiler and game in
parallel, or relax the requirement, for example game logic at 30 Hz on the heaviest ticks. The
decision was **compiler and game in parallel**. dampf goes on with M3, while the game works on
the hot spots no compiler can fix: the CPU partner, which costs up to 3.7 extra frames on the
worst tick, and the pairwise separation of bubbles. Relaxing the requirement stays as a
fallback, decided on measured numbers after M3 if rare worst ticks still do not fit.

The game changes in its own repository. dampf follows by refreshing its snapshot of the game's
code in `examples/blubberblasen` and measuring again with the benchmark, the replays and the
profile.

## Risks and open questions

The main risks and how they are met:

- **Miscompilation.** Four layers of tests, the naive code generator as an independent
  reference, and the game's replays as the oracle catch wrong code.
- **Different behaviour on PC and SNES.** The subset allows fixed-width types only, dampf
  follows C's promotion rules exactly, and the tests compare every program with gcc.
- **Gains smaller than hoped.** Each optimization is measured, and the order of the remaining
  ones follows the profile.
- **A wrong hint silently produces wrong code.** Once hints exist, every hint that states a fact
  is checked as an assertion in the PC build, with the replays running against it.
- **Scope creep.** The compiler could become the project and stall the game. The two run in
  parallel, and every milestone is measured against the game.

Open questions a user may run into:

- **A playable ROM.** Playing the game on real hardware needs a platform layer: start-up,
  joypad input, the vertical blank handler with DMA for sprites, tile maps and palettes, and the
  19 calls that the game's `video.c` makes. How these calls map onto dampf's
  [intrinsics](/glossary#intrinsic) is open; the layer is planned for when the game's SNES port
  needs it.
- **`goto`.** Forbid it entirely, or allow a restricted form that keeps the code structured.
- **Hints.** The names and the set of the hint macros and their assertions on the PC; whether a
  no-alias hint of dampf's own is clearer than `restrict`, and how `restrict` is checked on the
  PC.
- **Locals in Mesen.** How Mesen shows locals that live in fixed direct page slots.
- **Hand-written assembly.** How a linked-in assembly function declares its calling convention,
  its use of the direct page and its cycle cost, so that the timing analysis can include it.
- **Timing.** How to express path combinations that cannot happen, should the worst-case bounds
  turn out too pessimistic, and how much HDMA the game uses, which changes the budget of a frame.
- **Publishing.** The licence is decided: dampf is under the Apache License 2.0 with LLVM
  Exceptions, the same as LLVM, so code dampf puts into a ROM carries no licence conditions and
  games built with dampf can use any licence. Before dampf can go public (M5), the snapshot of
  Blubber Blasen and the hand-written reference function leave the repository and dampf's own
  examples take over their jobs as test case and benchmark. They are also in the git history, so
  either the history is cleaned or the public repository starts without it. dampf also gets its
  own C preprocessor, so that using it no longer needs gcc, which users on Windows would
  otherwise install only for that. Hosting the repository and putting the book online are part
  of M5 as well.
