# Testing

Correctness is the biggest risk: a miscompile does not crash, it makes a bubble pass through a
wall in round 37, sometimes. So every optimization is checked automatically against a reference.
The Gradle calls and their properties are in the book, [docs/book/inside/testing.md](book/inside/testing.md).

## The principle

The reference is clang on the PC (ADR-6FD2DGP0). Each test program is compiled by clang with the
undefined behaviour sanitizer (UBSan: a program with undefined behaviour fails instead of passing
by luck) and once more, optimized, for the values; dampf compiles it into two ROMs, one from the
naive code generator and one from the code generator from MIR. The naive code generator stays as
an independent reference: both ROMs must give the PC's results, and a difference between them
points to the optimization that caused it. gcc and clang give the same results for the subset
(ADR-6F0R64GD); gcc's bounds sanitizer reported indexes that a program never computes, which
clang's does not.

The program also runs on the MIR level, in the MIR interpreter (`dampf.mir.Interpreter`): once
straight after the lowering from the AST, with the verifier (`dampf.mir.Verifier`) after it, and
once after the passes, with the verifier after every pass. A wrong lowering or pass shows up
there, independent of any 65816 code. The interpreter also checks the range analysis: every
value it computes, and every parameter's value on entry to its function, must lie in its
inferred interval (`Ranges.checker`), and a `DAMPF_RANGE` hint that does not hold stops it ("a
hint does not hold"). Every hint that states a fact is also an assertion in the PC and test
builds ([hints.md](hints.md)). A load or store outside the global or frame slot its address is
based on stops it too ("an access outside its object"): C makes it undefined, and the PC build's
UBSan finds most such accesses as well.

What is compared: the dampf runtime writes to `$21FB` when the entry function returns; the
emulator, or Mesen's Lua script, then reads every global. All globals, flattened into their
scalar elements (`a[2]`, `s.x`), must have the same values on both sides. Pointers are left out
(their values are addresses), and so is constant data in ROM.

## The layers

| Layer | What it compares | Runs in | Time |
| --- | --- | --- | --- |
| Unit tests | One pass or component against expected output | JVM | Milliseconds |
| Execution tests | `compiler/src/test/resources/exec/*.c` against clang | dampf's emulator, and Mesen when there is one | Seconds |
| Random programs | Generated programs against clang | dampf's emulator | Minutes for many |
| Examples | Each example's `check.txt` played on the SNES and on the desktop, a checksum per tick, and the same DSP writes in the same ticks ([docs/sound.md](sound.md#the-checks)) | dampf's emulator and Rachael; Mesen in `dev/compare_example.sh` | Under a minute |
| The BRR Lab | Every use of `tools/brr_lab.html`, and its bytes against `superdevkit sfx` ([tools/lab_test](../tools/lab_test/README.md)) | Headless Chromium (Playwright), when installed | 10 seconds |
| Timing on the examples | Every call of every example against its static bounds | dampf's emulator | 20 seconds |

**Unit tests** cover the front end (`PreprocessorTest`, `ParserTest`, `AstBuilderTest`), MIR
passes written as MIR text (`NarrowTest`, `GvnTest`, `InlineTest`, `LicmTest`, `RangesTest`,
...), timing and budgets (`TimingTest`, `BudgetTest`) and the cycle table (`Cycles65816Test`).
`MirTextTest` prints the MIR of every execution program and of every example, parses it back
and checks that it verifies and prints to the same text. `StrengthReduceTest` compares every
reduced multiplication and division with the original in the interpreter (all 8-bit inputs,
many constants). `CountedLoopsTest` does the same for the loop analysis: random counting
loops run in the interpreter, which checks the number of head runs and every counter's value
against what the analysis claims.

**Execution tests** (`ExecutionTest`) are small C programs, each on one area. Each runs on the
PC, in the MIR interpreter before and after the passes, and as both ROMs in dampf's emulator;
the naive ROM also runs headless in Mesen (`--testrunner` with a Lua script) when one is
there. A third ROM from MIR is built with arbitrary block counts (`--block-counts`, a third of
the blocks "never ran") and must still give the PC's results, since a profile changes only the
order of the blocks. The ROMs from MIR also check the static timing against the emulator
([timing.md](timing.md), "Checked against the emulator").

**The guides' tests.** `StyleGuideTest` checks the speed rules of the C style guide
(`lib/C_STYLE.md`): one file per rule in `compiler/src/test/resources/style/`, two functions that compute
the same thing and a `claim:` line (`slower`, `faster`, `same` within 5 %); it checks the claim on
the timing report's worst cases, runs the program like an execution test, and compares the guide's
table of measurements (`STYLE_GUIDE=update` writes it). `LibCostTest` builds a program that calls
every function of the console API and writes their best and worst cases into the table of
`lib/README.md` (`LIB_COSTS=update`). `SnesHardwareGuideTest` checks the feature table of
`lib/SNES_HARDWARE.md` against `lib/snes` and `lib/rachael`, and `RachaelTest` that Rachael counts
the register writes she does not draw. Each fails when its document is no longer true.
`RachaelTest` also runs her renderer's tests in C (`compiler/src/test/c/rachael_test.c`, from the
game's tests of its pseudo SNES): small scenes rendered and checked pixel by pixel.

**The spelling test** (`SpellingTest`) checks that the same work, written the ways a programmer
would write it, costs the same ([archive/compiler-flaws.md](archive/compiler-flaws.md), T1).
Each file in `compiler/src/test/resources/spelling/` is a family: its functions without
parameters are its members, with an optional `setup` and `check`. The test generates a
`test_main` that runs every member (after `setup`) on the PC and on the SNES, and requires the
same globals on both and the same result and `check` from every member. Then it builds one ROM
per member, with an entry that calls it, and measures its CPU cycles in dampf's emulator: a
member costs at most the family's `tolerance` (percent) more than its cheapest member, unless a
`gap:` line names it with the step of the plan that should close it and the cause. A gap that a
change closes fails the test until its line is removed. The costs are written to
`compiler/build/reports/spelling.txt`.

**Random programs** (`RandomProgramTest`) run like the execution tests, without the block counts
and without Mesen. Two generators make programs free of undefined behaviour by construction
(divisors made odd, shift counts masked, wide arithmetic in `uint32_t`) and favour boundary
values like −32768, where sign and overflow handling go wrong:

- `RandomProgram` (version 1): global scalars, expressions, `if` and short loops; seeds 1 to 50
  in every build, always the same programs.
- `RandomLoopProgram` (version 2): what the optimizer's loop and pointer passes work on. Arrays
  of bytes, words and a struct, and a local array; counted loops with every counter type, up
  and down, steps 1 to 5, the tests `<`, `<=`, `>`, `>=` and `!=`, as `for`, `while` and `do`,
  with up to 300 rounds, constant, global and local bounds, `break` and `continue`, nested,
  pair loops; helper functions, small (inlined) and large, with pointer parameters that get
  one array or two; pointer walks and `switch`. A loop is made from the values its counter
  takes, so it always ends and its counter never wraps; an index is a counter shifted into the
  array's bounds or an expression modulo its length; calls are whole statements, so no
  result depends on the order of evaluation. 50 seeds a build, new every day
  (`LocalDate.now().toEpochDay() * 1000` onwards).

A failure names its seed and the command that runs that program alone (`-PrandomSeed=S
-Prandom=1`); `dev/bisect_passes.sh` with that command names the pass that broke it.
`dev/random_soak.sh` runs 5,000 of each before a release, in shares that run at once (as many
as half the cores, `JOBS=n` to change it).

**The examples** (`ExamplesTest`, ADR-6F60Z67N) build each example in `examples/` for the SNES
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

**Recordings.** Every session in the desktop's window is recorded, the joypad and the checksum of
every tick; F11 saves it in the format of `check.txt` (with `mark` and `hash` lines, which the
other players skip), so a bug seen on the desktop can be played again: in the window
(`--replay`, which pauses at the marks and at the first tick whose checksum differs from the
recorded one), and on the SNES side (`dev/replay.sh` in dampf's emulator for any ROM, `dev/replay_example.sh` for an example, also in Mesen).
When the ROM from MIR differs and the naive one does not, `dampf.emu.RomDiff good.sfc bad.sfc
recording.txt` plays both and names, tick by tick, the globals that differ (the video queues first,
which depend on speed; then the one the miscompiled code writes).
`ExamplesTest` has every example's check written as a recording too (`--record`) and requires the
same buttons and the SNES's checksum for every tick in it.

**The examples' benchmark** (`dev/bench_examples.sh`) plays every example's `check.txt` and
measures the work of each tick: its master cycles without the main loop's waiting, so an example
that does the same work every tick (Mandelbrot) shows a faster compiler as less work, not as
fewer frames. Its table has one line an example (on average, in the busiest 60 ticks, the share
of dampf's runtime), and with `BASELINE=` the ratio to an earlier table and their geometric
mean: an optimization or a tuned parameter is judged on all examples, not on one program. The
naive code generator, for example, does three times the work on the geometric mean, between 1.3
times (Mandelbrot, whose time is the runtime's multiplication) and 6.4 times (Er-Tüp).
A game outside the repository measures itself the same way: `dampf.emu.ExampleBench rom.sfc
rom.lbl recording.txt`, from any folder (it finds the kit's `lib/snes/snes.c` through the debug file).

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
over when it left ([ADR-6F60Z819](adr/6F60Z819-the-examples-replace-blubber-blasen-s-snapshot-as.md)).

## Supporting pieces

- **dampf's emulator** (`dampf.emu`: 65816 CPU, LoROM, WRAM, the SPC700's boot protocol and the kit's sound driver, no graphics, DMA or sound output) runs the
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
- **GitHub Actions** (`.github/workflows/build.yml`) runs `./gradlew build` for every pull
  request (ADR-6FB9QJ3R), with JDK 25 on Ubuntu, the
  runner's gcc (dampf's preprocessor), clang, python3 and node, and Ubuntu's cc65 (ca65 and ld65 for `BackendOutputTest`). Mesen, raylib, Emscripten and Playwright are not there: the execution
  tests run in dampf's emulator only, and `DesktopIsolationTest`, the symbol check of WebAssembly
  objects (`ProjectTest`) and the BRR Lab's browser tests skip. A pull request is merged only when this build passes.
- **Parallel test JVMs.** The compiler's test classes run in several JVMs at once, one per core
  and at most 4 (`maxParallelForks`; `-PtestForks=n` sets the number, 1 runs them one after
  the other). So a test class that writes files writes them where no other class does: into
  its `@TempDir`, or into a folder of its own (`build/backend-output`, an example's
  `build/timing/` and `build/reference/`). The one exception is an example's `build/gen/`,
  which every class that compiles an example converts again: the conversions replace each file
  atomically (`superdevkit.AtomicWrite`), so a class compiling from it never reads a file half
  written.
- **Mesen** (MesenCE in `.mesen/`, from `dev/fetch_mesen.sh`, or `-Pmesen=<path>`) runs the execution
  tests, and the examples in `dev/compare_example.sh`; the random programs run in dampf's emulator
  only.
- **Cycle table:** `CycleTableAgainstEmulatorTest` checks the cost model
  (`dampf.target.Cycles65816`) against the emulator for every opcode in dampf's processor state;
  `Cycles65816Test` spot-checks it against the W65C816S data sheet.
- **The pass limit** (`dampf.util.PassLimit`, as LLVM's `-opt-bisect-limit`): `dampf build
  --opt-limit n`, `-PoptLimit=n` for the tests, or `DAMPF_OPT_LIMIT=n`, stops the MIR and LIR
  passes after n applications that changed something (one pass on one function; inlining one
  call; one place put in a register; two places coalesced; the passes on the whole module once). `dev/bisect_passes.sh <command>` runs a check
  command with smaller and smaller limits and names the application that breaks a program, for
  example `dev/bisect_passes.sh ./gradlew -q :compiler:test --tests dampf.exec.ExecutionTest
  -Pexec=loops` (`-Pexec` runs one execution program). Selection and its decisions are not
  counted. With a limit, the tests' emulator allows 6,000 frames (code without the passes is up to
  ten times slower) and a budget the timing cannot guarantee is a warning. `PassLimitTest` checks
  the counting; the execution tests pass with any limit.
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
  [ADR-6F3CJR10](adr/6F3CJR10-a-refactoring-step-may-cost-about-1-speed-if-the.md)). It also reads every assembly file back as LIR
  (the same text again, and the LIR verifier passes), and builds every ROM with ca65 and ld65 and
  with dampf's own assembler and linker: the images must be the same byte for byte, the label
  files must name the same addresses, and the debug files must say the same (`DbgMeaning`: the
  bytes of every line, symbols, segments, scopes, C symbols). Beside the hashes it keeps the
  static worst case of every function of every example and of every spelling member
  (`compiler/src/test/resources/cost-baseline.txt`, written by the same update): a hash says the
  code changed, the diff of that file says which functions got slower.
- **`LirTest`**: blocks, printer, parser and verifier of LIR on small hand-written pieces;
  **`PlaceCheckTest`** the check of the frame places (no read of a byte not written, no write
  over another value's live byte) that runs in every compile.
- **`BookSourcesTest`** checks the bookkeeping of the generated book pages: the lock, the
  specs' sources, the pages' markers. Whether the pages are current is `./gradlew checkBook`,
  run before a release and not part of `build` (ADR-6F60ZB4N).
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
generation: labels, branch targets, known opcodes, the accumulator width on every path) and the
place check (`PlaceCheck`, after the places are assigned); its
meaning is checked by running the code. HIR is not built; its interpreter would be a second
definition of what the program means, next to the PC's compiler.

## When something hangs

A test must fail, not wait forever. Every program test (execution tests, random programs, the
MIR round trips) runs under a hard time limit (`assertTimeoutPreemptively`: 3, 2 and 2 minutes
per program), because
JUnit's default timeouts do not cover dynamic tests. External programs (the C compilers, the PC builds,
and ca65 and ld65 where the tests use them) run through `dampf.util.Processes` with a time limit and are killed when it runs
out. The emulator stops after 600 frames in the program tests, after a limit per example in its
checks, and after 200,000 frames in `dampf bench`; the MIR interpreter counts its steps. The Gradle test task has an
overall limit of 2 hours as a last resort. A program that loops forever fails in about a minute
with "did not finish within 60 s and was stopped (does it hang?)".
