# Roadmap

Milestones, risks and open questions. The plan was decided at the kickoff on 2026-11-17 and
extended by the design discussion the same day (hints, reference benchmarks, exact timing, three
IR levels).

## Milestones

The "Done when" column is the kickoff's definition. "Also" lists what the design discussion added
to each milestone.

| Milestone | Goal | Done when | Also | State |
| --- | --- | --- | --- | --- |
| M0 | Define the subset | Blubber Blasen's `src/game` is scanned; [subset.md](subset.md) lists what is allowed, what is not, and what the game code has to change | Decide on `goto` (forbidden, or restricted to keep code structured for the timing analysis). Define the hint macros and their PC assertions ([hints.md](hints.md)). | Done 2026-11-17; the decisions in subset.md are implemented as recommended until decided. Open: `goto`, the hint macros. |
| M1 | End to end | A small function is compiled, assembled with ca65, linked with ld65, runs automatically in a test, and Mesen shows its C source while stepping | The Java 65816 CPU emulator counts master cycles with the exact memory speeds. Cycle counts appear in the assembly comments from the start. All three IR levels exist with text format and verifier, even if their passes are still trivial. | Done 2026-11-17 (stepping through C in Mesen checked by hand). The additions are **not** done: no Java emulator (tests run in Mesen), no cycle counts in the comments, no IR levels yet (the naive generator works on the AST). They are carried over into M2. |
| M2 | Naive but complete | All of Blubber Blasen compiles, all replays pass, cycles per frame are measured and compared with Calypsi | Measured cycles per function from the replays. First reference assembly function, so the first dampf/reference ratio exists. HIR and MIR interpreters run in the tests. | In progress: the naive generator covers the C the game uses; 15 of 16 game files pass the front end. Missing: ROMs larger than 32 KB, the SNES platform layer, the whole game, replays, measurements, and the carried-over M1 additions. |
| M3 | The big levers | Optimizations A–D ([optimizations.md](optimizations.md)). Goal: faster than Calypsi | Static best/worst case per function and the timing report. `DAMPF_BUDGET` checked at build time ([timing.md](timing.md)). Remaining reference functions. | Not started |
| M4 | Fine tuning | Optimizations E–N until the frame budget is met | Profile-guided optimization from the replays. Superoptimization of short hot sequences. `DAMPF_TABLE`, `DAMPF_PIN_X` and the other transforming hints. | Not started |

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
  lists the 19 calls `video.c` makes).
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
