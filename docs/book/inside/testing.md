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
checks the MIR after every pass, and every value the interpreter computes must lie within the
[value range](/glossary#value-range) dampf inferred for it (`Ranges.checker`).

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

**Execution tests** are small C programs in `src/test/resources/exec/`, each written to
exercise one area: 8-bit wrap-around, calls, pointers, banks, 32-bit arithmetic, tables in ROM.
`ExecutionTest` runs each one on the PC with gcc, in the MIR interpreter before and after the
passes, and as two ROMs in dampf's emulator, one per code generator. When Mesen is available,
the ROM of the naive code generator also runs there. Every run must give the PC's globals.

The ROM of the code generator from MIR also checks dampf's static timing. dampf computes a best
and a worst case in CPU cycles for every function, and in the emulator `test_main` as a whole,
and every call of every function and runtime routine on the way, must take between the two
(`Timing.check`). A call is measured from its first instruction to the instruction after the
`JSL` that called it.

**Random programs** come from a generator, `RandomProgram`, that writes programs within the
[subset](/glossary#subset): ten global variables of the six integer types from `int8_t` to
`uint32_t`, and thirty statements of assignments, `if`, short `for` loops and expressions with
every operator. The programs are free of undefined behaviour by construction: divisors are made
odd, shift counts are masked, wide arithmetic is done in `uint32_t`, where overflow wraps.
Values at the edges of the types, such as `-32768`, `255` and `65536`, are chosen often, because
that is where sign and overflow handling go wrong. `RandomProgramTest` runs 50 of them per build,
with the same steps as the execution tests, the timing check included, but in dampf's emulator
only. The seeds are fixed, so a failure can be reproduced, and the failing program is printed
in the message.

**The game's replays** check the whole of [Blubber Blasen](/glossary#blubber-blasen). The game
records a play session as a `.bbr` file: its start (seed, save RAM, round, options), the joypad
input whenever it changes, and every 60 [ticks](/glossary#tick) a checksum of everything the
game shows. `tools/replay_to_c.py` turns a recording into C tables, and `tools/replay/driver.c`
plays them back the way the game's own replay code does, but written in the subset. `ReplayTest`
builds the driver with the game in its debug configuration (`BB_DEBUG`) with gcc and with both of
dampf's code generators, runs the ROMs in dampf's emulator and the optimized MIR in the
interpreter. The PC build must hit every checksum of the recording, and every dampf build must
end with the same results as the PC build. A recording is about 15,000
[frames](/glossary#frame) long, so the test runs only when you ask for it.

**The game's benchmark** (`tools/bench_game.sh`) is the second check of the whole game. It plays
3,610 ticks, compares three checksums per tick with a PC build of the same program, and counts
the cycles of every tick, which is how dampf's speed is measured. It runs in dampf's emulator
(`dampf bench`), and the MIR interpreter can run it as well (`dampf mir --bench main ...`), where
it also gives the PC's checksums on all 3,610 ticks. With `-Preplays`, `GameTimingTest` runs the
benchmark built by the code generator from MIR in the emulator as part of the tests, measures
every call of every function and checks that each lies between the best and the worst case
dampf computes for that function, as the execution tests do for their programs.

Next to these layers are tests of single parts: front-end tests such as `ParserTest` and
`AstBuilderTest`, and pass tests written as MIR text, such as `NarrowTest`, `GvnTest` and
`InlineTest`. The text format they use is described on [MIR](/inside/mir).

## dampf's emulator and Mesen

The SNES side of the tests runs in `dampf.emu`, a 65816 emulator in Java with LoROM, work RAM and
nothing else: no graphics and no sound. It exists for two reasons. It runs inside the JVM, so a
test needs no external program and runs much faster than a ROM started in
[Mesen](/glossary#mesen); that is what makes 50 random programs on every build affordable. And
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
- **The whole machine against Mesen** on the game's benchmark: the same checksums on all 3,610
  ticks, and the same master cycles per tick within a few cycles out of millions.

Mesen itself runs only for the execution tests, and only when it is there: when
`.mesen/Mesen.exe` exists in the repository (it is not in git) or `-Pmesen` names one.
`MesenRunner` starts it headless (`--testrunner`) with a Lua script that waits for the write to
`$21FB`, reads the globals and writes them to a file. The random programs, the replays and
the benchmark run in dampf's emulator only.

## Running the tests

`./gradlew build` compiles dampf and runs every test that needs no property: the unit tests, the
execution tests (in Mesen too, when `.mesen/` exists) and 50 random programs. The rest is
switched on with Gradle properties:

| Call | What it does |
| --- | --- |
| `./gradlew build` | build and run the default tests |
| `./gradlew test` | the default tests only |
| `./gradlew test --tests dampf.exec.ExecutionTest` | one test class |
| `./gradlew test -Pmesen=<path to Mesen.exe>` | execution tests also in this Mesen instead of `.mesen/Mesen.exe` |
| `./gradlew test -Prandom=<N>` | N random programs instead of 50 |
| `./gradlew test -PrandomSeed=<S>` | random programs from seed S on (default 1) |
| `./gradlew test -Preplays` | also the game's replays and `GameTimingTest` |
| `./gradlew test -PcpuTests` | also the emulator against SingleStepTests, 300 tests per opcode |
| `./gradlew test -PcpuTests=<N>` | the same with N tests per opcode |
| `./gradlew test -Pblubberblasen=<path>` | tests that need the game's sources read them from there |
| `tools/bench_game.sh` | the game's benchmark: checksums and cycles |

The first run with `-PcpuTests` downloads the start of each opcode's file of the suite, about
50 MB, into `~/.cache/dampf/65816-tests`; later runs use the copy. Without a property, the tests
that need one are skipped, not failed.

## Adding an execution test

An execution test is a C file in `src/test/resources/exec/` with globals and a function
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
with `gcc -std=c99 -O1 -Wall` and UBSan. Here the PC prints `lives 4` and `score 1275`, and the
test passes when the MIR interpreter, both ROMs in dampf's emulator and, if present, Mesen all
end with these values, and every call in the ROM from MIR stays within its static timing.

A test is only useful if its result depends on what it tests: a global that nothing writes
stays at its initial value whatever dampf does. Deliberately broken code generation must make a
test fail. Once it did not: a 32-bit arithmetic shift had high bits that no test looked at, and
a test was added for them.

## When something hangs

A test must fail, not wait forever. Every program test runs under a hard time limit
(`assertTimeoutPreemptively`): 3 minutes per execution test, 2 per random program, 15 per
replay and 2 per MIR round trip, because JUnit's default timeouts do not cover the dynamic tests
these are built from; `GameTimingTest` has 10 minutes. External programs (gcc, the PC builds,
ca65, ld65) run through `dampf.util.Processes` with a time limit and are killed when it runs
out. The emulator stops after 600 frames in the program tests and after 200,000 frames in
`dampf bench` and the replays; the MIR interpreter counts its steps. The Gradle test task has an
overall limit of 2 hours as a last resort. A program that loops forever fails in about a
minute with "did not finish within 60 s and was stopped (does it hang?)".

## Why it works this way

**The naive code generator stays.** dampf could have dropped its first code generator once the
one from MIR was better. It keeps it as a reference that shares only the front end with the
optimized path: when the two disagree, the difference points to the optimization at fault. The
cost is a second code generator to maintain and every test program built twice
(`docs/testing.md`, *Supporting pieces*).

**An emulator of its own.** Mesen is a complete SNES emulator and runs the execution tests where
it is installed, but it is an external program and much slower to run a ROM in. dampf's emulator
runs inside the JVM, so the random programs run on every build, the replays and the benchmark
run without Mesen, and every run counts master cycles at the same time. The cost is a second
emulator that has to be proved right, against the SingleStepTests suite and against Mesen
(`docs/testing.md`, *Supporting pieces*).

**UBSan on the PC side.** Without the sanitizer, a test program with undefined behaviour could
give gcc's answer by luck, and a later change on either side would break it for no visible
reason. With it, such a program fails at once, and the random program generator has to avoid
undefined behaviour by construction (`docs/testing.md`, *How the execution and random tests
work today*).
