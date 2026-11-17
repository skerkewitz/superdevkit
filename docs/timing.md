# Exact timing and cycle budgets

dampf computes how long code runs; refusing builds that break a cycle budget is still to come.
What is built (2026-11-17) is in the section "As built" at the end; the rest is the agreed design
(design discussion, 2026-11-17). The cost model it rests
on (memory speeds, multipliers) is in [optimizations.md](optimizations.md), section "Knowing the
machine".

## Why the 65816 can be timed exactly

The 65816 has no cache, no pipeline and no branch prediction. Every instruction takes a fixed,
documented number of cycles, which depends only on a few known conditions. That makes it far
easier to time exactly than any modern processor.

dampf counts internally in master cycles, because that is the only unit in which memory speed
differences are exact. Reports show both master cycles and CPU cycles.

## Level 1: exact to the cycle, per path

For a piece of code without branches, dampf computes the run time exactly. Every condition that
adds cycles is known to it:

| Extra cost | When | Known to dampf? |
| --- | --- | --- |
| 16-bit instead of 8-bit operand | Accumulator or index width (M and X flags) | Yes: dampf tracks the processor state at every instruction |
| Direct page access | The low byte of the direct page register D is not zero | Yes: dampf aligns D so this extra cycle does not occur |
| Memory region | FastROM, SlowROM, WRAM, I/O | Yes: every address is fixed at compile time |
| Indexed read across a page boundary | With an 8-bit index register, base + index crosses a 256-byte page. (With a 16-bit index, and for indexed writes, the extra cycle is always paid.) | Yes, if dampf aligns the arrays: base address fixed, index range known from `DAMPF_RANGE`. Then arrays can be placed so no page is crossed: the cycle disappears *and* the cost stops depending on the data. |
| Branch taken or not | Depends on data | See level 2 |

## Level 2: exact bounds for branches and loops

Once data decides which path runs, there is no single number any more but a best case and a worst
case. dampf computes both exactly:

- **Function by function, bottom-up over the call graph.** The subset has no recursion, so the
  call graph has no cycles. The cost of a call is the callee's already computed bound plus the
  call overhead.
- **Loops need a bound.** Loops with a constant trip count are bounded automatically. Other loops
  need `DAMPF_TRIPS(n)`, which is checked as an assertion on the PC ([hints.md](hints.md)). A
  function with an unbounded loop gets the worst case "unbounded".
- **Structured code.** Bounds are computed on the structure of the code (sequence, branch, loop),
  which keeps the analysis simple and exact. Unrestricted `goto` would need a more general
  analysis; whether the subset allows a restricted form is an open question
  ([subset.md](subset.md), decision 4).
- **Pessimism.** The worst case may count paths that never occur together in practice (two
  branches that are never both taken). The report always shows the costliest path, line by line,
  so a human can judge it. Annotations that exclude impossible path combinations can be added
  later if needed.

The replays in the emulator add the third view: the **measured** cost in real game situations.
Static bounds and measured values together give the full picture, and comparing the static worst
case with the measured maximum shows how pessimistic the bound is.

## Level 3: what happens outside the CPU

Some time is lost without any instruction causing it. All of it is fixed and can be accounted for
at the level of a whole frame:

- **DRAM refresh:** the CPU is halted for 40 master cycles on every scanline, about 3 % of the
  time.
- **HDMA** (if per-line effects are used): a small fixed cost per scanline for each active
  channel. Blubber Blasen uses HDMA for its colour windows.
- **Interrupts:** NMI (start of vertical blank) and IRQ arrive at known times, and dampf knows the
  cost of their handlers.

A function's own bound excludes interrupt time. Frame-level budgets add the handlers' costs.

## Frame numbers

- One NTSC frame: 262 scanlines × 1364 master cycles = 357,366 master cycles, which is roughly
  **45,000–55,000 CPU cycles** depending on how much code runs from FastROM and how often WRAM is
  accessed.
- Vertical blank: scanlines 225–261, roughly **6,000–8,000 CPU cycles**. Everything that talks to
  the graphics hardware (sprite table via DMA, tiles, palettes) must fit in here.
- Sound runs on its own CPU (the SPC700) and costs nothing here.
- The goal: game logic in Blubber Blasen's busiest scene uses at most 60 % of a frame, after
  vertical blank work and a safety margin. What the goal means compared with today's compilers is
  in [optimizations.md](optimizations.md).

## What dampf produces from this

**1. Cycle counts in the assembly comments.** Every instruction gets its cost, every C line the
sum; for code with branches, the line shows best and worst case. The format is shown in
[architecture.md](architecture.md).

**2. A timing report per function:** best case, worst case, and the costliest path. From the same
data, a view per C source line: where in the source do the cycles go?

**3. Cycle budgets checked by the compiler.**

```c
DAMPF_BUDGET(6800)
void vblank_handler(void) { ... }
```

- If the function's worst case exceeds its budget, the build fails. The error message shows the
  worst case, the budget, and the costliest path with the cost of each C line on it, so it is
  clear where to cut.
- If the worst case is above a warning threshold (for example 90 % of the budget), the build
  warns.
- A function with a budget whose worst case is "unbounded" (a loop without a known bound) is a
  build error: a budget can only be guaranteed for bounded code.
- In the PC build `DAMPF_BUDGET` expands to nothing. In the emulator test builds, the measured
  cycles of every budgeted function can additionally be checked against the budget during the
  replays.

