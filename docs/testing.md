# Testing

Correctness is the biggest risk: a miscompile does not crash, it makes a bubble pass through a
wall in round 37, sometimes. So every optimization is checked automatically against a reference.
The Gradle calls and their properties are in the book, [docs/book/inside/testing.md](book/inside/testing.md).

## The principle

The reference is gcc on the PC. Each test program is compiled by gcc with the undefined
behaviour sanitizer (UBSan: a program with undefined behaviour fails instead of passing by
luck), and by dampf into two ROMs, one from the naive code generator and one from the code
generator from MIR. The naive code generator stays as an independent reference: both ROMs must
give gcc's results, and a difference between them points to the optimization that caused it.

The program also runs on the MIR level, in the MIR interpreter (`dampf.mir.Interpreter`): once
straight after the lowering from the AST, with the verifier (`dampf.mir.Verifier`) after it, and
once after the passes, with the verifier after every pass. A wrong lowering or pass shows up
there, independent of any 65816 code. The interpreter also checks the range analysis: every
value it computes, and every parameter's value on entry to its function, must lie in its
inferred interval (`Ranges.checker`), and a `DAMPF_RANGE` hint that does not hold stops it ("a
hint does not hold"). Every hint that states a fact is also an assertion in the PC and test
builds ([hints.md](hints.md)).

What is compared: the dampf runtime writes to `$21FB` when the entry function returns; the
emulator, or Mesen's Lua script, then reads every global. All globals, flattened into their
scalar elements (`a[2]`, `s.x`), must have the same values on both sides. Pointers are left out
(their values are addresses), and so is constant data in ROM.

## The layers

| Layer | What it compares | Runs in | Time |
| --- | --- | --- | --- |
| Unit tests | One pass or component against expected output | JVM | Milliseconds |
| Execution tests | `src/test/resources/exec/*.c` against gcc | dampf's emulator, and Mesen when there is one | Seconds |
| Random programs | Generated programs against gcc | dampf's emulator | Minutes for many |
| Examples | Each example's `check.txt` played on the SNES and on the desktop, a checksum per tick | dampf's emulator and Rachael; Mesen in `dev/compare_example.sh` | Under a minute |
| Timing on the examples | Every call of every example against its static bounds | dampf's emulator | 20 seconds |

**Unit tests** cover the front end (`PreprocessorTest`, `ParserTest`, `AstBuilderTest`), MIR
passes written as MIR text (`NarrowTest`, `GvnTest`, `InlineTest`, `LicmTest`, `RangesTest`,
...), timing and budgets (`TimingTest`, `BudgetTest`) and the cycle table (`Cycles65816Test`).
`MirTextTest` prints the MIR of every execution program and of every example, parses it back
and checks that it verifies and prints to the same text. `StrengthReduceTest` compares every
reduced multiplication and division with the original in the interpreter (all 8-bit inputs,
many constants).

**Execution tests** (`ExecutionTest`) are small C programs, each on one area. Each runs on the
PC, in the MIR interpreter before and after the passes, and as both ROMs in dampf's emulator;
the naive ROM also runs headless in Mesen (`--testrunner` with a Lua script) when one is
there. A third ROM from MIR is built with arbitrary block counts (`--block-counts`, a third of
the blocks "never ran") and must still give gcc's results, since a profile changes only the
order of the blocks. The ROMs from MIR also check the static timing against the emulator
([timing.md](timing.md), "Checked against the emulator").

**The guides' tests.** `StyleGuideTest` checks the speed rules of the C style guide
(`lib/C_STYLE.md`): one file per rule in `src/test/resources/style/`, two functions that compute
the same thing and a `claim:` line (`slower`, `faster`, `same` within 5 %); it checks the claim on
the timing report's worst cases, runs the program like an execution test, and compares the guide's
table of measurements (`STYLE_GUIDE=update` writes it). `LibCostTest` builds a program that calls
every function of the console API and writes their best and worst cases into the table of
`lib/README.md` (`LIB_COSTS=update`). `SnesHardwareGuideTest` checks the feature table of
`lib/SNES_HARDWARE.md` against `lib/snes` and `lib/rachael`, and `RachaelTest` that Rachael counts
the register writes she does not draw. Each fails when its document is no longer true.

**Random programs** (`RandomProgramTest`, 50 per build) run like the execution tests, without
the block counts and without Mesen. The generator makes programs free of undefined behaviour by construction (divisors
made odd, shift counts masked, wide arithmetic in `uint32_t`) and favours boundary values like
−32768, where sign and overflow handling go wrong.

**The examples** (`ExamplesTest`, ADR record 38) build each example in `examples/` for the SNES
and play the input of its `check.txt` in dampf's emulator, through the kit's own main loop; where
raylib was fetched, the same input is played on the desktop (Rachael). After every tick both
compute a checksum of the game's state: the bytes the game names with `sdevkit_dbg_hash`, then
the sprite table. The lists must be the same, so dampf's code and gcc's, `lib/snes` and Rachael
computed the same game, tick for tick; the test names the first tick that differs. On the SNES
the call only writes the address and size of the memory to a port (`$21FC`), and the checker
reads it, so the check costs the game about 80 cycles a call. The emulator's run also writes the
frames per tick (`build/emu-check/frames.txt`: on average and over the slowest 60 ticks;
`ticks.txt`: the cycles of every tick), close to Mesen's but not equal, since a DMA takes no CPU
time there. A line `budget T1 T2` in `check.txt` makes it a measured budget: the test fails when
a tick from T1 to T2 - 1 took more than a frame (Er-Tüp's covers its busiest waves). Where the
time goes, the emulator says too: `dampf.emu.ExampleProfile` plays the same input and charges the
cycles of a range of ticks to the functions of the ROM, and with the ROM's `.dbg` beside it to
its C lines, which shows where in a function, and in the functions dampf inlined, the time goes. `dev/compare_example.sh` compares Mesen's checksums with the desktop's too,
with the pictures.

**The examples' benchmark** (`dev/bench_examples.sh`) plays every example's `check.txt` and
measures the work of each tick: its master cycles without the main loop's waiting, so an example
that does the same work every tick (Mandelbrot) shows a faster compiler as less work, not as
fewer frames. Its table has one line an example (on average, in the busiest 60 ticks, the share
of dampf's runtime), and with `BASELINE=` the ratio to an earlier table and their geometric
mean: an optimization or a tuned parameter is judged on all examples, not on one program. The
naive code generator, for example, does three times the work on the geometric mean, between 1.3
times (Mandelbrot, whose time is the runtime's multiplication) and 6.4 times (Er-Tüp).

**The static timing on the examples** (`ExamplesTimingTest`) runs every example for 3,000 frames
without input in dampf's emulator (the titles' demos, Mandelbrot computing, Schach playing) and
measures every call of every function, from its first instruction to the instruction after the
JSL that called it: each must lie within the function's static bounds ([timing.md](timing.md)).

**The reference functions** (`ReferenceFunctionsTest`): an example with hand-written functions in
`reference/` is built with them in place of the compiled ones, plays its `check.txt`, and every
tick's checksum must be the normal build's.

Until 2026-11-19 a copy of Blubber Blasen's code was a layer of its own: its benchmark of 3,610
ticks against a PC build, its recorded games played on both sides and through the platform layer
in Mesen, the static timing checked on every call of the benchmark. The examples took those jobs
over when it left ([ADR record 40](adr/0040-the-examples-replace-blubber-blasen-s-snapshot-as.md)).

## Supporting pieces

- **dampf's emulator** (`dampf.emu`: 65816 CPU, LoROM, WRAM, no graphics, DMA or sound) runs the
  execution and random-program tests and the examples inside the JVM, and counts master cycles with the memory speeds of the SNES (WRAM and SlowROM 8, I/O 6,
  internal cycles 6, DRAM refresh 40 per scanline). Of the picture it has the timing the kit's
  main loop polls: the line counter, the vertical blank flag, the automatic joypad read and the
  joypads. It is validated
  - against the SingleStepTests 65816 suite (github.com/SingleStepTests/65816,
    `CpuSingleStepTest`, `-PcpuTests`): native mode, all 256 opcodes, about 300 random tests
    each (76,059 in all); registers, flags, memory, CPU cycles and the number of memory accesses
    match. Emulation mode is only modelled as far as the start-up needs it;
  - against Mesen on Blubber Blasen's benchmark, while it was here: the same checksums on all
    3,610 ticks, and the same master cycles per tick within a few cycles out of millions
    (`dampf bench`); and on every example's check, the same checksums every tick.
- **Mesen** (MesenCE in `.mesen/`, from `dev/fetch_mesen.sh`, or `-Pmesen=<path>`) runs the execution
  tests, and the examples in `dev/compare_example.sh`; the random programs run in dampf's emulator
  only.
- **Cycle table:** `CycleTableAgainstEmulatorTest` checks the cost model
  (`dampf.target.Cycles65816`) against the emulator for every opcode in dampf's processor state;
  `Cycles65816Test` spot-checks it against the W65C816S data sheet.
- **Mutation checks:** deliberately broken code generation must make tests fail. When one did
  not (a 32-bit arithmetic shift whose high bits no test observed), a test was added.
- **Smaller tests:** `MappingTest` (where the cartridge's ROM appears, LoROM and HiROM);
  `ConstAddressTest` (pointers made from numbers, `REG8(0x210B + i)`, cannot run on the PC, so
  it runs them in dampf's emulator on WRAM addresses and compares with what C says).
- **`BackendOutputTest`** freezes the back end's output: a hash of the assembly and the static
  timing of every execution test, 20 random programs and every example (Er-Tüp also with its
  hand-written reference functions), with both code generators. A step that only moves code must keep every
  hash; one that changes code records new ones (`-PbackendOutput=update`, which also keeps a copy
  of the assembly in `build/backend-output-ref/` for diffing) together with the examples'
  benchmark (`dev/bench_examples.sh`) in [optimizations-log.md](optimizations-log.md), and never in the same step as a move (ADR
  [record 24](adr/0024-a-refactoring-step-may-cost-about-1-speed-if-the.md)). It also reads every assembly file back as LIR
  (the same text again, and the LIR verifier passes), and builds every ROM with ca65 and ld65 and
  with dampf's own assembler and linker: the images must be the same byte for byte, the label
  files must name the same addresses, and the debug files must say the same (`DbgMeaning`: the
  bytes of every line, symbols, segments, scopes, C symbols).
- **`LirTest`**: blocks, printer, parser and verifier of LIR on small hand-written pieces.
- **`BookSourcesTest`** fails when the spec or a source of a generated book page changed since
  the page was generated (see CLAUDE.md).
- **Coverage and static analysis**, not part of `./gradlew build`: JaCoCo coverage
  (`-Pcoverage`, off by default because it slows the emulator), PMD (findings do not fail the
  build) and CPD for copied code.
- **Reference assembly benchmarks:** hand-written versions of the examples' functions as the
  yardstick ([optimizations.md](optimizations.md), [reference/README.md](../reference/README.md)):
  Er-Tüp's `random16` and `sine`, linked with `--replace`.
- **Performance tracking** per commit is planned; today the numbers are written down by hand in
  [optimizations.md](optimizations.md).

## What the IR levels bring for testing

Every IR level ([architecture.md](architecture.md)) is to have a text format that parses back
(pass tests as IR text in, IR text out, like LLVM's), a verifier run after every pass, and, for
HIR and MIR, an interpreter, so a wrong result can be pinned to the lowering between two levels.
MIR has all three; LIR has the text format and the verifier (`LVerifier`, after every code
generation: labels, branch targets, known opcodes, the accumulator width on every path); its
meaning is checked by running the code. HIR is not built; its interpreter would be a second
definition of what the program means, next to gcc.

## When something hangs

A test must fail, not wait forever. Every program test (execution tests, random programs, the
MIR round trips) runs under a hard time limit (`assertTimeoutPreemptively`: 3, 2 and 2 minutes
per program), because
JUnit's default timeouts do not cover dynamic tests. External programs (gcc, the PC builds,
and ca65 and ld65 where the tests use them) run through `dampf.util.Processes` with a time limit and are killed when it runs
out. The emulator stops after 600 frames in the program tests, after a limit per example in its
checks, and after 200,000 frames in `dampf bench`; the MIR interpreter counts its steps. The Gradle test task has an
overall limit of 2 hours as a last resort. A program that loops forever fails in about a minute
with "did not finish within 60 s and was stopped (does it hang?)".
