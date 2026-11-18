# Roadmap

Milestones, versions, risks and open questions. The plan was set at the kickoff on 2026-11-17
and revised several times since; on 2026-11-18, at version 0.4.0, M3 and M4 were concluded and
what was left of them moved to the milestones after M5 (see [History](#history)).

## Where dampf stands

M0 to M4 and M6 were concluded with version 0.4.0 (2026-11-18): all of Blubber Blasen compiles,
the benchmark and the replays give the PC's checksums, on the SNES and in a playable ROM,
dampf's code is faster than Calypsi's, and dampf assembles and links itself. During play the
game takes 0.79 frames per tick against a goal of 0.6, so the compiler work goes on while the
game reduces its own work in parallel; the current numbers are in
[optimizations.md](optimizations.md#where-things-stand).

dampf is the compiler of a development kit for SNES games, superdevkit, which this repository
holds until it is published (ADR
[record 29](adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)). M5 is in work, as
version 0.5.0: Blubber Blasen builds its ROM with the dev kit, from its own repository.

## Versions

The version is the dev kit's; it is in `build.gradle.kts`, and `dampf --version` prints it. Milestone N is
released as version 0.N.0; fixes in between raise the last number. M6 was done ahead of M5 and
is part of 0.4.0, so there is no 0.6.0. Every version before 1.0 is a pre-release: the subset,
the command line and the files dampf writes may change from one to the next. Publishing is a
step of its own, not scheduled yet (see [Publishing](#publishing-not-scheduled)). What 1.0
needs is an open question. The rule is ADR
[record 26](adr/0026-milestone-n-is-version-0n0-and-the-first-public.md), amended by
[record 29](adr/0029-superdevkit-is-the-project-and-dampf-is-its.md).

## Milestones

| Milestone | Goal | Version | State |
| --- | --- | --- | --- |
| M0 | Define the subset: Blubber Blasen's `src/game` scanned, [subset.md](subset.md) written | 0.4.0 | Done 2026-11-17 |
| M1 | End to end: a small function compiled, assembled, linked, run in a test, stepped through in Mesen | 0.4.0 | Done 2026-11-17 |
| M2 | Naive but complete: all of Blubber Blasen compiles, the benchmark and replays give the PC's checksums, cycles measured and compared with Calypsi | 0.4.0 | Done 2026-11-17 |
| M3 | The big levers: MIR, LIR and the code generator from MIR, static timing and budgets, value ranges and pointer compression; faster than Calypsi | 0.4.0 | Done 2026-11-18 |
| M4 | Fine tuning, started early: block layout by a profile, peephole rules, loop and branch optimizations | 0.4.0 | Done 2026-11-18 |
| M6 | Standing alone: dampf's own assembler and linker | 0.4.0 | Done 2026-11-18 |
| M5 | Blubber Blasen builds its ROM with the dev kit, from its own repository | 0.5.0 | In work |
| M7 | The optimizer: what was left of M3, register allocation first | 0.7.0 | Planned |
| M8 | Language, platform and usability: C in interrupts, intrinsics, the open subset decisions, dampf's own preprocessor | 0.8.0 | Planned |
| M9 | Timing and debugging: master cycles, bounds for more loops, locals and types in Mesen | 0.9.0 | Planned |
| M10 | Fine tuning: what was left of M4, until the frame budget is met | 0.10.0 | Planned |

What each concluded milestone reached is in [History](#history). The order after M5 follows the
likely gain and what each milestone needs from the one before; it is revised when M5 is done.

## M5: Blubber Blasen builds with the dev kit (0.5.0)

Done when Blubber Blasen builds its SNES ROM from its own repository with a local install of the
dev kit (no release is needed while the repository is private), and that ROM plays the game's
recordings with the PC's checksums.

Correctness first, since the game will rely on the code:

- **The miscompilation with inlining up to 80** found and fixed, with an execution test (see
  [Open questions](#open-questions) for what is known). The limit of 60 only avoids the function
  that shows it.
- **Random programs that reach the optimizer:** loops over arrays of structs, pointers into
  arrays, byte stores in a row, 8.8 values, fewer divisions. Today their cycles go to the
  runtime's division routines, and the optimizations of 2026-11-18 did not move them at all.
- **More execution tests** for the patterns the optimizer looks for (29 programs today).
- **The playable ROM on a real console or flash cartridge** (needs the hardware), and the DMA
  margin `SETUP_LINES` measured again: `snesDmaFits` leaves 8 lines for the code around a copy,
  since a copy that starts after the vertical blank is lost.

A compiler a game can call:

- **All errors of a run reported**, not only the first.
- **Options in an argument file**, `--config` or `@file`, with folders standing for their `.c`
  files (ADR [record 28](adr/0028-options-can-be-kept-in-an-argument-file-given-by.md)); dampf's
  own scripts for the game use one.
- **`<string.h>` with `memset` and `memcpy`**, in C for now (as `MVN` or DMA in M10).
- **The install** (`./gradlew installDist`) carries the library and the tools as well as the
  compiler, and the game refers to it by its folder.

The dev kit in this repository (ADR
[record 29](adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)):

- **Done: the library.** `platform/snes` became `lib/snes`, and the console API the dev kit now
  owns is `lib/include/snes/snes.h`, taken over from the game with the same declarations;
  `dev/update_game_snapshot.sh` warns when the game's copy declares something else. The main
  loop (`lib/snes/main.c`) is the library's, and the frame rhythm it calls (`game_init`,
  `game_tick`, `game_vblank`, the pad bits) is declared in `lib/include/snes/loop.h`.
  [lib/README.md](../lib/README.md) has the table of built and stub per function and platform,
  and `LibApiTest` checks that every function of the API exists in `lib/snes` and in the table.
- **Done: `tools/` became `dev/`**, for developing the dev kit (benchmarks, profiles, the
  snapshot, the subset scan, the replay driver, the check of the playable ROM in Mesen). The
  split into `tools/` and `dev/` that was planned found nothing for other games: the Mesen check
  plays Blubber Blasen with scripted input, and the replay converter reads the game's format.
  `tools/` comes back with the first tool for game developers (a graphics converter, say).
- **Done: the snapshot** moved from `examples/blubberblasen` to `snapshots/blubberblasen`.
- **Examples as projects:** `examples/<name>/` with a README, an argument file `<name>.args`,
  `src/`, `assets/` and its own `build/`. The first is `examples/m1` turned into a project
  (stepping through C in Mesen); the book's getting-started page uses it.

The book (ADR [record 30](adr/0030-one-book-for-superdevkit-with-dampf-as-a-part-of.md)):

- **One book for the dev kit.** First the existing pages move into folders for dampf's part
  (`reference/compiler/`, `inside/compiler/`), with `doc-specs/` and its README following; then
  the user guide becomes the dev kit's (introduction, getting started from the first example
  project), and the reference gets the console API with its table of built and stub per
  platform (`reference/api/`).

For the game, through [game-findings.md](game-findings.md) (the game's own agent does the work
there):

- a build script for the dev kit next to the PVSnesLib one, with an argument file;
- the data as plain C (`map2c` and `gfx2c` without `--snes`);
- `BB_FAST` with a `__DAMPF__` branch;
- an ADR of the game's own, since its ADR-0005 names PVSnesLib.

## M7: the optimizer (0.7.0)

What was left of M3. The rows are from the plan made on 2026-11-17 and keep its numbers; the
gains are estimates from the profile's shares, not measurements, and several ideas that looked
good measured zero ([optimizations.md](optimizations.md#tried-and-rejected)). The done rows (1
FastROM, 12 hardware registers at constant addresses) are in [History](#history).

| # | Idea | Likely gain | Effort | Notes |
| --- | --- | --- | --- | --- |
| 2 | Register allocation: values in A, X and Y over several instructions instead of a direct page place each | 10-20 % | high | Loads and stores of direct page values are the largest share of the cycles. With one accumulator, choosing what lives where is the hard part. Today one pass per pattern (loop counters in X, indexes in Y, values handed on in A, `PointerRemat`, `ByteStretches`); a common model of what lives where comes first. Next steps: stores moved to the path that needs them, fewer reloads. |
| 3 | 8-bit stretches: the M flag followed (`REP`/`SEP`) across blocks, `uint8_t` code in 8-bit mode | 5-10 % | high | Fewer `AND #$00FF`, and 8-bit accesses are a cycle cheaper. Only a small start: 8-bit mode kept across C lines within a block. |
| 4 | Arrays of structs as one array per field (catalogue D, `DAMPF_SPLIT`), with HIR to do it in | 5-10 % in the bubble loops | high | `separate`, `bubbles_update` and `bubbles_draw` are 30 %. Needs a subset decision or a hint, since the layout then differs from the PC. |
| 5 | 8-bit index registers, arrays aligned to pages | 2-4 % | high | `LDA abs,X` a cycle cheaper; needs the X flag followed and the alignment in the memory map. |
| 6 | Comparisons on the flags: `BIT` for bit tests | 2-4 % for the whole row | low to medium | Done for `CMP #0`. Most remaining `CMP #imm` compare with other constants and feed `BCC`/`BCS`. |
| 7 | Calling convention (catalogue C): the first argument in A, `JMP` for tail calls | 1-3 % | medium | `ground_px` is called 12,000 times in 800 ticks. |
| 9 | Value ranges from array bounds | 1-2 % | medium | More 8-bit narrowing, fewer masks. |

Also in M7:

- **16-bit pointers into RAM** ([subset.md](subset.md), decision 3), and enum objects in one byte
  (decision 2), each once its subset decision is taken.
- **Constant shifts by a multiple of 8 as byte moves.** A 32-bit shift by 16 is a loop of 16
  steps today, about 600 cycles.

The order: register allocation (2), or the split of struct arrays (4) once its subset question
is decided; then 3, 5 and 7. The rest has to come from the game doing less work: the pair loop
of `separate` (16 %) and the CPU partner ([game-findings.md](game-findings.md), finding 1), as
the checkpoint after M2 concluded.

## M8: language, platform and usability (0.8.0)

- **C in interrupt handlers.** Frames have fixed places (ADR
  [record 6](adr/0006-no-recursion-no-free-function-pointers-and-no-heap.md)), so C called from
  an NMI or IRQ would overwrite the frames of the code it interrupted, and the platform layer
  waits for the vertical blank instead. Nearly every SNES game has an NMI handler, and raster
  effects need an IRQ handler. The functions an interrupt reaches can get a frame area of their
  own; open is how a handler is declared and what it saves
  ([optimizations.md](optimizations.md#knowing-the-machine) has the idea).
- **Intrinsics** for the hardware multipliers, the divider and DMA ([subset.md](subset.md),
  *Intrinsics*).
- **The open subset decision** 1 (plain `char` for text only) in [subset.md](subset.md); 2 and
  3 come with M7. Decision 4 is taken: `goto` stays rejected (ADR
  [record 27](adr/0027-goto-and-labels-stay-rejected-and-a-forward-and.md)).
- **Function pointers in constant tables**, allowed by design and not built.
- **Hand-written assembly with the code generator from MIR:** `--replace` works only with the
  naive one, and a linked-in function has no way to declare its calling convention, direct page
  use and cycles for the timing analysis.
- **dampf's own preprocessor**, so that using dampf needs nothing but Java: macro expansion
  following Prosser's algorithm, `#` and `##`, variadic macros with `__VA_OPT__`, `#if`
  arithmetic in `intmax_t`, line markers for the debug information. `gcc -E` stays in the tests
  as the oracle, on every test program, every example and generated macro cases. Today users on
  Windows install MinGW or MSYS only for `gcc -E`.
- **Less memory for a compile:** the game takes 1.8 GB.
- **The SNES facts out of the code generators** (DBR `$7E`, the frame addresses, long I/O
  addresses) into the target ([architecture.md](architecture.md#core-and-target)).
- **`restrict`** or a no-alias hint of dampf's own (see [Open questions](#open-questions)).

## M9: timing and debugging (0.9.0)

Timing ([timing.md](timing.md#not-built-yet)):

- master cycles instead of CPU cycles, in the report, the comments and the budgets;
- the costliest path per C line, in the report and in budget errors, and best and worst case in
  the comments of lines with a branch;
- bounds for more loops: 37 of the game's functions have none; `DAMPF_TRIPS` for loops without
  a counter (postponed, [hints.md](hints.md));
- the measured cycles of budgeted functions checked in the emulator tests;
- level 3: DRAM refresh, HDMA and interrupts at frame level.

Better C debugging in Mesen: Mesen shows the C source and the names of global variables from
the `.dbg` file, and that is all. Of a C symbol (`csym`) it reads only the name and the assembly
symbol it belongs to, and the type that cc65 and dampf write is always `"00"`. dampf's users
would gain from variables shown with their C type (`uint8_t`, signed `int16_t`), struct fields,
and the locals of a function in their direct page places. The cc65 format already has fields
for part of this (a storage class `sc` and an offset on `csym`) that Mesen ignores. The plan:
dampf first fills what the format has, then a small patch teaches Mesen to read it, offered to
Mesen's author; NES developers who use cc65 would gain from it too. A patch from a compiler with
users is easier to argue for, so the patch waits until the dev kit is public. With it, the code from MIR
writes the processor state line in the assembly as the naive code generator does.

## M10: fine tuning (0.10.0)

What was left of M4, until the frame budget is met:

| # | Idea | Likely gain | Effort | Notes |
| --- | --- | --- | --- | --- |
| 8 | The block layout by a profile by default (from the replays) | ~1 % | low | Built (`--block-counts`), opt-in; needs a profile in the game's build. |
| 10 | Superoptimizer for short hot sequences | 1-3 % | high | Gains spread thin. |
| 11 | `<<8` as a byte store, `switch` as a jump table, `MVN` for copies (and `memcpy`), unrolling by hint | <1 % each | low to medium | Large locally, small on the whole game. |

Also in M10:

- profile-guided inlining, and code size spent for speed where the profile says it is hot;
- a tuning option (`--tune`) that tries the parameters chosen today on one game (the inlining
  limit, the block order) on the user's own workload and keeps the fastest build, each candidate
  checked against the PC's results ([optimizations.md](optimizations.md));
- `DAMPF_TABLE`, `DAMPF_PIN_X`, `DAMPF_LIKELY`/`DAMPF_UNLIKELY` and the other transforming
  hints ([hints.md](hints.md));
- bank `$7F` for variables ([memory-map.md](memory-map.md));
- subrange types (`DAMPF_RANGED`, `DAMPF_AS`), only when a profile shows a hot value whose range
  dampf cannot infer ([subrange-types.md](subrange-types.md), ADR
  [record 23](adr/0023-value-ranges-are-inferred-first-and-subrange-types.md)).

## The dev kit after M5 (not scheduled)

- **The PC side** moves from the game into `lib/pc`: the pseudo SNES (`src/snes`) and the generic
  part of the raylib and web frontends. The game keeps its debug view (`debug_ui.cpp`) and
  `map2c`, which are its own. Done together with the game's agent, since it changes its daily
  work.
- **The compiler into `compiler/`**, once the PC side brings a second build world (Gradle for the
  compiler, something like CMake for the PC side).
- **Converters** such as `gfx2c` into `tools/`, and `assets/` in the examples with them.

## Publishing (not scheduled)

The dev kit goes public as superdevkit when it is useful to someone other than Blubber Blasen.
What it takes:

- **Own examples instead of Blubber Blasen.** The snapshot, its replays and the hand-written
  benchmark in `reference/` leave the repository. They are in the git history, so either the
  history is cleaned (`git filter-repo`) or the public repository starts without it. Own
  examples take over their jobs: a program large enough to be a test case, new hand-written
  reference functions, and a set of benchmarks in different styles rather than one busy scene.
  So far every speed decision was measured on one game: the optimizations of 2026-11-18 made
  Blubber Blasen's play 13 % faster but the execution tests only 0.9 % (median 0.6 %), and part
  of the 13 % comes from parameters chosen on this one game (the inlining limit, the block
  order) and from its idioms
  ([optimizations-log.md](optimizations-log.md#optimizations-on-the-branch-optimise-2026-11-18)).
  The set: code with many small calls, code that computes mostly in 16 and 32 bits, code with
  little access to structs (plain arrays, pointer walks), a fixed-point physics or particle loop,
  a table- and switch-driven state machine, text or tile work on bytes. Each optimization is
  measured on all of them, a tuned parameter is chosen on the whole set, and the docs say which
  set a measurement comes from.
- **The game's own findings** ([game-findings.md](game-findings.md)) go to the game's repository
  with the snapshot; `docs/game-guide.md` is written from them there.
- **The documents** say nothing that only holds for the private repository.
- **The repository renamed** to superdevkit, with dampf as its compiler.
- **Hosting:** a release on GitHub, a build on GitHub Actions (cc65 installed for the tests that
  compare with ca65 and ld65; no Mesen), the book on GitHub Pages.

## The playable ROM

Built 2026-11-17, ahead of the game's own SNES port, so that every iteration of dampf can be played
and the toolchain is tested on what only the console shows.

- **The platform layer** is `lib/snes`, in C: the 19 PVSnesLib-shaped calls of `snes.h` and
  the rest of its API (`snes.c`), and the main loop (`main.c`). It reaches the hardware through
  pointers to `volatile` memory and needs one language feature of its own, the cast from a pointer
  to an integer for DMA source addresses ([subset.md](subset.md), *Hardware access*).
- **No interrupt handler.** The loop waits for the vertical blank, then copies the OAM shadow and
  calls `game_vblank`. C code called from an NMI in the middle of a tick would overwrite the frames
  of the code it interrupted, since dampf keeps them at fixed places.
- **Not built:** sound (the game has none yet), save RAM (the high scores start empty at every
  power-on).
- **Building and checking:** `dev/build_game_rom.sh` writes `build/game/blubberblasen.sfc`.
  `dev/check_game_rom.sh` plays it in Mesen for a minute with scripted input, saves screenshots,
  and fails if any byte reaches VRAM, CGRAM or OAM outside a vertical blank. `ReplayTest`
  (`-Preplays`, with a Mesen) plays the recording through the platform layer: the same checksums
  as the PC, and no byte lost.

The console showed things the benchmark could not. A tick that needs more than one frame waits for
the next vertical blank, and the benchmark leaves out what `video.c` costs on the hardware. With the
naive code generator the vertical blank code is so slow that the room tiles never fit
([game-findings.md](game-findings.md), finding 2). The console figure (frames per tick during a
minute of play, measured again on 2026-11-18) is in
[optimizations.md](optimizations.md#where-things-stand).

Open: the DMA margin is in M5, constant shifts longer than 8 in M7.

## Risks

- **Miscompilation.** Mitigated by the test layers, the naive reference and the replays as
  oracle ([testing.md](testing.md)). One is known and not found yet (M5).
- **The optimizer is tested by one game.** 29 execution tests and 50 random programs per build,
  and the random programs barely reach the optimizer's patterns; in practice the optimizer is
  tested by Blubber Blasen's benchmark and one replay recording. M5 adds random programs and
  execution tests for the patterns, and more recordings would help
  ([game-findings.md](game-findings.md), finding 5).
- **Tuned on one game.** The inlining limit, the block order and the idioms the optimizer looks
  for come from Blubber Blasen. Mitigated by the set of benchmarks that publishing brings.
- **Never run on a console.** Everything is checked in dampf's emulator and in Mesen; FastROM,
  the DMA margin and timing at the edges have not run on hardware (M5).
- **Different behaviour on PC and SNES.** Mitigated by fixed-width types only, exact promotion
  rules and the differential tests against gcc.
- **The frame budget depends on the game.** The checkpoint after M2 concluded that the compiler
  alone cannot reach it; with the CPU partner the worst tick takes 3 frames.
- **Little human knowledge of the code.** About 23,000 lines were written in two days, mostly by
  AI agents. A bug like the open miscompilation needs someone who knows how the code generator
  works; today that knowledge is mostly in the documents and the ADR.
- **Scope creep.** The compiler could become the project and stall the game; the book and M6
  came before M5. Mitigated by running it in parallel and by milestones that are measured against
  Blubber Blasen.
- **Gains smaller than hoped.** Each optimization is measured; the catalogue was re-prioritised
  after M2.
- **A wrong hint silently produces wrong code.** Mitigated by checking every fact hint as an
  assertion on the PC, with the replays running against them ([hints.md](hints.md)).

## Open questions

- **A miscompilation in the code generator, not yet found** (2026-11-18; fixing it is part of
  M5). With functions of up to 80 instructions inlined (the limit is 60), the game's benchmark
  differs from the PC in 56 of 3,610 ticks, from tick 32 on. What is known: the optimized MIR is
  right (the interpreter gives the PC's checksums, every value within its range); ca65 and
  dampf's assembler build the same ROM; it stays with every LIR pass, `PointerRemat`, branch
  relaxation, the new block order, loop counters in X, indexes in Y, values handed on in A and
  arguments computed in place switched off. `main` before the branch `optimise` with the same
  limit is right, but there the limit counts unsimplified bodies and fewer functions are inlined.
  So an older part of the code generator (selection, `FrameLayout`, the masks) goes wrong on a
  function the larger inlining makes. Next step: find the function by comparing the emulator's
  globals with the interpreter's at tick 32, then reduce it to an execution test.
- **In which language the dev kit's tools are written.** The first one, the PNG converter for
  the examples, is Python for now (`tools/`). Java would keep using the dev kit to nothing but
  Java (goal 6 of the README) and could read PNG with `javax.imageio`; Python is quicker to
  write but is one more thing to install. To be looked at more closely before more tools come.
- What 1.0 needs: the frame budget met, the subset decisions taken, C in interrupts, or a set of
  users.
- How Mesen shows locals that live in fixed direct page places, several values sharing a place
  (M9).
- Why Calypsi's results drift from the PC's from tick 596 on: a miscompile, RAM its start-up
  leaves uncleared, or a step of the game's own Calypsi build that `dev/bench_compilers.sh`
  lacks. Until then the comparison rests on 102 ticks.
- How to express impossible path combinations, should the worst-case bounds turn out too
  pessimistic in practice ([timing.md](timing.md)).
- How `restrict` is checked on the PC in practice, and whether a dampf-specific no-alias hint is
  clearer than `restrict` ([hints.md](hints.md)).
- Which functions become reference benchmarks. `level_solid_px` is still the only one
  (`reference/`), and it leaves the repository with the game at publishing.
- How much HDMA the game uses, since it changes the frame-level budget ([timing.md](timing.md)).

## History

Dates are 2026-11-17 unless a line says otherwise. The measurements are in [optimizations-log.md](optimizations-log.md).

### Changes to the plan

- **After M1.** The design discussion had added three items to M1: the Java CPU emulator with
  cycle counting, cycle counts in the assembly comments, and the three IR levels with text format
  and verifier (and HIR/MIR interpreters to M2). M1 was completed by its original definition
  without them. They moved to the start of M3, because the IR levels and interpreters protect
  lowering and optimization passes, which do not exist before M3, and the M2 measurements work
  with Mesen. M2 was split into steps so the first measurement of the whole game came early, the
  checkpoint was added, and the playable ROM was separated from M2.
- **During M3.** The playable ROM was built before the game's own SNES port needs it, so that
  every iteration of dampf can be played and the toolchain is tested on the real console.
- **During M3.** dampf was measured against Calypsi on the same game version and is faster (M3's
  goal). What is left was estimated and ordered; FastROM was added as a platform step, and 816-tcc
  dropped from the comparisons (about 3.6 times slower than dampf).
- **During M3.** The design for value ranges ([subrange-types.md](subrange-types.md)) was added.
  Inferred ranges and pointer compression (planned for M4 in that design) went into M3, because
  they need no language change and the profile points at them. Declared subrange types wait until
  a profile shows a range dampf cannot infer.

- **At version 0.4.0** (2026-11-18). M3 and M4 were concluded: their goals as first set were
  partly reached (faster than Calypsi; the frame budget not yet), and too much stayed open in
  them while too little was planned after them. What was left moved to new milestones: the
  optimizations of M3 to M7, the fine tuning of M4 to M10, and the work found in the review of
  that day ([archive/status-2026-11-18.md](archive/status-2026-11-18.md)) to M5, M8 and M9. M5
  changed from publishing to cleanup and publishing, an alpha for use from Blubber Blasen's
  repository; dampf's own preprocessor moved from M5 to M8. Versions came in: milestone N is
  0.N.0 (ADR [record 26](adr/0026-milestone-n-is-version-0n0-and-the-first-public.md)).

- **The dev kit** (2026-11-18). dampf became the compiler of superdevkit, a development kit for
  SNES games held in this repository (ADR
  [record 29](adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)). M5 changed to the game
  building with the dev kit; publishing left M5 and is not scheduled.

### M0 to M2

- **M0.** The decisions in subset.md are implemented as recommended until decided. The hint macros
  keep their working names, from `<dampf.h>`.
- **M1.** Stepping through C in Mesen was checked by hand.
- **M2, step 1: ROMs larger than 32 KB.** Code and constant data spread over several banks, calls
  across banks, no object across a bank boundary; `exec/banks.c` spans four banks.
- **Step 2: the game's benchmark.** `bench.c` and all of `src/game` compile; all 3,610 ticks match
  the PC (`dev/bench_game.sh`). The game's finding 5 (`(unsigned)` casts in `video.c`) is in
  the snapshot, so all 16 files pass the front end.
- **Step 3: the replays.** A converter turns a replay into an input table in ROM;
  `plus_bot_seed42.bbr` (3,000 ticks, 50 checksums) matches on the PC and on the SNES
  (`./gradlew test -Preplays`).
- **Step 4: cycles per function.** `dev/profile_game.sh bench|replay` and `dampf profile`.
- **Step 5: first reference function.** `level_solid_px` by hand (`reference/`,
  `dampf build --replace`); dampf's naive code was 4.5 times slower.

### Checkpoint after M2

The question was whether the optimizations reach the budget or the game also has to do less
work. **Decided: compiler and game in parallel**, with 30 Hz game logic on the heaviest ticks as
the fallback; the numbers and the options weighed are in ADR
[record 22](adr/0022-the-compiler-and-the-game-work-in-parallel-towards.md). The request to the
game is finding 1 in [game-findings.md](game-findings.md).

### M3 and M4

- **MIR.** Lowering with SSA, a text format that parses back (`dampf.mir.MirParser`), verifier and
  interpreter; the whole game runs on MIR with the PC's checksums. The optimizing pipeline is
  built next to the naive code generator, which keeps working on the AST: an independent reference
  catches more bugs than one that shares code with what it checks.
- **The code generator from MIR** (now the default), correct on all test layers.
- **The CPU emulator** with master cycles and exact memory speeds; passes the SingleStepTests
  native-mode suite and matches Mesen on the benchmark (checksums and master cycles).
- **Cycle counts in the assembly comments**, in CPU cycles per instruction and per straight-line
  C line.
- **MIR passes:** width narrowing (B), constant multiplication and division (I), the standard
  optimizations (H), compare and branch in one.
- **Inferred value ranges** (`Ranges`, `RangeNarrow`): constants, operations, conditions on the
  path, loops, loop bounds for the timing analysis, parameter ranges from the call sites
  (`ParamRanges`), conditions between two values, narrowing after widening.
- **Values in registers and the direct page:** reloads of what A holds left out, φs written
  directly, frames in low RAM with the leaves in the direct page and shared places, inlining,
  indexed addressing, byte loads, short branches, `INC`/`DEC`, `STZ`. Register allocation across
  instructions in the form the 65816 allows: scheduling for hand-overs in A, comparisons and
  offsets taken from A, loop counters in X. Later steps of row 2: values every use takes from A
  are not stored, narrow switches, copied returns, indexes through pointers in Y, loop counters in
  X next to an index in Y, loop tests on the offset, sums as pointers for an index in Y.
- **Induction variables** (`Induction`).
- **Pointer compression** for pointers into a single object, within functions and through
  parameters (`PointerOffsets`, `PointerParams`).
- **Static timing:** best and worst case per function in CPU cycles, checked against the emulator
  on every call. `DAMPF_RANGE` bounds loops by hand, checked on the PC and in the MIR interpreter,
  at no cost on the SNES. `DAMPF_BUDGET` is checked at build time (an error over the budget or
  without a worst case, a warning above 90 %).
- **FastROM** (row 1): banks $80+ at 3.58 MHz, the default; `--slowrom` for a SlowROM cartridge.
  Calypsi gains as much with FastROM.
- **`CMP #0` left out** where the instruction before set the flags (row 6).
- **Hardware registers at constant addresses** (row 12): `STA f:$00210B` instead of a pointer
  through `dampf_ptr`. It matters for the playable ROM's vertical blank, which the benchmark does
  not measure.
- **Block layout by a profile** (`dampf profile --blocks`, `dampf build --block-counts`), opt-in.

### M6

dampf assembles and links itself, so ca65 and ld65 are no longer needed and using dampf needs
nothing but Java. dampf only uses a small part of ca65 (its own output) and of ld65 (its own
memory map), so both stay as the oracle in the tests: the ROM dampf builds itself must be
byte-identical to the one ca65 and ld65 build from the same assembly, and the debug information
must still let Mesen show the C source. It was planned as the last step, since it changes nothing in
the code dampf generates. It was decided on 2026-11-18 that it comes: dampf should need nothing but Java. It was done the same day,
ahead of M5 ([archive/transition-to-lir.md](archive/transition-to-lir.md), phase C): `dampf build` uses its own
assembler and linker, `--assembler ca65` keeps the old path, and the tests build every program
both ways.

### Answered questions

- An own assembler and linker: yes, in M6 (ADR
  [record 19](adr/0019-dampf-assembles-and-links-itself-with-ca65-and.md)). An own debugger
  stays a non-goal ([record 20](adr/0020-mesen-is-the-debugger-and-the-dbg-file-means-what.md)).
- Build tool: Gradle ([record 2](adr/0002-java-25-antlr-with-the-grammars-v4-c-grammar-and.md)).
- Stage 2 platform API: no intrinsics needed; a platform layer in C with volatile register access
  ([record 21](adr/0021-the-platform-layer-is-c-with-volatile-pointers-and.md)).
- Whole ROMs headless in tests: Mesen 2's test runner (`Mesen.exe --testrunner rom.sfc
  script.lua`), as the game's `tools/snesbench` does.
- Mesen shows the C source with dampf's debug output: yes.
- Licence: Apache License 2.0 with LLVM Exceptions
  ([record 25](adr/0025-dampf-is-under-the-apache-license-20-with-llvm.md)).
- What the Calypsi investigation finds: dampf is faster on the same scene; the order of the
  optimizations follows the plan above.

## Relation to Blubber Blasen

Blubber Blasen is dampf's first user and its main test case. Its deterministic replays are the
oracle for correctness, and its busiest scene is the benchmark for speed. dampf works on a snapshot
in `snapshots/blubberblasen` (refreshed with `dev/update_game_snapshot.sh`), so a change in the
game that dampf cannot handle yet never breaks it. What dampf finds worth changing in the game goes
to [game-findings.md](game-findings.md); when dampf is finished, `docs/game-guide.md` explains how
to write game code that dampf compiles well.
