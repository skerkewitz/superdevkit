# Roadmap

Milestones, risks and open questions. The plan was set at the kickoff on 2026-11-17, extended by
the design discussion the same day (hints, reference benchmarks, exact timing, three IR levels) and
revised after M1 (see [History](#history)).

## Where dampf stands

M0 to M2 are done: all of Blubber Blasen compiles, and the benchmark and the replays give the PC's
checksums, on the SNES and in a playable ROM. M3 is in progress, and its goal, faster than Calypsi,
is reached. Parts of M4 have started early. During play the game takes 0.79 frames per tick
against a goal of 0.6, so the compiler work goes on while the game reduces its own work in
parallel. The current numbers are in [optimizations.md](optimizations.md#where-things-stand).
M5 has not started. M6 is done (2026-11-18): dampf assembles and links itself.

## Milestones

| Milestone | Goal | Done when | State |
| --- | --- | --- | --- |
| M0 | Define the subset | Blubber Blasen's `src/game` is scanned; [subset.md](subset.md) lists what is allowed, what is not, and what the game code has to change | Done 2026-11-17; `goto` (decision 4) still open |
| M1 | End to end | A small function is compiled, assembled with ca65, linked with ld65, runs automatically in a test, and Mesen shows its C source while stepping | Done 2026-11-17 |
| M2 | Naive but complete | All of Blubber Blasen compiles with the naive code generator, the game's benchmark and replays give the same checksums as the PC, cycles are measured per tick and per function and compared with Calypsi, and the first reference assembly function gives the first dampf/reference ratio | Done 2026-11-17 |
| Checkpoint | Decide whether the plan reaches the budget | See [History](#checkpoint-after-m2) | Decided 2026-11-17: compiler and game in parallel |
| M3 | The big levers | Optimizations A–D ([optimizations.md](optimizations.md)), built on the three IR levels. Goal: faster than Calypsi (reached 2026-11-17). Also: static best/worst case per function and the timing report, `DAMPF_BUDGET` checked at build time ([timing.md](timing.md)), the remaining reference functions; inferred value ranges and pointer compression ([subrange-types.md](subrange-types.md)) | In progress: MIR, its passes, LIR and the code generator from MIR built; HIR and the rest open (below) |
| M4 | Fine tuning | Optimizations E–N until the frame budget is met, page-aligned arrays with 8-bit index registers. Also: profile-guided optimization from the replays, superoptimization of short hot sequences, `DAMPF_TABLE`, `DAMPF_PIN_X` and the other transforming hints | Started early: E, F, G, J, K and N in progress, block layout by a profile built |
| M5 | Publishing | dampf can go public: own examples replace Blubber Blasen as test case and benchmark, dampf has its own preprocessor, the repository is hosted | Not started |
| M6 | Standing alone | dampf has its own assembler and linker; using dampf needs nothing but Java | Done 2026-11-18, ahead of M5 ([transition-to-lir.md](transition-to-lir.md), phase C); gcc is still needed until M5's preprocessor |

## What is left in M3 and M4

The open rows of the plan made on 2026-11-17, by likely gain on play and effort. The gains are
estimates from the profile's shares, not measurements; several ideas that looked good measured
zero ([optimizations.md](optimizations.md#tried-and-rejected)). The numbers follow the original plan; the done rows
(1 FastROM, 12 hardware registers at constant addresses) are in [History](#history).

| # | Idea | Likely gain | Effort | Notes |
| --- | --- | --- | --- | --- |
| 2 | Register allocation: values in A, X and Y over several instructions instead of a direct page place each | 10-20 % | high | Loads and stores of direct page values are the largest share of the cycles. With one accumulator, choosing what lives where is the hard part. In progress (steps in History). Next: stores moved to the path that needs them, fewer reloads. |
| 3 | 8-bit stretches: the M flag followed (`REP`/`SEP`), `uint8_t` code in 8-bit mode | 5-10 % | high | Fewer `AND #$00FF`, and 8-bit accesses are a cycle cheaper. Needs the processor state across blocks. Only a small start: 8-bit mode kept across C lines within a block. |
| 4 | Arrays of structs as one array per field (catalogue D, `DAMPF_SPLIT`) | 5-10 % in the bubble loops | high | `separate`, `bubbles_update` and `bubbles_draw` are 30 %. Needs a subset decision or a hint, since the layout then differs from the PC. |
| 5 | 8-bit index registers, arrays aligned to pages | 2-4 % | high | `LDA abs,X` a cycle cheaper; needs the X flag followed and the alignment in the memory map. |
| 6 | Comparisons on the flags: `BIT` for bit tests | 2-4 % for the whole row | low to medium | Done for `CMP #0`; `BIT` not tried. Most remaining `CMP #imm` compare with other constants and feed `BCC`/`BCS`. |
| 7 | Calling convention (catalogue C): the first argument in A, `JMP` for tail calls | 1-3 % | medium | `ground_px` is called 12,000 times in 800 ticks. |
| 8 | The block layout by a profile by default (from the replays) | ~1 % | low | Built (`--block-counts`), opt-in; needs a profile in the game's build. |
| 9 | Value ranges from array bounds | 1-2 % | medium | More 8-bit narrowing, fewer masks. |
| 10 | Superoptimizer for short hot sequences | 1-3 % | high | Gains spread thin. |
| 11 | `<<8` as a byte store, `switch` as a jump table, `MVN` for copies, unrolling by hint | <1 % each | low to medium | Large locally, small on the whole game. |

The order: register allocation (2), or the split of struct arrays (4) once its subset question is
decided; then 3, 5 and 7. FastROM and rows 2 to 6 together might bring play to about 0.85-1.0
frames per tick. The rest has to come from the game doing less work: the pair loop of `separate`
(16 %) and the CPU partner ([game-findings.md](game-findings.md), finding 7), as the checkpoint
after M2 concluded.

Also open in M3:

- **HIR.** MIR is lowered straight from the AST; HIR is open and not scheduled.
  [architecture.md](architecture.md) places the split of struct arrays (row 4) there. LIR is built
  ([transition-to-lir.md](transition-to-lir.md)); the processor state across blocks (rows 3 and
  5) and peephole rules are its next passes.
- **Master cycles per C line.** The assembly comments and the timing report count CPU cycles.
  Next: master cycles and the costliest path per C line in the report and in the budget errors.
- **Unbounded loops.** 38 of the game's functions have no worst case yet; the report names the
  loop or call that causes it, and `DAMPF_RANGE` bounds such loops by hand. `DAMPF_TRIPS` is
  postponed ([hints.md](hints.md)).
- **Ranges from array bounds** (row 9).
- **Subrange types** (`DAMPF_RANGED`, `DAMPF_AS` and their checker, sections 3–6 of
  [subrange-types.md](subrange-types.md)) are not scheduled. They are built when a profile shows a
  hot value whose range dampf cannot infer and that costs real cycles.

## The playable ROM

Built 2026-11-17, ahead of the game's own SNES port, so that every iteration of dampf can be played
and the toolchain is tested on what only the console shows.

- **The platform layer** is `platform/snes`, in C: the 19 PVSnesLib-shaped calls of `snes.h` and
  the rest of its API (`snes.c`), and the main loop (`main.c`). It reaches the hardware through
  pointers to `volatile` memory and needs one language feature of its own, the cast from a pointer
  to an integer for DMA source addresses ([subset.md](subset.md), *Hardware access*).
- **No interrupt handler.** The loop waits for the vertical blank, then copies the OAM shadow and
  calls `game_vblank`. C code called from an NMI in the middle of a tick would overwrite the frames
  of the code it interrupted, since dampf keeps them at fixed places.
- **Not built:** sound (the game has none yet), save RAM (the high scores start empty at every
  power-on).
- **Building and checking:** `tools/build_game_rom.sh` writes `build/game/blubberblasen.sfc`.
  `tools/check_game_rom.sh` plays it in Mesen for a minute with scripted input, saves screenshots,
  and fails if any byte reaches VRAM, CGRAM or OAM outside a vertical blank. `ReplayTest`
  (`-Preplays`, with a Mesen) plays the recording through the platform layer: the same checksums
  as the PC, and no byte lost.

The console showed things the benchmark could not. A tick that needs more than one frame waits for
the next vertical blank, and the benchmark leaves out what `video.c` costs on the hardware. With the
naive code generator the vertical blank code is so slow that the room tiles never fit
([game-findings.md](game-findings.md), finding 8). The console figure (frames per tick during a
minute of play, measured again on 2026-11-18) is in
[optimizations.md](optimizations.md#where-things-stand).

Open:

- **Constant shifts longer than 8 still loop.** A 32-bit shift by 16 is a loop of 16 steps (about
  600 cycles); a shift by a multiple of 8 should move bytes.
- **The DMA margin is not measured again.** `snesDmaFits` leaves `SETUP_LINES` (8) lines for the
  code around a copy, because a copy that starts after the vertical blank is lost (the first
  version lost a row of the text layer). The margin can shrink since the register writes got
  faster.

## M5: publishing

- **Own examples.** The Blubber Blasen snapshot (`examples/blubberblasen`), its platform layer
  (`platform/snes`) and the hand-written benchmark in `reference/` (assembly of a Blubber Blasen
  function) leave the repository. Deleting them is not enough: they are in the git history, so
  either the history is cleaned (`git filter-repo`) or the public repository starts without it.
  Own examples take over their jobs: a program large enough to be a test case, a busy scene as
  speed benchmark, and new hand-written reference functions.
- **Own preprocessor.** Today dampf calls `gcc -E`, which users on Windows have to install (MinGW
  or MSYS) only for that. dampf gets its own C preprocessor in Java: macro expansion following
  Prosser's algorithm, `#` and `##`, variadic macros with `__VA_OPT__`, `#if` arithmetic in
  `intmax_t`, line markers for the debug information. `gcc -E` stays in the tests as the oracle:
  the output of dampf's preprocessor is compared with gcc's on every test program, every example
  and on generated macro cases. After this, gcc is only needed to develop dampf (the PC side of
  the execution tests), not to use it.
- **Hosting**, and the book online.

## After M5: better C debugging in Mesen

Mesen shows the C source and the names of global variables from the `.dbg` file, and that is
all: of a C symbol (`csym`) it reads only the name and the assembly symbol it belongs to, and the
type that cc65 and dampf write is always `"00"`. dampf's users would gain from variables shown
with their C type (`uint8_t`, signed `int16_t`), struct fields, and the locals of a function in
their direct page slots. The cc65 format already has fields for part of this (a storage class
`sc` and an offset on `csym`) that Mesen ignores. The plan: dampf first fills what the format
has, then a small patch teaches Mesen to read it, offered to Mesen's author. NES developers who
use cc65 would gain from it too. This comes when dampf is public and has users, since a patch
from a compiler with users is easier to argue for.

## M6: standing alone

dampf assembles and links itself, so ca65 and ld65 are no longer needed and using dampf needs
nothing but Java. dampf only uses a small part of ca65 (its own output) and of ld65 (its own
memory map), so both stay as the oracle in the tests: the ROM dampf builds itself must be
byte-identical to the one ca65 and ld65 build from the same assembly, and the debug information
must still let Mesen show the C source. It was planned as the last step, since it changes nothing in
the code dampf generates. It was decided on 2026-11-18 that it comes: dampf should need nothing but Java. It was done the same day,
ahead of M5 ([transition-to-lir.md](transition-to-lir.md), phase C): `dampf build` uses its own
assembler and linker, `--assembler ca65` keeps the old path, and the tests build every program
both ways.

## Risks

- **Miscompilation.** Mitigated by the four test layers, the naive reference and the replays as
  oracle ([testing.md](testing.md)).
- **Different behaviour on PC and SNES.** Mitigated by fixed-width types only, exact promotion
  rules and the differential tests against gcc.
- **Scope creep.** The compiler could become the project and stall the game. Mitigated by running
  it in parallel and by milestones that are measured against Blubber Blasen.
- **Gains smaller than hoped.** Each optimization is measured; the catalogue was re-prioritised
  after M2.
- **A wrong hint silently produces wrong code.** Mitigated by checking every fact hint as an
  assertion on the PC, with the replays running against them ([hints.md](hints.md)).

## Open questions

- **A miscompilation in the code generator, not yet found** (2026-11-18). With functions of up to
  80 instructions inlined (the limit is 60), the game's benchmark differs from the PC in 56 of
  3,610 ticks, from tick 32 on. What is known: the optimized MIR is right (the interpreter gives
  the PC's checksums, every value within its range); ca65 and dampf's assembler build the same
  ROM; it stays with every LIR pass, `PointerRemat`, branch relaxation, the new block order,
  loop counters in X, indexes in Y, values handed on in A and arguments computed in place
  switched off. `main` before the branch `optimise` with the same limit is right, but there the
  limit counts unsimplified bodies and fewer functions are inlined. So an older part of the code
  generator (selection, `FrameLayout`, the masks) goes wrong on a function the larger inlining
  makes. Next step: find the function by comparing the emulator's globals with the
  interpreter's at tick 32, then reduce it to an execution test.

- How Mesen shows locals that live in fixed direct page slots. The code generator from MIR keeps
  the game's values in the direct page, several values sharing a place; the `.dbg` file names the
  source files and lines (and, from the naive generator, the functions), not the locals.
- Why Calypsi's results drift from the PC's from tick 596 on: a miscompile, RAM its start-up
  leaves uncleared, or a step of the game's own Calypsi build that `tools/bench_compilers.sh`
  lacks.
- `goto`: forbid it entirely, or allow a restricted form that keeps the code structured?
  ([subset.md](subset.md), decision 4)
- How to express impossible path combinations, should the worst-case bounds turn out too
  pessimistic in practice ([timing.md](timing.md)).
- How `restrict` is checked on the PC in practice, and whether a dampf-specific no-alias hint is
  clearer than `restrict` ([hints.md](hints.md)).
- Which functions become reference benchmarks. The Calypsi comparison and the profiles that were
  to decide it are in; `level_solid_px` is still the only one (`reference/`).
- How a linked-in hand-written assembly function declares its calling convention, direct page
  usage and cycle cost, so the timing analysis can include it.
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

### M0 to M2

- **M0.** The decisions in subset.md are implemented as recommended until decided. The hint macros
  keep their working names, from `<dampf.h>`.
- **M1.** Stepping through C in Mesen was checked by hand.
- **M2, step 1: ROMs larger than 32 KB.** Code and constant data spread over several banks, calls
  across banks, no object across a bank boundary; `exec/banks.c` spans four banks.
- **Step 2: the game's benchmark.** `bench.c` and all of `src/game` compile; all 3,610 ticks match
  the PC (`tools/bench_game.sh`). The game's finding 5 (`(unsigned)` casts in `video.c`) is in
  the snapshot, so all 16 files pass the front end.
- **Step 3: the replays.** A converter turns a replay into an input table in ROM;
  `plus_bot_seed42.bbr` (3,000 ticks, 50 checksums) matches on the PC and on the SNES
  (`./gradlew test -Preplays`).
- **Step 4: cycles per function.** `tools/profile_game.sh bench|replay` and `dampf profile`.
- **Step 5: first reference function.** `level_solid_px` by hand (`reference/`,
  `dampf build --replace`); dampf's naive code was 4.5 times slower.

### Checkpoint after M2

The question was whether the optimizations reach the budget or the game also has to do less work.
After M2 the goal of 0.6 frames needed the naive code to get 9–11 times faster on average and
14–20 times on the worst tick. The optimizations were expected to bring 3–6 times, and the first
reference function agreed (4.5 times). Even at hand-written quality everywhere, play would have
stayed at about 1.2–1.7 frames on average and the worst tick with the CPU partner at 3–4 frames.
The compiler alone cannot close the gap.

The options were: compiler first and decide after M3, compiler and game in parallel, or relax the
requirement (for example 30 Hz game logic on the heaviest ticks). **Decided: compiler and game in
parallel.** dampf goes on with M3; the game works on the hot spots no compiler can fix, the CPU
partner and the pairwise bubble separation, as its own ROADMAP (step 2b) already planned. The
request to the game is finding 7 in [game-findings.md](game-findings.md). Relaxing the requirement
stays as a fallback, decided on measured numbers if rare worst ticks still do not fit. The game's
changes happen in its own repository; dampf refreshes its snapshot and measures.

### Done in M3 and M4

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

### Answered questions

- An own assembler and linker: yes, in M6, so that dampf needs nothing but Java (decided
  2026-11-18). An own debugger stays a non-goal.
- Build tool: Gradle (Kotlin DSL, with the wrapper).
- Stage 2 platform API: no intrinsics needed; a platform layer in C with volatile register access
  (`platform/snes`).
- Whole ROMs headless in tests: Mesen 2's test runner (`Mesen.exe --testrunner rom.sfc
  script.lua`), as the game's `tools/snesbench` does.
- Mesen shows the C source with dampf's debug output: yes.
- Licence: Apache License 2.0 with LLVM Exceptions, the same as LLVM, so code dampf puts into a
  ROM carries no licence conditions.
- What the Calypsi investigation finds: dampf is faster on the same scene; the order of the
  optimizations follows the plan above.

## Relation to Blubber Blasen

Blubber Blasen is dampf's first user and its main test case. Its deterministic replays are the
oracle for correctness, and its busiest scene is the benchmark for speed. dampf works on a snapshot
in `examples/blubberblasen` (refreshed with `tools/update_game_snapshot.sh`), so a change in the
game that dampf cannot handle yet never breaks it. What dampf finds worth changing in the game goes
to [game-findings.md](game-findings.md); when dampf is finished, `docs/game-guide.md` explains how
to write game code that dampf compiles well.
