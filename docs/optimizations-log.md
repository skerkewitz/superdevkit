# Optimizations: the log

The dated measurements behind [optimizations.md](optimizations.md), one entry per step, newest at
the end. Every entry keeps the numbers as they were measured at the time, on the game's benchmark:
frames per tick or master clocks per tick (one NTSC frame is 357,368 master clocks), at SlowROM
speed until the FastROM entry and with FastROM after it. Later steps change earlier numbers, so the
current ones are in [optimizations.md](optimizations.md), *Where things stand*, and nowhere else.

## Baseline

Blubber Blasen's benchmark (`tools/snesbench` in the game repository) runs the same game code with a
fixed input for 1,800 ticks, with every PVSnesLib call stubbed out, and measures each tick in
Mesen 2. One NTSC frame is 357,368 master clocks. Results from the game's `build/bench/` as of
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
overall expectation in [optimizations.md](optimizations.md) is a factor of 3 to 6 over dampf's own naive code generator, so the
naive generator has to be no slower than Calypsi, or the game has to get cheaper as well (the
game's ROADMAP already lists a cheaper CPU partner as a candidate; see also
[game-findings.md](game-findings.md)).

## dampf's naive code generator (2026-11-17)

The same benchmark built with dampf (`dev/bench_game.sh`): all 3,610 ticks give the same
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

## Where the naive code spends its time (2026-11-17)

`dev/profile_game.sh bench` profiles the benchmark's play ticks 900–1700 (as the game's own
profile does): dampf's emulator (or Mesen, with `WITH_MESEN=1`) counts how often each instruction runs, dampf's cycle table
(`dampf.target.Cycles65816`) gives what each instruction costs, and `dampf profile` adds it up
per function. The benchmark's own checksum code (`bench.c`) is left out. The model agrees with the
measurement: 231,000 CPU cycles per tick in the game code, about 8 master clocks per CPU cycle,
5.2 frames per tick (measured: 5.17).

| Share | Function | Cycles per call | Note |
| --- | --- | --- | --- |
| 37.3 % | `dampf_mul16` (runtime) | 304 | 283 calls per tick |
| 8.3 % | `separate` | 22,608 | bubble pairs, see game finding 2 |
| 7.6 % | `build_sprites` | 17,624 | |
| 3.7 % | `level_solid_px` | 384 | 22 calls per tick |
| 3.3 % | `bubbles_update` | 8,918 | |
| 2.9 % | `bubbles_draw` | 6,619 | |
| 2.6 % | `floating` | 78 | 76 calls per tick |

More than a third of the time goes into the runtime's general 16-bit multiplication, and nearly
all of those calls index arrays of structs: `bubbles[i]` multiplies `i` by 23 (`sizeof(Bubble)`),
items by 12, enemies by 21, vacuums by 53. The naive generator does that with a 16-step loop,
about 300 cycles per access. A constant factor as shifts and adds (optimization I) costs about
10–20 cycles; split struct arrays (optimization D) remove the multiplication altogether. Both
move up in the order of the optimizations.

The round intro (4.11 frames with Calypsi) is not game play and may be allowed to take more than
one frame; that is for the game to decide.

The two compilers give different checksums from tick 1,133 on (tick 596 in older runs), so at least
one of them does not compute what the PC computes. dampf's benchmark run has to match the PC
reference checksums (`tools/snesbench/pc`) on every tick.

Why Calypsi is slow is being investigated on the game's side; the results decide which rows of
the catalogue come first. Compared with Calypsi, dampf promises nothing until it is measured.
(Since then, both were measured on the same version of the game; see the summary of the code
from MIR below.)

## The first reference function, `level_solid_px` (2026-11-17)

| Function | dampf, naive | 816-tcc | Calypsi | Hand-written | dampf / hand-written |
| --- | --- | --- | --- | --- | --- |
| `level_solid_px` (2026-11-17) | 3,849 | 2,449 | 931 | 856 | **4.5** |

Master clocks per call, from the benchmark's micro part (1,000 calls per pass, the bare loop
subtracted); the 816-tcc and Calypsi numbers are from older runs on the game's side. The
hand-written version is `reference/level_solid_px.s`, linked with `--replace`; with it, all 3,610
benchmark ticks still match the PC. Over the whole benchmark this one function makes play 5 %
faster (5.17 → 4.92 frames per tick). dampf's code from MIR now takes 1,149 clocks at SlowROM
speed, 1.34 times the hand-written version (see the summary of the code from MIR below). On a small function like this one, Calypsi already comes
close to hand-written code.

The first version of the reference was wrong: it checked the rows below the field before the
walls, the opposite order of the C, and returned "open" for a position both outside the walls and
below the field. The game never asks for such a position, but the benchmark's micro part does, and
its checksums caught it. Hand-written code needs the same oracle as compiled code.

## dampf's code generator from MIR, before any pass (2026-11-17)

The code generator from MIR (`dampf.backend.MirCodeGen`, `BACKEND=mir dev/bench_game.sh`) keeps
every value in RAM and translates one MIR instruction at a time. It is the starting point the MIR
passes are measured from. All 3,610 ticks give the PC's checksums. Frames per tick:

| State | dampf, naive | dampf from MIR, no passes |
| --- | --- | --- |
| title | 1.94 | 12.21 |
| story | 0.70 | 3.56 |
| round intro | 5.76 | 12.13 |
| play | 5.17 (worst 8.58) | 18.93 (worst 33.76) |
| play, CPU partner | 6.55 (worst 12.23) | 23.13 (worst 46.17) |
| `level_solid_px`, per call | 3,849 clocks | 6,648 clocks |

It is 3.7 times slower than the naive generator during play. That is expected, for two reasons. MIR
computes in C's 32-bit `int` everywhere the C source does, and the naive generator already
narrows to 16 bits from the operand types. Also, every value makes a round trip through RAM. The
first pass, width narrowing (B), removes most of the first cause.

## After width narrowing (B), 2026-11-17

Pass B (`dampf.mir.Narrow`) works from the types alone, with exact rules: a truncated sum,
product or bit operation is computed at the narrow width; operations and comparisons on
zero- or sign-extended operands become narrow; casts of casts collapse; and pointer offsets become
16 bits, because an offset within an object always fits (objects are at most 32 KB and never
cross a bank, see [memory-map.md](memory-map.md)). Frames per tick, code from MIR:

| State | naive | MIR, no passes | B, types only | B with 16-bit offsets |
| --- | --- | --- | --- | --- |
| title | 1.94 | 12.21 | 11.40 | 4.03 |
| story | 0.70 | 3.56 | 3.21 | 1.41 |
| round intro | 5.76 | 12.13 | 10.73 | 7.00 |
| play | 5.17 | 18.93 | 16.08 | 8.16 (worst 14.34) |
| play, CPU partner | 6.55 | 23.13 | 20.55 | 9.92 (worst 19.96) |
| `level_solid_px`, per call | 3,849 | 6,648 | 4,789 | 4,214 clocks |

The 16-bit offsets bring the most: before them, 61 % of the time went into `dampf_mul32`, for the
32-bit index arithmetic in front of every array access. The multiplications that are left are
16-bit, by the constant size of a struct; pass I turns them into shifts and adds.

What the types cannot show stays wide. In `level_solid_px`, `(sy - 16) >> 3` needs 17 bits for
an arbitrary `int16_t sy`; only the branch before it (`sy < 16` returns) shows that `sy - 16`
cannot overflow. That needs value ranges with the conditions of branches (later, as part of B).

## After constant multiplication and division (I), 2026-11-17

Pass I (`dampf.mir.StrengthReduce`) writes a multiplication by a constant as shifts and adds (the
constant in non-adjacent form, at most four nonzero digits: 12 = 8 + 4, 7 = 8 − 1), unsigned
division and remainder by 2^k as a shift and a mask, and signed ones with C's rounding towards
zero. The code generator writes shifts by up to 8 out instead of looping. Frames per tick:

| State | naive | MIR after B | after B and I |
| --- | --- | --- | --- |
| title | 1.94 | 4.03 | 1.91 |
| story | 0.70 | 1.41 | 0.95 |
| round intro | 5.76 | 7.00 | 6.12 |
| play | 5.17 (worst 8.58) | 8.16 | 6.12 (worst 10.65) |
| play, CPU partner | 6.55 (worst 12.23) | 9.92 | 7.11 (worst 13.54) |
| `level_solid_px`, per call | 3,849 | 4,214 | 3,781 clocks |

The runtime multiplication falls from 61 % of the time (as `dampf_mul32`) to 4.5 % (`dampf_mul16`,
by variables and by constants with more digits). The code from MIR is now within 18 % of the naive
generator during play and faster in the title. What is left is mostly the code generator: every
value goes through RAM, with a load and a store per instruction.

## After the standard optimizations (H), 2026-11-17

Three passes, all exact:

