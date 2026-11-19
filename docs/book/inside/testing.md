<!-- GENERATED from doc-specs/inside/testing.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->

# Testing

A miscompile rarely crashes. More often it makes a bubble pass through a wall in round 37, and
only sometimes. That is why dampf compares everything it builds with a reference from the
start: this page shows how its code is checked against what gcc computes on the PC, which test
layers do it, what each one catches, and how you run them.

## The principle

Every test program has a reference result: the same C source compiled by gcc on the PC and run
there, the [PC build](/glossary#pc-build). dampf's result must match it bit for bit, because
that is the promise dampf makes: the same source behaves the same on both sides.

Each program is built for the SNES twice, once by the
[naive code generator](/glossary#naive-code-generator) and once by the
[code generator from MIR](/glossary#code-generator-from-mir). Both ROMs run, and both must give
the PC's values. The naive code generator does almost everything in 32-bit `int`, as C's
promotions say, and has none of the optimizer's cleverness; it stays in dampf as an independent
reference, so a difference between the two points at the optimization that caused it.

The program also runs on the level of [MIR](/glossary#mir) in between: dampf's MIR interpreter
(`dampf.mir.Interpreter`) executes it once straight after the lowering from the AST and once
after all the optimizer's [passes](/glossary#pass). A wrong lowering or a wrong pass shows up
there, before any 65816 code exists. In the optimized run the verifier (`dampf.mir.Verifier`)
checks the MIR after every pass, and every value the interpreter computes, and every
parameter's value on entry to its function, must lie within the
[value range](/glossary#value-range) dampf inferred for it (`Ranges.checker`). A `DAMPF_RANGE`
[hint](/glossary#hint) that does not hold stops the interpreter with "a hint does not hold".

On the PC side, gcc builds the program with [UBSan](/glossary#ubsan), its undefined behaviour
sanitizer: extra checks in the code that stop the program when it overflows a signed `int`,
shifts too far or divides by zero. The PC build uses `-fsanitize=undefined
-fno-sanitize-recover=all`. Without it, a test program whose result depends on undefined
behaviour could pass by chance on the PC and fail on the SNES, or the other way round; with it,
such a program fails the test.

What is compared are the globals after the [entry function](/glossary#entry-function) has
returned. The [runtime](/glossary#runtime) writes to `$21FB` at that point, and the test reads
every global from memory. Arrays and structs are flattened into their scalar elements (`a[2]`,
`s.x`), and each must have the same value on both sides. Pointers are left out, because their
values are addresses that differ between PC and SNES, and so are constant objects in ROM.

## The layers

**Execution tests** are small C programs in `compiler/src/test/resources/exec/`, each written to exercise
one area: 8-bit wrap-around, calls, pointers, banks, 32-bit arithmetic, tables in ROM.
`ExecutionTest` runs each one on the PC with gcc, in the MIR interpreter before and after the
passes, and as two ROMs in dampf's emulator, one per code generator. When
[Mesen](/glossary#mesen) is available, the ROM of the naive code generator also runs there.
Every run must give the PC's globals.

The ROM of the code generator from MIR also checks dampf's static timing. dampf computes a best
and a worst case in CPU cycles for every function, and in the emulator `test_main` as a whole,
and every call of every function and runtime routine on the way, must take between the two
(`Timing.check`). A call is measured from its first instruction to the instruction after the
`JSL` that called it. A third ROM from MIR lays the blocks out by arbitrary block counts (a
third of the blocks "never ran", as [`--block-counts`](/reference/command-line#block-counts)
would take them from a profile); it must give the same globals and keep the timing too, since
a profile may change only the order of the blocks.

**Random programs** come from a generator, `RandomProgram`, that writes programs within the
[subset](/glossary#subset): ten global variables of the six integer types from `int8_t` to
`uint32_t`, and thirty statements of assignments, `if`, short `for` loops and expressions with
every operator. The programs are free of undefined behaviour by construction: divisors are made
odd, shift counts are masked, wide arithmetic is done in `uint32_t`, where overflow wraps.
Values at the edges of the types, such as `-32768`, `255` and `65536`, are chosen often, because
that is where sign and overflow handling go wrong. `RandomProgramTest` runs 50 of them per build,
with the same steps as the execution tests, the timing check included, but without the ROM laid
out by block counts and in dampf's emulator only. The seeds are fixed, so a failure can be
reproduced, and the failing program is printed in the message.

**The examples** are the small games of the [dev kit](/glossary#dev-kit) in `examples/`.
`ExamplesTest` builds each one for the SNES with the code generator from MIR and plays the input
of its `check.txt` in dampf's emulator, through the kit's own main loop. Where raylib was
fetched, the same input is played on the desktop as well, on [Rachael](/glossary#rachael) with
gcc's code. After every [tick](/glossary#tick) both sides compute a checksum of the game's
state: the bytes the game names with `sdevkit_dbg_hash`, then the sprite table. The two lists
must be the same, so dampf's code and gcc's, `lib/snes` and Rachael computed the same game tick
for tick; the test names the first tick that differs. On the SNES the call only writes the
address and size of the memory to a port (`$21FC`), and the emulator reads and hashes it, so the
check costs the game about 80 cycles a call.

The emulator's run also writes the [frames](/glossary#frame) per tick to the example's
`build/emu-check/frames.txt` (on average and over the slowest 60 ticks) and the master cycles of
every tick to `ticks.txt` there. They come close to Mesen's figures but are not equal, because a
DMA takes no CPU time in dampf's emulator. A line `budget T1 T2` in `check.txt` turns this into
a measured budget: the test fails when a tick from T1 to T2 - 1 took more than a frame. Er-Tüp's
budget covers its busiest waves. To see where that time goes,
`dampf.emu.ExampleProfile` plays the same input in dampf's emulator and charges the cycles of a
range of ticks to the functions of the ROM, and, with the ROM's `.dbg` file beside it, to its C
lines, including the lines of functions dampf inlined.

**Recordings.** Every session in the desktop's window is recorded: the joypad and the checksum
of every [tick](/glossary#tick). F11 saves it in the format of `check.txt`, with `mark` and
`hash` lines that the other players skip, so a bug seen on the desktop can be played again: in
the window with `--replay`, which pauses at the marks and at the first tick whose checksum is not
the recorded one, and on the SNES side with `dev/replay_example.sh`, in dampf's emulator and in
Mesen. `ExamplesTest` writes every example's check as a recording too (`--record`) and requires
the same buttons and the SNES's checksum for every tick in it.

**The static timing on the examples** is checked by `ExamplesTimingTest`. It builds every
example with the code generator from MIR and runs it for 3,000 frames without input in dampf's
emulator: the titles' demos play, Mandelbrot computes, Schach plays. Every
call of every function is measured, from its first instruction to the instruction after the
`JSL` that called it, and each must lie between the best and the worst case dampf computes for
that function, as the execution tests do for their programs. The test fails as well when an
example makes fewer than 1,000 calls in that time. It takes about 20 seconds.

**The reference functions** are hand-written assembly versions of functions of the examples, in
`reference/`, kept as the yardstick for dampf's code: today Er-Tüp's `random16` and `sine`.
`ReferenceFunctionsTest` builds each example that has some with the naive code generator and all
its reference functions linked in place of the compiled ones, plays its `check.txt` in dampf's
emulator, and every tick's checksum must be the one of the example's normal build.

**The examples' benchmark** (`dev/bench_examples.sh`) builds every example that has a
`check.txt`, with the options you give it for `dampf build`, plays the check in dampf's emulator
(`dampf.emu.ExampleBench`) and measures the work of each tick: its master cycles without the
time the main loop spends waiting for the next frame. An example that does the same work every
tick, such as Mandelbrot, so shows a faster compiler as less work, not as fewer frames. The
table has one line per example: the work per tick on average and in the busiest 60 ticks, and
the share of it in dampf's runtime routines (multiplication, division, shifts by a variable).
With `BASELINE=` and an earlier table it adds the ratio to that table per example and their
geometric mean, so an optimization or a tuned parameter is judged on all examples, not on one
program. The naive code generator, for example, does three times the work on the geometric
mean, between 1.3 times (Mandelbrot, whose time is the runtime's multiplication) and 6.4 times
(Er-Tüp).

Two scripts play the examples in Mesen, which has the SNES's graphics chip
(the PPU) that dampf's emulator lacks. `dev/check_example.sh` plays an example headless with the
input of its `check.txt`, saves screenshots and the checksum of every tick, and counts every byte
written to VRAM, CGRAM or OAM outside a [vertical blank](/glossary#vertical-blank), the pause
between two pictures. The PPU drops such a byte, so the script fails when the count is not 0.
`dev/compare_example.sh` plays an example in Mesen and on the desktop with the same input and
compares the checksums of every tick and the screenshots, pixel for pixel.

Until 2026-11-19 a copy of [Blubber Blasen](/glossary#blubber-blasen)'s code was a layer of its
own, with its benchmark, its recorded games and the static timing checked on every call of the
benchmark; the examples took those jobs over when it left the repository.

Next to these layers are tests of single parts: front-end tests such as `ParserTest` and
`AstBuilderTest`, and pass tests written as MIR text, such as `NarrowTest`, `GvnTest` and
`InlineTest`. The text format they use is described on [MIR](/inside/mir). `MirTextTest` prints
the MIR of every execution program and of every example, parses it back and checks that it
verifies and prints to the same text.

`BackendOutputTest` freezes the output of the back end. It keeps a hash of the assembly and of
the static timing for every execution test, 20 random programs and every example, with both
code generators, and once more for Er-Tüp with its reference functions linked in, built by the
naive code generator. A
change that only moves code must keep every hash; one that changes the code on purpose records
new hashes with `-PbackendOutput=update`, which also keeps a copy of the assembly in
`build/backend-output-ref/` to compare against. The same test reads every assembly file back as
LIR, dampf's low-level representation (65816 code in basic blocks, written as ca65 source):
printed again, it must give the same text, and the LIR verifier must pass. And it builds every
ROM twice, with ca65 and ld65, the assembler and linker of the cc65 suite, and with dampf's own
assembler and linker. The two images must match byte for byte, the label files must name the
same addresses, and the debug files must say the same, whatever ids and order each file uses:
the bytes of every line, the symbols, segments, scopes and C symbols (`DbgMeaning`). This is why
you need ca65 and ld65 to develop dampf, but not to use it. `LirTest` checks the blocks, printer,
parser and verifier of LIR on small hand-written pieces.

`StyleGuideTest` measures the speed rules of the C style guide, `lib/C_STYLE.md`. Each rule is a
C file in `compiler/src/test/resources/style/`, named after the rule, with a pair of functions that
compute the same thing two ways and a first comment that states the claim, such as
`claim: sum_u8_counter slower sum_u16_counter`. The relation is `slower`, `faster` or `same`,
where `same` means within 5 %. The test builds each file with the code generator from MIR and
compares the worst cases of the two functions in the [timing report](/glossary#timing-report).
The program must also give the PC's globals in dampf's emulator, so a fast form that changes
the meaning cannot get in, and the measured cycles must lie within the static timing. A claim
that no longer holds fails the build: the rule changes or goes in the same commit as the change
to dampf that broke it. The table of measurements in the guide must be what the test measures.

Three more tests keep the documents of the [dev kit](/glossary#dev-kit) true. `LibCostTest`
builds a program that calls every function of the console API, the calls a game makes to the
dev kit's library, and checks their best and worst cases against the table in `lib/README.md`.
`SnesHardwareGuideTest` checks the feature table of the guide to the SNES hardware,
`lib/SNES_HARDWARE.md`, against `lib/snes` and against [Rachael](/glossary#rachael) in
`lib/rachael`. `RachaelTest` checks that Rachael counts the register writes she does not draw.
Like `StyleGuideTest`, each fails when its document is no longer true.

`SplashTest` checks the course of the splash screen every game of the dev kit starts with, the
same code on every platform (`lib/splash/splash.h`). Without a button it fades in for 31
frames, stays for three seconds (180 frames) and fades out for 31. A button pressed while it
fades in or stays ends it once it has faded in, without the fade out. The test builds a small
program on Rachael with gcc that presses A in a given frame and checks how many frames the
splash lasted and the brightness of its last frame: a press in frame 5 ends it after 31 frames
at full brightness, a press in frame 212, when the fade out has begun, changes nothing.

## dampf's emulator and Mesen

The SNES side of the tests runs in `dampf.emu`, a 65816 emulator in Java with LoROM and work
RAM, but no graphics, DMA or sound. Of the picture it has only the timing that the kit's main
loop polls: the line counter, the vertical blank flag and the automatic joypad read, and it has
the joypads. It exists for two reasons. It runs inside the JVM, so a test needs no external
program and runs much faster than a ROM started in
Mesen; that is what makes 50 random programs on every build affordable. And
it counts master cycles with the memory speeds of the SNES (work RAM and SlowROM 8 per access,
I/O 6, internal cycles 6, DRAM refresh 40 per scanline), so the same run that checks the results
also measures the speed.

An emulator written for the tests is only worth something if it is right. It is checked in two
ways:

- **The CPU against the SingleStepTests 65816 suite.** For each of the 256 opcodes the suite
  holds random starting states, each followed by one instruction and the state after it.
  `CpuSingleStepTest` runs 300 of them per opcode in native mode and compares registers, flags,
  memory, the number of CPU cycles and the number of memory accesses. Emulation mode is modelled
  only as far as the start-up needs it.
- **The whole machine against Mesen.** On Blubber Blasen's benchmark, while the game's code was
  here, both gave the same checksums on all 3,610 ticks and the same master cycles per tick
  within a few cycles out of millions. On the check of every example they give the same
  checksum after every tick.

In the Gradle tests, Mesen runs only for the execution tests, and only when it is there: when
`.mesen/` in the repository holds one (it is not in git) or `-Pmesen` names one.
`dev/fetch_mesen.sh` puts MesenCE there, the Linux build `Mesen` and the Windows build
`Mesen.exe`, both portable and with the settings the tests need; Gradle takes `.mesen/Mesen`
when it exists, else `.mesen/Mesen.exe`. `MesenRunner` starts it headless (`--testrunner`) with
a Lua script that waits for the write to `$21FB`, reads the globals and writes them to a file.
It works with the Linux build, whose libraries it takes from `.mesen/lib` when that folder
exists, and from WSL with the Windows build. The random programs, the examples, their timing and
reference functions, and the examples' benchmark run in dampf's emulator only.

Outside the Gradle tests, `dev/check_example.sh` and `dev/compare_example.sh` start Mesen. Each
takes the Mesen that `MESEN` names, else `.mesen/Mesen`, else `.mesen/Mesen.exe`
(`dev/mesen.sh`).

## Running the tests

`./gradlew build` compiles dampf and runs every test that needs no property: the unit tests, the
execution tests (in Mesen too, when `.mesen/` exists), 50 random programs, the examples, the
static timing on the examples, the reference functions, the check of the back end's output and
the tests of the guides. The rest is switched on with
Gradle properties:

| Call | What it does |
| --- | --- |
| `./gradlew build` | build and run the default tests |
| `./gradlew test` | the default tests only |
| `./gradlew test --tests dampf.exec.ExecutionTest` | one test class |
| `./gradlew test -Pmesen=<path to Mesen>` | execution tests also in this Mesen instead of the one in `.mesen/` |
| `./gradlew test -Prandom=<N>` | N random programs instead of 50 |
| `./gradlew test -PrandomSeed=<S>` | random programs from seed S on (default 1) |
| `./gradlew test --tests '*BackendOutputTest' -PbackendOutput=update` | records new hashes of the back end's output and copies its assembly to `build/backend-output-ref/` |
| `STYLE_GUIDE=update ./gradlew test --tests dampf.exec.StyleGuideTest` | writes today's measurements into the table of `lib/C_STYLE.md` |
| `LIB_COSTS=update ./gradlew test --tests '*LibCostTest'` | writes today's best and worst cases into the table of `lib/README.md` |
| `./gradlew test -PcpuTests` | also the emulator against SingleStepTests, 300 tests per opcode |
| `./gradlew test -PcpuTests=<N>` | the same with N tests per opcode |
| `./gradlew test -Pcoverage` | also measures code coverage with JaCoCo; the report is in `build/reports/jacoco/test/html/index.html` (and as XML), without the parser ANTLR generates |
| `./gradlew pmdMain pmdTest` | PMD's checks with the rules in `config/pmd/ruleset.xml`; reports in `build/reports/pmd/`; findings do not fail the build |
| `./gradlew cpd` | copied code in `compiler/src/main/java` (PMD's CPD), from 100 tokens on; report in `build/reports/cpd/main.txt` |
| `./gradlew cpd -PcpdTokens=<N>` | the same from N tokens on |
| `dev/bench_examples.sh [options]` | the examples' benchmark: the work per tick of every example, built with these options for `dampf build` |
| `BASELINE=<table> dev/bench_examples.sh` | the same, and the ratio to an earlier table, per example and as the geometric mean |
| `dev/check_example.sh <name>` | an example in Mesen: lost bytes, screenshots, checksums of every tick |
| `dev/compare_example.sh <name>` | an example in Mesen and on the desktop: checksums of every tick and screenshots compared |
| `dev/replay_example.sh <name> <recording>` | a recording of the desktop on the SNES side, in dampf's emulator (`WITH_MESEN=1`: in Mesen too): the first tick whose checksum differs |

The first run with `-PcpuTests` downloads the start of each opcode's file of the suite, about
50 MB, into `~/.cache/dampf/65816-tests`; later runs use the copy. Without a property, the tests
that need one are skipped, not failed.

Coverage, PMD and CPD are not part of `./gradlew build`. JaCoCo is off by default because it
slows down the tests that run the emulator, and PMD's findings are there to be read, not to stop
a build. All scripts in `dev/`, with their arguments and variables, are listed in
`dev/README.md` in the repository.

## Adding an execution test

An execution test is a C file in `compiler/src/test/resources/exec/` with globals and a function
`test_main`, which is the entry function on both sides. It needs no output and no assertions:
the globals it leaves behind are the result. A comment at the top says what it tests. A test
of 8-bit wrap-around could look like this:

```c
/* An 8-bit counter wraps at 256; the sum keeps counting in 16 bits. */
#include <stdint.h>

uint8_t lives = 250;
uint16_t score;

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 10; i++) {
        lives++;
        score += lives;
    }
}
```

`ExecutionTest` picks up every `.c` file in the folder by itself. For the PC side it writes a
small harness that includes the file, calls `test_main` and prints every global, and compiles it
with `gcc -std=c99 -O1 -Wall` and UBSan. It adds dampf's header directory with `-idirafter`, so
a test can include `<dampf.h>`; gcc searches that directory after its own, so `<stdint.h>`
still comes from the PC. Here the PC prints `lives 4` and `score 1275`, and the
test passes when the MIR interpreter, both ROMs in dampf's emulator and, if present, Mesen all
end with these values, and every call in the ROM from MIR stays within its static timing.

A test is only useful if its result depends on what it tests: a global that nothing writes
stays at its initial value whatever dampf does. Deliberately broken code generation must make a
test fail. Once it did not: a 32-bit arithmetic shift had high bits that no test looked at, and
a test was added for them.

## When something hangs

A test must fail, not wait forever. Every program test runs under a hard time limit
(`assertTimeoutPreemptively`): 3 minutes per execution test, 2 per random program and 2 per MIR
round trip, because JUnit's default timeouts do not cover the dynamic tests these are built
from; `StyleGuideTest` has 5 for all its rules. External programs (gcc, the PC builds, and ca65
and ld65 where the tests use them) run through `dampf.util.Processes` with a time limit and are
killed when it runs out. The emulator stops after 600 frames in the program tests, after 40
frames per tick of the check plus 2,000 in the examples' checks, and after 200,000 frames in
`dampf bench`; the MIR interpreter counts its steps. The Gradle test task has an overall limit
of 2 hours as a last resort. A program that loops forever fails in about a minute with "did not
finish within 60 s and was stopped (does it hang?)".

## Why it works this way

**The naive code generator stays.** dampf could have dropped its first code generator once the
one from MIR was better. It keeps it as a reference that shares only the front end with the
optimized path: when the two disagree, the difference points to the optimization at fault. The
cost is a second code generator to maintain and every test program built twice
(`docs/testing.md`, *The principle*).

**An emulator of its own.** Mesen is a complete SNES emulator and runs the execution tests where
it is installed, but it is an external program and much slower to run a ROM in. dampf's emulator
runs inside the JVM, so the random programs and the examples run on every build and the
examples' benchmark runs without Mesen, and every run counts master cycles at the same time. The
cost is a second emulator that has to be proved right, against the SingleStepTests suite and
against Mesen (`docs/testing.md`, *Supporting pieces*).

**UBSan on the PC side.** Without the sanitizer, a test program with undefined behaviour could
give gcc's answer by luck, and a later change on either side would break it for no visible
reason. With it, such a program fails at once, and the random program generator has to avoid
undefined behaviour by construction (`docs/testing.md`, *The principle*).
