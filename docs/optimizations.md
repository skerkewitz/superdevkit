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

### dampf's code generator from MIR, before any pass (2026-11-17)

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

### After width narrowing (B), 2026-11-17

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

### After constant multiplication and division (I), 2026-11-17

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

### After the standard optimizations (H), 2026-11-17

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

### Compare and branch in one, 2026-11-17

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

### Value ranges (the rest of B), 2026-11-17

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

### Fewer loads and copies in the code generator, 2026-11-17

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

### Frames in the direct page (first part of A), 2026-11-17

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

### Shared places within a function, 2026-11-17

Values whose lifetimes do not overlap now share a place in the frame (`dampf.backend.FrameLayout`:
liveness, an interference graph, greedy colouring by byte ranges). The frames of the whole game
shrink to 214 bytes, so every value of every function is in the direct page.

| State | naive | before | shared places |
| --- | --- | --- | --- |
| title | 1.94 | 1.03 | 0.98 |
| play | 5.17 (worst 8.58) | 2.90 (worst 4.93) | 2.72 (worst 4.64) |
| play, CPU partner | 6.55 (worst 12.23) | 3.68 (worst 6.92) | 3.45 (worst 6.38) |

### Inlining and smaller things, 2026-11-17

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

### Layout, results in A, shared places for φs, 2026-11-17

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

### Arrays indexed with X, multiplication in A, 2026-11-17

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

### Byte loads and more results in A, 2026-11-17

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

### φs written before a branch, comparisons in canonical form, 2026-11-17

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

### Fewer stores: scheduling and more hand-overs in A, 2026-11-17

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

### Induction variables, 2026-11-17

