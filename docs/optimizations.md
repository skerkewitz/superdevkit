# Optimizations

What makes dampf's code fast: the measured starting point, the catalogue of optimizations with
what each is expected to bring and what it has shown in measurements, how close dampf can get to
hand-written assembly, and what knowing the exact machine allows. Expected gains are hypotheses,
not measurements; each one is measured as soon as the naive compiler (M2) works, and the catalogue
is re-prioritised after M2.

## The budget

Game logic in Blubber Blasen's busiest scene may use at most 60 % of an NTSC frame. A frame has
roughly 45,000–55,000 CPU cycles, depending on how much code runs from FastROM and how often WRAM
is accessed; vertical blank work (DMA for sprites and tiles) and a safety margin come off that.
Sound runs on its own CPU (SPC700) and costs nothing here. The exact frame numbers are in
[timing.md](timing.md).

What matters in the end is not the distance to perfect assembly but whether the game logic fits
this budget.

## Baseline

Blubber Blasen's benchmark (`tools/snesbench` in the game repository) runs the same game code with a
fixed input for 1,800 ticks, with every PVSnesLib call stubbed out, and measures each tick in
Mesen 2. One NTSC frame is 357,366 master clocks. Results from the game's `build/bench/` as of
2026-11-17 (`calypsi_asm`, the latest Calypsi run):

| State | Ticks | 816-tcc | Calypsi |
| --- | --- | --- | --- |
| title | 61 | 1.90 frames | 0.91 frames |
| story | 530 | 1.22 | 0.58 |
| round intro | 120 | 6.47 | 4.11 |
| play | 1,089 | 6.42 | 2.72 (worst tick 3.82) |
| all | 1,810 | 4.76 | 2.13 |

The goal is game logic in at most 0.6 frames in the busiest scene. During play that means dampf
has to be about 4.5 times as fast as Calypsi on average and 6.4 times on the worst tick. The
overall expectation below is a factor of 3 to 6 over dampf's own naive code generator, so the
naive generator has to be no slower than Calypsi, or the game has to get cheaper as well (the
game's ROADMAP already lists a cheaper CPU partner as a candidate; see also
[game-findings.md](game-findings.md)).

### dampf's naive code generator (2026-11-17)

The same benchmark built with dampf (`tools/bench_game.sh`): all 3,610 ticks give the same
checksums as the PC reference. Frames per tick:

| State | Ticks | dampf, naive | 816-tcc | Calypsi |
| --- | --- | --- | --- | --- |
| title | 61 | 1.94 | 1.90 | 0.91 |
| story | 530 | 0.70 | 1.22 | 0.58 |
| round intro | 120 | 5.76 | 6.47 | 4.11 |
| play | 1,089 | 5.17 (worst tick 8.58) | 6.42 | 2.72 (worst 3.82) |
| round intro, CPU partner | 120 | 6.15 | | |
| play, CPU partner | 1,089 | 6.55 (worst tick 12.23) | | |
| `level_solid_px`, per call | | 3,849 clocks | 2,449 | 931 |

Caveats: the 816-tcc and Calypsi columns are the older runs above, made with an earlier version of
the game and without the CPU partner section, which the benchmark has had only since 2026-11-17. A
fair comparison needs all three compilers on the same version of the game; dampf cannot build the
other two, so that run has to happen on the game's side.

What it shows already: the naive generator is about as fast as 816-tcc and about 1.9 times slower
than Calypsi during play. Calls are its weakest point (`level_solid_px`: 1.6 times 816-tcc), because
every argument goes through memory and every call is a `JSL`. With the CPU partner, the worst play
tick takes 12.23 frames, which is where the 60 % budget is hardest to meet.

The round intro (4.11 frames with Calypsi) is not game play and may be allowed to take more than
one frame; that is for the game to decide.

The two compilers give different checksums from tick 1,133 on (tick 596 in older runs), so at least
one of them does not compute what the PC computes. dampf's benchmark run has to match the PC
reference checksums (`tools/snesbench/pc`) on every tick.

Why Calypsi is slow is being investigated on the game's side; the results decide which rows of
the catalogue come first. Compared with Calypsi, dampf promises nothing until it is measured.

## Catalogue

