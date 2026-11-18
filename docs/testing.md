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
| Replays | The whole game against its recorded checksums and the PC | dampf's emulator; Mesen for the platform layer | Minutes; only with `-Preplays` |
| Game benchmark | 3,610 ticks against a PC build, plus cycles per tick | dampf's emulator, Mesen, the MIR interpreter | About a minute |

**Unit tests** cover the front end (`PreprocessorTest`, `ParserTest`, `AstBuilderTest`), MIR
passes written as MIR text (`NarrowTest`, `GvnTest`, `InlineTest`, `LicmTest`, `RangesTest`,
...), timing and budgets (`TimingTest`, `BudgetTest`) and the cycle table (`Cycles65816Test`).
`MirTextTest` prints the MIR of every execution program and of the whole game, parses it back
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

**Random programs** (`RandomProgramTest`, 50 per build) run like the execution tests, without
the block counts and without Mesen. The generator makes programs free of undefined behaviour by construction (divisors
made odd, shift counts masked, wide arithmetic in `uint32_t`) and favours boundary values like
−32768, where sign and overflow handling go wrong.

**Replays.** The game records a play session as a `.bbr` file: the start (seed, save RAM, round,
options), the joypad words whenever they change, and a checksum of everything the game shows
every 60 ticks. `dev/replay_to_c.py` turns a recording into C tables in ROM, and
`dev/replay/driver.c` plays them exactly like the game's own `replay_run()` (the game's
`replay.c` is host code with stdio and `malloc`, so the driver is written in dampf's subset).
`ReplayTest` builds the driver with the game in its debug configuration (`BB_DEBUG`) with gcc
and with dampf, and requires both to match every recorded checksum and each other; the build
from MIR and the optimized MIR in the interpreter must match too.

With a Mesen, `ReplayTest` also plays each recording through the SNES platform layer
(`lib/snes`, the code of the playable ROM) instead of the stubs: the driver, built with
`REPLAY_PLATFORM`, starts the console before `game_init` and after every tick waits for the
vertical blank, copies the OAM shadow and runs `game_vblank`'s uploads. This needs Mesen, since
dampf's emulator has no PPU. The checksums must match the PC, and `MesenRunner` (with its PPU
check) counts every byte written to VRAM, CGRAM or OAM outside a vertical blank with the screen
on, which must be none. Lua runs for every one of those bytes; with flags kept by the frame
events instead of the PPU state read per byte, the test takes about a minute (ADR record 33). The playable ROM has a quicker check, `dev/check_game_rom.sh`: a minute of scripted
play in Mesen with the same count of lost bytes, the frames per tick, and screenshots. It found
the first platform bug (a map row copied after the vertical blank had ended).

**The game's benchmark** (`dev/bench_game.sh`) runs 3,610 ticks with three checksums per tick
against a PC build of the same program, plus the cycles per tick. It also runs in the MIR
interpreter (`dampf mir --bench main ...`, 11 seconds) and gives the PC's checksums on all
ticks. `GameTimingTest` (with `-Preplays`) checks the static timing on it.

## Supporting pieces

- **dampf's emulator** (`dampf.emu`: 65816 CPU, LoROM, WRAM, no graphics or sound) runs the
  execution, random-program and replay tests and the game's benchmark inside the JVM, and counts
  master cycles with the memory speeds of the SNES (WRAM and SlowROM 8, I/O 6, internal cycles
  6, DRAM refresh 40 per scanline). It is validated
  - against the SingleStepTests 65816 suite (github.com/SingleStepTests/65816,
    `CpuSingleStepTest`, `-PcpuTests`): native mode, all 256 opcodes, about 300 random tests
    each (76,059 in all); registers, flags, memory, CPU cycles and the number of memory accesses
    match. Emulation mode is only modelled as far as the start-up needs it;
  - against Mesen on the game's benchmark: the same checksums on all 3,610 ticks, and the same
    master cycles per tick within a few cycles out of millions (`dampf bench`).
- **Mesen** (MesenCE in `.mesen/`, from `dev/fetch_mesen.sh`, or `-Pmesen=<path>`) runs the execution tests and the replay
  through the platform layer; the random programs and the other replays run in dampf's emulator
  only.
- **Cycle table:** `CycleTableAgainstEmulatorTest` checks the cost model
  (`dampf.target.Cycles65816`) against the emulator for every opcode in dampf's processor state;
  `Cycles65816Test` spot-checks it against the W65C816S data sheet.
- **Mutation checks:** deliberately broken code generation must make tests fail. When one did
  not (a 32-bit arithmetic shift whose high bits no test observed), a test was added.
- **Smaller tests:** `MappingTest` (where the cartridge's ROM appears, LoROM and HiROM);
  `ConstAddressTest` (pointers made from numbers, `REG8(0x210B + i)`, cannot run on the PC, so
  it runs them in dampf's emulator on WRAM addresses and compares with what C says);
  `BlubberBlasenParseTest` (every file of the snapshot parses, or of the live game with
  `-Pblubberblasen=<path>`).
- **`BackendOutputTest`** freezes the back end's output: a hash of the assembly and the static
  timing of every execution test, 20 random programs and the game (also with the hand-written
  reference function), with both code generators. A step that only moves code must keep every
  hash; one that changes code records new ones (`-PbackendOutput=update`, which also keeps a copy
  of the assembly in `build/backend-output-ref/` for diffing) together with the game's numbers
  in [optimizations-log.md](optimizations-log.md), and never in the same step as a move (ADR
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
- **Reference assembly benchmarks:** hand-written versions of hot functions as the yardstick
  ([optimizations.md](optimizations.md)); one so far, `reference/level_solid_px.s`, linked with
  `--replace`.
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

A test must fail, not wait forever. Every program test (execution tests, random programs, replays,
the MIR round trips) runs under a hard time limit (`assertTimeoutPreemptively`: 3, 2, 15 (20
through the platform layer) and 2 minutes per program, 10 minutes for `GameTimingTest`), because
JUnit's default timeouts do not cover dynamic tests. External programs (gcc, the PC builds,
and ca65 and ld65 where the tests use them) run through `dampf.util.Processes` with a time limit and are killed when it runs
out. The emulator stops after 600 frames in the program tests and 200,000 frames in
`dampf bench` and the replays; the MIR interpreter counts its steps. The Gradle test task has an
overall limit of 2 hours as a last resort. A program that loops forever fails in about a minute
with "did not finish within 60 s and was stopped (does it hang?)".
