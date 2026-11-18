# Optimizations: the log

The dated measurements behind [optimizations.md](optimizations.md), one entry per step, newest at
the end. Every entry keeps the numbers as they were measured at the time, on the game's benchmark:
frames per tick or master clocks per tick (one NTSC frame is 357,366 master clocks), at SlowROM
speed until the FastROM entry and with FastROM after it. Later steps change earlier numbers, so the
current ones are in [optimizations.md](optimizations.md), *Where things stand*, and nowhere else.

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
overall expectation in [optimizations.md](optimizations.md) is a factor of 3 to 6 over dampf's own naive code generator, so the
naive generator has to be no slower than Calypsi, or the game has to get cheaper as well (the
game's ROADMAP already lists a cheaper CPU partner as a candidate; see also
[game-findings.md](game-findings.md)).

## dampf's naive code generator (2026-11-17)

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

## Where the naive code spends its time (2026-11-17)

`tools/profile_game.sh bench` profiles the benchmark's play ticks 900–1700 (as the game's own
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

The code generator from MIR (`dampf.backend.MirCodeGen`, `BACKEND=mir tools/bench_game.sh`) keeps
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
tools/profile_game.sh bench`) adds up the cycles per instruction and addressing mode. In the game code at 1.50 frames per tick: `sta dp` 18.8 %, `lda dp` 16.5 %,
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
instructions (it was 5.6 % of the cycles). `tools/profile_game.sh` writes
`build/profile/<what>.blocks.txt`; `BLOCKS=<file>` passes it to `tools/bench_game.sh` and
`tools/profile_game.sh`.

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
same version (the snapshot, `tools/bench_compilers.sh`, measured in dampf's emulator, which
matches Mesen on Calypsi's ROM), in the play ticks where both play the same scene (711-812),
Calypsi took 1.67 frames per tick and dampf 1.14 (both at SlowROM speed), 1.47 times as fast;
with FastROM for both and the steps since, Calypsi takes 1.42 and dampf 0.79, 1.81 times as fast.
Calypsi's results drift
from the PC's from tick 596 on, so later ticks compare other scenes ([roadmap.md](roadmap.md),
*The plan from here*). The hand-written `level_solid_px` takes
856 clocks (at SlowROM speed), so for that function dampf is now 1.34 times hand-written assembly
(1,149 clocks at the same speed).


## The back end on LIR, 2026-11-18

The steps of [transition-to-lir.md](transition-to-lir.md) that change the generated code, each
measured on the game's benchmark in dampf's emulator (FastROM), in master clocks per tick. Before
them: play 326,596 (0.91 frames), play with the CPU partner 528,446, all ticks 316,986.

| Step | Play | Play, CPU partner | All | What changed |
| --- | --- | --- | --- | --- |
| B2: branch relaxation on LIR | 326,592 | 528,440 | 316,983 | A few long branches that reach now get the short form; the passes before stopped early in those places. |
| B4: dead stores as a LIR pass | 325,572 | 527,421 | 316,359 | Stores to a function's own value places that no path reads afterwards go (byte-wise liveness over the blocks), on top of the stores the passes already left out. No function of the game or the tests got slower. |
| B3b: redundant loads as a LIR pass | 324,897 | 527,103 | 316,042 | Loads of what A, X or Y holds on every way in go (an `LDA` whose N and Z a branch reads becomes `CMP #0`), found by the data flow analysis over the code the passes keep; the passes assume less than it finds at loop heads and labels inside blocks. 50 functions of the tests and the game faster, none slower. |
| B3c: each function written once | 325,209 | 527,410 | 316,248 | Selection runs once per function and assumes nothing where a block starts; the redundant loads (with `LDA x / ADC y` where A holds y becoming `ADC x`), the dead stores and the branch forms come from the LIR passes. Before, `generate()` wrote each function up to 14 times. **A regression of 312 clocks per tick in play (0.1 %)**, see below. |

**The regression of B3c.** Of the game's functions, 18 got faster and 8 slower (`_alloc_bubble`,
`_bubble_shoot`, `_bubble_spawn_special`, `_build_sprites`, `_emit_bubbles`, `_shoot`,
`_update_rescue`, `_video_frame`; their worst cases by 0.1 to 2 %). All 8 lose for the same
reason: an indexed access chooses X or Y while it is written, and the code that is written once
does not know that Y already holds the index where the block starts, so it loads X instead, and
a pass after selection cannot exchange the registers without knowing where X is read later.
Taken anyway: writing each function once removes the repeated writing, the consistency checks
and the second attempt from the code generator, which is what B3 is for. The next step, `IndexInY`, wins it back and more.
| `IndexInY`: Y for an index it holds | 324,844 | 527,096 | 316,002 | `LDX p / LDA table,X` becomes `LDA table,Y` where Y holds p on every way in, nothing reads X afterwards and N and Z of the `LDX` are not read. Play is now 53 clocks per tick faster than before B3c. Two of the 8 functions that B3c made slower are faster again; the others choose X for a new index where X held another one, which a pass after selection would have to rename across several instructions. |
