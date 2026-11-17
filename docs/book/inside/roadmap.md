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
| M0 | Define the [subset](/glossary#subset) of C from what the game uses | Done; `goto` still open |
| M1 | End to end: compile, assemble with ca65, link with ld65, run in a test, step through C in [Mesen](/glossary#mesen) | Done |
| M2 | Naive but complete: all of Blubber Blasen compiles, matches the PC, and is measured | Done |
| Checkpoint | Decide whether the plan reaches the budget | Decided: compiler and game in parallel |
| M3 | The big levers: the optimizer on [MIR](/glossary#mir), timing report, inferred value ranges, pointer compression; faster than [Calypsi](/glossary#calypsi) | In progress; faster than Calypsi since 2026-11-17 |
| M4 | Fine tuning until the frame budget is met | Started early: the block layout by a profile |
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
[value ranges](/glossary#value-range) inferred from constants, operations, conditions,
loops and call sites, values in the [direct page](/glossary#direct-page) instead of RAM,
register allocation in the form the 65816 allows, induction variables, and
[pointer compression](/glossary#pointer-compression) for pointers into a single object. With
them, play of Blubber Blasen takes 1.31 frames per tick, against 5.17 with the naive code;
1.10 with FastROM, which is switched on since, 1.08 without the comparisons with 0 that
the flags already answer, 1.06 without storing values that every use takes from A, 1.04
with switches on bytes that compare 16 bits, and 1.02 with returns copied into each path.
That reaches M3's goal against Calypsi. On the same version of the game, with Calypsi's options,
in the play ticks where both compilers play the same scene, Calypsi takes 1.67 frames per tick
and dampf 1.14: dampf is 1.47 times as fast, and 1.3 to 1.8 times on the title, the story and
the round intro. Only those ticks are compared because Calypsi's results drift away from the
PC's from tick 596 on. The claim rests on one game and on dampf's subset; WDC's commercial
compiler was not measured, and 816-tcc, about 3.6 times slower than dampf, is no longer
compared.
The static timing analysis is built as well: a best and a worst case in CPU cycles for every
function, written to `<rom>.timing.txt` and checked against the emulator on every call. Of the
game's functions, 38 still have no bound, and the report names the loop or call that makes each
of them so. The first [hint](/glossary#hint), `DAMPF_RANGE` from `<dampf.h>`, bounds such a
loop by hand; it is checked on the PC and costs nothing on the SNES. A cycle budget
(`DAMPF_BUDGET`) is checked at build time: a function over its budget, or without a worst
case, fails the build, and one above 90 % of it gets a warning. `DAMPF_TRIPS` is postponed.
Still to come in M3: timing in master cycles and the costliest path per C line in the report
and in the budget errors; more hand-written reference functions, and value ranges from array
bounds.

**A playable ROM, built during M3.** The playable ROM was planned for when the game's own SNES
port needs it, but it was built ahead of that, so that every version of dampf can build the real
game and the whole toolchain is tested on what only the console shows. Its platform layer,
`platform/snes`, is written in C: the 19 PVSnesLib-shaped calls the game makes, the rest of its
console API, and the main loop. It reaches the hardware through pointers to `volatile` memory,
so it needs no [intrinsics](/glossary#intrinsic). There is no interrupt handler: the loop waits
for the vertical blank itself, because C code called from an interrupt in the middle of a tick
would overwrite the frames of the functions it interrupted, which dampf keeps at fixed places.
Sound and save RAM are not built. A check plays the ROM in Mesen for a minute and fails if any
byte reaches video memory outside the vertical blank, and the replay test plays the recording
through the platform layer with the checksums of the PC. On the console the game runs at 2.28
frames per tick during that minute of play, more than the benchmark shows: a tick that needs
more than one frame waits for the start of the next vertical blank, and the benchmark leaves out
what the game's video code costs on the hardware. Most of the vertical blank goes to C code, so
two improvements come next: a `volatile` access at a constant address as one long `sta` or
`lda` instead of an access through a pointer, and a 32-bit shift by a multiple of 8 that moves
bytes instead of looping.

**M4, next.** M4 is fine tuning until the frame budget is met: the remaining optimizations of
the catalogue, arrays aligned to a page so that 8-bit index registers reach them,
optimization guided by the profiles of the replays (the first part, laying out the blocks by
a profile's counts, is built), superoptimization of short hot sequences,
and the hints that transform code, such as `DAMPF_TABLE` and `DAMPF_PIN_X`.

**What is left, and in which order.** Play takes 1.31 frames per tick (1.02 with FastROM, the
first item, the comparisons on the flags, the first step of register allocation, narrower
switches and copied returns, done since), and the goal is 0.6. A
profile of play shows where the cycles of the game code go: moving values to and from the
direct page takes 30 % (`sta` 16.0 %, `lda` 14.2 %), comparisons with a constant 8.5 %, indexed
reads 7 %, byte masks 5 % and jumps 3 to 4 %. The runtime's multiplication and division are no
longer among the most expensive. The table orders what is left by its likely gain on play.
The gains are estimates from those shares, not measurements, and several ideas that looked
good measured nothing.

| # | Idea | Likely gain | Effort |
| --- | --- | --- | --- |
| 1 | FastROM: code in banks `$80` and up runs at 3.58 MHz | 10–15 % (measured: 16 %, done) | tiny |
| 2 | Register allocation: values in A, X and Y over several instructions instead of a direct page place each | 10–20 % | high |
| 3 | 8-bit stretches: the [`M` flag](/glossary#m-and-x-flags) followed, `uint8_t` code in [8-bit mode](/glossary#8-bit-and-16-bit-mode) | 5–10 % | high |
| 4 | Arrays of structs as one array per field | 5–10 % in the bubble loops | high |
| 5 | 8-bit index registers, arrays aligned to pages | 2–4 % | high |
| 6 | Comparisons on the flags: no `CMP` where the instruction before set them, `BIT` for bit tests | 2–4 % (measured: 1.3 %, done for `CMP #0`) | low to medium |
| 7 | Calling convention: the first argument in A, `JMP` for tail calls | 1–3 % | medium |
| 8 | The block layout by a profile by default | about 1 % | low |
| 9 | Value ranges from array bounds | 1–2 % | medium |
| 10 | Superoptimization of short hot sequences | 1–3 % | high |
| 11 | `<<8` as a byte store, `switch` as a jump table, `MVN` for copies, unrolling by a hint | under 1 % each | low to medium |

FastROM is a platform choice: the cartridge must support it, as flash cartridges do. Calypsi
can use it as well, so it changes the frames, not the comparison. Register allocation is the
largest lever and the hardest, because with one accumulator the hard part is choosing what
lives where. Splitting arrays of structs needs a decision on the subset or a hint, since the
layout in memory then differs from the PC's.

The order: FastROM first, if a FastROM cartridge is acceptable for the game, then the
comparisons (6) as a quick check, then register allocation (2), or the split of struct arrays
(4) once its subset question is decided; then 3, 5 and 7. FastROM and 2 to 6 together might
bring play to about 0.85 to 1.0 frames per tick. The rest has to come from the game doing less
work, in the pair loop of `separate` (16 % of play) and the CPU partner, as the checkpoint after
M2 concluded.

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
- **A wrong hint silently produces wrong code.** Every hint that states a fact is checked as an
  assertion in the PC build, with the replays running against it.
- **Scope creep.** The compiler could become the project and stall the game. The two run in
  parallel, and every milestone is measured against the game.

Open questions a user may run into:

- **`goto`.** Forbid it entirely, or allow a restricted form that keeps the code structured.
- **`restrict`.** Whether a no-alias hint of dampf's own is clearer than `restrict`, and how
  `restrict` is checked on the PC.
- **Locals in Mesen.** How Mesen shows locals that live in fixed direct page slots.
- **Hand-written assembly.** How a linked-in assembly function declares its calling convention,
  its use of the direct page and its cycle cost, so that the timing analysis can include it.
- **Timing.** How to express path combinations that cannot happen, should the worst-case bounds
  turn out too pessimistic, and how much HDMA the game uses, which changes the budget of a frame.
- **Publishing.** The licence is decided: dampf is under the Apache License 2.0 with LLVM
  Exceptions, the same as LLVM, so code dampf puts into a ROM carries no licence conditions and
  games built with dampf can use any licence. Before dampf can go public (M5), the snapshot of
  Blubber Blasen, its platform layer and the hand-written reference function leave the
  repository and dampf's own examples take over their jobs as test case and benchmark. They are
  also in the git history, so either the history is cleaned or the public repository starts
  without it. dampf also gets its own C preprocessor, so that using it no longer needs gcc,
  which users on Windows would otherwise install only for that. Hosting the repository and
  putting the book online are part of M5 as well.