| # | Optimization | What it does | Expected gain | Prerequisite | Measured gain | Status |
| --- | --- | --- | --- | --- | --- | --- |
| A | Static frames in the direct page | Locals, parameters and temporaries get fixed direct page slots, shared between functions that are never active at the same time. Possible because the call graph is complete and has no recursion. No stack frames, no stack-relative access. | Very high; probably the largest single gain | complete call graph, no recursion (given, see [subset.md](subset.md)) | | open (the naive generator already uses static frames, in RAM and not shared) |
| B | Width analysis (8/16 bit) | Choose the smallest width per value, track `REP`/`SEP` as processor state, minimise switches | High, especially for `uint8_t`-heavy code | H | | open |
| C | Inlining and calling convention | Inline small functions, pass parameters in A/X/Y or the direct page, `JMP` instead of `JSR`+`RTS` for tail calls | High with many small helpers | call graph | | open |
| D | Data layout | Split arrays of structs into one array per field (index in X/Y), or point D at the current object | High in the loops over bubbles and enemies | | | open |
| E | 8.8 fixed point | `>>8`, `<<8` and comparisons of the integer part as byte accesses instead of shifts | Medium to high; the game uses 8.8 everywhere | B | | open |
| F | Loops | Counter in X/Y, count down to zero, hoist invariant code, unroll small loops (by hint) | Medium | H | | open |
| G | Branches | Branch on flags instead of materialising 0/1, common path falls through, correct branch distances | Medium | | | open |
| H | Standard optimizations | Constant folding and propagation, dead code removal, common subexpressions | Medium; mostly a prerequisite for A–G | IR in SSA form | | open |
| I | Multiply and divide | Constants as shifts and adds, hardware multiplier with useful work during its latency, division by powers of two with correct signed rounding | Medium, depends on the code | range analysis for signed division | | open |
| J | Banks and placement | Keep the data bank register right, 16-bit instead of 24-bit addresses, hot code in FastROM | Small to medium | pointer width analysis | | open |
| K | Peephole | Remove redundant loads and stores, `STZ`, `INC`/`DEC` on memory | Small, but everywhere | | | open |
| L | `switch` as jump table | `JMP (abs,X)` | Small overall, large locally | | | open |
| M | Copy and fill | `MVN`/`MVP` or DMA instead of loops | Large locally | | | open |
| N | Values in X/Y | Keep values in the index registers within a block | Small to medium | | | open |

**Overall expectation:** roughly a factor of 3–6 over dampf's own naive code generator.

Later additions, from the design discussion: profile-guided optimization from the replays,
superoptimization of short hot sequences, and the transforming hints (`DAMPF_TABLE`,
`DAMPF_PIN_X`, `DAMPF_SPLIT`; see [hints.md](hints.md)). They are described below.

## How close can dampf get to hand-written assembly?

### Expectation

| Code generator | Cycles compared with good hand-written assembly |
| --- | --- |
| 816-tcc (PVSnesLib) | probably 5–10 × (observed: very slow, needed workarounds) |
| Calypsi | unknown; currently being investigated |
| dampf, naive code generator (milestone M2) | like a simple compiler, clearly slower than hand-written code |
| dampf after the optimizations, hot loops, without hints | about 1.2–1.5 × |
| dampf after the optimizations, hot loops, with hints | about 1.1–1.2 ×, in places equal |
| dampf, the rest of the code | equal to or better than what a human would write there in practice |

All of these are estimates, not measurements. The reference assembly benchmarks below replace
them with measured numbers.

### Where a gap remains, and why

A skilled assembly programmer knows things about the program that are not in the C code, and uses
them:

1. **Data representation chosen for the CPU.** For example a state flag in bit 7, so that `BIT`
   followed by `BMI` tests it without a compare, or a lookup table in ROM instead of a
   calculation.
2. **Conventions across many functions.** For example "X always holds the index of the current
   bubble during the update phase", or "this whole routine runs in 8-bit mode".
3. **Flags as data.** The carry flag as a yes/no return value, the overflow flag after `BIT` as a
   second test bit.
4. **Instruction-level tricks.** `XBA`, moving the direct page register with `TCD`, `PEI`/`PEA`
   for fast copies, stack tricks for DMA tables.

Most of this can be closed with hints ([hints.md](hints.md)) and optimization rules. What cannot
be closed fully: a human sometimes invents a new trick on the spot, while dampf only applies the
tricks it has been taught. The reference benchmarks are the loop that keeps teaching it.

### Where dampf can beat a human

- **Consistency.** People hand-optimize the two or three hottest routines and write the rest
  pragmatically. dampf optimizes every line equally.
- **Whole-program direct page allocation.** Fixed direct page slots for thousands of local
  variables, shared between functions that are never active at the same time. Almost nobody does
  this cleanly by hand.
- **Re-optimizing after every change.** Hand-written assembly is rarely re-tuned after a design
  change. dampf re-optimizes on every build.
- **Aggressive inlining.** People keep functions separate so the code stays maintainable. dampf
  does not have to maintain its output.
- **Exhaustive search** for short sequences (superoptimization, below).

## Reference assembly benchmarks