- **Simplify:** constants fold (with the interpreter's arithmetic), identities such as
  `x + 0` and `x - x` simplify, branches on constants become jumps, unreachable blocks and
  trivial φs go, and a block that only one block jumps to is merged into it.
- **Gvn:** a computation that a dominating one already did is reused. A load is reused when
  nothing in between may have written its address, and a store forwards its value to later
  loads. A store kills only what it may overlap: two different globals or frame slots never
  overlap. A call kills nothing if the called function writes no memory.
- **B, extended:** narrowing reaches through φs, so `(uint16_t)(d > 0 ? x - 64 : x + 64)` is
  computed in 16 bits.

Frames per tick:

| State | naive | MIR after B and I | after B, I and H |
| --- | --- | --- | --- |
| title | 1.94 | 1.91 | 1.67 |
| story | 0.70 | 0.95 | 0.77 |
| round intro | 5.76 | 6.12 | 5.78 |
| play | 5.17 (worst 8.58) | 6.12 (worst 10.65) | 5.17 (worst 8.77) |
| play, CPU partner | 6.55 (worst 12.23) | 7.11 (worst 13.54) | 6.10 (worst 11.22) |
| `level_solid_px`, per call | 3,849 | 3,781 | 3,806 clocks |

The code from MIR has caught up with the naive generator during play and is 7 % faster with
the CPU partner, the case where the budget is tightest. The naive generator is not simple: it
narrows from the types, indexes with X and keeps values in A within an expression. The code from
MIR still does none of that. Every MIR instruction loads its operands from RAM and stores its
result, a comparison stores its truth value before the branch reads it again, and every value
has its own RAM address. The next gains are in the code generator: compare and branch in one,
values in registers and in the direct page (A, C, D in the catalogue).

## Compare and branch in one, 2026-11-17

A comparison that only its block's branch uses no longer stores a truth value: the code
generator sets the flags and branches on them (`bcc`/`bcs`/`beq`/`bne` over a `JMP`, because
blocks can be far apart; a comparison with 0 needs no `CMP`). Frames per tick:

| State | naive | MIR after H | + compare and branch |
| --- | --- | --- | --- |
| title | 1.94 | 1.67 | 1.49 |
| story | 0.70 | 0.77 | 0.69 |
| round intro | 5.76 | 5.78 | 5.34 |
| play | 5.17 (worst 8.58) | 5.17 (worst 8.77) | 4.48 (worst 7.58) |
| play, CPU partner | 6.55 (worst 12.23) | 6.10 (worst 11.22) | 5.37 (worst 10.12) |
| `level_solid_px`, per call | 3,849 | 3,806 | 3,276 clocks |

## Value ranges (the rest of B), 2026-11-17

`dampf.mir.Ranges` infers an interval for every value: from constants, from the operations, and
from the conditions of branches (in a block reached only when `sy >= 16`, sy is 16..32767). Loops
are iterated to a fixed point, with widening. `RangeNarrow` uses the intervals: it decides
comparisons and does i32 operations and comparisons in 16 bits when operands and result fit. In
`level_solid_px`, `(sy - 16) >> 3` is now 16-bit. The tests check the analysis itself: the
interpreter reports every value it computes, and each must lie in its inferred interval (all
execution tests, the random programs, the replay, and `dampf mir -O --check-ranges --bench`).

| State | naive | before | with value ranges |
| --- | --- | --- | --- |
| play | 5.17 (worst 8.58) | 4.48 (worst 7.58) | 4.38 (worst 7.50) |
| play, CPU partner | 6.55 (worst 12.23) | 5.37 (worst 10.12) | 5.15 (worst 9.13) |
| `level_solid_px`, per call | 3,849 | 3,276 | 2,433 clocks (816-tcc: 2,449; Calypsi: 931) |

Later additions to the analysis (2026-11-17): narrowing after the widened fixpoint, conditions
between two values (`r < n`, from a first run's ranges), parameter ranges from all call sites
(`ParamRanges`) and `DAMPF_RANGE` hints; see the summary section below and
[timing.md](timing.md).

## Fewer loads and copies in the code generator, 2026-11-17

Two small changes to the code generator from MIR. It remembers what A holds (a frame place or a
constant) and leaves out a load of the same thing; labels, calls, stores through pointers and
anything that changes A make it forget, and loads whose flags a branch needs always happen.
And the predecessors of a block write its φs directly, without the second copy through an
"incoming" place, when every predecessor ends with a jump and no φ reads another φ of the block.

| State | naive | before | fewer loads | and direct φs |
| --- | --- | --- | --- | --- |
| play | 5.17 (worst 8.58) | 4.38 | 4.04 | 3.85 (worst 6.59) |
| play, CPU partner | 6.55 (worst 12.23) | 5.15 | 4.81 | 4.61 (worst 8.22) |
| `level_solid_px`, per call | 3,849 | 2,433 | 2,217 | 2,217 clocks |

## Frames in the direct page (first part of A), 2026-11-17

The frames of the code from MIR moved from BSS to the free low RAM at `$0020-$1DFF`, and the
compiled stack was turned around: a function's frame starts after the frames of the functions
it calls, so the leaves of the call graph (small, and called most often) start at `$0020`, in
the direct page (D stays 0). There a load or store costs 4 cycles instead of 5, and a pointer
can be used as `[place],y` without copying it to `dampf_ptr` first. At the same time: a load or
store at a pointer plus a constant takes the constant into Y (or into the operand, for a global)
and the `ptradd` is not computed, and a pointer copied into `dampf_ptr` is not copied again until
a label or a call.

| State | naive | before | frames in the direct page |
| --- | --- | --- | --- |
| title | 1.94 | 1.48 | 1.03 |
| play | 5.17 (worst 8.58) | 3.85 (worst 6.59) | 2.90 (worst 4.93) |
| play, CPU partner | 6.55 (worst 12.23) | 4.61 (worst 8.22) | 3.68 (worst 6.92) |
| `level_solid_px`, per call | 3,849 | 2,217 | 1,912 clocks |

Calypsi took 2.72 frames per tick during play, measured on an older version of the game; a fair
comparison needs both on the same version (on the game's side).

## Shared places within a function, 2026-11-17

Values whose lifetimes do not overlap now share a place in the frame (`dampf.backend.FrameLayout`:
liveness, an interference graph, greedy colouring by byte ranges). The frames of the whole game
shrink to 214 bytes, so every value of every function is in the direct page.

| State | naive | before | shared places |
| --- | --- | --- | --- |
| title | 1.94 | 1.03 | 0.98 |
| play | 5.17 (worst 8.58) | 2.90 (worst 4.93) | 2.72 (worst 4.64) |
| play, CPU partner | 6.55 (worst 12.23) | 3.68 (worst 6.92) | 3.45 (worst 6.38) |

## Inlining and smaller things, 2026-11-17

- **Inlining** (`dampf.mir.Inline`): functions of at most 30 MIR instructions, and functions
  with one call site of at most 150, are copied into their callers, leaves first; then the
  passes run again on the larger functions.
- **Casts that only copy bytes** (zero extension, truncation to 16 bits, integer/pointer) get no
  place and no code; their users read the source.
- **No mask** after an 8-bit add, subtract, multiply or left shift whose unsigned operand ranges
  keep the 16-bit result below 256, and never after a right shift.
- **Jump threading** in `Simplify`: a branch to a block that only jumps goes to the final target.

| State | naive | before | all of these |
| --- | --- | --- | --- |
| title | 1.94 | 0.98 | 0.91 |
| play | 5.17 (worst 8.58) | 2.72 (worst 4.64) | 2.55 (worst 4.28) |
| play, CPU partner | 6.55 (worst 12.23) | 3.45 (worst 6.38) | 3.31 (worst 6.08) |
| `level_solid_px`, per call | 3,849 | 1,912 | 1,774 clocks |

With inlining the frames grow to 410 bytes; 91 % of the frame accesses in the code still use the
direct page.

## Layout, results in A, shared places for φs, 2026-11-17

- **Block layout:** blocks are written in chains, each followed by a successor not yet written
  (for a branch the true side), so most branches skip one jump instead of choosing between two.
- **Shifts by 8 or more** of a 16-bit value start with `XBA` (swap the bytes) and a mask.
- **Results kept in A:** a 1-word result whose only reader is the next instruction, which starts
  by loading it, is not stored at all. The cases are listed one by one in
  `MirCodeGen.readsFirst`.
- **φs share their operands' places** where the lifetimes allow it, so the copy at the end of a
  loop body disappears.

| State | naive | before | layout and XBA | results in A | φ places |
| --- | --- | --- | --- | --- | --- |
| play | 5.17 (worst 8.58) | 2.55 | 2.50 | 2.40 | 2.35 (worst 3.88) |
| play, CPU partner | 6.55 (worst 12.23) | 3.31 | 3.27 | 3.18 | 3.12 (worst 5.69) |
| `level_solid_px`, per call | 3,849 | 1,774 | 1,700 | 1,636 | 1,636 clocks |

## Arrays indexed with X, multiplication in A, 2026-11-17

- **Indexed addressing:** a load or store at a global (or frame slot) plus a 16-bit index the
  value ranges show to be non-negative, plus a constant, is `ldx index` and
  `lda symbol+constant,x`: no pointer is computed. (A negative index would carry into the bank,
  because X adds as an unsigned number.) X is remembered like A and Y.
- **Multiplication by a constant** in 8 and 16 bits stays a `mul` in MIR, where the value ranges
  of the product are exact (after pass I turned `j * 23` into `(j << 5) - (j << 3) - j`, the
  intervals lost the connection between the three parts and could not show the index is never
  negative). The code generator multiplies in A, Horner's way over the non-adjacent form: shift
  per digit, add or subtract the factor where a digit is ±1. Pass I keeps the rule for i32.

| State | naive | before | indexed | and multiplication in A |
| --- | --- | --- | --- | --- |
| title | 1.94 | 0.82 | 0.80 | 0.80 |
| play | 5.17 (worst 8.58) | 2.35 | 2.29 | 2.07 (worst 3.51) |
| play, CPU partner | 6.55 (worst 12.23) | 3.12 | 3.05 | 2.79 (worst 5.33) |

## Byte loads and more results in A, 2026-11-17

- Between a byte load and a byte store the accumulator stays 8 bit (`REP`, `AND`, `SEP` that
  would follow each other are left out).
- A byte load that is not volatile reads 16 bits and masks the high byte: `lda; and #$00FF`
  instead of `sep; lda; rep; and #$00FF`. The extra byte read is in the same bank, because the
  last byte of every bank stays free; I/O registers (volatile) are read one byte exactly.
- More results stay in A: for the second operand of a commutative operation (the operands swap),
  through casts that need no code, and for the first φ copy at the end of a block.

Where the game's time goes now (the measured play ticks, without the benchmark's own code):
loads and stores of values in the direct page are 38 % of the cycles, `JMP` 5 %, comparisons and
masks 5 % each.

| State | naive | before | 8 bit between byte copies | 16-bit byte loads | more in A |
| --- | --- | --- | --- | --- | --- |
| play | 5.17 (worst 8.58) | 2.07 | 2.05 | 1.98 | 1.95 (worst 3.31) |
| play, CPU partner | 6.55 (worst 12.23) | 2.79 | 2.77 | 2.69 | 2.66 (worst 5.12) |

Then short branches straight to blocks within reach (each function is generated twice, the first
pass gives the distances) and two small peepholes (a mask right after a wider one, the bank word
of a pointer that stays in place): play 1.93 frames per tick (worst 3.29), with the CPU partner
2.64 (worst 5.09).

## φs written before a branch, comparisons in canonical form, 2026-11-17

- A φ after an `if` without `else` (or any join reached by a branch) used to go through its
  "incoming" place, because one predecessor ends with a branch rather than a jump. Writing the φ
  directly before that branch is correct when the φ is not live into the branch's other target
  (`FrameLayout` now computes liveness first and decides then); the write also must not clobber
  what the branch itself reads, so it interferes with that too.
- `Simplify` writes a comparison with a constant as `x < c` or `x >= c`, constant on the right:
  `c < x` becomes `x > c`, `x > c` becomes `x >= c + 1`, `x <= c` becomes `x < c + 1`. The code
  generator compares with an immediate operand directly instead of preparing the constant.

| State | naive | before | both |
| --- | --- | --- | --- |
| title | 1.94 | 0.77 | 0.70 |
| play | 5.17 (worst 8.58) | 1.93 (worst 3.29) | 1.76 (worst 3.00) |
| play, CPU partner | 6.55 (worst 12.23) | 2.64 (worst 5.09) | 2.45 (worst 4.71) |
| `level_solid_px`, per call | 3,849 | 1,525 | 1,463 clocks |
| benchmark loop alone, per pass | 1,068 | 1,252 | 801 clocks |

Then the value ranges also choose cheaper operations: `ashr`, `sdiv` and `srem` of values that
are never negative become `lshr`, `udiv` and `urem`, and a signed comparison of two such values
becomes unsigned (no sign bias); a 16-bit test for `< 0` or `>= 0` is the sign flag of the load
(`bmi`/`bpl`). Play 1.74 frames per tick (worst 2.98), with the CPU partner 2.43 (worst 4.68);
`level_solid_px` 1,230 clocks per call (816-tcc: 2,449; Calypsi: 931).

Jump threading over constant φs (`Simplify`): a block that only branches on a φ (directly, or
on a comparison of it with a constant) is skipped by every predecessor that gives the φ a
constant, because that predecessor already knows the way. This is the shape `a || b` and an
inlined function returning a truth value leave behind. Play 1.67 frames per tick (worst 2.87),
with the CPU partner 2.36 (worst 4.62).

## Fewer stores: scheduling and more hand-overs in A, 2026-11-17

On the 65816 the direct page is the register file: arithmetic works between A and memory, X and
Y hold indices. "Register allocation" here means storing and loading less. Counting the result
stores that still ran (play, game code): 31 % were values with several users, 21 % values used
by a φ, 14 % values going into the next comparison, 14 % offsets going into the next `ptradd`,
8 % values going into the next cast.

- `Schedule` (the last MIR pass) moves an instruction whose value one later instruction of the
  same block uses right before that user (loads not past stores, copies, calls or volatile
  accesses).
- A comparison with 0 of a value that is in A (and was perhaps never stored) uses `cmp #0`
  instead of loading it for the flags; comparisons may now take their operand from A.
- A `ptradd` whose 16-bit offset is in A adds the pointer to it, unless it is an indexed array
  access, whose loads read the offset into X from its place (`exec/indexed_pointer.c` tests that).

Play 1.67 → 1.61 frames per tick (worst 2.80), with the CPU partner 2.36 → 2.30 (worst 4.52).

Loop rotation in the layout: a small loop head (φs, comparison, branch) is placed after the block
that jumps back to it, so a round of the loop costs one branch instead of a jump and a branch.
About 0.5 % (the benchmark's bare loop: 801 → 783 clocks per pass).

## Induction variables, 2026-11-17

`dampf.mir.Induction`: in a loop whose counter grows by a constant per round, a product of the
counter and a constant (the offset of `array[i]` in an array of structs, `i * 23` for the
bubbles) becomes a variable of its own that grows by the constant times the step: an addition per
round instead of a multiplication. Exact modulo 2^16; an 8-bit counter must not wrap around on
the way back into the loop, which the value ranges show. The new φ carries the range of the
product in the loop head (the counter's value after the last round included), because indexed
addressing needs to know it is never negative; the first version took the range inside the loop
body, and the tests' range check caught it at once.

Play 1.61 → 1.57 frames per tick (worst 2.74), with the CPU partner 2.29 → 2.26 (worst 4.50).

## Lazy byte masks, 2026-11-17

An 8-bit value used to be masked to zero-extended form where it was computed. Now it may carry
any high byte unless one of its readers needs it clean: comparisons, extensions (which have no
code; their readers read the place as 16 bits), right shifts and divisions, shift counts, calls,
returns and switches. Parameters and results stay clean by convention. A φ that must be clean
passes that on to its operands. Some values are clean anyway (constants, call results, an AND
with a clean operand, a sum the value ranges keep below 256). Only the rest get an
`and #$00FF`.

The gain is small: play 1.57 → 1.56 frames per tick (0.3 %). Most of the game's bytes are loaded
to be compared or to index an array, and both need a clean value; `and #imm` still runs 826,000
times in the measured play ticks (883,000 before, some of them real ANDs of the C code). The
tests found one hole at once: AND, OR and XOR never masked, because clean operands used to give a
clean result.

## Pointer compression, 2026-11-17

Two passes turn pointers into 16-bit offsets into one global object (or frame slot), so loads
and stores through them index the object with X, and the 4-byte pointers, their copies and
additions go:

- `PointerOffsets`, within a function: a pointer φ whose values all point into the same object
  (`o = oamMemory; ... o += 4` in `build_sprites`) becomes the object plus an offset φ.
- `PointerParams`, across calls: a pointer parameter that every call fills with a pointer into the
  same global becomes an offset; the callee adds the object back at its start.

Both rely on C's rule that a pointer stays within its object or just past its end, which dampf
already relies on for 16-bit pointer offsets: the offset is then 0 to the object's size, and the
new φs and parameters carry that range for indexed addressing. The tests' range check doubles as a
check of the rule: in the first version of `exec/pointer_offsets.c`, a loop of mine stepped a
pointer two bytes past the end of its array, and the check reported it.

Indirect accesses (`[dp],y`) in the measured play ticks: `build_sprites` 148,000 → 53,000,
`player_update` 56,000 → 21,000. Play 1.56 → 1.54 frames per tick (worst 2.68), with the CPU
partner 2.26 → 2.24 (worst 4.47). What is left are pointers into several objects (a `Bubble *`
that may point into either of two arrays) and pointers loaded from memory; those would need a
points-to analysis over the whole program, and the remaining indirect accesses do not justify it
now.

## Loop counters in X, 2026-11-17

A φ of a loop head can live in X for the whole loop when X serves it well and nothing else needs
X there: it is compared only with constants (`CPX`), indexes the loop's array accesses (no `LDX`),
and is stepped by a constant on the way back (`INX`, `DEX`, or `TXA`/`ADC`/`TAX`). Excluded are
loops with calls, the runtime's multiplication or division, variable shifts, 32-bit sign
extension, or an array access with another index; the value must not be used after the loop or
after the step, and an 8-bit counter must not wrap. Nested loops: the innermost gets X. A loop is
also skipped if it is entered from a block where X belongs to another loop: with two loops in a
row, the first loop's test block enters the second, and loading the second loop's start value
there broke the first loop's test (the benchmark hung and ran into its frame limit;
`exec/x_counters.c` now covers it). As a guard, the code generator stops with an error if any
code tries to read the value's place or to change X inside such a loop.

The gain is small, as expected: the game's hot loops (`separate`, `build_sprites`) index two
arrays with different values, and only one fits in X. Play 1.54 → 1.53 frames per tick (worst
2.67), with the CPU partner 2.24 → 2.22 (worst 4.46).

## Where the time goes, by instruction (2026-11-17)

`dampf profile ... --instructions` (for the benchmark's play ticks: `INSTRUCTIONS=1
dev/profile_game.sh bench`) adds up the cycles per instruction and addressing mode. In the game code at 1.50 frames per tick: `sta dp` 18.8 %, `lda dp` 16.5 %,
`cmp #imm` 7.4 %, `lda abs,x` 7.2 %, `jmp abs` 5.6 %, `ldx dp` 4.8 %, `and #imm` 4.7 %,
`lda #imm` 3.8 %, `adc #imm` 3.5 %, `bne` 3.2 %, `clc` 3.1 %. Values going to and from the direct
page are still a third of the time; a `jmp` runs almost a million times in the 800 ticks, mostly
where a branch cannot reach its target because a large loop body lies between them.

## A remembers every place it equals, byte loads for 8.8 values, 2026-11-17

The code generator knew one place A held. After `LDA x` / `STA y` it knew only `y` and loaded
`x` again; now it keeps every place A equals (a store adds its place, a write to a place drops it
and its byte neighbours): 409 such reloads in the game's code. Play 537,567 → 533,388 clocks
per tick.

The integer part of an 8.8 value (`PX(b->x)`, `b->x >> 8`) was a 16-bit load, `XBA` and a mask.
`ByteLoads` (catalogue E) makes a 16-bit load whose only use shifts it right by 8 or more a byte
load of the high byte, widened again: `LDA _g_bubbles+20,X` and the mask. Volatile loads stay as
they are. Play 533,388 → 529,880 clocks per tick (1.48 frames).

## Short branches where they reach, 2026-11-17

A conditional branch whose target is not the next block either reaches it (a short branch) or
skips a `JMP` to it. 1,505 of the 3,715 `JMP`s in the game's code were such long forms, run
about 684,000 times in the 800 play ticks of the profile, 5 cycles each where a taken branch
costs 3. Three causes, all fixed:

- an equality test of a 16-bit value made a label for "the first word differs" that only 32-bit
  values need, and with it the code generator never took the short form (525,171 clocks per
  tick, from 529,803);
- the distances came from one first pass that wrote every branch long; now the passes repeat
  until no further branch reaches. Each pass is at most as long as the one before everywhere,
  so a branch that reached keeps reaching;
- a jump that reaches its block is `BRA`, the same 3 cycles as `JMP` and one byte shorter, so
  more branches reach (524,771).

The long forms left mostly jump over large loop bodies, such as the pair loop of `separate`,
whose rarely run parts (bubbles that touch) lie between its test and its start. Placing such
blocks at the end of the function needs to know which blocks are rare: profile-guided layout
from the replays (M4).

## INC and DEC for counter steps, 2026-11-17

A step `x + 1` or `x - 1` whose result goes back to `x`'s place (a loop counter, `++counter`)
was `CLC`, `LDA`, `ADC #1`, `STA`: 13 cycles. Now it is `INC` or `DEC` on the place: 7 cycles,
the same 16 bits as `ADC #1` (an i8 minus 1 differs in the high byte only, which an i8 that needs
no mask may have any). It needs `x` really in its place: a value handed on in A has a place of
that name but was never stored there, which the execution tests caught (`locals.c`). Play
524,771 → 510,797 clocks per tick (1.43 frames), title 0.60 → 0.58.

An i8 step whose result must be clean (a counter a comparison reads) still needed `AND #$00FF`.
If the old value is clean too (a parameter, or a φ of clean values), an 8-bit `INC`/`DEC`
between `SEP` and `REP` keeps the high byte 0: 11 cycles instead of 16. Play 496,395 → 494,619
clocks per tick (1.38 frames).

## STZ for zeros, 2026-11-17

A zero stored to a fixed address (direct page, absolute, X-indexed; not long or through a
pointer) is `STZ` instead of `LDA #0` and `STA`, and so is a zero a φ gets (a counter's start,
unless A holds 0 already). Play 510,797 → 510,007 for the stores, → 503,373 clocks per tick with
the φs (1.41 frames).

## A bit chosen by a bit, 2026-11-17

The sprite code builds attribute bytes as `((attr & SPR_ATTR_FLIP_Y) ? 0x80u : 0u) | ...`: per
bit a branch and a φ of two constants. When the test is one bit (`x & M`, M a power of two) and
the φ chooses between one bit K and 0, the φ is the masked bit itself, shifted from M's place to
K's (`BitSelect`; `(x & M) ? 0 : K` is the same with the bit inverted). The branch then has no
reason left and goes. Play 503,373 → 496,395 clocks per tick (1.39 frames).

## Blocks laid out by a profile, 2026-11-17

The jumps over large loop bodies (the section on short branches) need to know which blocks run
rarely. A profile knows: `dampf profile ... --blocks file` writes how often each block ran (the
block labels are in the label file), and `dampf build --block-counts file` lays the blocks out
by it: the successor that ran more often follows, and blocks that never ran go to the end of the
function. The code that runs stays together, its branches reach, and `JMP` drops out of the top
instructions (it was 5.6 % of the cycles). `dev/profile_game.sh` writes
`build/profile/<what>.blocks.txt`; `BLOCKS=<file>` passes it to `dev/bench_game.sh` and
`dev/profile_game.sh`.

Only the order of the blocks follows the counts, so counts of other code make the code slower,
never wrong; the execution tests build every program once more with arbitrary counts (a third of
the blocks "never ran") and compare it with gcc. Play 494,619 → 488,323 clocks per tick (1.37
frames) with counts from the same ticks, 488,877 with counts from other ticks (2000–3610): the
gain does not depend on profiling exactly what is measured. Without counts nothing changes; the
numbers in this document are measured without them unless they say so.

## Fewer copies: arguments in place, loop exits, 2026-11-17

A third of the time goes to direct page loads and stores. In the game code (the benchmark's harness
left out) about 14 % of the stores are pure copies (`LDA` of one place, `STA` to another), about
14 % store a constant, the rest store computed values that are read again later: with one
accumulator, a value that is not used right away has to go to memory. Two kinds of copy go:

- An argument computed for a call was stored to its own place and copied to the callee's
  parameter at the call. If the call is its only use, later in its block, with no other call
  on the way, it is computed straight into the parameter's place (frames on a call path never
  overlap). Play 494,619 → 492,510 clocks per tick. Later also when its other uses all lie
  between its definition and the call (the callee may change its parameter, so nothing may read
  the place after the call): the whole benchmark 431,343 → 431,280, almost nothing, since the
  hot copies left are φs or values needed after the call.
- A block's φ values are written before the branch of a predecessor that branches, which saves a
  jump; on a loop's exit edge that meant writing them in every round for an edge taken once.
  Such an edge now gets a block of its own just before code generation (`ExitEdges`), and the φ
  values are written there. Play 492,510 → 488,759 clocks per tick (1.37 frames).

## What A, X and Y hold across blocks; a second index in Y, 2026-11-17

How much could knowing the registers save? Counted in the emulator over the whole benchmark,
41 % of the `LDX dp` load the value X holds already, and 28 % of the `LDA dp` the value A holds
(counted by value, so some are coincidences). The code generator forgot A, X and Y at every
label. Now it learns what each holds where a block starts: what every way into the block brings
(the branches and jumps to it, and the code that falls through). The passes that already find
the short branches carry it: a pass assumes what the pass before found. The code that is kept
must be consistent, every assumption holding on every way in, and every short branch must still
reach; otherwise the function is written again without assumptions (8 of the game's functions at first; none once the passes keep the meet after a contradiction, see below).
A meet that takes only the first way in, on purpose, fails five execution tests. Play 487,041 →
478,185 clocks per tick (1.34 frames).

Before that, an indexed access uses Y when X holds another index (two bubbles of a pair, one in
X, one in Y), for the instructions that have an absolute,Y form (`LDA`, `STA` and the
arithmetic, not `INC`, `STZ` or direct page): play 488,759 → 487,041.

Keeping the pointer in `dampf_ptr` across blocks as well (forgotten where its definition runs
again) changed nothing measurable: pointer compression left almost no pointer accesses in the
hot code. It was not kept.

## Loop-invariant code, 2026-11-17

An instruction whose operands are all defined outside a loop gives the same value in every
round; `Licm` moves it to the block before the loop (catalogue F). Only cheap instructions
without side effects move (arithmetic without multiplication and division, which call the
runtime; casts; pointer additions; comparisons stay with their branches), and only from blocks
every round passes, so no work moves out of a branch that rarely runs. Title 199,811 → 193,463
clocks per tick, round intro 637,713 → 630,469, play 478,185 → 478,018.

An index used in a loop where it does not change, while the loop also indexes with something
else, now prefers Y wherever it is used: it stays in Y across the loop's rounds, and X is left
to the index that changes. Play 478,018 → 474,124 clocks per tick (1.33 frames). In the pair loop
of `separate` it does not help yet: the rare path for bubbles that touch uses Y for something
else, so Y is not known at the loop's head; that path would have to reload Y before it goes
back.

That was not the cause after all: Y only ever holds bubble a there. The labels inside a block
(where a branch skips a `JMP`, for example) still forgot everything. They are reached by
branches written earlier in the same pass, so what holds there is known exactly: what those
branches and the code falling through bring. The code generator's own small loops (shifts by a
variable count, copies) are the exception, a branch comes back to their label, so nothing is
assumed there; and a branch back to any other label already placed must bring what was assumed
there, or the pass counts as inconsistent (a safety net: the code generator writes no such
branch today). After a contradiction the passes now keep only what the assumption and what
arrived agree on and go on, instead of writing the function without assumptions at once; the
assumptions only shrink then, so the passes end. Play 474,124 → 468,441 clocks per tick (1.31
frames), title 193,463 → 192,815.

The pair loop of `separate` still loads bubble a into Y in every round. The analysis starts
pessimistic: in the first pass nothing is assumed at the loop's head, so the way back brings
nothing, and the meet keeps it so. An optimistic start was tried (a block first assumes what
its ways in that are not the way back of a loop bring, and shrinks after a contradiction, with
up to six optimistic passes): it cut the `LDY`s of `separate` from 13 to 2, but not the one in
the hot block, because the head's state reaches the end of the body only one block per pass, and
the game as a whole got no faster (468,257 clocks per tick). It was not kept.

## Counting down, tried, 2026-11-17

A counter used for nothing but its own test (`for (i = 0; i < n; i++)` once the induction
variables have taken over its other uses; 210 such loops in the game's MIR, copies by inlining
included) can count down instead: j = n - i, the test `j != 0`, the step a `DEC`, no comparison
with n. It is exact (0 <= i <= n at the head), and it made the title screen faster (192,815 →
191,346 clocks per tick), but play slower (468,441 → 469,298) and the whole benchmark too
(431,343 → 431,849): copying n into j at every loop entry costs more than the comparisons it
saves in the game's short loops, and an i8 j would need a clean high byte for `j != 0`. It was
not kept.

## Registers at constant addresses, 2026-11-17

A write to a hardware register, `REG8(0x210B + i) = v` in the platform layer, went through
`dampf_ptr` like any pointer: the 24-bit address built in the direct page, then
`STA [dampf_ptr],Y`, 33 cycles. A pointer made from a constant (`inttoptr` of a constant in MIR)
is now a fixed address like a variable's: `STA f:$00210B`, 15 cycles with the switch to 8 bits.
With a constant plus a 16-bit index whose value range is never negative and stays in the bank
(below $8000 with C's sign extension of `int`), it is `STA f:$00210B,X`, and the sum is never
computed. Long addressing, because the data bank is $7E: an absolute `$210B` would reach WRAM.
The benchmark does not change (its platform functions are empty); the playable ROM's register
accesses do. `ConstAddressTest` runs such accesses on WRAM in dampf's emulator (a program
writing to fixed addresses cannot run on the PC).

Several register accesses on consecutive lines also stay in 8-bit mode now: the held `REP` is no
longer written at each new C line, so the `SEP` of the next line drops it. The I/O example of the
book (three register accesses) takes 24 cycles instead of 36, the whole benchmark 431,280 →
430,851 clocks per tick.

## FastROM, 2026-11-17

All of dampf's code and constant data lie in banks $80 and up, which the SNES can run at
3.58 MHz instead of 2.68 MHz once `MEMSEL` ($420D) says so; the ROM chip must be fast enough
(FastROM; flash cartridges and emulators are). The start-up now switches it on and the header
says FastROM ($30); a ROM access takes 6 master cycles instead of 8, internal cycles and WRAM do
not change. `dampf build --slowrom` keeps SlowROM. Play 468,119 → 391,423 clocks per tick (1.31 →
1.10 frames), the whole benchmark 430,851 → 364,774. Mesen measures the same: identical checksums
on all 3,610 ticks, master clocks within 40 per tick of dampf's emulator. A platform choice more than an
optimization: Calypsi with FastROM (the game's `BENCH_FASTROM`) gains as much, 1.67 → 1.42 frames
per tick in the comparable play ticks against dampf's 1.14 → 0.95, so dampf stays 1.5 times as
fast.

## No CMP #0 where the flags are set, 2026-11-17

In 800 play ticks 1.3 million `CMP #imm` ran. Most compare with a constant other than 0 and feed
`BCC`/`BCS`; about 150,000 compared with 0 right after an instruction that had set N and Z for
the value in A already: an `AND` (110,000), a load, an addition before a store. The code generator
now follows whether N and Z describe the value in A: the instructions that load or compute A set
that, stores, `REP #$20`, `CLC`/`SEC`, jumps and branches keep it, anything else (`LDX`, `INX`,
`CMP`, `BIT`, a read-modify-write on memory, a call, `SEP`, a label) clears it. A test for 0 or
for the sign of a value that A holds then needs no `CMP #0` while it is set. Play 391,423 →
386,306 clocks per tick (1.08 frames). Counting every instruction as setting them, on purpose,
fails `exec/flags_after.c` (a value tested after an index load, a store, a call, a comparison)
and two random programs.

## Values not stored, 2026-11-17

The first step of register allocation (row 2 of the plan in [roadmap.md](roadmap.md)): a 16-bit
value that every use takes from A is not stored to its place in the direct page. Measured before,
a quarter of the direct-page stores (2.05 of 7.85 million in 800 play ticks) were overwritten
before anything read them.

The code generator learns it over its passes, as it learns what the registers hold (*What A, X
and Y hold across blocks*). In each pass it notes every value it stored and every value whose
place an instruction read; a value stored but never read from its place is not stored in the
next pass. If that pass reads it after all (A changed in between, so the use loads it), the pass
is thrown away and the value is stored for good. Which value a read is for: every read of a place
follows the request for the operand of the value read there, so the place is mapped to the value
it was last handed out for. Two things count as a read without an instruction: a φ whose place
the value shares (no copy at the end of the block, but the φ reads the place), and an argument
computed in its parameter's place.

The first version mapped a place to every value that ever had it. Values that share a place one
after another then blocked each other, and only 57 of 7,105 stores went (play −0.07 %). With the
place mapped to the last value: 409 stores fewer in the game's code, dead stores over the whole
benchmark run 2.15 → 1.66 million, play 386,306 → 379,855 clocks per tick (1.06 frames).
`exec/unstored.c` has the cases where a read is easy to miss; counting no reads at all fails it
and 19 other programs of `ExecutionTest`, leaving out the φ's read fails it and 11 others.

Among the dead stores left, the most frequent were in `items_update`: its `switch` on a
`uint8_t` compared an `int`, so the high word was zeroed (six times, in the same place) and
compared with 0 for every case. That was a matter of the switch's width (next section).

## Switches on bytes compare 16 bits, 2026-11-17

C widens the value of a `switch` to `int`, so `switch (it->kind)` on a `uint8_t` compared two
words per case: the low word, then the high word, which is known to be 0 (and was zeroed six
times in a row, for every case's comparison). `Simplify` now switches on the 16-bit widening
instead (or on a `uint16_t` itself) when every case lies in the range of the narrow value: 0 to
255 for a `uint8_t`, -128 to 127 for an `int8_t`. A case outside it keeps the switch at 32
bits, since a case such as 65,545 would equal 9 in 16 bits (`exec/switch_narrow.c`; narrowing
without the check fails it). Play 379,855 → 370,620 clocks per tick (1.04 frames).

## Returns copied into each path, 2026-11-17

Inlining leaves a shape behind that `ground_px` shows well: it returns the value of the inlined
`level_solid_px`, which returns the value of the inlined `level_solid_tile`, and each returns
from several places. In MIR that is a chain of φs ending in a block that only returns: every
constant was written to the φ's place before a branch, copied from one φ to the next, and loaded
again before `RTL`. Just before code generation (after `ExitEdges`), `Returns` copies a block
that holds nothing but φs and a `ret` into each predecessor: a predecessor that jumps there
returns itself, a branch or a switch gets a block of its own for the edge. Each path then
returns its own value (`LDA #1`, `RTL`), and the φs are gone. Earlier, the passes would lose the
φs they work on (`BitSelect` chooses between the constants of such a φ). Play 370,620 → 364,829
clocks per tick (1.02 frames). `ReturnsTest` covers a jump, a branch and a switch with two cases
to the same return; `exec/returns.c` fails if a path returns another path's value.

## A pointer plus an index: the index in Y, 2026-11-17

`cells[((uint16_t)ty << 5) + (uint16_t)tx]` with `cells` a pointer variable computed the sum
of pointer and index (`CLC`, `LDA`, `ADC`, `STA`, `LDA`, `STA`: 22 cycles) and then read with
`LDY #0`, `LDA [dp],y`. `[dp],y` adds Y to the 24-bit pointer, so the index can go into Y
directly: `LDY index`, `LDA [dp],y`. `FrameLayout` folds a pointer plus a 16-bit index the
value ranges show to be never negative (Y adds as an unsigned number and carries into the bank;
a nonnegative index stays inside the object, which never crosses a bank), when the sum is only
the address of loads and stores. A 32-bit access steps Y with `INY INY` for the second word. An
index plus a constant (a field of `p[i]`) still computes the sum, since Y has no room for the
constant. Play 364,829 → 359,710 clocks per tick (1.01 frames); `level_solid_px` 951 → 871
clocks per call. `exec/pointer_index.c`: allowing a negative index fails it, and so does
stepping Y by one for the second word.

The pointer may itself be a sum, such as a row of a 2D array (`gfx_sheet_slot[sheet]` in
`build_sprites`): a sum used as the base of another sum is never folded into its accesses, so it
has a place of its own for `[dp],y`. That took play 329,834 → 326,596 clocks per tick and the
title 158,966 → 139,475.

An index that is an induction variable (an offset that grows by the element size every round)
is not folded yet: the range analysis gives it the type's whole range, since it cannot see that
the loop ends before the offset wraps.

## Loop counters in X next to an index in Y, 2026-11-17

A loop whose array accesses use a second index was left out of X entirely: such an access
would load X. In the pair loop of `separate` (bubble b's offset changes, bubble a's does not),
a's index goes into Y anyway (*What A, X and Y hold across blocks*), and every instruction that
indexes memory there has an absolute,Y form (`LDA`, `STA`). Only `STZ` (a store of 0) and
accesses in the direct page or at a long address have none; such an access still keeps X out.
With that, b's offset lives in X: `TXA`, `ADC #23`, `TAX` instead of `CLC`, `LDA`, `ADC`, `STA`
and an `LDX` in every round. The code generator throws if anything changes X in a loop whose
counter lives there, so a mistake here would fail the build instead of the program. Play
359,710 → 356,238 clocks per tick.

## The loop test on the offset, 2026-11-17

After `Induction`, a loop over an array of structs has two counters: `i` and the offset
`i * size`. Often `i` is only compared with the bound and stepped. `Induction` then moves the
test to the offset, `i < N` becoming `offset < N * size`, and `i` goes (`DeadCode` removes φs
that only feed each other). The offset is recognised by its form, whichever round made it: a
16-bit φ of the same loop head that starts at `zext(start) * F` and grows by `step * F`. Exact
for an 8-bit counter that stays in its byte, with `0 < F` and `F * 255 <= 0xFFFF`: the offset
never wraps, so the order is the same; for unsigned comparisons and equality only. The pair loop
of `separate` is now `CPX #$0170`, `BCC`, its body, and `TXA`/`ADC #23`/`TAX`. Most loops over
the game's arrays of structs have this shape: play 356,238 → 329,834 clocks per tick (0.92
frames, −7.4 %). `exec/loop_test_moved.c` has element sizes at the limit (256, 257), beyond it
(258), one where the offset would pass 65,536 inside the loop (allowing it fails the test), and
loops whose test must stay; a bound one element short fails it and others.

A counter that counts down (`i--`, a subtraction in MIR) gets no induction variable yet, and the
value ranges treat an 8-bit counter above 127 as negative, so such a loop keeps its
multiplication.

## Summary of the code from MIR so far (2026-11-17, the latest measurement)

Measured after the range analysis learned to narrow after widening: a loop of more than 24
rounds (`for (i = 0; i < 768; i++)`) used to give its counter the type's whole range, which kept
it from being narrowed and from indexed addressing as nonnegative. Narrowing again after the
widened fixpoint gives it 0..768. Play 1.53 → 1.50, title 0.68 → 0.60. Then A's places and the
byte loads, the short branches, INC/DEC, STZ, the bit selections, fewer copies and the registers
across blocks (sections above): play 1.31. The last column adds FastROM, the CMP #0 that went, the
values not stored, the narrow switches, the copied returns, the index through a pointer in Y (rows
of 2D arrays included), the loop counters in X next to an index in Y and the loop tests on the
offset (see their sections); the column before it is SlowROM speed, as the earlier columns were measured.

| State | naive | from MIR, no passes | SlowROM | now (FastROM) |
| --- | --- | --- | --- | --- |
| title | 1.94 | 12.21 | 0.54 | 0.39 |
| story | 0.70 | 3.56 | 0.22 | 0.17 |
| round intro | 5.76 | 12.13 | 1.76 | 1.39 |
| play | 5.17 (worst 8.58) | 18.93 | 1.31 (worst 2.25) | 0.91 (worst 1.60) |
| play, CPU partner | 6.55 (worst 12.23) | 23.13 | 1.98 (worst 4.08) | 1.48 (worst 3.18) |
| `level_solid_px`, per call | 3,849 | 6,648 | 1,149 | 871 clocks |

Calypsi's 2.72 frames per tick during play (on an older version of the game) is passed. On the
same version (the snapshot, `dev/bench_compilers.sh`, measured in dampf's emulator, which
matches Mesen on Calypsi's ROM), in the play ticks where both play the same scene (711-812),
Calypsi took 1.67 frames per tick and dampf 1.14 (both at SlowROM speed), 1.47 times as fast;
with FastROM for both and the steps since, Calypsi takes 1.42 and dampf 0.79, 1.81 times as fast.
Calypsi's results drift
from the PC's from tick 596 on, so later ticks compare other scenes
([history.md](history.md#answered-questions)). The hand-written `level_solid_px` takes
856 clocks (at SlowROM speed), so for that function dampf is now 1.34 times hand-written assembly
(1,149 clocks at the same speed).


## The back end on LIR, 2026-11-18

The steps of [archive/transition-to-lir.md](archive/transition-to-lir.md) that change the generated code, each
measured on the game's benchmark in dampf's emulator (FastROM), in master clocks per tick. Before
them: play 326,596 (0.91 frames), play with the CPU partner 528,446, all ticks 316,986.

| Step | Play | Play, CPU partner | All | What changed |
| --- | --- | --- | --- | --- |
| B2: branch relaxation on LIR | 326,592 | 528,440 | 316,983 | A few long branches that reach now get the short form; the passes before stopped early in those places. |
| B4: dead stores as a LIR pass | 325,572 | 527,421 | 316,359 | Stores to a function's own value places that no path reads afterwards go (byte-wise liveness over the blocks), on top of the stores the passes already left out. No function of the game or the tests got slower. |
| B3b: redundant loads as a LIR pass | 324,897 | 527,103 | 316,042 | Loads of what A, X or Y holds on every way in go (an `LDA` whose N and Z a branch reads becomes `CMP #0`), found by the data flow analysis over the code the passes keep; the passes assume less than it finds at loop heads and labels inside blocks. 50 functions of the tests and the game faster, none slower. |
| B3c: each function written once | 325,209 | 527,410 | 316,248 | Selection runs once per function and assumes nothing where a block starts; the redundant loads (with `LDA x / ADC y` where A holds y becoming `ADC x`), the dead stores and the branch forms come from the LIR passes. Before, `generate()` wrote each function up to 14 times. **A regression of 312 clocks per tick in play (0.1 %)**, see below. |
| `IndexInY`: Y for an index it holds | 324,844 | 527,096 | 316,002 | `LDX p / LDA table,X` becomes `LDA table,Y` where Y holds p on every way in, nothing reads X afterwards and N and Z of the `LDX` are not read. Play is now 53 clocks per tick faster than before B3c. Two of the 8 functions that B3c made slower are faster again; the others choose X for a new index where X held another one, which a pass after selection would have to rename across several instructions. |

**The regression of B3c.** Of the game's functions, 18 got faster and 8 slower (`_alloc_bubble`,
`_bubble_shoot`, `_bubble_spawn_special`, `_build_sprites`, `_emit_bubbles`, `_shoot`,
`_update_rescue`, `_video_frame`; their worst cases by 0.1 to 2 %). All 8 lose for the same
reason: an indexed access chooses X or Y while it is written, and the code that is written once
does not know that Y already holds the index where the block starts, so it loads X instead, and
a pass after selection cannot exchange the registers without knowing where X is read later.
Taken anyway: writing each function once removes the repeated writing, the consistency checks
and the second attempt from the code generator, which is what B3 is for. The next step, `IndexInY`, wins it back and more.

## Optimizations on the branch `optimise`, 2026-11-18

One step at a time, each measured on the game's benchmark in dampf's emulator (FastROM), in
master clocks per tick. Before them: play 324,844, play with the CPU partner 527,096, all ticks
316,002 (the end of *The back end on LIR*).

| Step | Play | Play, CPU partner | All | What changed |
| --- | --- | --- | --- | --- |
| `RangeChecks`: neighbouring constants as one range check | 324,256 | 526,568 | 315,705 | `x == 2 \|\| x == 3`, as a chain of two blocks in MIR, becomes `(x - 2) <u 2` (as LLVM's InstCombine does): one load, subtraction and compare instead of a second block with its own load, compare and often a long jump. The game has it in `floating()` (bubble state float or trapped), which `separate` asks for every pair of bubbles; 13 chains merged. 5 functions faster; 3 have a best case 2 to 4 cycles slower (the way where the first test matched was shorter), their worst cases are faster. |
| φ ranges with the conditions of their edges | 322,222 | 525,415 | 314,743 | `Ranges` evaluated a φ's operand in the predecessor block, without the condition of the edge itself: in `x < 0 ? -x : x` the operand x comes along the edge where `x < 0` is false, so it is not negative there. With it, `abs16` of a difference of two bytes is 0..255, and the comparisons with it become unsigned (no `EOR #$8000`; 184 of them in the game before, 160 after). 30 functions faster, none slower. |
| Branch relaxation everywhere, switches too | 321,043 | 524,276 | 313,979 | `Bc @s / JMP T / @s:` becomes `B!c T` wherever `@s` has no other use, not only at the end of a block (B2 had kept the old code generator's limit), and a `switch` writes its jumps relaxable: a matching case is now a taken `BEQ` (3 cycles) instead of `BNE` not taken and `JMP` (5). Where both rules apply, the branch to a jump comes first, as before. 102 functions faster, 2 slower in their worst case (by 16 and 98 cycles). |
| Peephole: `STA p / LDX p` as `STA p / TAX` | 318,591 | 522,294 | 312,186 | A value stored only to load it into X or Y right away (an index from a byte load) goes over with `TAX`/`TAY` (2 cycles instead of 3 or 4); the store, where nothing reads it afterwards, then goes too (`DeadStores`). The first rule of the new `Peephole` pass on LIR. 75 functions faster, none slower. |
| Peephole: shifts, `REP`/`SEP`, high bytes | 316,639 | 520,629 | 310,802 | Three more rules: `LSR A` × k then `ASL A` × m (m > k) as `AND` with a mask and m - k shifts (`(slot >> 3) << 5` in `build_sprites`: 7 cycles instead of 16); `REP #$20 / SEP #$20` where A was 8 bit before goes; `LDA x / XBA / AND #$00FF` as `LDA x+1 / AND #$00FF`. Rules may span C lines (a `REP` ending one line and the `SEP` starting the next). 69 functions faster; one 4 cycles slower in its worst case and 6 faster in its best. |
| `KnownCarry`: no `CLC` where the carry is clear | 315,047 | 518,756 | 309,354 | A data flow analysis of the carry over the blocks: a branch on the carry tells it on both ways, `CLC`/`SEC` set it, arithmetic, compares, shifts and calls make it unknown. A `CLC` where it is clear already goes (typically `CMP #n / BCC body`, then `CLC / ADC` in the body), likewise a `SEC` where it is set. 313 of the game's 1,488 `CLC` go; 201 functions faster, none slower. |
| `PointerRemat`: pointer sums computed where their value is needed | 309,008 | 512,257 | 305,381 | `Item *it = &g_items[i]` in a loop: its loads and stores are addressed with the index in X or Y, the sum is never computed. But one call with `&it->x` on a rare way (`level_fall` for thrown items) made the code generator compute `it` and `&it->x` in full, bank word included, in every round. Now each use of a pointer sum's value gets its own copy of the chain just before it (never in a loop the original is not in, never for a φ), and the original goes when nothing else uses it. 27 functions faster, 7 slower in their worst case by up to 20 cycles (cold code: high scores, name entry). |
| Range checks of aligned blocks as a mask | 306,298 | 509,646 | 303,719 | Where the values of a range check are an aligned block of a power of two, `RangeChecks` writes `(x & ~(n - 1)) == lo` instead of `(x - lo) <u n`: `floating()` is `(state & $FE) == 2`, and the mask `#$00FE` also clears the high byte of the 16-bit load, so `SEC`, `SBC` and the byte mask go (5 cycles in the inner loop of `separate`, 49,059 times in the play ticks). 14 functions faster, none slower. |
| Functions simplified before their callers inline them | 301,962 | 504,774 | 302,053 | Inlining goes bottom-up: a function is considered for its callers after its own callees were inlined into it. Its size was then that of the unsimplified copies, so `ground_px` (29 instructions once simplified, 12,008 calls in the play ticks) counted as more than the limit of 30 and stayed a call. Now a function is simplified as soon as something was inlined into it, as LLVM's inliner does; `ground_px` is inlined at all its 58 call sites (25 fewer `JSL` in the game), and its truth value becomes the callers' branch. 17 functions faster, 5 slower (`think_hopper` best case by 84 cycles, `video_frame` worst case by 0.4 %). |
| Inlining up to 60 instructions (was 30) | 290,433 | 493,872 | 295,234 | Now that sizes are measured on simplified bodies, the limit for inlining a function with several call sites was measured again: 40 gave play 293,163, 50 gave 291,703, 60 gave 290,433 (play with the CPU partner and the whole benchmark the same from 40 on). The game's code grows from 99,925 to 113,859 bytes; the ROM has room, and the frames still fit the low RAM. |
| `ByteStretches`: short 16-bit stretches between byte stores in 8 bits | 286,947 | 490,828 | 292,825 | In `SEP / STA o,Y / REP / LDA x / SEC / SBC #1 / SEP / STA o+1,Y` the `REP` and `SEP` go and the stretch computes in 8 bits (`SBC #$01`, with `.a8`/`.a16` for the assembler): only its low byte is stored, and every instruction allowed in it gives the same low byte in 8 bits. Not with stores, right shifts, compares, `XBA`, `TAX`, calls, a carry from inside the stretch, loads of hardware registers, or where a flag or the high byte of A is read afterwards. 417 of the game's 1,576 `REP #$20` go; 111 functions faster, none slower. The first piece of row 3 (`REP`/`SEP` across code). |
| Block order: a successor only this block reaches comes next | 286,244 | 490,087 | 291,986 | Without a profile, the block order took the true side of a branch as the next block. For an `\|\|` chain that is the shared target, and the next test (a block with no other way in) went far away, behind a long jump. Now a successor with this block as its only predecessor comes first: a block with other ways in needs a jump from somewhere anyway. The function bounds move both ways (92 better, 98 worse), the benchmark gains on all three measures. |
| Block order: the way to the next round first | 284,286 | 486,715 | 290,349 | Refines the step before, as in Ball and Larus' loop heuristic: a successor that is a latch of one of the block's loops (the way to the next round, a filter loop's `continue`) comes next; then one only this block reaches; then the true side. The step before had put the rest of the loop body first in `if (!floating(b)) continue;`, so the common way out took a long jump. |
| Peephole: no `CMP #0` where N and Z are set already | 283,728 | 486,210 | 289,946 | `CMP #$0000` right after an instruction that set N and Z from A in the same width (stores between them change neither) goes where the carry it sets is not read afterwards; mostly the case `0` of a `switch` after a load. 73 functions faster, none slower. |
| Loop rotation by the loop's own latch | 282,498 | 484,490 | 289,027 | A small loop test goes right behind the block that jumps back to it, so that block falls through into the test and the test branches back into the loop. The latch was taken as "the one predecessor placed after the test"; with the new block order the loop's entry block can lie after the test too, and the inner loop of `separate` lost its rotation (a `BRA` back to the test in every round). The latch now comes from the loop analysis. |

**Outside the game.** The same steps, measured on the execution tests and 50 random programs
(master cycles of the whole program in dampf's emulator, code from MIR, `main` before the branch
against the branch's end): the execution tests 6,223,446 → 6,164,334 (-0.9 %; median -0.6 %,
best `returns.c` -11.3 %, `tables.c` -5.8 %, `control.c` -5.4 %; one slower, `pointer_offsets.c`,
+0.2 %), the random programs 24,369,308 → 24,362,084 (±0.0 %; their cycles go to the runtime's
division routines, which no step touched). The general steps help elsewhere too, much less than
the game's 13 %; see the roadmap's M5 on varied benchmarks.

**Against Calypsi and the hand-written function, measured again.** In the play ticks where both
compilers play the same scene (102 ticks, up to tick 812), Calypsi 1.42 frames per tick against
dampf 0.66 (0.79 before the branch): 2.14 times as fast instead of 1.81. `level_solid_px` 871 →
827 master clocks per call, against 724 for the hand-written one (1.14 times instead of 1.20).

**The playable ROM, measured again** (on the branch `m5`, after the console API and the main
loop moved into the dev kit's library, which changes no generated code of the game). A minute of
play in Mesen (`dev/check_game_rom.sh`): 3,098 ticks in 3,600 frames, 1.16 frames per tick, no
byte lost (before the branch `optimise`: 2,296 ticks, 1.57).

## Dead loops (2026-11-19, on the examples)

`DeadLoops` (catalogue H) removes a loop that has no effect (no store, copy, call or volatile load),
whose values nobody reads after it, and that is proved to end: one way out, on a counter compared
with a constant, stepped by the same constant every round, so that the comparison fails before
the counter could wrap (an odd step for `!=`). gcc keeps a C loop that might not end, so dampf
keeps every loop it cannot prove finite, and every loop around one. The loops it finds are those
left empty after inlining: the debug view's calls are empty functions on the SNES (ADR-6F3CK03Z),
and `for (i = 0; i < CATS; i++) sdevkit_dbg_box(...)` left a counting loop of 19 cycles in every
tick of CatchMe.

Measured with `dev/bench_examples.sh`, without the pass against with it: CatchMe 52,888 → 52,473
master cycles per tick (-0.8 %), the six other examples the same to the cycle; none of the
execution tests and random programs changed its code (`BackendOutputTest`). `DebugCallsTest`
checks that a tick with the registrations and a box loop costs the same as one without them.

## Comparisons next to their branches (2026-11-19, on the pair loops and the examples)

The pair loops' benchmark (`exec/pair_loops.c`, `PairLoopsBench`) is a case of the kit's own for
the game's finding 9: a counted pair loop or a list sorted by x, over a 23-byte struct array or
one array per field. Its baseline with 0.7.0, master cycles per frame of data: A (counter,
struct array) 271,055, B (list, struct array) 232,098, C (counter, fields) 218,615, D (list,
fields) 214,867.

In its pair function `dx < 0` was a truth value: value numbering merged the test of the inlined
`abs16` with the same test of the shove, and the one comparison then fed two branches in other
blocks, so the code generator computed 0 or 1 (`EOR #$8000 / CMP / ROL / EOR #1`), stored it and
tested it later. `SinkCompares` computes such a comparison again right before each branch that
uses it, after value numbering, so compare and branch fuse (`LDA dx / BMI`).

| | Before | After |
| --- | ---: | ---: |
| A counter, struct array | 271,055 | 239,556 (-11.6 %) |
| B sorted list, struct array | 232,098 | 214,099 (-7.8 %) |
| C counter, one array per field | 218,615 | 187,116 (-14.4 %) |
| D sorted list, one array per field | 214,867 | 196,866 (-8.4 %) |

The examples (`dev/bench_examples.sh`): Steinschlag -1.3 %, Er-Tüp -0.1 %, Schach +0.02 %, the
rest the same; geometric mean -0.2 %.

## Scaled index parameters (2026-11-20, on the pair loops and the examples)

The pair function of the benchmark is too large to inline at its two call sites, so it took the
indexes i and j as bytes and multiplied both by 23 (the struct's size) in every call, about 44
cycles a pair, although the counted loop steps that offset already. `IndexParams` makes such a
parameter the 16-bit product when the function uses it only as `zext` then `mul` by one constant
(255 times it within 16 bits), or in unsigned comparisons with a constant or another parameter
scaled alike; the callers multiply instead, and in the loop value numbering finds the stepped
offset.

| | Before | After |
| --- | ---: | ---: |
| A counter, struct array | 239,556 | 193,421 (-19.3 %) |
| B sorted list, struct array | 214,099 | 211,955 (-1.0 %) |
| C counter, one array per field | 187,116 | 187,116 |
| D sorted list, one array per field | 196,866 | 196,866 |

B gains little: its caller loads the index from the list and multiplies it there. C and D index
arrays of different sizes, so their parameters stay bytes. The examples are the same (none passes
an index into an array of structs to a function that is not inlined).

## Ranges kept when inlining (2026-11-20, on the spelling families and the examples)

`Induction` replaces `i * C` by a variable stepped by C and gives its φ the range it knows
(`Instr.Phi.known`), so the code generator indexes with X. `Inline` copied a φ without that
field, and the passes do not derive it again, so an inlined loop computed a 24-bit pointer every
round ([archive/compiler-flaws.md](archive/compiler-flaws.md#r4-inlining-drops-the-range-of-a-derived-variable),
R4, found by the spelling test). The copy keeps it now.

The spelling families (CPU cycles of one call, inlined): `fill_u16` 3,930 → 1,945, `pairs_u16`
29,523 → 21,411, `struct_u16` 2,970 → 1,881, `fields_u16` 3,610 → 1,721; 18 of 90 members
faster. `pairs_ptr` 30,417 → 31,437 (+3.4 %): its pointers are compared as 32-bit values, so the
pointer is computed every inner round anyway and the X and Y indexes now come on top of it; C6
removes the pointer.

| | Before | After |
| --- | ---: | ---: |
| A counter, struct array | 193,421 | 191,409 (-1.0 %) |
| B sorted list, struct array | 211,955 | 209,197 (-1.3 %) |
| C counter, one array per field | 187,116 | 187,116 |
| D sorted list, one array per field | 196,866 | 195,624 (-0.6 %) |

The examples (`dev/bench_examples.sh`): Er-Tüp -1.6 %, Hello -0.7 %, CatchMe -0.5 %, Steinschlag
-0.2 %, Schach, Mandelbrot and Mode 7 the same; geometric mean -0.4 %. Their code changed in all
seven, and in 4 execution programs (`BackendOutputTest`, 31 hashes).

## A canonical form for arithmetic and comparisons (2026-11-20, on the spelling families and the examples)

`Simplify` writes every instruction in one form, so that later passes know one spelling
([archive/compiler-flaws.md](archive/compiler-flaws.md#flaw-c-the-mir-has-no-canonical-form),
C1 to C3): `x - c` is `x + (-c)`, a commutative operation has its constant on the right, and a
comparison with a constant is one of `eq ne ult uge slt sge` (`x >= 1` unsigned is `x != 0`;
one the type decides is folded). `Gvn` finds `b + a` as `a + b`. The passes that took `sub` as
a step (`Timing`, `DeadLoops`, `ArithSelection.incDec`, `RangeChecks`) take the canonical form
only.

The loops that count down: their test is `i != 0` instead of `i >= 1`, about 5 % on the spelling
members (`g_u16_down` 6,046 → 5,743, the `u16_down` of every bound family -5 %, `fill_u16_down`
-5.8 %, `copy_down` -3.5 %, `m_u16_from1` -4.6 %); no gap closed. `i - 1` as an index and the step
`i--` are now one instruction, so the step has two users and the counter stays out of X: the
gaps now name that (L2, L3).

| | Before | After |
| --- | ---: | ---: |
| A counter, struct array | 191,409 | 191,283 (-0.1 %) |
| B sorted list, struct array | 209,197 | 208,537 (-0.3 %) |
| C counter, one array per field | 187,116 | 186,990 (-0.1 %) |
| D sorted list, one array per field | 195,624 | 194,965 (-0.3 %) |

The examples: Er-Tüp and CatchMe -0.1 %, Steinschlag +0.01 %, the rest the same; geometric mean
the same. Code changed in all seven and in 112 of the 285 programs of `BackendOutputTest`; the
style guide's `slow-compare-sum` 2 cycles slower on both sides.

## Two-dimensional arrays as one index (2026-11-20, on the spelling families and the examples)

`t[r][c]` was `ptradd (ptradd @t, r * 16), c`: a 24-bit pointer to the row, built in every round
of the inner loop and read as `[dp],y`, since only a `ptradd` on a global or a slot itself is
indexed with X. `Simplify` makes it `ptradd @t, r * 16 + c` (C9), and `(i >> 4) * 16 + (i & 15)`
is `i` (C10, with `(x >> k) << k` as `x & -2^k`).

The table family (`table-2d`): `flat_index` (`t[i >> 4][i & 15]`) 10,795 → 3,238 cycles, the
family's cheapest now and its gap closed; `rows_u16` 7,950 → 5,774. `rows_ptr`, the row pointer
written by hand, 5,578 → 5,774 (+3.5 %): `row[c]` is the same sum now, one addition in every inner
round, where the pointer was built once a row. L4, which makes such a sum a counter of the inner
loop, takes both to the flat loop's cost.

The examples: Steinschlag -12.8 % (its cave is `cave[y][x]`), Er-Tüp +0.2 %, CatchMe and Schach
the same on average (Schach's busiest ticks +0.4 %), the rest the same; geometric mean -1.9 %.
The pair loops the same. Code changed in all seven examples and in 5 execution programs.

## Widening by the end that moved (2026-11-20, on the spelling families)

The range analysis widened a value that changed more than 24 times to its type's whole range,
both ends. A signed counter from 0 upwards lost its 0, the loop's condition only brought the
upper end back, and the counter was "-32,768..100": a signed comparison, and the array access
through a 24-bit pointer. Now only the end that moved is widened
([archive/compiler-flaws.md](archive/compiler-flaws.md#r1-widening-throws-away-the-bound-that-did-not-move), R1).

Eight gaps of the spelling test closed: `c_i16_lt100` 6,146 → 2,538 cycles, the `int16_t`
counter of every bound family from 30 rounds on (the cliff at 24 rounds: `int16_t i < 30` was 61
cycles a round, now 25), `copy_i16` 8,825 → 2,517, `find_return_i16` 6,306 → 4,395; `fill_i16`
and `pairs_i16` now cost what their `uint16_t` spellings cost. No example and no program of
`BackendOutputTest` changed its code: none has a signed counter over more than 24 rounds.

## Unsigned ranges (2026-11-20, on the spelling families and the examples)

A range was one interval of signed values, so a byte holding 0..199 had none, and with it the
loop counters over more than 127 rounds: their index went through a 24-bit pointer and their step
was written in 8 bits. Every value now has two intervals, of its signed and of its unsigned
reading, which tighten each other where one lies on one side of the sign change; a signed
condition bounds the one, an unsigned the other
([archive/compiler-flaws.md](archive/compiler-flaws.md#r2-bytes-above-127-have-no-range), R2).
The tests check every computed value against both.

The spelling families: the byte counters to 129 and 200 cost what the `uint16_t` ones cost
(5,461 → 3,263 and 8,443 → 5,038 cycles, their gaps closed); `struct_down` 5,300 → 2,838,
`pairs_down` 25,896 → 21,220, `k_u8_var_bound` -7 %. The style guide's `slow-u8-counter` no
longer held (both 5,025 cycles) and went, with the entry in *Tried and rejected* that waited for
unsigned byte ranges; `pairs_by_list` of `slow-index-list` 23,186 → 20,858.

| | Before | After |
| --- | ---: | ---: |
| A counter, struct array | 191,283 | 191,283 |
| B sorted list, struct array | 208,537 | 207,207 (-0.6 %) |
| C counter, one array per field | 186,990 | 186,990 |
| D sorted list, one array per field | 194,965 | 193,633 (-0.7 %) |

The examples: Schach -0.2 %, Er-Tüp +0.1 %, the rest the same; geometric mean the same. Code
changed in all seven and in 73 of the programs of `BackendOutputTest`.

## Counters take their range from the counted loop (2026-11-20, on the spelling families and the examples)

The range analysis iterates a loop counter's interval, and a test with `!=` (the canonical form
of `i > 0` going down, and of `i != 100`) bounds nothing it can iterate to: the byte counter of
`i != 100` "might wrap", and `i - 1` in the body of `i != 0` might be -1, so it was no index and
went through a 24-bit pointer. `Ranges` now meets each counter with the interval `CountedLoops`
gives it (before and after widening), and `x != c` cuts c off an end of x's interval in the
blocks where it holds ([archive/compiler-flaws.md](archive/compiler-flaws.md#flaw-l-four-passes-recognise-loops-each-differently),
L2).

The spelling families: `n_u16_ne100` 5,643 → 2,738 (its gap closed), `d_u8_ne100` 4,641 → 2,936,
`g_u16_down` 5,743 → 3,743, `h_u8_down` 6,243 → 4,443, the `u16_down` of every bound family
-35 % (7,339 → 4,779 for 128 rounds), `copy_down` 8,422 → 3,522, `fill_u16_ne` 3,094 → 1,945,
`fill_u16_down` 3,158 → 2,390, `fill_u8_down` 3,478 → 2,710, `struct_down` 2,838 → 2,070. What
is left of them is L3 (the step `i - 1` has two users), L4 and C5.

| | Before | After |
| --- | ---: | ---: |
| A counter, struct array | 191,283 | 191,283 |
| B sorted list, struct array | 207,207 | 194,004 (-6.4 %) |
| C counter, one array per field | 186,990 | 186,990 |
| D sorted list, one array per field | 193,633 | 180,432 (-6.8 %) |

The examples: the same (Steinschlag -0.01 %); their code changed in all seven, and in 10
programs of `BackendOutputTest`. Loop bounds of the timing analysis got tighter: a loop of
`exec/dead_loops.c` that had none has one, and `add_up` of `exec/hint_range.c` (`while (k)`
with k at most 7) 9,730 → 256 cycles at worst, since its counter now stays in its byte and
`Induction` makes `k * 3` a variable of its own.

## Bounds that change in the loop (2026-11-20, on the execution programs)

`CountedLoops` took only a bound defined outside the loop; `Timing` took any, with its range, and
needs to keep doing so when it moves onto `CountedLoops` (L3): `either` of `exec/loop_bounds.c`
runs `r < (layer == 0 ? 24 : 32)`, whose bound is a φ of the loop. What an ordered test says
holds for such a bound too, as long as it lies in its range every round; `!=` needs a bound that
stays the same (the random loops found that one that changes steps around the counter). Also from
`Timing`: a step of 1 from the far side of a constant goes round the type once to it.

Code changed in one program: `either`'s byte counter is now known not to pass 32, so its step is a
16-bit `INC` (7 cycles) instead of an 8-bit one inside the store's `SEP`/`REP` (5 cycles); its
worst case 2,520 → 2,584. The examples and the spelling families the same (no example's code
changed).

## Dead loops proved by the counted loop (2026-11-20, on the execution programs)

`DeadLoops` proved a loop finite on its own: a counter compared with a constant, not
zero-extended, and with `!=` only for an odd step. It now takes everything `CountedLoops`
proves, from constants or else from the ranges of start and bound: `i < n` with any n, a byte
compared zero-extended with a word below 256, and an even step that meets its constant exactly
(`for (u = 0; u != 40; u += 2)`, which `exec/dead_loops.c` kept until now and loses). No example
and no other program changed.

## Counters in X read after their step (2026-11-20, on the spelling families and the execution programs)

The X pattern wanted the step as the last instruction before the jump back, with no other user:
the counter of a `do` loop, whose test reads the stepped value, and a loop counting down that
indexes with `i - 1` (the step itself since C1) stayed in memory, and a signed test `i >= 0`
was not one CPX takes. `findXCounters` now takes its counters from `CountedLoops` (a commit of
its own that kept every hash) and a step anywhere in the round, once a round: before it X
holds the counter, after it the stepped value, which the fused exit test (`CPX`) and array
indexes may read. The counter must not be read after the step, nor either value after the loop.
A signed test against 0 of a 16-bit counter is `CPX #0` and `BMI`/`BPL`
([archive/compiler-flaws.md](archive/compiler-flaws.md#flaw-l-four-passes-recognise-loops-each-differently),
L3; `exec/x_stepped.c` has the cases).

The spelling families: 15 gaps closed. `i_u16_dowhile` 4,030 → 2,725, `g_u16_down` 3,743 →
2,738, `copy_down` 3,522 → 2,517, and in every bound family `u16_down` and `i16_down` -27 %
(4,779 → 3,494 for 128 rounds); `fill_do` 2,765 → 1,932. The examples, the pair loops and the
style guide's measurements the same (no example's code changed); `exec/pointer_index.c` keeps a
counter in X it kept in memory (worst case -25 cycles).

## A cast follows its operand's condition (2026-11-20, on the examples)

A condition bounds the value it tests, in the blocks where it holds; a zero extension of that
value computed before the test kept the range it had there (`zext(n)` made before `n < 13`
stayed 0..255 after it). In a block, the range of a cast is now also the cast of what the block
knows of its operand. Found while moving loop tests to derived variables (L4), whose new bound
`zext(n)` is computed in front of a `DAMPF_RANGE` test.

The examples: CatchMe -2.3 %, its busiest 60 ticks -16 % (`maze_draw` 1,166,480 → 641,488 cycles
at worst, `draw_cell` and `maze_clear_cell` 5,600 → 3,100: an index made 16-bit before its
check now goes through no pointer); the others the same, geometric mean -0.3 %. Code changed in
CatchMe only.

## Two counters with one start and step are one (2026-11-20, on the spelling families and the examples)

A pointer walked beside a counter (`*p++` with `i++`, two arrays copied together) becomes, after
`PointerOffsets`, a second counter with the same start and step as the first: two variables in
the frame where one would do, and neither in X. `Induction` now replaces the second by the first
([archive/compiler-flaws.md](archive/compiler-flaws.md#flaw-l-four-passes-recognise-loops-each-differently),
L5). `Schedule` puts a value only φs read (a step) at the end of its block: the merged step had
come first, before the loads that read the counter, which kept it out of X.

The spelling families: `x_ptr_walk` 6,145 → 2,736 and `copy_ptr` 8,428 → 2,715 (both gaps
closed), `fill_ptr_walk` 3,288 → 2,071, `copy_while_n` 9,741 → 4,532; the rest of these are
counters that step by different amounts (L4). The style guide's `pairs_by_list` 20,858 → 20,630.

| | Before | After |
| --- | ---: | ---: |
| A counter, struct array | 191,283 | 179,353 (-6.2 %) |
| B sorted list, struct array | 194,004 | 190,127 (-2.0 %) |
| C counter, one array per field | 186,990 | 186,990 |
| D sorted list, one array per field | 180,432 | 180,432 |

The examples: CatchMe -0.5 %, Steinschlag -0.3 %, Schach +0.1 %, the rest the same; geometric
mean -0.1 %. Code changed in five examples and 9 programs of `BackendOutputTest`.

## Affine values of a counter as variables, the loop test on them (2026-11-20, on the spelling families and the examples)

`Induction` made a variable of `i * C` only, and moved the loop test to it for byte counters
only, so a 16-bit counter stayed in memory beside the offset it stepped (the style guide's
`slow-u16-counter-words`), and `i - 1`, `(i - 1) * 2` or `r * 16 + c` were computed every round.
It now takes any affine value of a counter: the zero extension of a byte counter, `+` a constant,
a value from outside the loop or another affine value, `*` and `<<` a constant
([archive/compiler-flaws.md](archive/compiler-flaws.md#flaw-l-four-passes-recognise-loops-each-differently),
L4). A value with a factor other than 1 becomes a variable, as before; if nothing but such
values, the step and one loop test read the counter, every one does, and the test moves to the
first: `i < 64` with `o = i * 2` is `o <= 126`, which `Simplify` writes `o < 127`. The move is
exact when the compared values map into one 16-bit reading without wrapping (the value ranges
show it); `<` becomes `<=` with the bound minus one, and `!=` of a counter that never passes its
bound becomes `>` first, so that the variable's range in the loop leaves out the value after the
last round. A value that is another variable plus a constant is that addition. Found on the
way: users counted only from live instructions (a dead `zext` kept counters), and the bound
offset added in 16 bits after the zero extension (`n - 1` of a byte `n = 0` is 255).

The spelling families: 15 gaps closed. `fill_u16` and its spellings 1,945 → 1,233
(`fill_u16_down` 2,390, `fill_u8_down` 2,710), `pairs_u16` 21,411 → 17,990, `pairs_hoisted`
19,899 → 16,574, `struct_u16` 1,881 → 1,521, `fields_u16` 1,721 → 1,361, `struct_down` 2,070 →
1,585, `m_u16_from1` 4,146 → 2,538, `d_u8_ne100` 2,936 → 2,538, `h_u8_down` 4,443 → 2,738;
`rows_u16` 5,774 → 5,397, whose test now compares with a bound in a place, which the X pattern
does not take yet. The style guide: `slow-u16-counter-words` (both 1,233) and `slow-word-array`
(`sum_words` 7,033 → 4,825, now cheaper than `sum_bytes`) retired; `fast-array-per-field` and
`slow-pointer-walk` 360 cycles cheaper on both sides.

| | Before | After |
| --- | ---: | ---: |
| A counter, struct array | 179,353 | 179,361 |
| B sorted list, struct array | 190,127 | 190,430 (+0.2 %) |
| C counter, one array per field | 186,990 | 186,996 |
| D sorted list, one array per field | 180,432 | 180,735 (+0.2 %) |

The examples: Er-Tüp -2.0 %, CatchMe -0.2 %, Schach +0.2 %, the rest the same; geometric mean
-0.3 %. Code changed in all seven and in 23 programs of `BackendOutputTest`.

## A counter in X compared with a value in its place (2026-11-20, on the spelling families and the examples)

The X pattern took a loop test against a constant only. A test moved by L4 often compares with a
value computed before the loop (`r * 16 + 16` in a table's row, `2 * (i + 1)` in a pair loop
counting down). A 16-bit counter in X may now be compared with a 16-bit value from outside the
loop that has a place: `CPX` on it, the value kept stored (not only handed on in A).

The spelling families: `pairs_down` 21,027 → 17,460 (gap closed), `rows_u16` and its spellings
5,397 → 3,737; what is left of them is the outer loop, whose rows lie one after another and
could be one loop (`flat_index`, 3,238; not in the plan). The pair loops the same.

The examples: Schach -0.5 %, Er-Tüp and Steinschlag -0.1 %, the rest the same; geometric mean
-0.1 %. Code changed in six examples and 2 programs of `BackendOutputTest`.

## A counter gives its test to a sibling (2026-11-20, on the spelling families)

A pointer walked beside a counter that steps by another amount (`*p++` over words beside
`i++`, `n--` beside the pointers' offsets counting up) is, after `PointerOffsets`, a second
counter that is a * i + b of the first. L5 merged such counters only for a equal 1 and b equal
0. A counter that nothing but its step and its loop test reads now gives the test to such a
sibling, from constant starts (`Induction`, L5 second half, with the move of L4): `n != 0` with
`o = 100 - n` is `o <= 99`, and the counter goes.

The spelling families: `copy_while_n` 4,532 → 2,715 (gap closed), `fill_ptr_walk` 2,071 →
1,359 (what is left: its loop head computes the pointer, without code, which keeps it from
being rotated), `struct_ptr_count` 1,943 → 1,583 (the stride, as `struct_u16`). The style
guide's `move_by_pointer` 1,943 → 1,583, 4 % above `move_by_index`. The examples and the pair
loops the same; code changed in 2 programs of `BackendOutputTest`.

## Loop heads with a pointer sum are rotated (2026-11-20, on the spelling families and the examples)

A loop head goes after its latch (the latch falls through into the test, which branches back:
one branch a round instead of a jump and a branch) only when it holds nothing but φs, a
comparison and the branch. `PointerOffsets` leaves a pointer sum in the head where the pointer
was a φ, which mostly has no code (its loads and stores add the offset), and kept such loops
unrotated. A head may now hold pointer sums too.

The spelling families: `fill_ptr_walk` 1,359 → 1,233 (gap closed), `x_ptr_walk` 2,736 → 2,538,
`copy_ptr` 2,715 → 2,517: each now costs what its indexed spelling costs. The style guide's
`move_by_pointer` costs what `move_by_index` costs (1,521), so `slow-pointer-walk` became
`same-pointer-walk`. The examples: Er-Tüp -0.1 %, the rest the same; code changed in Er-Tüp and
2 programs of `BackendOutputTest`.

## Loads out of loops (2026-11-20, on the spelling families and the examples)

`Licm` moved arithmetic only, so a loop's bound in a global (`i < count`) was loaded every
round. It now moves a load whose address does not change in the loop when nothing in the loop
may write what it reads: no store or copy that may overlap it, no call of a function that may
write memory, not volatile ([archive/compiler-flaws.md](archive/compiler-flaws.md#flaw-c-the-mir-has-no-canonical-form),
C7). Outside the loop's head only from a global (bank $7E, all RAM) or a known place of a frame
slot, which can be read once more than the program does. Whether two accesses may overlap is
`Gvn`'s rule, which now follows a variable offset too: `t[i]` stays in `t`, as C's pointers do,
so it overlaps no other global or slot (before, it might have been anything). The X pattern
compares with a byte bound through its zero extension (`CPX` on the byte's place, which holds it
zero-extended).

The spelling families: `k_u8_var_bound` 5,354 → 2,651 (gap closed), `j_u16_var_bound` 6,651
→ 5,553, now what `l_u16_local_bound` costs (R3). The style guide's `pairs_by_list` 20,630 →
18,170.

| | Before | After |
| --- | ---: | ---: |
| A counter, struct array | 179,361 | 179,361 |
| B sorted list, struct array | 190,430 | 185,678 (-2.5 %) |
| C counter, one array per field | 186,996 | 186,996 |
| D sorted list, one array per field | 180,735 | 175,984 (-2.6 %) |

The examples: Er-Tüp -0.5 %, Schach -0.4 %, CatchMe and Steinschlag -0.1 %, the rest the same;
geometric mean -0.2 %. Code changed in all seven examples and in 28 programs of
`BackendOutputTest`; `sdevkit_sfx_play` one cycle cheaper at worst.

## Pointers in one object compare as offsets (2026-11-20, on the spelling families)

`for (p = data; p < data + 100; p++)` compared two 24-bit pointers every round, as 32-bit values.
Two addresses in the same global or frame slot now compare as their 16-bit offsets, unsigned:
C lets a pointer point into its object or one past it, so both lie in 0..size
([archive/compiler-flaws.md](archive/compiler-flaws.md#flaw-c-the-mir-has-no-canonical-form),
C6). `PointerOffsets` has made the walked pointer an offset already, so the loop is then a counter
loop like any other.

The spelling families: the seven C6 gaps closed. `y_ptr_end` 6,989 → 2,538, `copy_ptr_end`
6,968 → 2,517, `fill_ptr_end` 4,228 → 1,233, `flat_ptr` 8,921 → 3,238, `find_ptr` 7,266 → 3,352,
`pairs_ptr` 31,437 → 16,115 (now the cheapest pair loop), `struct_ptr` 2,948 → 1,521 (the stride
is left, as for `struct_u16`). No example compares pointers: their code is the same, as are the
pair loops; code changed in one program of `BackendOutputTest`.

## An index into an array is inside it (2026-11-20, on the spelling families and the examples)

A global or frame slot indexed by a 16-bit value was indexed with X only where the value ranges
showed the index never negative, since X adds unsigned; otherwise every access computed a 24-bit
pointer. C makes an access outside its object undefined, so a sum read only as the address of
loads and stores (directly, or through a constant offset of at most 0) is now indexed with X
without a range ([ADR-6F8NBESR](adr/6F8NBESR-an-access-to-an-array-tells-dampf-its-index-is.md),
R3). The MIR interpreter stops a program that accesses outside its object, so the tests catch a
program the rule would break.

The spelling families: `l_u16_local_bound` and `j_u16_var_bound` 5,553 → 2,648 (both gaps
closed). The style guide's `fast-range-index` no longer holds (56 cycles with the hint and
without) and is retired. The examples: Schach -2.6 % (its busiest 60 ticks -3.0 %: the move
list's 16-bit indexes), Steinschlag -0.1 %, the rest the same; geometric mean -0.4 %. The pair
loops the same. Code changed in all seven examples and 4 programs of `BackendOutputTest`.


## Places get their addresses on the code (2026-11-20, on the examples)

The frame places were coloured on MIR before selection, by a prediction of the code; they are
now coloured on the LIR selection wrote ([ADR-6F8NBG4P](adr/6F8NBG4P-frame-places-are-coloured-on-the-lir-and.md),
B2), with the predicted address as each place's first choice. That is about safety, not speed:
a wrong prediction now costs a different address, not a miscompilation. Where a value handed on
in A had a place nothing wrote, that place is gone, so three examples' frames lie 2 bytes lower
(Ertüp, Schach, Steinschlag). No addressing form changed with them, so no cycle count did: the
examples' timings, the worst cases of `cost-baseline.txt` and the pair loops are the same. Code
changed in three programs of `BackendOutputTest`.

## φs through their incoming places, the copies coalesced (2026-11-20, on the examples)

`FrameLayout` let the predecessors write a block's φs directly where it predicted that was safe;
three miscompilations came from that prediction. Every φ now goes through its incoming place,
the copies at the end of each predecessor and on entry are `LCopy`, and the colouring on the
LIR coalesces them: the two places of a copy become one where they do not conflict, and the copy
is then no code ([ADR-6F8NBHZ6](adr/6F8NBHZ6-phis-go-through-their-incoming-places-and-the.md), B2).

The way there, measured on the examples against the code before: without direct φs and without
coalescing +8.5 %; greedy preference for a copy's other place +4.5 %; φs and their operands
paired in `FrameLayout`'s prediction (so selection steps a counter in place) and aggressive
coalescing +2.4 %; no value handed on in A where it is predicted in the φ's own place +1.2 %; a
loop head's load moved to the entry where the back edge holds it in A (`RedundantLoads`) +0.9 %;
a copy conflicting only in the bytes it does not copy (an i8 copy is 2 bytes, both copied) −0.4 %;
no load moved into the long form of a conditional branch, a block that only jumps and that the
branch falls through into (`BranchRelax` could not fold it back: a cycle per round in the
`search` family) −0.3 %.

The examples: Ertüp −1.4 %, Hello −0.3 %, Steinschlag −0.3 %, Schach −0.2 %, Catch Me +0.2 %,
the rest the same; geometric mean −0.3 %. The pair loops: B −0.4 %, C −0.2 %, D −0.5 %, A the
same. The spelling family `fill-words`: `fill_do` 1,224 → 1,035, the constant kept in A over the
loop; the other spellings test at the top of the loop and keep 1,233, a new gap (B6).

## What the registers hold after a transfer (2026-11-21, on the examples)

`RegContents`, which `RedundantLoads` and `IndexInY` ask what A, X and Y hold, took `TAX` and
`TAY` for instructions that change A, and did not know `TXY` and `TYX` at all: after `TXY` it
still said Y held what it was loaded with. Nothing wrote `TXY` or `TYX` yet, so no program was
wrong; the choice of X and Y by cost (B3) writes them. Now a transfer from A leaves A known, and
a transfer between X and Y copies what one holds to the other. `sdevkit_sram` 14 → 11 cycles (a
load after `TAX`); the examples' work is the same. Code changed in 7 programs of
`BackendOutputTest`.

## Places in X and Y by cost (2026-11-21, on the examples, the spelling families and the pair loops)

Selection keeps a value in X or Y only where one of its patterns matches (a loop's counter, an
index that prefers Y); everything else lives in the frame. `RegisterChoice` (B3 of
[archive/compiler-flaws.md](archive/compiler-flaws.md#b3-x-and-y-assigned-by-cost)) now looks at
every group of places left after coalescing, before they get addresses: it goes to X, then Y, for
its whole live range where every instruction that names it has a form with the register
(`LDA p` is `TXA`, `STA p` `TAX`, `LDX p` goes, `INC p` is `INX`, a φ copy `LDX`/`STX`, `LDA q /
ADC p` is `TXA / ADC q`), nothing else writes the register while it is live, no flag a branch
reads changes, and the cycles saved, weighted by `Weights` (K1: the profile's count, else 8 to the
power of the loop depth), are more than none. Loads `RedundantLoads` would take anyway count as
free.

The way there, against the code before: the first version broke Schach, which showed that
`RegContents` did not know `TXY` (fixed in the commit before); then −0.9 %; no `TAX` for stores
nobody reads, and loads A holds already counted as such −1.0 %; `RTL` reading X only for a high
word (Y was never free before a return) −1.9 %. A reload that set the flags for a branch was
dropped (a random loop program, seed 20778008; now it is `TXA`), and a store of A right after
another store was not seen as a copy by the colouring (`i_u16_dowhile` got a `TAY` in its loop;
now the colouring coalesces it); that copy let the colouring put a value into a callee's
parameter place (a random loop program, seed 22125; fixed in the commit before); the commutative
form and the free loads left −1.9 %.
Compiling took up to 3.7 times as long until one round took every candidate whose live range meets
no chosen one's: Ertüp 7.6 → 8.2 s.

The examples: Schach −3.3 %, Steinschlag −3.3 %, Ertüp −3.1 %, Catch Me −2.7 %, Hello −0.7 %,
Mode 7 −0.3 %, Mandelbrot −0.1 %; geometric mean −1.9 %. The pair loops: C −2.8 %, D −1.5 %, B
−0.8 %, A +0.1 %. The spelling families: `search` 3,672 → 2,563 (the counter in X; `find_ptr`
3,712 → 2,603), `pairs` 38,086 → 35,323, `table-2d` 27,033 → 26,928; no function's worst case
got slower. The style guide's measurements moved (`compare_sum_narrowed` 98 → 88,
`pairs_by_list` 18,170 → 17,531); every rule holds.

## Steps and comparisons in X and Y, and calls that take no registers (2026-11-21, on the examples, the spelling families and the pair loops)

`RegisterChoice` took a counter in X as `TXA / ADC #2 / TAX` and `TXA / CMP q` where selection's
pattern writes `INX / INX` and `CPX q`; and it took every call for an instruction that reads X
and Y, so no value lived in a register over the code before a call. Now, with the liveness of A
on the LIR (`RTL` reads it unless the function returns nothing):

- `LDA p / CLC / ADC #c / STA p` (in either order of `CLC` and `LDA`, with one or more stores of
  the place) is c times `INX` (`DEX` for `SBC`), c up to 3, where A, the carry and V are not read
  afterwards;
- `LDA p / CMP q` is `CPX q` where A is not read afterwards;
- a call writes X and Y but reads neither: a C function takes its arguments in its frame, and a
  runtime routine in A and R0 to R6;
- `TAX` right after `TAX` (two stores of one group) is one.

Two rewrites of one block could disagree: a load left out because A held the place after a store
or a load that the step or the comparison form then replaced (a random loop program, seed
20778041, compared the wrong value). A store a step may take and a load before a `CMP` no longer
count as holding the place.

The examples, against the code before: Schach −3.0 %, Steinschlag −2.4 %, Catch Me −1.8 %,
Ertüp −1.3 %, Hello −1.0 %, Mode 7 −0.8 %, Mandelbrot −0.1 %; geometric mean −1.5 %. The pair
loops: A −3.6 %, C −2.7 %, D −2.1 %, B −1.3 %. The spelling families: `search` 2,563 → 2,361,
`pairs_down` 38,047 → 37,827; no function's worst case got slower. Without selection's patterns
(`findXCounters`, `findYIndexes`, `IndexInY`) the examples are still 0.4 % slower than with them
(Ertüp, Schach), so the patterns stay.

## Loads into X, flags that describe A, and calls that take no A (2026-11-21, on the examples)

Comparing `RegisterChoice` without selection's patterns with the patterns showed what it still
missed; three things, each useful with the patterns as well:

- `LDA q / STA p` (q an immediate or a place without an index) is `LDX q` where A is not read
  afterwards (a constant start value: `LDX #$0040` instead of `LDA #$0040 / TAX`);
- a branch that reads N and Z after a store no longer keeps a place out of X where the flags
  describe A (the instruction that set them last set them from A): `STA p` as `TAX` sets them
  the same, `STZ p` as `LDX #0` gets a `CMP #0` after it, a `LDX p` that goes becomes `CPX #0`
  (where the carry is not read); a loop counter whose φ copy stood between `AND #$00FF` and `BNE`
  stayed in memory before;
- a call reads A only if it calls a runtime routine; a C function takes its arguments in its
  frame, so A is dead after a loop that ends in a call, and the loop's test is `CPX`.

A load A holds already counts as no read for the `CPX` form either (it saved nothing there:
Catch Me's `glyph` got a `TAX` in its loop at first), and `LDA q / STA p` as `LDX q` reads
nothing of the place.

The examples, against the code before: Steinschlag −1.5 %, Hello −0.8 %, Catch Me −0.7 %, Ertüp
−0.7 %, Schach −0.1 %, Mandelbrot −0.1 %, Mode 7 the same; geometric mean −0.5 %. The pair loops
−0.1 %. No function's worst case got slower; of the style guide's examples `pairs_by_list` costs
24 cycles more (a store `DeadStores` cannot prove dead, now kept from A instead of `TAX`).
Without selection's patterns the examples are 0.2 % slower than with them (Ertüp 0.8 %).

## A register for one loop, and an index in Y (2026-11-21, on the examples)

Two things selection's patterns did that `RegisterChoice` could not, found in Ertüp's
`carousel_collide`: a value kept in X in one loop and in memory around it, and a loop counter in
Y used as the index (`LDA t,Y`), which leaves X free for something else.

- Splitting (B3): a candidate may take a register for one loop only. It is loaded at the end of
  the blocks that enter the loop (where it is live into it) and stored at the start of the blocks
  the loop leaves to (where the loop changes it and it is live there); a block that enters the loop
  and goes elsewhere too, or one left to from outside as well, keeps it out, as do X, Y or the
  flags read at the head from outside. The load and the store name the owner live there, so the
  check of the places follows them. The loops are MIR's, their LIR blocks found by label.
- A place in Y whose `LDX p` is followed only by accesses with an absolute,Y form takes those
  accesses with Y (what `IndexInY` does after the passes; here before, so X is free).

The first version put the store after a loop in front of the exit block's label, where comments
before the label belong to the block above: a random loop program (seed 20778026) and Ertüp
computed wrong values; it goes right after the label now.

The examples, against the code before: Ertüp −0.5 %, Schach −0.2 %, Catch Me −0.1 %, the rest the
same; geometric mean −0.2 %. The pair loops and the spelling families the same; no function's
worst case got slower.

A miscompilation in the step before (`LDA q / STA p` as `LDX q`, 2026-11-21): a load left out
because A held the place after `STA p` relied on that store, which the load form of the same
or a later round could take, so A never got the value (a random loop program, seed 27879,
compared the wrong number; the soak found it after the commit). A store the load form may take
no longer counts as holding the place where a load is left out; to judge only what
`RedundantLoads` would do (the commutative form) it still does. No code of the tests or the
examples changed.

## Counters going down, and loop tests on the flags of X (2026-11-21, on the examples); the patterns stay

`RegisterChoice` takes `CLC / ADC #-1` to `#-3` (MIR writes x - c as x + -c) as `DEX`, and after
the choice drops a `TXA` or `TYA` that only sets N and Z for a branch where they describe that
register on every way in already (a loop test after `DEX`, entered after `LDX`); a φ copy between
counts as setting them (it becomes `LDA` / `STA`: two random loop programs, seeds 32199 and 33005,
branched on the wrong value before that). The examples, against the code before: Hello −0.2 %,
Ertüp −0.1 %, Catch Me −0.1 %, Schach −0.1 %; geometric mean −0.1 %; no function's worst case
changed.

Deleting selection's patterns (`findXCounters`, `findYIndexes`, `IndexInY`) was tried (B3): the
examples did 0.2 % more work (Ertüp 0.8 %), the pair loops B, C and D 0.5 to 3.2 % more, the
spelling family `pairs` 7 % more and `fill_u16_down` 20 % more. Two causes were found and closed
(the steps going down and the flags above); what is left is mostly a choice selection makes
before the register choice runs: where X holds something, it writes an index load with Y
(`LDY i / LDA t,Y`), which then keeps Y from a better candidate (the count in `pairs`), while
without that choice Schach does 1.8 % more work. Above the price of ADR-6F3CJR10 for a step, so
the patterns stay; the register choice takes what they leave.

## A constant kept in A over a loop with its test at the top (2026-11-21, on the spelling families)

`RedundantLoads` moves a load that starts a loop's body over the loop's test to the entry, where
the test leaves A alone (`CPX`, branches) and A is not read after the loop (B6): a constant
stored in every round is loaded once. The spellings of `fill-words` cost 1,233 → 1,044, against
1,035 of `fill_do`, whose load moved to the entry already; their ten gaps are closed. Steinschlag's
`hud_clear` has a worst case of 1,057 instead of 1,246. The examples, the pair loops and the other
spelling families are the same. A first version also moved the load after a loop into the loop
when the entry held the value and the body did not (Catch Me's `best_load`, 547 → 571 in the
worst case): the load must lie on the loop, and the ways that get it must not.

## TXY and TYX read no flags (2026-11-21, on the examples)

`RedundantLoads.reads` did not know `TXY` and `TYX`, which the choice of registers writes (B3),
and took them as reading every flag: N, Z, C and V were live before each of them. A random loop
program (seed 35675) crashed the compiler with it: a φ copy before a `TYX` was to become `LDY`,
and the choice asked how to keep the flags a branch would read after it, which it can only answer
for an instruction (it now leaves such a copy where it is). With the two known, fewer flags count
as live, and the passes that ask take more: Schach −0.8 %, Catch Me −0.2 %, the rest the same;
geometric mean −0.1 %. No function's worst case got slower.

## Loops over bytes in 8 bits from entry to exit (2026-11-21, on the examples and the spelling families)

Measured first (B5): outside the main loop's waiting, `SEP`/`REP #$20` are 0.5 % (Mandelbrot)
to 4.5 % (Catch Me) of the examples' work, nearly all of it around byte stores in 16-bit code;
a pair with nothing between them that depends on the width was 0.8 % of Ertüp's work, in one
loop (the `memset` of the sprite table's high part), and nothing elsewhere. `ByteLoops` takes that
case: a loop whose code needs A in 8 bits only, and loads and logic whose low byte is the same in
8 bits, runs in 8 bits from its entry to its exits (`SEP` before the loop, `REP` where it is left).
The text must say the width (ca65 and `LParser` follow `REP`/`SEP` in the order of the text), so
a loop whose entry lies elsewhere in the text keeps its switches.

The examples, against the code before: Ertüp −0.8 %, Schach −0.1 %, the rest the same; geometric
mean −0.1 %. The spelling family `copy` 2,517 → 1,823; the kit's start-up (`sdevkit__reset_ppu`)
12,307 → 8,737 in the worst case. No function's worst case got slower; the pair loops are the
same.

## No second variable for a word index beside a counter that stays (2026-11-21, on the examples)

`Induction` made a variable of its own for every `a * i + b` with a factor other than 1, also
where the counter stays because something else reads it; the loop then carries both (K2). Four
rules were measured against that, with the counter staying: no new variable at all (Steinschlag
+14.8 %, the pair loops A, B and D up to +0.9 %: out); none for the factor 2; none for 2 and 4;
none for 2, 4 and 8 (Mode 7's `make_tiles` slower). None for 2 and 4, one or two shifts where
the value is read, was the best and is the rule: Schach −0.3 %, Ertüp −0.15 %, the rest the same;
geometric mean −0.1 %. Slower: the pair loop D +0.2 %, and in the worst case Ertüp's
`checkpoint_before` 299 → 329 (its counter is read after the loop), Schach's `draw_moves` +0.3 %
and `pairs_down` +0.2 %; Schach's `score_moves` −5.5 % and `search_clear` −21 % among the faster.

## A large function called once in a loop is inlined (2026-11-21, on the examples)

The inlining limits measured (K4): 40, 80 and 120 instructions for any callee instead of 60,
and 100, 300 and 600 for a callee with one call site instead of 150. No pair was best for every
example: 600 for one call site made Steinschlag 14 % faster and Ertüp 2.8 % and Mandelbrot 1.4 %
slower, 40 made everything slower (Catch Me 13 %). The gain was one call in every round of a
loop, Steinschlag's `fall`, called for every cell of the cave. A callee called once in a loop
of its caller, without a loop of its own, is now inlined up to 600 instructions (with loops of
their own the callees made Mandelbrot slower, where `compute` went into `main`): Steinschlag
−15.8 %, Schach −0.2 %, the rest the same; geometric mean −2.5 %. The pair loops are the same;
the worst case of `cave_scan_rows`, which has `fall` in it now, grew by 0.001 %.

## A loop's bound as its start plus a constant (2026-11-21, on the spelling families)

The two loops over the rows of a table (`table-2d`) cost 26,910 cycles in the worst case, the
flat loop over the same 128 bytes 3,238: eight times as much, although the code of the rows is
the flat loop's with an outer loop around it. The worst case was wrong, not the code: the inner
loop runs from the row's offset o to o + 16, and `CountedLoops` bounded its rounds by o's range
(up to 134) instead of by the difference. An exit that compares the counter with its own start
plus a constant K now bounds the head runs by K / |c| + 1, for `<` and `>` where the start's range
shows that no step wraps, and for `!=` with a step of one in any case. The rows 3,614, 12 %
above the flat loop: the outer loop's rounds, which only collapsing the loops would remove.
Ertüp's `sway_init` 1,639 → 883. The examples' work and the pair loops are the same.

## The rows of a table as one loop (2026-11-21, on the spelling families)

After the bound of a row was known (the entry before), the two loops over the rows of a table
(`table-2d`) cost 12 % more than one loop over the whole table: the outer loop's 8 rounds with the
inner loop's entries and exits. `Collapse` takes such a nest, where the outer loop steps a row's
offset by the row's length K from a constant, the inner loop walks the row in steps that divide
K, and nothing else happens per row: the inner loop runs to the end of the last row and leaves
to where the outer loop left, whose φs are read there through new φs of the exit. The four rows
spellings cost 3,614 → 3,238, as the flat loop; their gaps are closed. Steinschlag does 9 cycles
a tick less; the other examples, the pair loops and every other worst case are the same.

## Constant shifts written out (2026-11-22, on the examples)

A shift by a constant above 8 was a loop of single shifts: `ldx #n`, then per place `asl a` (or
`asl`/`rol` of both words in memory for 32 bits), `dex`, `bne`. A 32-bit shift by 16 cost about
600 cycles, `(uint32_t)hi << 16 | lo` 665. Now every shift by a constant below the width is
written out (`ArithSelection.unrolledShift`). A 16-bit shift above 8 swaps the bytes (`XBA`), masks
and shifts the rest. A 32-bit shift by 16 or more moves one word to the other, shifts it in A by
the rest and stores 0 or the sign in the word it leaves. A 32-bit shift by 3 to 15 builds each
word of the result in A from both words of x (`hi = H << c | L >> 16-c`); by 1 or 2 places the
two words still step in memory, which is cheaper there. Only variable shifts loop now.

On the examples: Mandelbrot −6.4 %, Mode 7 −7.4 %, Steinschlag −0.8 %, Schach −0.2 %, Ertüp
−0.1 %, the rest the same; geometric mean −2.2 %. 56 worst cases are smaller and none is larger;
Schach's `chess_init`, which fills the Zobrist keys with xorshift, 4,932,758 → 1,047,779. In the
library, `sdevkit_bg_set_gfx_ptr` 189 → 75 and `sdevkit_oam_init_gfx_attr` 147 → 41 (a shift by
12 or 13 of an address). The pair loops are the same: the code of variant C is unchanged, its
111 cycles a frame more come from the frames' setup inlined into `test_main`, which chooses X and
Y the other way round. The style guide's `slow-shift-above-8` is retired: `v << 9` and
`(uint16_t)(v << 8) << 1` cost 19 cycles each.

## A computed pointer sum is read through itself (2026-11-22, a bug)

Found while measuring dampf without `Schedule` (the entry after this one). A pointer plus an index
whose sum is computed, because something else reads it too (`p->row` reads at `p + 2`), was still
split into `[pointer],y` with the index in Y for the access at the sum itself (`p->col`). The
index could have been handed on in A into the sum and never stored; the read then took its place
unwritten, or `RegisterChoice` made it a `TXY` from an X nobody had set
(`exec/regress_pointer_index_sum.c`). `Schedule` usually puts something between the index and the
sum, so no example was hit. `FrameLayout.address` now splits the sum only where it is not computed
(as it already did for a number made into a pointer); a computed sum is read as `[sum],y` with
`LDY #0`. Ertüp's `stage_init` and its callers are 29 cycles faster in the worst case,
`waves_spawn` 1 slower; the examples' work is the same.

## Without findForwards or Schedule (measured 2026-11-22)

Since values get their places and registers on the LIR (`PlaceColouring`, `RegisterChoice`),
`RedundantLoads` drops a load of what A holds and `DeadStores` a store nobody reads. So the
selection's own plan for results handed on in A (`SelectionPlan.findForwards`) and `Schedule`,
which moves a value next to its only user for it, might no longer be needed. Measured with each
switched off (the examples' work against `develop`, and the worst cases of the examples, the
spelling families and the execution tests):

| Switched off | Examples (geometric mean) | Worst cases larger / smaller | Worst cases (geometric mean) |
| --- | ---: | ---: | ---: |
| `findForwards` | +1.6 % (Ertüp +3.8 %, Schach +2.0 %) | 200 / 25 | +2.2 % |
| `Schedule` | +0.5 % (Schach +1.3 %, Ertüp +1.0 %) | 186 / 54 | +0.5 % |
| both | +2.1 % | 295 / 64 | +2.5 % |

Both stay; why the LIR passes do not make up for them was not followed function by function.
The spelling families lose most: the copy loops 1,823 → 2,517 without `findForwards`, and the
copies through a pointer 1,823 → 3,219 without `Schedule`. Against it, some functions are faster without
`Schedule`: `memcpy` 10.4 %, Ertüp's `hud_lives` 12 %, `lose_life` 11 %; its order costs there, which
is open in the roadmap (M9, row 2). Without `Schedule` dampf also stopped on Ertüp, which led to
the bug of the entry before.

## Multiplication on the CPU's multiplier (2026-11-22, on the examples)

`dampf_mul16` and `dampf_mul32` were loops of 16 and 32 shifts and adds, 287 to 367 and 1,168 to
1,968 cycles, and Mandelbrot spent 87 % of its work in the runtime. The SNES has an 8 x 8 multiplier at
`$4202`/`$4203` with the product in `$4216` after 8 CPU cycles. `dampf_mul16` now adds up the three
byte products that reach the low 16 bits (108 cycles), `dampf_mul32` the ten that reach the low 32
(398 to 400). Where both factors of a 16-bit product are below 256 by their ranges, or the result is
a byte, the code goes to the multiplier directly, without a call: `(uint16_t)(ua * ub)` costs 62
cycles in the C guide's measurement against 140 for two `uint16_t`.

The emulator used to give the product at once, so a read that comes too early would have passed
every test here and failed on a console. It now adds one bit per CPU cycle (`MultiplierTest`). With
the runtime's wait one cycle shorter, both dampf's emulator and Mesen give wrong products.

On the examples: Mandelbrot 230,944 → 76,575 (0.332), Mode 7 24,940 → 11,649 (0.467), Ertüp 0.981,
Catch Me 0.990, the rest the same; geometric mean 0.763. 48 worst cases are smaller and none is
larger. The pair loops multiply by constants and are the same. `same-multiply-width` is now
`fast-multiply-bytes`.

## A loaded value goes right before a store through a pointer (2026-11-22, on the examples)

`memcpy` was 10 % faster without `Schedule` (the entry on `findForwards` and `Schedule`). A store's
address and value are both read by the store alone, so each wanted the place right before it, and
`Schedule` moved them in turns until its limit of rounds. In `memcpy` the address won: the loaded
byte was stored in a temporary and loaded back while the address was computed, 8 cycles a byte.
Where the address is a pointer plus a variable, whose sum needs A, and the value is a load, the
load now comes right before the store. `memcpy` 5,046,223 → 4,521,943 at its worst case (10.4 %);
every other worst case, the examples' work and the pair loops are the same.

Wider rules measured worse. The value last for every store made Schach 0.5 % slower: its stores
into global arrays need no A for the address, and the new order took Y from the counter of `next`.
Only the other operand left in place made seven worst cases larger. What stays open is a computed
value stored at an index into a global: value last makes Ertüp's `hud_lives` 12 % faster and
Schach's `draw_square` slower, so that choice needs a cost model.

## A signed division of a value known not to be negative (2026-11-22, on the C guide)

`v / 8` on an `int16_t` with `DAMPF_RANGE(v, 0, 1000)` cost 63 cycles against 17 for the same
division written as unsigned. C promotes `v` to 32 bits. `RangeNarrow` narrowed the division back
to a signed 16-bit one, and `StrengthReduce`, later in the same round, gave it its correction for
negative values before the next round could see that there are none. Where neither operand can be
negative, `RangeNarrow` now narrows to the unsigned operation at once (`sdiv` to `udiv`, `srem` to
`urem`, `ashr` to `lshr`). The division with the hint costs what the unsigned one costs. No example
divides that way, so their code, the worst cases and the pair loops are the same.
`slow-signed-divide` stays, for a value dampf knows nothing about.

## A comparison with a sum, in 16 bits (2026-11-22, on the examples)

`a > b + 32` on two `int16_t` is a 32-bit comparison: C adds in `int`, and the sum may not fit 16
bits. But where it does not fit, the answer is known: a sum above 32767 is greater than every
`int16_t`, one below -32768 less. `CompareSum` turns such a branch into a test whether the sum fits,
which goes straight to the known answer if not, and a 16-bit comparison if it does; where the ranges
show that it always fits, the next round removes the test. A branch that leaves a loop stays, since
the loop passes read its test. Exact for every predicate and either order (`CompareSumTest`,
`exec/compare_sum.c`, at the values where the sum stops fitting).

The C guide's example costs 164 → 104 cycles (86 with the cast). Steinschlag's `approach` 183 → 119
and `camera` 448 → 320, Ertüp's `crate_update` 502 → 384. Steinschlag does 0.9 % less work, the
others are the same within 0.04 %; geometric mean 0.999. 5 worst cases are smaller, none larger.
The pair loops are the same.

## `BIT` for bit tests, measured and not built (2026-11-23, on the examples)

Row 6 of the roadmap expected 2 to 4 % for the whole row, which includes the `CMP #0` already left
out. What `BIT` itself can add follows from the 65816's cycles. With a 16-bit A, on the direct page,
`lda x; and #mask`, `lda x; bit #mask` and `lda #mask; bit x` all cost 7 cycles, so replacing one by
another gains nothing. `BIT` gains where an instruction can go: the `AND` of a test of the top bit
(N is already set by the load), the `AND` of a test of bit 14 or 6 (`BIT` on memory puts it in V),
a load of the value `BIT #mask` left in A, or a load of the mask when A already holds it. `BIT` has
no long or indirect modes, so it cannot read a local through `[dampf_frame],y`.

Before building it, every example's check was played in dampf's emulator on `develop` at `ee9657b`,
and every `AND #mask` directly before a `BEQ` or `BNE` was classified as it ran, with the measured
cost of the instruction that would go. The frame-wait loop is left out, as in
`dev/bench_examples.sh`. The numbers are shares of the example's work, and upper limits: the count
does not check whether the result of the `AND` is still needed.

| Share of the work (%) | CatchMe | Ertüp | Hello | Mandelbrot | Mode 7 | Schach | Steinschlag |
| --- | --- | --- | --- | --- | --- | --- | --- |
| All `AND #mask` before a branch | 1.41 | 2.29 | 0.18 | 0.15 | 0.31 | 2.61 | 0.57 |
| Of these, the mask `$00FF` (a byte in a 16-bit A) | 1.00 | 2.05 | 0.03 | 0.07 | 0.00 | 0.93 | 0.19 |
| Top bit: the `AND` goes | 0.00 | 0.03 | 0.00 | 0.00 | 0.15 | 0.00 | 0.00 |
| `BIT #mask` keeps A, the same load comes next | 0.04 | 0.01 | 0.00 | 0.00 | 0.24 | 0.00 | 0.00 |
| A already holds the mask | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.02 |
| **The most `BIT` could save** | 0.04 | 0.04 | 0.00 | 0.00 | 0.40 | 0.00 | 0.03 |

The last row adds the unrounded numbers. No test of bit 14 or bit 6 ran after a load `BIT` can
replace. The geometric mean of the savings is 0.07 %, and Mode 7's 0.40 % is the largest. Theory and
measurement agree: `BIT` saves a whole instruction, 3 cycles or more, where one of these cases
applies, and in this code they are rare. Most of the tests are the mask `$00FF`, which is a matter of 8-bit code (row 3).

Not built: the gain does not pay for another pass and its tests. Code written for it, with its flags
in the top bits, would gain more. Blubber Blasen was not measured.

## 32-bit values combined bit by bit, as two halves (2026-11-23, on the C guide)

`hash ^= key` on two `uint32_t` cost 84 cycles against 36 for the same on two `uint16_t` halves:
each word went through a frame place. An AND, OR or XOR never carries from one half to the other,
so `SplitWords` makes a 32-bit value that is only loaded, combined bit by bit and stored into two
16-bit values, with its constants, zero extensions and φs. Anything else that reads it (an
addition, a comparison, a call) leaves the whole web as it is.

The split alone gave 46 cycles: `Schedule` would not move the high half's loads past the store to
the low half. A load may now pass a store whose address is known to be other bytes, of the same
global or another one. `xor_32` costs 36, as the halves do. `slow-32-bit` now measures 32-bit
addition, and `same-32-bit-bits` holds the new fact.

The examples hardly have such values: Catch Me 0.998 (the moved loads), hello 16 master cycles a tick
more (0.03 %), the rest the same; one worst case smaller, none larger. The pair loops are the same.

## Tests for equality on split 32-bit values (2026-11-23, on a collision loop)

`if (rows[y] & mask[y])` on two `uint32_t` kept the value whole, because `SplitWords` did not take
a comparison as a reader. It now takes a test for equality: `x != 0` is `(lo | hi) != 0`, and
`x == y` compares `(xl ^ yl) | (xh ^ yh)` with 0. An ordered comparison still keeps the web whole.

A loop that tests 32-bit rows against a mask went from 1,777 to 1,073 cycles (-40 %). The other
eight 32-bit loops measured for the split (hashes, bitboards, xorshift, CRC) are the same. Schach
0.999 (370,752 to 370,259), the other examples 1.000; no worst case smaller or larger. The pair
loops' code is the same, and only the check after them is 41 cycles shorter.


## The register choice keeps its weights after the first choice (2026-11-23, on the examples)

`RegisterChoice` cuts the code into new blocks each time it applies a choice, and its block weights
and its list of loops still named the old ones. From the second round on, every block weighed 1 and
no value could take a register for one loop only. They now follow the blocks.

The examples: hello 0.995, Er-Tüp 0.999, Catch Me 1.000, Schach 1.000 (370,259 to 370,396), the rest
the same; geometric mean 0.999. Pair loop B 0.998, the others the same. Of the worst cases 17 are
smaller and 21 larger, by at most 1.5 % (`sfx_voice`, in every ROM). The spelling families are the
same.

## The index register choice, tried in `RegisterChoice` (2026-11-23)

The goal was one place that decides X or Y, without selection's `findXCounters`, `findYIndexes` and
the pass `IndexInY`. Selection wrote every index into X. `RegisterChoice` gave X to a value live
through a loop and moved other indexes out of its way to Y, and a new step spread the indexes left
over both registers by how often they alternate. Nine versions were measured, none good enough:

| Version | Examples | Pair loops | Largest worst case |
| --- | ---: | --- | ---: |
| X by benefit, a second round for X | 1.0001 | C 1.015, D 1.004 | +7 % |
| X to the value that loses most in Y | 1.0000 | C 1.015, D 1.003 | +15 % (Mode 7's `make_map`), `pairs_down` +32 % |

The choice is greedy and counts cycles on paths that exclude each other, so a short value can take
Y from an index used all over a function (`pair_f`). Doing better needs a cost model of the whole
function, so selection's two patterns stay. Without `findYIndexes` the pair loops B and D cost 1.7
and 1.8 % more; without `findXCounters` the examples cost 0.15 % more, the pair loops up to 0.9 %
and worst cases up to 3 %.

`IndexInY` did go. Once the weights were right it found almost nothing: Schach does 21 master
cycles a tick more (370,396 to 370,417, ADR-6F3CJR10's price for a pass less), everything else is
the same, and no worst case changed.

## `FrameLayout`'s predictions, measured and kept (2026-11-23)

Selection reads `FrameLayout`'s predicted places for three decisions: a value stepped in place
(`ArithSelection.incDec`), a φ's operand already in the φ's place
(`SelectionPlan.firstPhiCopyReads`), and a pointer in the direct page (`MemorySelection.via`,
`SelectionPlan.addressNeedsNoA`). The experiment, against `af009ad`, disabled `incDec` and took
the places out of the φ test. Work per tick, new against old:

| Example | Ratio |
| --- | ---: |
| catchme | 0.9969 |
| ertuep | 1.0039 |
| hello | 1.0209 |
| mandelbrot | 1.0013 |
| mode7 | 1.0000 |
| schach | 1.0076 |
| steinschlag | 1.0487 |
| geometric mean | 1.0112 |

The pair loops: A 1.0034, B 1.0087, C 1.0056, D 0.9813. The spelling family `pairs_down` 1.006.
Worst cases: 14 smaller, 104 larger, by up to 8 %.

The pointer decision was counted without changing it. Er-Tüp has 5 accesses through a pointer
predicted outside the direct page, of 384 through `[frame]`; Schach has none of 92, and the other
examples have none. The case is rare and still has to compile correctly in large programs.

Nothing of this was built. The predictions stay until a model of the whole function replaces them
(ADR-6FGF7X0N).

## `switch` as a jump table (2026-11-24, on the examples)

Row L of the catalogue, moved into M10. A `switch` on an 8- or 16-bit value can now jump through a
table in its function's segment (`ASL A / TAX / JMP (.loword(table),X)`, 10 cycles), with a range
check in front where the value's range does not keep it within the table (`CMP / BCC` or `BCS`, 5
cycles per end, or `SEC / SBC #lo` and one `CMP / BCS`). The break-even against the chain of
compares was measured in dampf's emulator: for each form and number of cases n, one program per
value, `test_main` a switch over cases with a store each, the code generator once forced to the
chain and once to the table. The table below gives the table's average CPU cycles over the n
cases minus the chain's (negative: the table is faster); the value that is no case cost the table
19 to 26 cycles whatever n was, the chain 5 more for each case.

| Form | n = 3 | 4 | 5 | 6 | 7 | 8 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `uint16_t`, cases 0..n-1, upper check | +3.6 | +2.5 | +0.8 | −1.1 | −3.3 | −5.5 |
| `uint8_t`, cases 0..n-1, upper check | +3.6 | +2.5 | +0.8 | −1.1 | −3.3 | −5.5 |
| `v & 15`, cases 0..n-1, no check from n = 4 | +3.6 | −1.3 | −3.2 | −5.3 | −7.6 | −9.9 |
| `uint16_t`, cases 100.., two checks | +8.3 | +6.0 | +3.6 | +1.2 | −1.3 | −3.8 |
| `int16_t`, cases around 0, subtraction and check | +8.3 | +6.0 | +3.6 | +1.2 | −1.3 | −3.8 |

The cost model in `SwitchSelection` (the chain 5k + 6 for its k-th case, 3 less when the first case
is 0 and needs no `CMP`; the table its dispatch, and a first case 0 tested before it with a `BEQ`)
gives the same break-even in every row: 6 cases, 4, 7. That is the rule (ADR-6FM7MWGX), with at
most four entries per case.

The first version assumed every case equally likely and tested no case before the table. On the
examples Ertüp got 0.8 % slower: `draw_items` and `draw_parts` walk arrays whose empty slots, the
first case `0`, are most of them, and the chain reached that case with one `BEQ`. Testing a first
case 0 before the table costs 2 cycles on the other ways and fixed it.

| Example | Work before | after | Busiest tick before | after | Code bytes before | after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| CatchMe | 47,297 | 47,297 | 127,586 | 127,586 | 24,887 | 24,887 |
| Ertüp | 132,235 | 132,217 | 262,654 | 260,166 | 86,087 | 85,954 |
| Schach | 370,417 | 370,307 | 523,463 | 523,337 | 43,782 | 43,791 |
| Steinschlag | 74,322 | 74,628 | 134,089 | 134,423 | 27,513 | 27,500 |

Hello, Mandelbrot and Mode 7 have no switch that changed. Of the 48 switches the examples have after
inlining, 8 became tables; most of the others have two to five cases. Steinschlag is 0.4 % slower:
`cave_scan_rows` switches on the kind of each cell that moves, and its first case, the boulder, is
by far the most common, where the chain needs one compare. The worst cases of the timing report
went down in every function that changed (`cost-baseline.txt`).

## A constant on an index into the operand (2026-11-25, on the examples)

`a[i + 1]` with `a` a global indexed by a 16-bit value computed the sum for every access:
`CLC`, `LDA i`, `ADC #1`, `TAY`, then `STA a,y` (11 cycles more than the access). The constant
now goes into the operand, `STA a+1,y`, and X or Y holds `i` (`FrameLayout.offsetIndex`). X adds as
an unsigned 16-bit number in both forms, so they reach the same byte exactly when `i + k` cannot
pass `$FFFF`; the unsigned range of `i` shows it. Only a positive constant (a negative one puts the
operand before the symbol), and only where the sum has no other use: a sum that is computed may
have taken `i` handed on in A, and then `i`'s place was never written. The first version missed
that rule and failed the frame check on CatchMe's `actors_draw` (`id = 4 + i * 4` read by
`oam[id]`, with `id + 1` to `id + 3` folded onto it); `exec/global_index_offset.c` has the case. A
loop counter that indexes only through such sums now stays in X (`SelectionPlan.usesFitX`).

Found while measuring the console API's sprite calls
([brainstorm](brainstorm/console-api-shadow-state_2026-11-24.md), measurement B): packing 128
sprites from parallel arrays into the sprite table, 37,455 → 29,646 cycles worst case.

| Example | Work before | after | Busiest tick before | after | Code bytes before | after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| CatchMe | 47,297 | 45,114 | 127,586 | 125,400 | 24,887 | 24,457 |
| Ertüp | 132,217 | 131,906 | 260,166 | 259,452 | 85,954 | 85,503 |
| Hello | 58,869 | 58,870 | 59,838 | 59,838 | 11,481 | 11,246 |
| Mandelbrot | 76,575 | 76,463 | 89,588 | 89,576 | 14,683 | 14,425 |
| Mode 7 | 11,649 | 11,649 | 11,673 | 11,673 | 12,175 | 11,923 |
| Schach | 370,307 | 370,306 | 523,337 | 523,336 | 43,753 | 43,496 |
| Steinschlag | 74,628 | 74,629 | 134,423 | 134,408 | 27,500 | 27,260 |

The geometric mean of the work is 0.7 % lower; every example's code is smaller. Most of the gain
is in the console API's own sprite functions (`sdevkit_oam_set` 293–405 → 217–329 cycles,
`lib/README.md`), which CatchMe calls for every actor. Er-Tüp writes its sprites through a pointer
(`o[1]`), which this does not touch.
