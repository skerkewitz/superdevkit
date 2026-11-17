# Roadmap

Milestones, risks and open questions. The plan was decided at the kickoff on 2026-11-17 and
extended by the design discussion the same day (hints, reference benchmarks, exact timing, three
IR levels). It was revised on 2026-11-17 after M1, see *Changes* below.

## Milestones

| Milestone | Goal | Done when | State |
| --- | --- | --- | --- |
| M0 | Define the subset | Blubber Blasen's `src/game` is scanned; [subset.md](subset.md) lists what is allowed, what is not, and what the game code has to change | Done 2026-11-17. The decisions in subset.md are implemented as recommended until decided. Still open: `goto` (decision 4). The hint macros: decided 2026-11-17 that the working names are the names, from `<dampf.h>`; `DAMPF_RANGE` and `DAMPF_BUDGET` are built ([hints.md](hints.md)). |
| M1 | End to end | A small function is compiled, assembled with ca65, linked with ld65, runs automatically in a test, and Mesen shows its C source while stepping | Done 2026-11-17 (stepping through C in Mesen checked by hand) |
| M2 | Naive but complete | All of Blubber Blasen compiles with the naive code generator, the game's benchmark and replays give the same checksums as the PC, cycles are measured per tick and per function and compared with Calypsi, and the first reference assembly function gives the first dampf/reference ratio | Done 2026-11-17, see the steps below. Open: 816-tcc and Calypsi on the same game version (on the game's side) |
| Checkpoint | Decide whether the plan reaches the budget | See below | Decided 2026-11-17: compiler and game in parallel (below) |
| M3 | The big levers | Optimizations A–D ([optimizations.md](optimizations.md)), built on the three IR levels. Goal: faster than Calypsi. Also: static best/worst case per function and the timing report, `DAMPF_BUDGET` checked at build time ([timing.md](timing.md)), the remaining reference functions; inferred value ranges and pointer compression ([subrange-types.md](subrange-types.md)) | In progress: MIR, the code generator from MIR (the default), the MIR passes (B, I, H, value ranges, inlining, induction variables, pointer compression), values kept in A and X, the static timing (best and worst case per function) and `DAMPF_BUDGET` are built, see below |
| M4 | Fine tuning | Optimizations E–N until the frame budget is met, page-aligned arrays with 8-bit index registers. Also: profile-guided optimization from the replays, superoptimization of short hot sequences, `DAMPF_TABLE`, `DAMPF_PIN_X` and the other transforming hints | Started early: the block layout by a profile (`dampf profile --blocks`, `dampf build --block-counts`) |
| M5 | Publishing | dampf can go public: own examples replace Blubber Blasen as test case and benchmark, dampf has its own preprocessor, the repository is hosted | Not started; see below |
| M6 | Standing alone | dampf has its own assembler and linker; using dampf needs nothing but Java | Not started. The very last step, taken only when everything else runs to our satisfaction |

### M2 in steps

The steps are ordered so that the most important question (does the game run on dampf, and how far
is it from the budget?) is answered as early as possible.