3–5 hot functions of Blubber Blasen are written by hand in 65816 assembly as **reference
versions**. They are not the game's code; they are the yardstick. Candidates (to be confirmed by
the Calypsi investigation and by profiling): bubble movement and bubble-to-bubble pushing, the
chain reaction when bubbles pop, collision against tiles, and building the sprite table (OAM). The
first profile of the Calypsi build points the same way: `bubbles_update`, `video_frame` and
`enemies_draw` are the top three ([game-findings.md](game-findings.md)).

How they are used:

1. Each reference function and dampf's version of the same C function run in the cycle-counting
   emulator on the same inputs.
2. The result is a ratio per function: dampf needs *x* times the cycles of the reference. This
   ratio is tracked over the milestones, so "how close are we" is a number, not a feeling.
3. Where the reference is faster, we look at why. The answer becomes either a new hint or a new
   optimization rule. The gap shrinks step by step.
4. Escape hatch: if dampf stays too far behind on one function despite all efforts, the reference
   version already exists. dampf can then link that hand-written function in its place, with a
   declared calling convention and declared direct page usage, so the rest of the program and the
   timing analysis still know what it does. This is only used when measurement demands it.
   Hand-written assembly for the whole game was tried years ago and failed; for a few hot
   functions it stays possible, but it is not the plan.

## Knowing the machine

A PC compiler has to produce code for thousands of different processors, under an operating
system, next to other software. dampf knows exactly where its code runs: a Ricoh 5A22 at 60 Hz
(NTSC), with 128 KB of WRAM, no operating system and no other software. Like on modern consoles,
this fixed target allows optimizations that are impossible on general hardware.

| Knowledge | Optimization |
| --- | --- |
| **Exact cost of every memory access.** The SNES counts in master cycles (21.477 MHz on NTSC). A CPU memory access costs 6 master cycles for FastROM and most I/O registers, 8 for WRAM and SlowROM, 12 for a few old I/O registers. | dampf's cost model is exact, not estimated. A surprising consequence: constant tables are read *faster* from FastROM than from WRAM, so hot code and constant tables belong in FastROM. Because every instruction byte is fetched from ROM, code size counts directly in cycles. |
| **Two hardware multipliers.** The CPU's unit (8 × 8 bit, unsigned, result after 8 CPU cycles; it also divides 16 / 8 bit, result after 16 cycles) and the PPU's multiplier (16 × 8 bit, signed, result available immediately). The PPU multiplier is unavailable only while Mode 7 is being drawn. | Blubber Blasen does not use Mode 7, so dampf always has a multiplier with an immediate result. For each multiplication it picks the cheapest option: shifts and adds for constants, the PPU multiplier for signed 16 × 8, the CPU unit with useful work filled into its latency where that is cheaper. The hardware divider is unsigned only, so signed division needs sign handling around it. |
| **DMA units** | Large `memset` / `memcpy` become DMA, for example clearing WRAM by DMA from a constant byte in ROM to the WRAM data port (`$2180`). dampf knows the costs exactly and therefore the size from which DMA pays off. |
| **Fixed addresses, no operating system** | Every address is known at compile time. `array[3]` is a fixed address, not a pointer plus an offset. No foreign code expects registers to be preserved, and there is no external calling convention to follow. |
| **We control the interrupts** | dampf sees which registers and variables the NMI and IRQ handlers touch. It saves only what is needed and gives the handlers their own direct page. |
| **No dynamic memory** | Total memory use and the **maximum stack depth** are known at compile time. dampf reports them exactly, and a stack overflow cannot happen. |
| **Real game situations are recorded** (replays) | Profile-guided optimization, below. |

### Profile-guided optimization with the replays

Blubber Blasen's replays are recordings of real play. Running them in the cycle-counting emulator
shows how often each branch is taken and which functions are hot. This profile data is fed back
into dampf:

- the common side of each branch falls through without a jump,
- hot functions are inlined more readily,
- hot code and its tables are placed in FastROM,
- optimizations that trade code size for speed are spent where they matter.

Modern compilers do this too, but rarely with such representative data. Many
`DAMPF_LIKELY`/`DAMPF_UNLIKELY` hints become unnecessary.

### What this means for the expectation

This does not close a new gap to hand-written assembly: a good SNES programmer knows all of this
too. It makes sure dampf applies the knowledge everywhere and always, not only in the three
routines a human optimizes by hand. Exact timing ([timing.md](timing.md)) and profile-guided
optimization go beyond what is practical by hand.

## Superoptimization for very short sequences

For short, very hot instruction sequences (about 5–8 instructions), dampf can search instead of
following rules: generate candidate instruction sequences up to a given length, run each in the
cycle-counting emulator, and keep the one that produces the same result in the fewest cycles. For
sequences with 8-bit inputs, equivalence can even be checked exhaustively (256 inputs for one
byte, 65,536 for two). This sometimes finds sequences no human would think of. It is an optional,
late optimization (M4).
