# Testing

Correctness is the biggest risk: a miscompile does not crash, it makes a bubble pass through a
wall in round 37, sometimes. Testing is therefore built in from the first day. Every optimization
is checked automatically against a reference.

## Four layers

| Layer | What | Speed | State |
| --- | --- | --- | --- |
| Unit tests | Each compiler pass on its own, at IR level | Milliseconds | Front-end tests exist (`ParserTest`, `AstBuilderTest`); IR-level tests come with the IRs |
| Execution tests | Small C programs compiled with gcc on the PC and with dampf; the results must match | Seconds | Built: `src/test/resources/exec/*.c`, run in Mesen |
| Random programs | A generator produces thousands of random programs within the subset; each is compared against gcc | Minutes, e.g. nightly | Built: `RandomProgramTest`, 10 per build, `-Prandom=N` for more |
| Replays | The whole of Blubber Blasen with its deterministic replays and a checksum of the game state per frame; also cycles per frame as the benchmark | Minutes | Not yet (M2, step 3) |

### How the execution and random tests work today

Each program is compiled twice: by gcc on the PC, with the undefined behaviour sanitizer (a test
program with undefined behaviour fails instead of passing by luck), and by dampf into a ROM that
runs headless in Mesen 2 (`--testrunner` with a Lua script). The dampf runtime writes to `$21FB`
when the entry function returns; the script then reads every global and writes it to a file. All
globals, flattened into their scalar elements (`a[2]`, `s.x`), must have the same values on both
sides. Pointers are left out (their values are addresses), and so is constant data in ROM.

The random program generator makes programs free of undefined behaviour by construction
(divisors made odd, shift counts masked, wide arithmetic in `uint32_t`) and favours boundary
values like −32768, where sign and overflow handling go wrong.

Mutation checks keep the tests honest: deliberately broken code generation must make tests fail.
When one did not (a 32-bit arithmetic shift whose high bits no test observed), a test was added.

## Supporting pieces

- **A 65816 CPU emulator in Java** (CPU only, no graphics or sound) runs the execution and
  random-program tests inside the JVM, without starting Mesen, and **counts cycles in master
  cycles**, with the exact memory speeds (FastROM, SlowROM, WRAM, I/O; [timing.md](timing.md)).
  Every test can then also report whether code got faster or slower. The emulator itself is
  validated against public per-instruction CPU test suites. It also runs the candidate sequences
  of the superoptimizer ([optimizations.md](optimizations.md)). *Not built yet* (start of M3,
  [roadmap.md](roadmap.md)); the tests use Mesen for now.
- **Naive versus optimized:** the naive code generator stays as a reference. Every optimized
  build is compared against it; a difference points to the optimization that caused it.
- **Performance tracking:** cycle counts of the benchmarks are recorded per commit, so a slowdown
  is noticed.
- **Reference assembly benchmarks:** hand-written versions of hot functions as the yardstick for
  dampf's code ([optimizations.md](optimizations.md)).
- **Hints are checked on the PC:** every hint that states a fact is an assertion in the PC and
  test builds, and the replays run with the assertions enabled ([hints.md](hints.md)).

## What the IR levels bring for testing

The three IR levels ([architecture.md](architecture.md)) are designed for testing:

1. **Every level has a text format** that can be printed *and parsed back*. Tests for a single
   pass then look like LLVM's: IR text in, run the pass, compare with the expected IR text. Such
   tests are precise and easy to generate in large numbers.
2. **Every level has a verifier** that checks its rules after every pass. In MIR: do widths
   match, is every value defined before it is used? In LIR: is the processor state consistent at
   every instruction? A broken pass is caught immediately, not three levels later as a wrong
   result.
3. **Interpreters for HIR and MIR.** The whole program can be executed on each level and the
   result compared with gcc on the PC and with the emulator. If HIR is still right and MIR is
   not, the bug is in the lowering between them. This narrows bugs down much faster than a wrong
   result at the end of the chain. The HIR interpreter is also an independent definition of what
   the program means, next to gcc.