Typical budgets in a SNES game: the vertical blank handler (must fit into vertical blank), the
per-frame update of the game logic (the 60 % target), and any IRQ handler that runs at a fixed
scanline. Without this check, an overrun is only noticed when the screen flickers or the game
slows down; with it, the build refuses the change that causes it.

## Open questions

- How to express impossible path combinations, should the worst-case bounds turn out too
  pessimistic in practice.
- How much HDMA the game uses in the end, since it changes the frame-level budget.

## As built (2026-11-17)

`dampf.backend.Timing` computes, for every function the code generator from MIR writes, a best
and a worst case in **CPU cycles** from its entry to its return, calls included. `dampf build`
writes them to `<rom>.timing.txt`, most expensive first.

- **Per block** (level 1): the code as written, instruction by instruction, with the cycle table
  (accumulator width followed through the code; index registers 16 bit). Inside a block there
  are only forward branches over a few instructions and the code generator's small loops (shifts,
  copies), which it marks with their bound. A conditional branch costs 2 when not taken, 3 when
  taken.
- **Across blocks** (level 2): the loops of the function, innermost first, each collapsed into
  one node: worst case = (rounds - 1) × the longest way round + the longest way out, best case =
  the shortest way out (no round). The rest is free of cycles: shortest and longest path to the
  return.
- **Loop bounds** come from the code, as long as there are no hints. A counter φ that every way
  back steps by the same constant c bounds how often the head runs, by the first of these rules
  that applies:
  - a finite value range in the head and a step that cannot wrap: (hi - lo) / |c| + 1;
  - an ordered test that every round passes (in the head, or further down where it dominates
    every way back) and that keeps the loop going while the counter, or the counter
    zero-extended, is on the near side of another value (`i < n`, `i > rank`, `v >= 100`,
    signed or unsigned): the counter cannot pass that value's extreme, and if the step cannot
    wrap there, the head runs at most (extreme - start) / |c| + 2 times. This needs no range of
    the counter itself, which an 8-bit unsigned counter does not have in the signed view of the
    value ranges; an unknown start counts as the type's end;
  - an equality test of the same kind (`k != 0` keeps going) with c = ±1: the counter meets the
    constant after at most the distance modulo 2^w, so at most 2^w head runs even from the other
    side.

  Other loops make the worst case "unbounded". A `DAMPF_RANGE` hint on the counter in the loop
  ([hints.md](hints.md)) gives such a loop a bound through the value ranges; `DAMPF_TRIPS`, for
  loops without a counter, is not built yet. Some of them are
  right to: `for (y = t; y <= b; y++)` with an 8-bit `b` never ends when `b` is 255. The value
  ranges carry a loop head's entry condition into the loop when the head's other predecessors
  are back edges (an outer `i < 5` reaches a body that starts with an inner loop), take a
  condition between two values (`r < n`) into account with the other value's range, narrow
  again after widening (a counter of a loop of 768 rounds is 0..768, not the whole type), and
  give a parameter the join of its arguments' ranges at every call (`ParamRanges`).
- **Calls**: callee bounds first (no recursion); the runtime's multiplication and division have
  fixed bounds counted from `runtime.s`.

**Budgets.** `DAMPF_BUDGET(n)` from `<dampf.h>`, in front of a function definition
(`DAMPF_BUDGET(6800) void vblank_handler(void) { ... }`), gives the function a budget in CPU
cycles, calls included. It expands to `__attribute__((dampf_budget(n)))` for dampf and to nothing
on the PC; `n` must be a number. After the timing, `dampf build` checks it on the function as
compiled on its own:

- worst case above the budget: a compile error at the function, with the worst and the best
  case (`b.c:5:42: error: 'sum' takes up to 1201 CPU cycles, over its budget of 1000
  (DAMPF_BUDGET); best case 17, see the .timing.txt file`, for a loop over 32 bytes);
- no worst case: a compile error that names the loop or the call without a bound, since a budget
  can only be guaranteed for bounded code;
- worst case above 90 % of the budget: a warning, printed by `dampf build`.

The budget is in CPU cycles, like the report; the frame-level numbers above are in master
cycles, about 8 per CPU cycle. Not yet: the costliest path per C line in the error message, and
checking the measured cycles of budgeted functions in the emulator tests.

Not yet: master cycles (memory speeds; the benchmark measures those), `DAMPF_TRIPS`, the
costliest path per C line in the report, cycle ranges in the assembly comments, and interrupts at
frame level.

**Checked against the emulator.** Every execution test and random program measures every call
of every function and runtime routine (from its first instruction to the instruction after the
`JSL`) and checks best ≤ measured ≤ worst; with `-Preplays` the game's benchmark does the same
for its 180,000 calls (`GameTimingTest`). A runtime bound set too low on purpose (`dampf_mul16`
worst 300 instead of 367) failed 94 tests at once; the measured maximum was 362.

In the game, 292 functions get a bound and 38 do not. The report names, after each unbounded
function, the first loop in it without a bound (`(loop at text.c:104)`) or else the first call of
a function without one (`(calls _text_number)`). The loops left are those that run over memory
(`while (s[n]) n++;`, a queue, `while (stage_next < stage_count)`), whose counter is not stepped
by a constant, or that could really run forever in C; they need a hint. Examples:
`abs16` 25–37 cycles, `floating` 44–75, `level_solid_px` 21–209, `text_number` 233–4,319,
`bg_clear` 126–85,456, `separate` 33–190,481.