| Step | What | Done when | State |
| --- | --- | --- | --- |
| 1 | ROMs larger than 32 KB | Code and constant data spread over several ROM banks; calls across banks; no object crosses a bank boundary; a test program larger than one bank runs in Mesen | Done 2026-11-17 (`exec/banks.c` spans four banks) |
| 2 | The game's benchmark on dampf | `bench.c` from the game (in the snapshot) and all of `src/game` compile with dampf. `bench.c` stubs out every PVSnesLib call, so no platform layer is needed. Its per-tick checksums match the PC reference (`tools/snesbench/pc`), and its cycles per tick are compared with 816-tcc and Calypsi in [optimizations.md](optimizations.md) | Done 2026-11-17: all 3,610 ticks match the PC (`tools/bench_game.sh`). Open: 816-tcc and Calypsi on the same game version, run on the game's side. |
| 3 | The replays | A converter turns a replay (`tests/replays/*.bbr`: joypad words per tick, a checksum every 60 ticks) into an input table in ROM; the run on the SNES gives the recorded checksums. Replays need the game's debug code (`BB_DEBUG`) | Done 2026-11-17: `plus_bot_seed42.bbr` (3,000 ticks, 50 checksums) matches on the PC and on the SNES (`./gradlew test -Preplays`) |
| 4 | Cycles per function | A Mesen profile script (as in the game's `tools/snesbench/profile.lua`) gives cycles per function for the benchmark and the replays | Done 2026-11-17: `tools/profile_game.sh bench|replay` and `dampf profile`; results in [optimizations.md](optimizations.md) |
| 5 | First reference function | One hot function written by hand in assembly; its cycles against dampf's version give the first ratio ([optimizations.md](optimizations.md)) | Done 2026-11-17: `level_solid_px`, dampf's naive code is 4.5 times slower than hand-written (`reference/`, `dampf build --replace`) |

The game's change from finding 5 (`(unsigned)` casts in `video.c`, [game-findings.md](game-findings.md))
is in the snapshot since 2026-11-17: all 16 files of the game pass dampf's front end.

### Checkpoint after M2

During play, the game logic has to become about 4.5 times as fast as with Calypsi (6.4 times on the
worst tick) to fit 60 % of a frame. The optimizations are expected to bring a factor of 3–6 over
the naive code generator, which does almost everything in 32-bit `int` and will probably be slower
than Calypsi. With the measurements of M2 in hand, decide explicitly: does the plan reach the
budget, or does the game also have to do less work (for example a cheaper CPU partner or a
cheaper bubble separation, see [game-findings.md](game-findings.md))? The answer also sets the order
of the optimizations in M3 and M4.

#### The decision (2026-11-17)

The numbers after M2, in frames per tick of dampf's naive code:

| | Average | Worst tick |
| --- | --- | --- |
| Play | 5.17 | 8.58 |
| Play with the CPU partner | 6.55 | 12.23 |

The goal (0.6 frames) needs 9–11 times faster on average and 14–20 times on the worst tick. The
optimizations are expected to bring 3–6 times over the naive code, and the first reference
function agrees (hand-written `level_solid_px` is 4.5 times faster). Even if dampf reached
hand-written quality everywhere, play would be at about 1.2–1.7 frames on average and the worst
tick with the CPU partner at 3–4 frames. The compiler alone cannot close the gap; a factor of about
2–3 on average, more on the worst ticks, has to come from the game doing less work.

Options were: compiler first and decide after M3 (defer), compiler and game in parallel, or relax
the requirement (for example 30 Hz game logic on the heaviest ticks). **Decided: compiler and game
in parallel.** dampf goes on with M3; the game works now on the hot spots no compiler can fix: the
CPU partner (up to 3.7 extra frames on the worst tick) and the pairwise bubble separation. The
game's ROADMAP (step 2b) already plans this. The request to the game is finding 7 in
[game-findings.md](game-findings.md). Relaxing the requirement stays as a fallback, to be decided
on measured numbers after M3 if rare worst ticks still do not fit.

The game's changes happen in the game's repository; dampf follows by refreshing its snapshot and
measuring with the benchmark, the replays and the profile.

### Start of M3: the foundation for the optimizer

Before the first optimization, M3 builds:

- **The three IR levels** (HIR, MIR, LIR; [architecture.md](architecture.md)), each with a text
  format that can be parsed back and a verifier, and interpreters for HIR and MIR in the tests.
  MIR first (2026-11-17): lowering with SSA, text format, verifier and interpreter are built; the
  whole game runs on the MIR level with the PC's checksums. The text format parses back
  (`dampf.mir.MirParser`; printing, parsing and printing again gives the same text for every
  test program and the whole game). The simple code generator from MIR is built
  (`--backend mir`): correct on the execution tests, the random programs, the replay and the
  benchmark, and 3.7 times slower than the naive generator before any pass
  ([optimizations.md](optimizations.md)). The MIR passes have started: width narrowing (B)
  from the types and with 16-bit pointer offsets brings play from 18.93 to 8.16 frames per tick;
  constant multiplication and division (I) to 6.12 (naive: 5.17); the standard optimizations (H)
  to 5.17, the same as the naive generator, and 6.10 against 6.55 with the CPU partner.
  The optimizing pipeline is built next to the naive code generator, which keeps working on the
  AST: an independent reference catches more bugs than one that shares code with what it checks.
- **The Java 65816 CPU emulator** with master-cycle counting and the exact memory speeds
  ([testing.md](testing.md)), validated against public per-instruction test suites. It makes the
  tests fast and lets every test report cycles. Done 2026-11-17: passes the SingleStepTests
  native-mode suite and matches Mesen on the game's benchmark (checksums and master cycles); the
  benchmark takes 30 seconds instead of 6 minutes, a replay 11 instead of 100.
- **Cycle counts in the assembly comments** ([architecture.md](architecture.md)). Done 2026-11-17
  for CPU cycles per instruction and per straight-line C line.

Then the optimizations. B (from the types), I and H are built (see above). Next, in this order;
the profile after each step can change the order of the later ones:

1. **Compare and branch in one** in the code generator from MIR. Done 2026-11-17: play 5.17 →
   4.48 frames per tick, now 13 % faster than the naive generator.
2. **Inferred value ranges in MIR** ([subrange-types.md](subrange-types.md), section 7). Done
   2026-11-17 for constants, operations, conditions on the path and loops (`Ranges`,
   `RangeNarrow`): play 4.48 → 4.38, with the CPU partner 5.37 → 5.15. Since then also: loop
   bounds for the timing analysis, parameter ranges from the call sites (`ParamRanges`),
   conditions between two values, and narrowing after widening (play 1.53 → 1.50). Still open:
   ranges from array bounds.
3. **Pointer compression** (section 8 there): a points-to analysis over the whole program, and a
   pointer that only ever points into one array becomes an index (`b->x` as `lda bub_x,x` instead
   of a 4-byte pointer through `[dampf_ptr],y`). The game passes `Bubble *` everywhere. Needs the
   subset rules on integer/pointer casts and pointer arithmetic, which the game already keeps.
4. **Values in registers and the direct page** (A, C, D in [optimizations.md](optimizations.md)):
   the code generator stops sending every value through RAM. Started 2026-11-17: reloads of what
   A holds are left out, φs are written directly, and the frames lie in low RAM with the leaves in
   the direct page, values share places where their lifetimes allow, so all of the game's
   values are in the direct page; with inlining, indexed addressing and smaller changes play is
   at 1.53 frames per tick (1.50 after the range fixes in step 2, 1.41 after byte loads, short
   branches, `INC`/`DEC` and `STZ` in the code generator, [optimizations.md](optimizations.md)).

   Where the time goes now (play, game code only): loads and stores of values in the direct page
   are 37 % of the cycles, `JMP` 5.5 %, comparisons 5.7 %, masks 4.5 %. Next, in this order:

   1. **Register allocation across instructions:** done 2026-11-17 in the form the 65816 allows
      (the direct page is its register file): scheduling for hand-overs in A, comparisons and
      offsets taken from A, loop counters in X. Play 1.67 → 1.53 together with 2 and 3.
   2. **Induction variables:** done 2026-11-17 (`Induction`): play 1.61 → 1.57.
   3. **Pointer compression:** done 2026-11-17 for pointers into a single object, within functions
      and through parameters (`PointerOffsets`, `PointerParams`): play 1.56 → 1.54.

   **Static timing** (best and worst case per function, [timing.md](timing.md)): built
   2026-11-17 in CPU cycles and checked against the emulator on every call. The loop bounds
   were extended after it (unsigned counters, tests further down the loop, limits that are not
   constants): 38 of the game's functions are still unbounded, and the report names the loop
   or call that makes each of them so. The first hint, `DAMPF_RANGE` from `<dampf.h>`, bounds
   such loops by hand (`while (s[n]) { DAMPF_RANGE(n, 0, 10); n++; }`), checked on the PC and
   in the MIR interpreter, at no cost on the SNES. `DAMPF_BUDGET` is checked at build
   time (an error over the budget or without a worst case, a warning above 90 %).
   `DAMPF_TRIPS` is postponed ([hints.md](hints.md)). Next: master cycles and the costliest
   path per C line in the report and in the budget errors.

**Subrange types** (`DAMPF_RANGED`, `DAMPF_AS` and their checker, sections 3–6 there) are not
scheduled. They are built when a profile shows a hot value whose range dampf cannot infer and
that costs real cycles. Until then the design stays as it is.

### After M2: a playable ROM

Built 2026-11-17, ahead of the game's own SNES port: with every iteration of dampf, the real
game can be built and played, and the whole toolchain is tested on what only the console shows.

- **The platform layer** is `platform/snes`, in C: the 19 PVSnesLib-shaped calls of `snes.h`
  and the rest of its API (`snes.c`), and the main loop (`main.c`). It reaches the hardware
  through pointers to `volatile` memory and needs one new language feature, the cast from a
  pointer to an integer for DMA source addresses ([subset.md](subset.md), *Hardware access*).
- **No interrupt handler.** The loop waits for the vertical blank, then copies the OAM shadow and
  calls `game_vblank`. C code called from an NMI in the middle of a tick would overwrite the
  frames of the code it interrupted, since dampf keeps them at fixed places.
- **Not built:** sound (the game has none yet), save RAM (the high scores start empty at every
  power-on).
- **Building and checking:** `tools/build_game_rom.sh` writes `build/game/blubberblasen.sfc`.
  `tools/check_game_rom.sh` plays it in Mesen for a minute with scripted input, saves
  screenshots, and fails if any byte reaches VRAM, CGRAM or OAM outside a vertical blank.
  `ReplayTest` (`-Preplays`, with a Mesen) plays the recording through the platform layer: the
  same checksums as the PC, and no byte lost.

What the console showed that the benchmark could not:

- **The game runs at 2.28 frames per tick** during a minute of play (26 ticks a second), not 1.34:
  a tick that needs more than one frame waits for the start of the next vertical blank, and the
  benchmark leaves out what `video.c` costs on the hardware.
- **The vertical blank is mostly spent on C code.** `video_vblank` reaches its first upload about
  25 of the 37 lines into it. Every register write goes through `[dampf_ptr],y` with the pointer
  built first (about 30 cycles); a volatile access at a constant address should be one long
  `sta`/`lda`. A 32-bit shift by 16 is a loop of 16 steps (about 600 cycles); a shift by a
  multiple of 8 should move bytes. Both are worth doing next.
- **`snesDmaFits` must leave room for the code around a copy.** From the check to the start of a
  tileset copy takes 3.5 lines, and `video.c` asks once for two copies (tiles and palette).
  A copy that starts after the last line of the vertical blank is lost; the first version lost a
  row of the text layer ("READY!" stayed on screen). The margin is 8 lines now and can shrink when
  the register writes get faster.
- **With the naive code generator the game never gets its room tiles**: its vertical blank code is
  so slow that no upload ever fits ([game-findings.md](game-findings.md), finding 8).

### M5: publishing

- **Own examples.** The Blubber Blasen snapshot (`examples/blubberblasen`), its platform layer
  (`platform/snes`) and the hand-written
  benchmark in `reference/` (assembly of a Blubber Blasen function) leave the repository. Deleting
  them is not enough: they are in the git history, so either the history is cleaned
  (`git filter-repo`) or the public repository starts without it. Own examples take over their
  jobs: a program large enough to be a test case, a busy scene as speed benchmark, and new
  hand-written reference functions.
- **Own preprocessor.** Today dampf calls `gcc -E`, which users on Windows have to install
  (MinGW or MSYS) only for that. dampf gets its own C preprocessor in Java: macro expansion
  following Prosser's algorithm, `#` and `##`, variadic macros with `__VA_OPT__`, `#if`
  arithmetic in `intmax_t`, line markers for the debug information. `gcc -E` stays in the tests
  as the oracle: the output of dampf's preprocessor is compared with gcc's on every test program,
  every example and on generated macro cases. After this, gcc is only needed to develop dampf
  (the PC side of the execution tests), not to use it.
- **Hosting**, and the book online.

### M6: standing alone

dampf assembles and links itself, so ca65 and ld65 are no longer needed and using dampf needs
nothing but Java. dampf only uses a small part of ca65 (its own output) and of ld65 (its own
memory map), so both stay as the oracle in the tests: the ROM dampf builds itself must be
byte-identical to the one ca65 and ld65 build from the same assembly, and the debug information
must still let Mesen show the C source. This is deliberately the last step: it changes nothing
in the code dampf generates, so it waits until everything else runs to our satisfaction.

### Changes

- **2026-11-17, after M1.** The design discussion had added three items to M1: the Java CPU
  emulator with cycle counting, cycle counts in the assembly comments, and the three IR levels
  with text format and verifier (and HIR/MIR interpreters to M2). M1 was completed by its original
  definition without them. They move to the start of M3: the IR levels and interpreters protect
  lowering and optimization passes, which do not exist before M3, and the M2 measurements work
  with Mesen. M2 was split into steps so the first measurement of the whole game comes early, the
  checkpoint was added, and the playable ROM was separated from M2.
- **2026-11-17, during M3.** The playable ROM was built before the game's own SNES port needs it,
  so that every iteration of dampf can be played and the whole toolchain is tested on the real
  console (see *After M2: a playable ROM*).
- **2026-11-17, during M3.** The design for value ranges ([subrange-types.md](subrange-types.md))
  was added. Inferred ranges and pointer compression (planned for M4 in that design) go into
  M3, because they need no language change and the profile points at them. Declared subrange
  types wait until a profile shows a range dampf cannot infer.

## Risks

- **Miscompilation.** Mitigated by the four test layers, the naive reference and the replays as
  oracle ([testing.md](testing.md)).
- **Different behaviour on PC and SNES.** Mitigated by fixed-width types only, exact promotion
  rules and the differential tests against gcc.
- **Mesen cannot show the C source with our debug output.** Checked in M1, before the code
  generator was settled: it works.
- **Scope creep.** The compiler could become the project and stall the game. Mitigated by running
  it in parallel and by milestones that are measured against Blubber Blasen.
- **Gains smaller than hoped.** Each optimization is measured; the catalogue is re-prioritised
  after M2.
- **A wrong hint silently produces wrong code.** Mitigated by checking every fact hint as an
  assertion on the PC, with the replays running against them ([hints.md](hints.md)).

## Open questions

Resolved:

- ~~Build tool: Gradle or Maven?~~ Gradle (Kotlin DSL, with the wrapper), decided 2026-11-17.
- ~~How Blubber Blasen's stage 2 platform API maps onto dampf intrinsics~~: no intrinsics are
  needed. The API is a platform layer in C with volatile register access (`platform/snes`),
  decided 2026-11-17.
- ~~How whole ROMs run headless in tests~~: Mesen 2 has a test runner mode
  (`Mesen.exe --testrunner rom.sfc script.lua`). Blubber Blasen's `tools/snesbench` already uses
  it to measure compilers tick by tick, with checksums against the PC; dampf's execution tests
  use it too.
- ~~Whether Mesen shows the C source with dampf's debug output~~: yes, stepping through C works
  (2026-11-17).
- ~~Licence~~: Apache License 2.0 with LLVM Exceptions, the same as LLVM (2026-11-17). The
  exception keeps the games free: code dampf puts into a ROM carries no licence conditions.

Open:

- How Mesen shows locals that live in fixed direct page slots (with the naive generator they are
  in RAM; the `.dbg` file declares them as static C symbols).
- What the Calypsi investigation finds, and what that means for the order of the optimizations.
  First numbers are in [optimizations.md](optimizations.md): Calypsi needs 2.72 frames per tick
  during play, so the 0.6-frame goal is about 4.5 times faster than Calypsi.
- `goto`: forbid it entirely, or allow a restricted form that keeps the code structured?
  ([subset.md](subset.md), decision 4)
- How to express impossible path combinations, should the worst-case bounds turn out too
  pessimistic in practice ([timing.md](timing.md)).
- How `restrict` is checked on the PC in practice, and whether a dampf-specific no-alias hint is
  clearer than `restrict` ([hints.md](hints.md)).
- Which functions become reference benchmarks: decided once the Calypsi investigation and the
  first profiles show where the cycles go.
- How a linked-in hand-written assembly function declares its calling convention, direct page
  usage and cycle cost, so the timing analysis can include it.
- How much HDMA the game uses, since it changes the frame-level budget ([timing.md](timing.md)).

## Relation to Blubber Blasen

Blubber Blasen is dampf's first user and its main test case. Its deterministic replays (recorded
input, checksum of the game state per frame) are the oracle for correctness, and its busiest scene
is the benchmark for speed. Both projects run in parallel: the game keeps going on the PC, and
dampf catches up until it can build the SNES version.

dampf works on a snapshot of the game's code in `examples/blubberblasen` (refreshed with
`tools/update_game_snapshot.sh`), so the compiler is never broken by a change in the game that it
cannot handle yet. What dampf finds worth changing in the game is collected in
[game-findings.md](game-findings.md) and passed on to the game. When dampf is finished, a guide
(`docs/game-guide.md`) explains how to write game code that dampf compiles well.
