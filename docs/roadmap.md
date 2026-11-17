# Roadmap

Milestones, risks and open questions. The plan was decided at the kickoff on 2026-11-17 and
extended by the design discussion the same day (hints, reference benchmarks, exact timing, three
IR levels). It was revised on 2026-11-17 after M1, see *Changes* below.

## Milestones

| Milestone | Goal | Done when | State |
| --- | --- | --- | --- |
| M0 | Define the subset | Blubber Blasen's `src/game` is scanned; [subset.md](subset.md) lists what is allowed, what is not, and what the game code has to change | Done 2026-11-17. The decisions in subset.md are implemented as recommended until decided. Still open: `goto` (decision 4) and the names and set of the hint macros with their PC assertions ([hints.md](hints.md)); both are needed by M3. |
| M1 | End to end | A small function is compiled, assembled with ca65, linked with ld65, runs automatically in a test, and Mesen shows its C source while stepping | Done 2026-11-17 (stepping through C in Mesen checked by hand) |
| M2 | Naive but complete | All of Blubber Blasen compiles with the naive code generator, the game's benchmark and replays give the same checksums as the PC, cycles are measured per tick and per function and compared with Calypsi, and the first reference assembly function gives the first dampf/reference ratio | In progress, see the steps below |
| Checkpoint | Decide whether the plan reaches the budget | See below | After M2 |
| M3 | The big levers | Optimizations A–D ([optimizations.md](optimizations.md)), built on the three IR levels. Goal: faster than Calypsi. Also: static best/worst case per function and the timing report, `DAMPF_BUDGET` checked at build time ([timing.md](timing.md)), the remaining reference functions | Not started |
| M4 | Fine tuning | Optimizations E–N until the frame budget is met. Also: profile-guided optimization from the replays, superoptimization of short hot sequences, `DAMPF_TABLE`, `DAMPF_PIN_X` and the other transforming hints | Not started |

### M2 in steps

The steps are ordered so that the most important question (does the game run on dampf, and how far
is it from the budget?) is answered as early as possible.

| Step | What | Done when | State |
| --- | --- | --- | --- |
| 1 | ROMs larger than 32 KB | Code and constant data spread over several ROM banks; calls across banks; no object crosses a bank boundary; a test program larger than one bank runs in Mesen | Done 2026-11-17 (`exec/banks.c` spans four banks) |
| 2 | The game's benchmark on dampf | `bench.c` from the game (in the snapshot) and all of `src/game` compile with dampf. `bench.c` stubs out every PVSnesLib call, so no platform layer is needed. Its per-tick checksums match the PC reference (`tools/snesbench/pc`), and its cycles per tick are compared with 816-tcc and Calypsi in [optimizations.md](optimizations.md) | Next |
| 3 | The replays | A converter turns a replay (`tests/replays/*.bbr`: joypad words per tick, a checksum every 60 ticks) into an input table in ROM; the run on the SNES gives the recorded checksums. Replays need the game's debug code (`BB_DEBUG`) | Open |
| 4 | Cycles per function | A Mesen profile script (as in the game's `tools/snesbench/profile.lua`) gives cycles per function for the benchmark and the replays | Open |
| 5 | First reference function | One hot function written by hand in assembly; its cycles against dampf's version give the first ratio ([optimizations.md](optimizations.md)) | Open |

The game's change from finding 5 (`(unsigned)` casts in `video.c`, [game-findings.md](game-findings.md))
is needed before step 2.

### Checkpoint after M2

During play, the game logic has to become about 4.5 times as fast as with Calypsi (6.4 times on the
worst tick) to fit 60 % of a frame. The optimizations are expected to bring a factor of 3–6 over
the naive code generator, which does almost everything in 32-bit `int` and will probably be slower
than Calypsi. With the measurements of M2 in hand, decide explicitly: does the plan reach the
budget, or does the game also have to do less work (for example a cheaper CPU partner or a
cheaper bubble separation, see [game-findings.md](game-findings.md))? The answer also sets the order
of the optimizations in M3 and M4.

### Start of M3: the foundation for the optimizer

Before the first optimization, M3 builds:

- **The three IR levels** (HIR, MIR, LIR; [architecture.md](architecture.md)), each with a text
  format that can be parsed back and a verifier, and interpreters for HIR and MIR in the tests.
  The optimizing pipeline is built next to the naive code generator, which keeps working on the
  AST: an independent reference catches more bugs than one that shares code with what it checks.
- **The Java 65816 CPU emulator** with master-cycle counting and the exact memory speeds
  ([testing.md](testing.md)), validated against public per-instruction test suites. It makes the
  tests fast and lets every test report cycles.
- **Cycle counts in the assembly comments** ([architecture.md](architecture.md)).

### After M2: a playable ROM

A ROM that plays the game on real hardware needs a platform layer for dampf: start-up, joypad
input, the vertical blank handler with DMA for sprites, tile maps and palettes, and the 19
PVSnesLib-shaped calls that `video.c` makes ([subset.md](subset.md)). It is not needed for the M2
measurements (the benchmark stubs those calls out) and is planned when the game's SNES port (its
stage 3 proof of concept) needs it.

### Changes

- **2026-11-17, after M1.** The design discussion had added three items to M1: the Java CPU
  emulator with cycle counting, cycle counts in the assembly comments, and the three IR levels
  with text format and verifier (and HIR/MIR interpreters to M2). M1 was completed by its original
  definition without them. They move to the start of M3: the IR levels and interpreters protect
  lowering and optimization passes, which do not exist before M3, and the M2 measurements work
  with Mesen. M2 was split into steps so the first measurement of the whole game comes early, the
  checkpoint was added, and the playable ROM was separated from M2.

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
- ~~How whole ROMs run headless in tests~~: Mesen 2 has a test runner mode
  (`Mesen.exe --testrunner rom.sfc script.lua`). Blubber Blasen's `tools/snesbench` already uses
  it to measure compilers tick by tick, with checksums against the PC; dampf's execution tests
  use it too.
- ~~Whether Mesen shows the C source with dampf's debug output~~: yes, stepping through C works
  (2026-11-17).

Open:

- Repository hosting and licence (the compiler should be open source like the game).
- How Mesen shows locals that live in fixed direct page slots (with the naive generator they are
  in RAM; the `.dbg` file declares them as static C symbols).
- What the Calypsi investigation finds, and what that means for the order of the optimizations.
  First numbers are in [optimizations.md](optimizations.md): Calypsi needs 2.72 frames per tick
  during play, so the 0.6-frame goal is about 4.5 times faster than Calypsi.
- How Blubber Blasen's stage 2 platform API maps onto dampf intrinsics ([subset.md](subset.md)
  lists the 19 calls `video.c` makes); needed for the playable ROM.
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
