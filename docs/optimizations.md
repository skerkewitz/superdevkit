# Optimizations

What makes dampf's code fast: the budget it has to meet, where it stands, the catalogue of
optimizations (A to N) with what each has shown, and how close dampf can get to hand-written
assembly. Every step was measured on the game's benchmark; the dated measurements are in
[optimizations-log.md](optimizations-log.md). The current numbers are in *Where things stand*
below and nowhere else.

## Budget

Game logic in Blubber Blasen's busiest scene may use at most 60 % of an NTSC frame, after vertical
blank work (DMA for sprites and tiles) and a safety margin: about 0.6 frames per tick. Sound runs
on its own CPU and costs nothing here. How many CPU cycles a frame has is in
[timing.md](timing.md), *Frame numbers*.

What matters in the end is whether the game logic fits this budget, more than the distance to
perfect assembly.

## Where things stand

The game's benchmark (`tools/snesbench` in the game repository, built with
`tools/bench_game.sh`) runs the game code with a fixed input, every PVSnesLib call stubbed out; all
3,610 ticks give the PC's checksums. One NTSC frame is 357,366 master clocks.
Code from MIR with all passes, FastROM (the default). All figures measured on 2026-11-18:

| What | Now |
| --- | --- |
| Play, frames per tick | 0.91 average, worst tick 1.60 |
| Play with the CPU partner | 1.48 average, worst tick 3.18 |
| Title, story, round intro | 0.39, 0.17, 1.39 |
| The whole benchmark | 0.89 |
| dampf's naive code generator | play 4.37 (worst 7.26), with the CPU partner 5.58 (worst 10.53), also FastROM; 4.8 times as slow in play |
| Against Calypsi | 1.81 times as fast: in the play ticks where both play the same scene (711-812), FastROM for both, Calypsi 1.42 frames per tick, dampf 0.79 (`tools/bench_compilers.sh`; Calypsi drifts from the PC from tick 596 on, see [roadmap.md](roadmap.md#open-questions)) |
| Against hand-written `level_solid_px` | 1.20 times the cycles: 871 against 724 master clocks per call, both FastROM (`REPLACE=` in `tools/bench_game.sh`) |
| On the console (playable ROM) | 1.57 frames per tick during a minute of play (3,600 frames, 2,296 ticks), no byte lost in the vertical blank (`tools/check_game_rom.sh`). This includes waiting for the vertical blank and the platform layer, so it is not comparable with the benchmark |

Play is not yet within the budget. With the CPU partner, the worst tick is where it is hardest
to meet. The numbers are measured without block counts from a profile unless they say so.

## Catalogue

Status: built, partly built, or not built. The gain is the latest measured one, in frames per
tick or master clocks per tick during play, as measured at the time; the link goes to its log
entry.

| # | Optimization | Status | Latest measured gain |
| --- | --- | --- | --- |
| A | Static frames in the direct page: locals, parameters and temporaries in fixed direct page places, shared between functions never active at the same time (the call graph is complete and has no recursion, [subset.md](subset.md)) | Partly. Frames as a compiled stack with the leaves in the direct page, places shared within a function by liveness, values every use takes from A not stored; open: the rest of register allocation | 386,306 → 379,855 ([values not stored](optimizations-log.md#values-not-stored-2026-11-17)) |
| B | Width analysis (8/16 bit) and value ranges | Partly. Narrowing from the types, 16-bit pointer offsets, value ranges (see below), switches on bytes in 16 bits, 8 bit kept between a byte load and a byte store and across register accesses; open: full `REP`/`SEP` tracking | 379,855 → 370,620 ([switches on bytes](optimizations-log.md#switches-on-bytes-compare-16-bits-2026-11-17)) |
| C | Inlining and calling convention | Partly. Inlining (`Inline`), arguments computed in the callee's parameter place, returns copied into each path; open: parameters in registers, tail calls | 370,620 → 364,829 ([returns copied](optimizations-log.md#returns-copied-into-each-path-2026-11-17)) |
| D | Data layout: arrays of structs split into one array per field, or D pointed at the current object | Not built | |
| E | 8.8 fixed point: the integer part as a byte access | Partly. A load shifted right by 8 or more is a byte load (`ByteLoads`); open: `<<8` | 1.49 → 1.48 ([byte loads for 8.8 values](optimizations-log.md#a-remembers-every-place-it-equals-byte-loads-for-88-values-2026-11-17)) |
| F | Loops | Partly. Counters in X, induction variables, the loop test moved to the offset, `INC`/`DEC` steps, loop-invariant code (`Licm`), loop rotation; open: unrolling | 356,238 → 329,834, −7.4 % ([loop test on the offset](optimizations-log.md#the-loop-test-on-the-offset-2026-11-17)) |
| G | Branches | Partly. Compare and branch in one, jump threading, short branches wherever they reach, `BRA` for near jumps, bit selections without a branch, rare blocks laid out by a profile (`--block-counts`) | 494,619 → 488,323 with a profile ([blocks by a profile](optimizations-log.md#blocks-laid-out-by-a-profile-2026-11-17)) |
| H | Standard optimizations: folding, identities, branch folding, block merging, value numbering, redundant loads, dead code | Built | 6.12 → 5.17 ([after H](optimizations-log.md#after-the-standard-optimizations-h-2026-11-17)) |
| I | Multiply and divide | Partly. Constants as shifts and adds (in MIR for i32, in A for 8 and 16 bits), powers of two with C's signed rounding; open: the hardware multipliers | 2.29 → 2.07 ([multiplication in A](optimizations-log.md#arrays-indexed-with-x-multiplication-in-a-2026-11-17)) |
| J | Banks and placement | Partly. All code and constant data in banks $80 and up at FastROM speed (`--slowrom` switches it off), registers at constant addresses as long addresses | 1.31 → 1.10 ([FastROM](optimizations-log.md#fastrom-2026-11-17)) |
| K | Peephole | Partly. A remembers every place it equals, `INC`/`DEC` in place, `STZ`, no `CMP #0` where N and Z already describe A | 391,423 → 386,306 ([no `CMP #0`](optimizations-log.md#no-cmp-0-where-the-flags-are-set-2026-11-17)) |
| L | `switch` as a jump table (`JMP (abs,X)`) | Not built | |
| M | Copy and fill with `MVN`/`MVP` or DMA | Not built | |
| N | Values in X and Y | Partly. Loop counters in X (also next to an index in Y), a second index in Y, loop-invariant indexes in Y, the index through a pointer in Y (rows of 2D arrays included), what A, X and Y hold known across blocks | 329,834 → 326,596 ([index in Y](optimizations-log.md#a-pointer-plus-an-index-the-index-in-y-2026-11-17)) |

The expectation before any of this was a factor of 3 to 6 over the naive code generator; what
play measures now is in *Where things stand*.

### Value ranges (B)

`dampf.mir.Ranges` computes an interval for every integer value of a function from constants, the
operations (masks, shifts, division and remainder included), the conditions on the path (against
a constant and between two values) and loops (widening, then narrowing again). `ParamRanges` gives
a parameter the join of its arguments at all call sites, for optimization only. `DAMPF_RANGE`
adds a range at one point ([hints.md](hints.md)). The ranges are used by `RangeNarrow` (16 bits
instead of 32, comparisons the range decides, unsigned operations and comparisons for values that
are never negative), by indexed addressing (an index that is never negative), by induction
variables and by the loop bounds of the timing analysis. The tests check every value the MIR
interpreter computes against its range (`dampf mir -O --check-ranges`).

Not built: ranges from array accesses, return values and enums; page-aligned arrays, `switch`
jump tables and `DAMPF_TABLE`; declared subrange types. The design is in
[subrange-types.md](subrange-types.md).

### Pointer compression

A smaller form than the one in [subrange-types.md](subrange-types.md), without a points-to
analysis. `PointerOffsets`, within a function: a pointer φ whose values all point into the same
global object or frame slot (`o = oamMemory; ... o += 4`) becomes the object plus a 16-bit
offset, and loads and stores through it index the object with X. `PointerParams`, across calls: a
pointer parameter that every call fills with a pointer into the same global becomes a 16-bit
offset; callers pass two bytes instead of four, and the callee adds the object back.

Both rely on C's rule that a pointer stays within its object or just past its end, so the offset
lies between 0 and the object's size; the new φs and parameters carry that range. Pointers stored
in memory stay 4 bytes, there is no 8-bit index and no `NULL` as `0xFF`, and a pointer that may
point into more than one object is left as it is. Casts between integers and pointers stay
allowed (the platform layer uses them); this form does not need the rule against them.

## Tried and rejected

- Counting down to zero: exact, faster on the title, slower in play and over the whole benchmark,
  because copying the bound in at every loop entry costs more than the comparisons it saves in
  the game's short loops ([log](optimizations-log.md#counting-down-tried-2026-11-17)).
- An optimistic start for what the registers hold across blocks: fewer `LDY` in `separate`, but
  not in the hot block, and the game no faster
  ([log](optimizations-log.md#loop-invariant-code-2026-11-17)).
- Keeping the pointer in `dampf_ptr` across blocks: nothing measurable, since pointer compression
  left almost no pointer accesses in the hot code
  ([log](optimizations-log.md#what-a-x-and-y-hold-across-blocks-a-second-index-in-y-2026-11-17)).
- Bounding a byte by a condition on its zero extension (2026-11-18): `i < n` on bytes is compared
  as `zext(i) < zext(n)`, so `i` itself gets no bound, and the increment of a byte loop counter is
  written in 8 bits (`SEP`, `INC`, `REP`: 11 cycles, 31,812 times in the play ticks) to keep the
  high byte clean. Passing the bound on to `i` changed one of the game's 27 such increments: the
  range analysis keeps 8-bit values as signed intervals, and with a byte-sized `n` the counter is
  0..254, which is not one. In a test it even cost (an 8-bit `INC` inside an 8-bit stretch that
  exists anyway is cheaper than a 16-bit one). Left out; it needs unsigned ranges for bytes, or
  byte loop counters widened to 16 bits, for about 0.3 %.
- Inlining larger functions with a single call site (2026-11-18): raising that limit from 150
  instructions to 200 made play slower (290,433 → 294,651 master clocks per tick), to 400 slower
  still (297,979), although no call is copied. Why is not clear: every frame access of the play
  ticks still goes to the direct page. The limit stays at 150.
- `PointerRemat` for φ uses (2026-11-18): `best = v` in a loop keeps `v` computed in every round.
  Copying the chain to the end of the predecessor instead, with the condition that the chain
  then folds away completely (else the copy comes on top), made play slower (286,947 → 288,149
  master clocks per tick). The condition alone, without φ uses, gave the same: copies that leave
  part of the chain behind still pay off in the game, and the condition prevented them. Left as
  it was.
- Block order, the loop exit heuristic (2026-11-18): after the latch, preferring a successor inside
  the loop over one that leaves it changed nothing measurable (284,286 → 284,347); the true side
  of a loop's test is the inside already.
- Jump tables for `switch` (considered, 2026-11-18): a table dispatch (subtract, range check,
  `ASL`, `TAX`, `JMP (table,X)`) costs about 24 cycles, as much as the chain of compares over three
  to four cases, needs X, and gives the timing analysis an indirect jump. The game's switches are
  short; after the branch relaxation a matching case costs a taken `BEQ`.

## Ideas not yet built

The open parts of the catalogue (D, L, M, the hardware multipliers, parameters in registers and
tail calls, `<<8` as a byte access, full `REP`/`SEP` tracking, unrolling); page-aligned arrays with
8-bit index registers; a points-to analysis for pointers into several objects or loaded from
memory; induction variables for counters that count down, and an induction variable as the index
in Y (the range analysis cannot yet see that the offset never wraps); the transforming hints
`DAMPF_TABLE`, `DAMPF_PIN_X` and `DAMPF_SPLIT` ([hints.md](hints.md); of the hints only
`DAMPF_RANGE` and `DAMPF_BUDGET` are built, and neither transforms code); more profile-guided
optimization and superoptimization (below). Which come next, by likely gain and effort, is in
[roadmap.md](roadmap.md#what-is-left-in-m3-and-m4).

## How close can dampf get to hand-written assembly?

| Code generator | Cycles compared with good hand-written assembly |
| --- | --- |
| 816-tcc (PVSnesLib) | probably 5–10 × (observed: very slow, needed workarounds) |
| Calypsi | about 1.1 × on `level_solid_px` (an older run) |
| dampf, naive code generator | 4.5 × on `level_solid_px` ([log](optimizations-log.md#the-first-reference-function-level_solid_px-2026-11-17)) |
| dampf from MIR, today | 1.20 × on `level_solid_px` ([Where things stand](#where-things-stand)) |
| dampf after the optimizations, hot loops, without hints | estimated 1.2–1.5 × |
| dampf after the optimizations, hot loops, with hints | estimated 1.1–1.2 ×, in places equal |
| dampf, the rest of the code | equal to or better than what a human writes there in practice |

A skilled assembly programmer uses what the C code does not say:

1. Data chosen for the CPU: a state flag in bit 7, so `BIT` and `BMI` test it without a compare,
   or a table in ROM instead of a calculation.
2. Conventions across functions: "X holds the current bubble during the update", "this routine
   runs in 8-bit mode".
3. Flags as data: the carry as a yes/no result, the overflow flag after `BIT` as a second bit.
4. Instruction tricks: `XBA`, moving the direct page with `TCD`, `PEI`/`PEA` for fast copies.

Hints ([hints.md](hints.md)) and new rules close most of this. A human sometimes invents a trick on
the spot, while dampf applies only what it has been taught; the reference benchmarks keep teaching
it. In return dampf optimizes every line, where people tune the two or three hottest routines; it
allocates the direct page for the whole program, which almost nobody does cleanly by hand; it
re-optimizes on every build after a design change; it inlines without caring about maintainable
output; and it can search exhaustively for short sequences.

### Reference assembly benchmarks

3–5 hot functions of the game are written by hand in 65816 assembly as reference versions, the
yardstick and not the game's code. Candidates: bubble movement and pushing, the chain reaction
when bubbles pop, collision against tiles, building the sprite table; the first profile of the
Calypsi build points the same way ([game-findings.md](game-findings.md)). The first is
`reference/level_solid_px.s`, linked with `dampf build --replace`; with it all benchmark ticks
still match the PC. Its first version was wrong, and the benchmark's checksums caught it:
hand-written code needs the same oracle as compiled code.

Each reference and dampf's version of the same function run in the cycle-counting emulator on the
same inputs, which gives a ratio per function, tracked over the milestones. Where the reference is
faster, the reason becomes a hint or an optimization rule. If dampf stays too far behind on one
function, the reference can be linked in its place, with a declared calling convention and direct
page use so the timing analysis still knows what it does; only when measurement demands it.
Hand-written assembly for the whole game was tried years ago and failed.

## Knowing the machine

dampf knows exactly where its code runs: a Ricoh 5A22 at 60 Hz (NTSC), 128 KB of WRAM, no
operating system, no other software. That allows what a compiler for general hardware cannot do.

| Knowledge | What dampf does with it |
| --- | --- |
| The cost of every memory access, in master cycles (21.477 MHz on NTSC): 6 for FastROM and most I/O registers, 8 for WRAM and SlowROM, 12 for a few old I/O registers | The cost model is exact. Constant tables read faster from FastROM than from WRAM, and every instruction byte is fetched from ROM, so code size counts directly in cycles. |
| Two hardware multipliers: the CPU's (8 × 8 bit unsigned, result after 8 CPU cycles; also 16 / 8 bit unsigned division, after 16) and the PPU's (16 × 8 bit signed, result at once, unavailable only while Mode 7 is drawn) | The game does not use Mode 7, so a multiplier with an immediate result is always there. Per multiplication the cheapest: shifts and adds for constants, the PPU for signed 16 × 8, the CPU unit with useful work in its latency. Signed division needs sign handling around the unsigned divider. |
| DMA units | Large `memset`/`memcpy` as DMA (WRAM cleared from a constant byte in ROM through `$2180`), from the size where it pays off. |
| Fixed addresses, no operating system | `array[3]` is a fixed address. No foreign code expects registers preserved, no external calling convention. |
| dampf controls the interrupts | It sees what the NMI and IRQ handlers touch, saves only that, and can give them their own direct page. |
| No dynamic memory | Total memory and the maximum stack depth are known at compile time; a stack overflow cannot happen. |
| Recorded game play (the replays) | Profile-guided optimization, below. |

A good SNES programmer knows all of this too, so it closes no gap to hand-written code. dampf
applies it everywhere, not only in the routines a human tunes, and exact timing
([timing.md](timing.md)) and profile-guided optimization go beyond what is practical by hand.

### Profile-guided optimization with the replays

The game's replays are recordings of real play. Run in the cycle-counting emulator, they show how
often each branch is taken and which functions are hot. Built: the layout of the blocks by a
profile's block counts (G). Open: inlining hot functions more readily, and spending code size for
speed where it matters. Hot code and its tables belong in FastROM, which today holds all of
dampf's code and constant data anyway. Many `DAMPF_LIKELY`/`DAMPF_UNLIKELY` hints become
unnecessary.

## Superoptimization for very short sequences

For short, very hot sequences (about 5–8 instructions), dampf can search instead of following
rules: generate candidate sequences up to a length, run each in the cycle-counting emulator, and
keep the fastest that gives the same result. With 8-bit inputs equivalence can be checked
exhaustively (256 inputs for one byte, 65,536 for two). Optional and late (M4).