`dampf.mir.Induction`: in a loop whose counter grows by a constant per round, a product of the
counter and a constant (the offset of `array[i]` in an array of structs, `i * 23` for the
bubbles) becomes a variable of its own that grows by the constant times the step: an addition per
round instead of a multiplication. Exact modulo 2^16; an 8-bit counter must not wrap around on
the way back into the loop, which the value ranges show. The new φ carries the range of the
product in the loop head (the counter's value after the last round included), because indexed
addressing needs to know it is never negative; the first version took the range inside the loop
body, and the tests' range check caught it at once.

Play 1.61 → 1.57 frames per tick (worst 2.74), with the CPU partner 2.29 → 2.26 (worst 4.50).

### Lazy byte masks, 2026-11-17

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

### Pointer compression, 2026-11-17

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

### Loop counters in X, 2026-11-17

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

### Where the time goes, by instruction (2026-11-17)

`dampf profile ... --instructions` (for the benchmark's play ticks: `INSTRUCTIONS=1
tools/profile_game.sh bench`) adds up the cycles per instruction and addressing mode. In the game code at 1.50 frames per tick: `sta dp` 18.8 %, `lda dp` 16.5 %,
`cmp #imm` 7.4 %, `lda abs,x` 7.2 %, `jmp abs` 5.6 %, `ldx dp` 4.8 %, `and #imm` 4.7 %,
`lda #imm` 3.8 %, `adc #imm` 3.5 %, `bne` 3.2 %, `clc` 3.1 %. Values going to and from the direct
page are still a third of the time; a `jmp` runs almost a million times in the 800 ticks, mostly
where a branch cannot reach its target because a large loop body lies between them.

### A remembers every place it equals, byte loads for 8.8 values, 2026-11-17

The code generator knew one place A held. After `LDA x` / `STA y` it knew only `y` and loaded
`x` again; now it keeps every place A equals (a store adds its place, a write to a place drops it
and its byte neighbours): 409 such reloads in the game's code. Play 537,567 → 533,388 clocks
per tick.

The integer part of an 8.8 value (`PX(b->x)`, `b->x >> 8`) was a 16-bit load, `XBA` and a mask.
`ByteLoads` (catalogue E) makes a 16-bit load whose only use shifts it right by 8 or more a byte
load of the high byte, widened again: `LDA _g_bubbles+20,X` and the mask. Volatile loads stay as
they are. Play 533,388 → 529,880 clocks per tick (1.48 frames).

### Short branches where they reach, 2026-11-17

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

### INC and DEC for counter steps, 2026-11-17

A step `x + 1` or `x - 1` whose result goes back to `x`'s place (a loop counter, `++counter`)
was `CLC`, `LDA`, `ADC #1`, `STA`: 13 cycles. Now it is `INC` or `DEC` on the place: 7 cycles,
the same 16 bits as `ADC #1` (an i8 minus 1 differs in the high byte only, which an i8 that needs
no mask may have any). It needs `x` really in its place: a value handed on in A has a place of
that name but was never stored there, which the execution tests caught (`locals.c`). Play
524,771 → 510,797 clocks per tick (1.43 frames), title 0.60 → 0.58.

### STZ for zeros, 2026-11-17

A zero stored to a fixed address (direct page, absolute, X-indexed; not long or through a
pointer) is `STZ` instead of `LDA #0` and `STA`, and so is a zero a φ gets (a counter's start,
unless A holds 0 already). Play 510,797 → 510,007 for the stores, → 503,373 clocks per tick with
the φs (1.41 frames).

### Summary of the code from MIR so far (2026-11-17, the latest measurement)

Measured after the range analysis learned to narrow after widening: a loop of more than 24
rounds (`for (i = 0; i < 768; i++)`) used to give its counter the type's whole range, which kept
it from being narrowed and from indexed addressing as nonnegative. Narrowing again after the
widened fixpoint gives it 0..768. Play 1.53 → 1.50, title 0.68 → 0.60. Then A's places and the
byte loads, the short branches, INC/DEC and STZ (sections above): play 1.41.

| State | naive | from MIR, no passes | now |
| --- | --- | --- | --- |
| title | 1.94 | 12.21 | 0.56 |
| story | 0.70 | 3.56 | 0.24 |
| round intro | 5.76 | 12.13 | 1.83 |
| play | 5.17 (worst 8.58) | 18.93 | 1.41 (worst 2.43) |
| play, CPU partner | 6.55 (worst 12.23) | 23.13 | 2.09 (worst 4.25) |
| `level_solid_px`, per call | 3,849 | 6,648 | 1,208 clocks |

Calypsi's 2.72 frames per tick during play (on an older version of the game) is passed; a fair
comparison still needs both compilers on the same version. The hand-written `level_solid_px` takes
856 clocks, so for that function dampf is now 1.4 times hand-written assembly.

### Where the naive code spends its time (2026-11-17)

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

## Catalogue

| # | Optimization | What it does | Expected gain | Prerequisite | Measured gain | Status |
| --- | --- | --- | --- | --- | --- | --- |
| A | Static frames in the direct page | Locals, parameters and temporaries get fixed direct page slots, shared between functions that are never active at the same time. Possible because the call graph is complete and has no recursion. No stack frames, no stack-relative access. | Very high; probably the largest single gain | complete call graph, no recursion (given, see [subset.md](subset.md)) | play 3.85 → 2.90 frames per tick (code from MIR) | in progress: shared frames (compiled stack) and shared places within a function; all of the game's values fit the direct page; open: values in registers |
| B | Width analysis (8/16 bit) | Choose the smallest width per value, track `REP`/`SEP` as processor state, minimise switches | High, especially for `uint8_t`-heavy code | H | play 18.93 → 8.16 frames per tick (code from MIR) | in progress: narrowing from the types and 16-bit pointer offsets are built; value ranges, `REP`/`SEP` tracking open |
| C | Inlining and calling convention | Inline small functions, pass parameters in A/X/Y or the direct page, `JMP` instead of `JSR`+`RTS` for tail calls | High with many small helpers | call graph | | open |
| D | Data layout | Split arrays of structs into one array per field (index in X/Y), or point D at the current object | High in the loops over bubbles and enemies | | | open |
| E | 8.8 fixed point | `>>8`, `<<8` and comparisons of the integer part as byte accesses instead of shifts | Medium to high; the game uses 8.8 everywhere | B | play 1.49 → 1.48 frames per tick | in progress: a load shifted right by 8 or more is a byte load (`ByteLoads`); `<<8` open |
| F | Loops | Counter in X/Y, count down to zero, hoist invariant code, unroll small loops (by hint) | Medium | H | | open |
| G | Branches | Branch on flags instead of materialising 0/1, common path falls through, correct branch distances | Medium | | play 1.48 → 1.47 frames per tick | in progress: compare and branch in one, short branches wherever they reach, `BRA` for near jumps; the layout of rare blocks open |
| H | Standard optimizations | Constant folding and propagation, dead code removal, common subexpressions | Medium; mostly a prerequisite for A–G | IR in SSA form | play 6.12 → 5.17 frames per tick (code from MIR) | built: folding, identities, branch folding, block merging, value numbering, redundant loads, dead code; loop-invariant code open |
| I | Multiply and divide | Constants as shifts and adds, hardware multiplier with useful work during its latency, division by powers of two with correct signed rounding | Medium, depends on the code; **measured: 37 % of the naive code's time is the runtime multiplication, mostly struct array indexing by a constant size** | range analysis for signed division | play 8.16 → 6.12 frames per tick (code from MIR) | constants built (shifts and adds, powers of two); hardware multiplier open |
| J | Banks and placement | Keep the data bank register right, 16-bit instead of 24-bit addresses, hot code in FastROM | Small to medium | pointer width analysis | | open |
| K | Peephole | Remove redundant loads and stores, `STZ`, `INC`/`DEC` on memory | Small, but everywhere | | play 1.47 → 1.41 frames per tick (`INC`/`DEC`, `STZ`) | in progress: A remembers every place it equals, `INC`/`DEC` for steps in place, `STZ` |
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

### Measured so far

| Function | dampf, naive | 816-tcc | Calypsi | Hand-written | dampf / hand-written |
| --- | --- | --- | --- | --- | --- |
| `level_solid_px` (2026-11-17) | 3,849 | 2,449 | 931 | 856 | **4.5** |

Master clocks per call, from the benchmark's micro part (1,000 calls per pass, the bare loop
subtracted); the 816-tcc and Calypsi numbers are from older runs on the game's side. The
hand-written version is `reference/level_solid_px.s`, linked with `--replace`; with it, all 3,610
benchmark ticks still match the PC. Over the whole benchmark this one function makes play 5 %
faster (5.17 → 4.92 frames per tick). On a small function like this one, Calypsi already comes
close to hand-written code.

The first version of the reference was wrong: it checked the rows below the field before the
walls, the opposite order of the C, and returned "open" for a position both outside the walls and
below the field. The game never asks for such a position, but the benchmark's micro part does, and
its checksums caught it. Hand-written code needs the same oracle as compiled code.

### The plan

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
