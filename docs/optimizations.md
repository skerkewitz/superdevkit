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

Since 2026-11-19 dampf is measured on its examples: `dev/bench_examples.sh` gives the work per
tick of each (master cycles without the main loop's waiting; it counts a frame as 262 lines of
1,364, 357,368) and compares a
change with a baseline on all of them ([testing.md](testing.md)). Code from MIR, FastROM, measured
on 2026-11-28:

| Example | Work per tick, average | Busiest 60 ticks | In dampf's runtime |
| --- | --- | --- | --- |
| Er-Tüp | 129,061 | 252,268 | 1.1 % |
| Schach | 364,621 | 513,450 | 1.2 % |
| Steinschlag | 71,544 | 132,150 | 1.5 % |
| Mandelbrot | 74,622 | 87,493 | 62.7 % |
| Catchme | 45,831 | 143,012 | 20.4 % |
| Hello | 80,270 | 81,272 | 50.0 % |
| Mode 7 | 10,604 | 10,634 | 49.2 % |
| modes | 53,386 | 121,709 | 3.4 % |
| Gruft | 130,683 | 435,653 | 7.3 % |

On 2026-11-20, before the runtime used the CPU's multiplier, the naive code generator did 3.0
times the work on the geometric mean of all seven examples, from 1.26 times (Mandelbrot, whose
time was the runtime's 32-bit multiplication) to 6.4 times (Er-Tüp).

Blubber Blasen itself, on 2026-11-27: dampf 0.10.1 built a copy of the game on the kit's console
API, and two scripted inputs played it in dampf's emulator, its work per tick counted as the
examples' is ([log](optimizations-log.md#the-games-three-hottest-functions-by-hand-2026-11-27-on-blubber-blasen)):

| Play, frames of work per play tick | Average | Busiest tick |
| --- | --- | --- |
| Solo (ticks 180 to 6,000) | 0.594 | 1.48 |
| With the CPU partner (ticks 270 to 6,000) | 0.932 | 2.11 |

These are not comparable with the figures of 2026-11-18 below: those came from the game's own
benchmark in Mesen, with another input and every PVSnesLib call stubbed out; these come from the
game's code of today, which has changed in between, on the kit's console API with nothing
stubbed, from other inputs and in dampf's emulator.

Before, dampf was measured on Blubber Blasen's benchmark (`tools/snesbench` in the game
repository), which ran the game code with a fixed input, every PVSnesLib call stubbed out; all
3,610 ticks gave the PC's checksums. Its last figures follow.
Code from MIR with all passes, FastROM (the default). All figures measured on 2026-11-18; all
after the optimizations on the branch `optimise`
([log](optimizations-log.md#optimizations-on-the-branch-optimise-2026-11-18)):

| What | Now |
| --- | --- |
| Play, frames per tick | 0.79 average, worst tick 1.42 |
| Play with the CPU partner | 1.36 average, worst tick 3.03 |
| Title, story, round intro | 0.37, 0.16, 1.41 |
| The whole benchmark | 0.81 |
| dampf's naive code generator | play 4.37 (worst 7.26), with the CPU partner 5.58 (worst 10.53), also FastROM; 5.5 times as slow in play |
| Against Calypsi | 2.14 times as fast: in the play ticks where both play the same scene (102 ticks, up to 812), FastROM for both, Calypsi 1.42 frames per tick, dampf 0.66 (Calypsi drifts from the PC from tick 596 on, see [history.md](history.md#answered-questions)) |
| Against hand-written `level_solid_px` | 1.14 times the cycles: 827 against 724 master clocks per call, both FastROM |
| In Mesen (playable ROM) | 1.16 frames per tick during a minute of play (3,600 frames, 3,098 ticks), no byte lost in the vertical blank. This includes waiting for the vertical blank and the platform layer, so it is not comparable with the benchmark |

Play is not yet within the budget. With the CPU partner, the worst tick is where it is hardest
to meet. The numbers are measured without block counts from a profile unless they say so.

## Catalogue

Status: built, partly built, or not built. The gain is the latest measured one, in frames per
tick or master clocks per tick during play, as measured at the time; the link goes to its log
entry.

| # | Optimization | Status | Latest measured gain |
| --- | --- | --- | --- |
| A | Static frames in the direct page: locals, parameters and temporaries in fixed direct page places, shared between functions never active at the same time (the call graph is complete and has no recursion, [subset.md](subset.md)) | Partly. Frames as a compiled stack with the leaves in the direct page, places shared within a function by liveness, values every use takes from A not stored, a value in X or Y for the common part of a loop (`RegisterChoice`), a store only a rare way reads moved onto it (`DeadStores`); open: a model of the whole function (roadmap M12, row 2) | 386,306 → 379,855 ([values not stored](optimizations-log.md#values-not-stored-2026-11-17)) |
| B | Width analysis (8/16 bit) and value ranges | Partly. Narrowing from the types, 16-bit pointer offsets, value ranges (see below), switches on bytes in 16 bits, 8 bit kept between a byte load and a byte store and across register accesses, short 16-bit stretches between byte stores in 8 bits (`ByteStretches`), loops over bytes in 8 bits from entry to exit (`ByteLoops`); open: full `REP`/`SEP` tracking | 379,855 → 370,620 ([switches on bytes](optimizations-log.md#switches-on-bytes-compare-16-bits-2026-11-17)) |
| C | Inlining and calling convention | Partly. Inlining (`Inline`), arguments computed in the callee's parameter place, returns copied into each path; open: parameters in registers, tail calls | 370,620 → 364,829 ([returns copied](optimizations-log.md#returns-copied-into-each-path-2026-11-17)) |
| D | Data layout: arrays of structs split into one array per field, or D pointed at the current object | Not built | |
| E | 8.8 fixed point: the integer part as a byte access | Partly. A load shifted right by 8 or more is a byte load (`ByteLoads`); open: `<<8` | 1.49 → 1.48 ([byte loads for 8.8 values](optimizations-log.md#a-remembers-every-place-it-equals-byte-loads-for-88-values-2026-11-17)) |
| F | Loops | Partly. Counters in X, induction variables, the loop test moved to the offset, `INC`/`DEC` steps, loop-invariant code and loads (`Licm`), loop rotation; open: unrolling | 356,238 → 329,834, −7.4 % ([loop test on the offset](optimizations-log.md#the-loop-test-on-the-offset-2026-11-17)) |
| G | Branches | Partly. Compare and branch in one, a signed comparison of two 16-bit values by the sign of one subtraction (N xor V; [log](optimizations-log.md#signed-comparisons-by-subtraction-2026-11-28-on-the-examples-and-blubber-blasen)), `a > b ? a - b : b - a` as one subtraction and a negation (`AbsDiff`; [log](optimizations-log.md#the-absolute-difference-as-one-subtraction-2026-11-28-on-the-examples-and-blubber-blasen)), jump threading, short branches wherever they reach, `BRA` for near jumps, bit selections without a branch, tests against neighbouring constants as one range check (`RangeChecks`), the latch and single-predecessor successors laid out first, rare blocks laid out by a profile (`--block-counts`) | 494,619 → 488,323 with a profile ([blocks by a profile](optimizations-log.md#blocks-laid-out-by-a-profile-2026-11-17)) |
| H | Standard optimizations: folding, identities, branch folding, block merging, value numbering, redundant loads, dead code, loops that do nothing and are proved to end (`DeadLoops`), comparisons computed again next to each branch that uses them (`SinkCompares`), index parameters passed as the offset into their struct array (`IndexParams`) | Built | 6.12 → 5.17 ([after H](optimizations-log.md#after-the-standard-optimizations-h-2026-11-17)) |
| I | Multiply and divide | Partly. Constants as shifts and adds (in MIR for i32, in A for 8 and 16 bits), powers of two with C's signed rounding; two variables on the CPU's 8 x 8 multiplier (2026-11-22; two bytes without a call). Open: the PPU's multiplier, the hardware divider | 2.29 → 2.07 ([multiplication in A](optimizations-log.md#arrays-indexed-with-x-multiplication-in-a-2026-11-17)) |
| J | Banks and placement | Partly. All code and constant data in banks $80 and up at FastROM speed (`--slowrom` switches it off), registers at constant addresses as long addresses | 1.31 → 1.10 ([FastROM](optimizations-log.md#fastrom-2026-11-17)) |
| K | Peephole | Partly. A remembers every place it equals, `INC`/`DEC` in place, `STZ`, no `CMP #0` where N and Z already describe A, `TAX` for a reload of a stored value, shifts as a mask, the high byte as a byte load (`Peephole`), no `CLC` where the carry is known clear (`KnownCarry`) | 391,423 → 386,306 ([no `CMP #0`](optimizations-log.md#no-cmp-0-where-the-flags-are-set-2026-11-17)) |
| L | `switch` as a jump table (`JMP (abs,X)`) | Built (M10). A `switch` on an 8- or 16-bit value whose table costs fewer cycles than its compares on average over the cases, with at most four entries per case (ADR-6FM7MWGX): from 4 cases without a range check, 6 from 0, 7 far from 0 or negative; a first case 0 tested before the table. Open: a switch in a loop whose counter lives in X, 32-bit values, cases weighed by a profile | Ertüp's busiest tick 262,654 → 260,166 master clocks ([jump tables](optimizations-log.md#switch-as-a-jump-table-2026-11-24-on-the-examples)) |
| M | Copy and fill with `MVN`/`MVP` or DMA | Not built | |
| N | Values in X and Y | Partly. Loop counters in X (also next to an index in Y, and with their stepped value read after the step: a `do` loop's test, `i - 1` going down; compared with a constant or a 16-bit value in its place), a second index in Y, loop-invariant indexes in Y, the index through a pointer in Y (rows of 2D arrays included), what A, X and Y hold known across blocks, a load of what another register holds as a transfer, a place in X or Y for the part of a loop that leaves the register alone | 329,834 → 326,596 ([index in Y](optimizations-log.md#a-pointer-plus-an-index-the-index-in-y-2026-11-17)) |

The expectation before any of this was a factor of 3 to 6 over the naive code generator; what
play measures now is in *Where things stand*.

### Value ranges (B)

`dampf.mir.Ranges` computes a signed and an unsigned interval for every integer value of a
function from constants, the operations (masks, shifts, division and remainder included), the
conditions on the path (against a constant, `x != c` cutting c off an end, and between two
values; a cast made before a condition on its operand follows the operand after it) and loops (widening, then narrowing again; a loop counter gets the interval its counted
loop gives, `CountedLoops`). `ParamRanges` gives
a parameter the join of its arguments at all call sites, for optimization only. `DAMPF_RANGE`
adds a range at one point ([hints.md](hints.md)). The ranges are used by `RangeNarrow` (16 bits
instead of 32, comparisons the range decides, unsigned operations and comparisons for values that
are never negative), by indexed addressing (an index that is never negative), by induction
variables, by the loop bounds of the timing analysis and by the jump tables of `switch` (no range
check where the value stays within the table). The tests check every value the MIR
interpreter computes against its range (`dampf mir -O --check-ranges`).

An index into an array needs no range for its access (ADR-6F8NBESR), but the value ranges do not
learn from that access. Not built: ranges from array accesses, return values and enums;
page-aligned arrays and `DAMPF_TABLE`; declared subrange types. The design is in
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

- `restrict`, or a no-alias hint (2026-11-25): with every pointer treated as `restrict`, the most
  any such hint could promise, the examples' work per tick changed by 0.01 % at most, Steinschlag
  got slower; pointer compression and inlining already know where the pointers point
  ([log](optimizations-log.md#every-pointer-as-restrict-the-ceiling-2026-11-25-on-the-examples)).
- Counting down to zero: exact, faster on the title, slower in play and over the whole benchmark,
  because copying the bound in at every loop entry costs more than the comparisons it saves in
  the game's short loops ([log](optimizations-log.md#counting-down-tried-2026-11-17)).
- An optimistic start for what the registers hold across blocks: fewer `LDY` in `separate`, but
  not in the hot block, and the game no faster
  ([log](optimizations-log.md#loop-invariant-code-2026-11-17)).
- Keeping the pointer in `dampf_ptr` across blocks: nothing measurable, since pointer compression
  left almost no pointer accesses in the hot code
  ([log](optimizations-log.md#what-a-x-and-y-hold-across-blocks-a-second-index-in-y-2026-11-17)).
- Inlining larger functions with a single call site (2026-11-18): raising that limit from 150
  instructions to 200 made play slower (290,433 → 294,651 master clocks per tick), to 400 slower
  still (297,979), although no call is copied. Why is not clear: every frame access of the play
  ticks still goes to the direct page. The limit for a single call site stays at 150. A candidate
  cause, found on 2026-11-20: inlining dropped the range of a loop's derived index variable, so
  an inlined loop indexed through a 24-bit pointer ([log](optimizations-log.md#ranges-kept-when-inlining-2026-11-20-on-the-spelling-families-and-the-examples));
  not measured again, since the game's code is no longer here. On the examples (2026-11-21, K4
  of [archive/compiler-flaws.md](archive/compiler-flaws.md)) 600 for every single call site made
  Ertüp and Mandelbrot slower; 600 only for a call in a loop, to a callee without a loop of its
  own, is built.
- `PointerRemat` for φ uses (2026-11-18): `best = v` in a loop keeps `v` computed in every round.
  Copying the chain to the end of the predecessor instead, with the condition that the chain
  then folds away completely (else the copy comes on top), made play slower (286,947 → 288,149
  master clocks per tick). The condition alone, without φ uses, gave the same: copies that leave
  part of the chain behind still pay off in the game, and the condition prevented them. Left as
  it was.
- Block order, the loop exit heuristic (2026-11-18): after the latch, preferring a successor inside
  the loop over one that leaves it changed nothing measurable (284,286 → 284,347); the true side
  of a loop's test is the inside already.
- Inlining up to 80 instructions (2026-11-18): play 282,246 master clocks per tick (from
  282,498), but the benchmark's checksums differ from the PC: the larger inlining makes a
  function the code generator gets wrong. The bug was found and fixed on 2026-11-19
  (`exec/regress_branch_phis.c`); the limit stays at 60, and a larger one was not measured again.
- Jump tables for `switch` (considered, 2026-11-18): a table dispatch (subtract, range check,
  `ASL`, `TAX`, `JMP (table,X)`) costs about 24 cycles, as much as the chain of compares over three
  to four cases, needs X, and gives the timing analysis an indirect jump. The game's switches are
  short; after the branch relaxation a matching case costs a taken `BEQ`.
  Built in M10 after all (row L), for the switches where it costs fewer cycles than the compares:
  with the value's range the check often goes, and the dispatch is 10 to 20 cycles.
- Dropping `findForwards` (the code generator's plan for values handed on in A) or `Schedule` (a
  value moved next to its only user) and leaving their work to `RedundantLoads` and `DeadStores`
  on the LIR (2026-11-22): without `findForwards` the examples were 1.6 % slower, without
  `Schedule` 0.5 %, without both 2.1 %, and the copy loops of the spelling families up to 77 %
  slower ([log](optimizations-log.md#without-findforwards-or-schedule-measured-2026-11-22)).
- `BIT` for bit tests (measured, not built, 2026-11-23): `BIT #mask` costs what `AND #mask` costs,
  and the few places where it would gain add up to at most 0.07 % of the examples' work on average
  and 0.4 % on Mode 7
  ([log](optimizations-log.md#bit-for-bit-tests-measured-and-not-built-2026-11-23-on-the-examples)).
- Rewriting a list of indexes into a list of byte offsets (2026-11-23): the whole gap of
  `slow-index-list` is the multiplication by the element's size (17,505 cycles with indexes, 15,385
  with offsets, 15,493 for the counter), so a pass could close it. Not built: no example has the
  pattern and the game's sorted list is no longer a goal. The C guide has the form
  (`same-list-of-offsets`).
- The index registers chosen by `RegisterChoice` alone, without `findXCounters` and `findYIndexes`:
  nine versions, none as good as selection's choice on the pair loops and the worst cases
  ([log](optimizations-log.md#the-index-register-choice-tried-in-registerchoice-2026-11-23)).
- Parts of loops in registers where they make a worst case larger (2026-11-27): judged by the
  weights from the branches alone, they gained more on average (the examples 1.8 % less work
  instead of 1.4 %, Er-Tüp 4.9 % instead of 2.9 %), and 40 worst cases grew by up to 6.9 % (the
  spelling family `pairs`). A budget is a worst case, so a part is now taken only where no
  function's worst case grows
  ([log](optimizations-log.md#the-parts-of-loops-without-a-larger-worst-case-2026-11-27-on-the-examples)).
- A register's loads and stores for part of a loop on a way of their own (2026-11-27): where a
  way into or out of the part leaves a block that also goes elsewhere, putting the load or store
  on that way alone changed no example's work
  ([log](optimizations-log.md#loads-and-stores-on-a-way-of-their-own-tried-2026-11-27-on-the-examples)).
- Selection without `FrameLayout`'s predictions: the examples do 1.1 % more work and 104 worst
  cases grow, so they stay until a model of the whole function replaces them
  ([log](optimizations-log.md#framelayouts-predictions-measured-and-kept-2026-11-23)).

## Ideas not yet built

The open parts of the catalogue (D, L, M, the divider and mode 7's multiplier, parameters in registers and
tail calls, `<<8` as a byte access, full `REP`/`SEP` tracking, unrolling); page-aligned arrays with
8-bit index registers; a points-to analysis for pointers into several objects or loaded from
memory; induction variables for counters that count down, and an induction variable as the index
in Y (the range analysis cannot yet see that the offset never wraps); the transforming hints
`DAMPF_TABLE`, `DAMPF_PIN_X` and `DAMPF_SPLIT` ([hints.md](hints.md); of the hints only
`DAMPF_RANGE` and `DAMPF_BUDGET` are built, and neither transforms code); more profile-guided
optimization and superoptimization (below); a tuning option (`--tune`, the user's idea of
2026-11-18) that builds with several values of the parameters chosen today on one game (the
inlining limit, the block order), runs the user's own workload (a benchmark or a replay, as
`dampf profile` does) in dampf's emulator and keeps the fastest build, each candidate checked
against a reference of the results (the PC build's checksums), since a larger inlining limit once
exposed a miscompilation (fixed on 2026-11-19). Which come next, by likely gain and effort, is in
[roadmap.md](roadmap.md#m12-fine-tuning-0120) (M12).

## How close can dampf get to hand-written assembly?

| Code generator | Cycles compared with good hand-written assembly |
| --- | --- |
| 816-tcc (PVSnesLib) | probably 5–10 × (observed: very slow, needed workarounds) |
| Calypsi | about 1.1 × on `level_solid_px` (an older run) |
| dampf, naive code generator | 4.5 × on `level_solid_px` ([log](optimizations-log.md#the-first-reference-function-level_solid_px-2026-11-17)) |
| dampf from MIR, today | 1.14 × on `level_solid_px` ([Where things stand](#where-things-stand)) |
| dampf from MIR, 2026-11-27 | 2.0 to 2.5 × on the game's three hottest functions in play ([below](#the-games-hottest-functions-by-hand-2026-11-27)) |
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

Hot functions are written by hand in 65816 assembly as reference versions, the yardstick and not
the programs' code ([reference/README.md](../reference/README.md)). The first was Blubber Blasen's
`level_solid_px`; its first version was wrong, and the benchmark's checksums caught it:
hand-written code needs the same oracle as compiled code. Since the game's code left, they are
functions of the examples, checked against their C bodies and by the examples' checksums
(`ReferenceFunctionsTest`): Er-Tüp's `random16` and `sine`, which were 2.3 and 1.4 times faster
than the naive code generator's. Against the code from MIR, as functions of their own, `random16`
takes 68 cycles both ways and `sine` 24 against 21 (in the game the code from MIR inlines both).
Candidates for more: Er-Tüp's pair loop, Mandelbrot's iteration with its products, Steinschlag's
cave scan.

Each reference and dampf's version of the same function run in the cycle-counting emulator on the
same inputs (the check of `DAMPF_ASM`, which links a reference in place of the compiled function),
which gives a ratio per function, tracked over the milestones. Where the reference is faster, the
reason becomes a hint or an optimization rule. If dampf stays too far behind on one function, a
game links the hand-written version in its place with `DAMPF_ASM`, whose convention and analysis
keep the timing exact ([hints.md](hints.md)); only when measurement demands it.
Hand-written assembly for the whole game was tried years ago and failed.

### The game's hottest functions by hand (2026-11-27)

How much is left: Blubber Blasen's three hottest functions in play (the profile in *Where things
stand*), `oam_add`, `separate` and `evaluate`, each compiled by dampf from the C as it is (A),
compiled from the best C spelling (B), and written by hand in 65816 assembly with everything
dampf inlines into it (C). B and C compute the same as A for every input, and both inputs played
with all of WRAM identical to A's at the end of every tick. The method, the numbers and what
each gap is made of are in the
[log](optimizations-log.md#the-games-three-hottest-functions-by-hand-2026-11-27-on-blubber-blasen).

| Function | A | B | C | A / C | Work per tick with C, solo / with the CPU partner |
| --- | ---: | ---: | ---: | ---: | ---: |
| `oam_add` | 2,268 | 1,790 | 1,067 | 2.13 | −6.6 % / −5.3 % |
| `separate` | 27,772 | 24,887 | 14,195 | 1.96 | −6.0 % / −3.8 % |
| `evaluate` | 29,409 | 17,923 | 11,877 | 2.48 | (not called) / −5.3 % |

Master cycles per call on average, with the CPU partner. The gap between A and C by class:

| Class | `oam_add` | `separate` | `evaluate` |
| --- | ---: | ---: | ---: |
| the compiler, with the facts it has | 49 % | 34 % | 100 % |
| the compiler, given a fact it cannot infer | 0 | 0 | 0 |
| the C spelling | 42 % | 53 % | (B closes 65 %) |
| only a person | 10 % | 13 % | 0 |

What follows:

- dampf's code takes 2 to 2.5 times the cycles of hand-written code on these functions, far from
  the 1.14 of `level_solid_px` and from the 1.2 to 1.5 estimated above for hot loops. Hand-written
  versions of all three, added up, would take about 14 % off the work with the CPU partner and
  13 % solo.
- No gap needed a fact dampf cannot infer. Every hand-written version relies only on what the C
  says; a hint would have added 2 % to `evaluate` (no overflow in a difference) and nothing to the
  others.
- Most of what a compiler could get lies in rules M12 did not list: in `evaluate` about 14,300 of
  the 17,532 cycles a call, all but rows 2 and 8 (signed compares of two variables, a field array
  at a constant offset reached through a 24-bit pointer, loop-invariant work across a loop nest, a
  nest that has nothing to do, two induction variables where one would do), in `oam_add` the C
  spelling's items (a flag tested after the paths that set it, a chain of constant returns, a
  pointer global). `separate`'s compiler part is mostly in rows that exist (2, 3, 8, 11); the rest
  of it is the algorithm, a list of the bubbles that take part, which pays only once dampf compiles
  lists well. M12 was reworked from this ([roadmap](roadmap.md#m12-fine-tuning-0120)).
- The C spelling gets part of the way: B takes 2.1 %, 0.8 % and 3.0 % off the work with the CPU
  partner, where C takes 5.3 %, 3.8 % and 5.3 %. The spellings that pay went to the game
  ([game-findings.md](game-findings.md)) and, where they hold in general, into the C guide.

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
profile's block counts (G); on the game it made the run with the CPU partner 0.7 % slower
(2026-11-27, [log](optimizations-log.md#the-games-three-hottest-functions-by-hand-2026-11-27-on-blubber-blasen)).
Open: inlining hot functions more readily (one hot callee inlined by hand at its five hottest
call sites took 1.3 to 2.3 % off the work, the same log entry), and spending code size for speed
where it matters. Hot code and its tables belong in FastROM, which today holds all of
dampf's code and constant data anyway. Many `DAMPF_LIKELY`/`DAMPF_UNLIKELY` hints become
unnecessary.

## Superoptimization for very short sequences

For short, very hot sequences (about 5–8 instructions), dampf can search instead of following
rules: generate candidate sequences up to a length, run each in the cycle-counting emulator, and
keep the fastest that gives the same result. With 8-bit inputs equivalence can be checked
exhaustively (256 inputs for one byte, 65,536 for two). Optional and late (M12).
