# Testing

Correctness is the biggest risk: a miscompile does not crash, it makes a bubble pass through a
wall in round 37, sometimes. Testing is therefore built in from the first day. Every optimization
is checked automatically against a reference.

## Four layers

| Layer | What | Speed | State |
| --- | --- | --- | --- |
| Unit tests | Each compiler pass on its own, at IR level | Milliseconds | Front-end tests exist (`ParserTest`, `AstBuilderTest`); IR-level tests come with the IRs |
| Execution tests | Small C programs compiled with gcc on the PC and with dampf; the results must match | Seconds | Built: `src/test/resources/exec/*.c`, run in dampf's emulator and in Mesen |
| Random programs | A generator produces thousands of random programs within the subset; each is compared against gcc | Minutes, e.g. nightly | Built: `RandomProgramTest`, 50 per build, `-Prandom=N` for more |
| Replays | The whole of Blubber Blasen with its deterministic replays and a checksum of the game state per frame; also cycles per frame as the benchmark | Minutes | Built: `ReplayTest` (`-Preplays`) and `tools/bench_game.sh` |

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

### How the replays work

The game records a play session as a `.bbr` file: the start (seed, save RAM, round, options),
the joypad words whenever they change, and a checksum of everything the game shows every 60
ticks. `tools/replay_to_c.py` turns a recording into C tables in ROM, and `tools/replay/driver.c`
plays them exactly like the game's own `replay_run()` (the game's `replay.c` is host code with
stdio and `malloc`, so the driver is written in dampf's subset instead). `ReplayTest` builds the
driver with the game in its debug configuration (`BB_DEBUG`) twice, with gcc and with dampf, and
requires both to match every recorded checksum and each other. One recording takes about two
minutes in Mesen, so the test runs only with `-Preplays`.

The game's compiler benchmark (`tools/bench_game.sh`) is the second whole-game check: 3,610 ticks,
three checksums per tick against a PC build of the same program, plus the cycles per tick.

## Supporting pieces

- **A 65816 CPU emulator in Java** (`dampf.emu`: CPU, LoROM, WRAM, no graphics or sound) runs
  the execution, random-program and replay tests and the game's benchmark inside the JVM, and
  **counts master cycles**, with the memory speeds of the SNES (WRAM and SlowROM 8, I/O 6,
  internal cycles 6, DRAM refresh 40 per scanline; [timing.md](timing.md)). It also runs the
  candidate sequences of the superoptimizer later ([optimizations.md](optimizations.md)).
  Validation:
  - the CPU against the SingleStepTests 65816 suite (github.com/SingleStepTests/65816): native
    mode, all 256 opcodes, about 300 random tests each (76,059 in all): registers, flags, memory,
    CPU cycles and the number of memory accesses match (`./gradlew test -PcpuTests`; downloads
    about 50 MB once). Emulation mode is only modelled as far as the start-up needs it.
  - the whole machine against Mesen on the game's benchmark: the same checksums on all 3,610
    ticks, and the same master cycles per tick within a few cycles out of millions
    (`dampf bench`).
  - the cycle table of the cost model (`dampf.target.Cycles65816`) against the emulator, every
    opcode in dampf's processor state (`CycleTableAgainstEmulatorTest`).

  The execution tests run in both the emulator and Mesen when Mesen is available; the random
  programs (50 per build) and the replays run in the emulator only.
- **Naive versus optimized:** the naive code generator stays as a reference. Every optimized
  build is compared against it; a difference points to the optimization that caused it.
- **Performance tracking:** cycle counts of the benchmarks are recorded per commit, so a slowdown
  is noticed.
- **Reference assembly benchmarks:** hand-written versions of hot functions as the yardstick for
  dampf's code ([optimizations.md](optimizations.md)).
- **Hints are checked on the PC:** every hint that states a fact is an assertion in the PC and
  test builds, and the replays run with the assertions enabled ([hints.md](hints.md)).

## The MIR level in the tests (built)

Every execution test and every random program also runs on the MIR level: lowered from the AST
and run by the MIR interpreter (`dampf.mir.Interpreter`), and compared with gcc. A wrong lowering
shows up there, independent of any 65816 code. The verifier (`dampf.mir.Verifier`) runs after the
lowering. `MirTextTest` prints the MIR of every execution program and of the whole game,
parses it back and checks that it verifies and prints to the same text; pass tests can therefore
be written as MIR text, as `NarrowTest`, `GvnTest` and `InlineTest` do. The interpreter can also check the range analysis: every value it computes must lie in its
inferred interval (`Ranges.checker`), in the execution tests, the random programs and the replay.
`StrengthReduceTest` compares every reduced
multiplication and division with the original in the interpreter (all 8-bit inputs, many
constants). The execution tests and the random programs also
run the MIR after the passes in the interpreter, with the verifier after every pass. The execution tests, the random programs and the replays also build every
program with the code generator from MIR and run it in the emulator. The game's benchmark runs in the MIR interpreter as well (`dampf mir --bench main ...`,
11 seconds) and gives the PC's checksums on all 3,610 ticks.

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

## When something hangs

A test must fail, not wait forever. Every program test (execution tests, random programs, replays,
the MIR round trips) runs under a hard time limit (`assertTimeoutPreemptively`: 3, 2, 15 and 2
minutes per program), because JUnit's default timeouts do not cover dynamic tests. External
programs (gcc, the PC builds, ca65, ld65) run through `dampf.util.Processes` with a time limit
and are killed when it runs out. The emulator stops after 600 frames in the program tests and
200,000 frames in `dampf bench` and the replays; the MIR interpreter counts its steps. The Gradle
test task has an overall limit of 2 hours as a last resort. A program that loops forever fails in
about a minute with "did not finish within 60 s and was stopped (does it hang?)".

