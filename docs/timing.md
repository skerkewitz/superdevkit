# Exact timing and cycle budgets

dampf computes how long code runs and refuses builds that break a cycle budget. The cost model
underneath (memory speeds, multipliers) is in [optimizations.md](optimizations.md), section
"Knowing the machine".

## As built

`dampf.backend.Timing` computes, for every function the code generator from MIR writes, a best
and a worst case in **CPU cycles** from its entry to its return, calls included. `dampf build`
writes them to `<rom>.timing.txt`, most expensive first. After each unbounded function the
report names the first loop in it without a bound (`(loop at game.c:395)`) or else the first
call of a function without one (`(calls _level_fall)`). A function that never returns has no best
case either; the report shows `never` for it.

The assembly comments (`dampf.backend.AsmWriter`) give every instruction its cycles and every C
line without a branch or label inside its sum (`[ 12 cyc ]`, or `[ 43 cyc + calls ]` when it
calls a function); a line with a branch gets no sum.

### How the bounds are computed

- **Per block** (level 1): the code as written, instruction by instruction, with the cycle table
  (accumulator width followed through the code; index registers 16 bit). Inside a block there
  are only forward branches over a few instructions and the code generator's small loops (shifts,
  copies), which it marks with their bound. A conditional branch costs 2 when not taken, 3 when
  taken.
- **Across blocks** (level 2): the loops of the function, innermost first, each collapsed into
  one node: worst case = (rounds - 1) × the longest way round + the longest way out, best case =
  the shortest way out (no round). The rest is free of cycles: shortest and longest path to the
  return.
- **Loop bounds** come from the code, as long as there are no hints, through the loop analysis
  the optimizer uses too (`dampf.mir.CountedLoops`). A counter φ that every way back steps by
  the same constant c bounds how often the head runs, by the smallest bound of these rules (each
  applies or not):
  - a finite value range in the head and a step that cannot wrap: (hi - lo) / |c| + 1;
  - an ordered test that every round passes (in the head, or further down where it dominates
    every way back) and that keeps the loop going while the counter, or the counter after its
    step (a `do` loop), either one possibly zero-extended, is on the near side of another value
    (`i < n`, `i > rank`, `v >= 100`, signed or unsigned; the value may change in the loop, its
    range counts): the counter cannot pass that value's extreme, and if the step cannot wrap
    there, the head runs at most (extreme - first compared value) / |c| + 2 times. This needs
    no range of the counter itself; an unknown start counts as the type's end;
  - an equality test of the same kind (`k != 0` keeps going) on a value that stays the same:
    with c = ±1 from the near side as the ordered test; from the far side the counter meets
    the constant after the distance modulo 2^w; with any odd step after at most 2^w head runs;
  - a test against the counter's own start plus a constant K (a row of a table walked from its
    offset o to o + K, or a count down from a bound plus K to the bound): at most K / |c| + 1
    head runs, whatever the start is; for `<` and `>` only where the start's range shows that no
    step wraps, for `!=` with a step of one always.

  Other loops make the worst case "unbounded", some of them rightly: `for (y = t; y <= b; y++)`
  with an 8-bit `b` never ends when `b` is 255. A `DAMPF_RANGE` hint on the counter in the loop
  ([hints.md](hints.md)) gives such a loop a bound through the value ranges. The value ranges
  carry a loop head's entry condition into the loop when the head's other predecessors are back
  edges (an outer `i < 5` reaches a body that starts with an inner loop), take a condition
  between two values (`r < n`) into account with the other value's range, narrow again after
  widening (a counter of a loop of 768 rounds is 0..768, not the whole type), and give a
  parameter the join of its arguments' ranges at every call (`ParamRanges`).
- **Calls**: callee bounds first (no recursion); the runtime's multiplication and division have
  fixed bounds counted from `runtime.s`.

### Budgets

`DAMPF_BUDGET(n)` from `<dampf.h>`, in front of a function definition
(`DAMPF_BUDGET(6800) void vblank_handler(void) { ... }`), gives the function a budget in CPU
cycles, calls included. It expands to `__attribute__((dampf_budget(n)))` for dampf and to nothing
on the PC; `n` must be a number. After the timing, `dampf build` checks it on the function as
compiled on its own:

- worst case above the budget: a compile error at the function, with the worst and the best
  case (`b.c:5:42: error: 'sum' takes up to 1137 CPU cycles, over its budget of 1000
  (DAMPF_BUDGET); best case 17, see the .timing.txt file`, for a loop over 32 bytes);
- no worst case: a compile error that names the loop or the call without a bound, since a budget
  can only be guaranteed for bounded code;
- worst case above 90 % of the budget: a warning, printed by `dampf build`.

With `--opt-limit` (a build that stops the passes early, to find the one that broke a program)
the two errors are warnings: such a build is slower on purpose.

The budget is in CPU cycles, like the report; the frame numbers below are in master cycles,
about 8 per CPU cycle.

### Checked against the emulator

Every execution test and random program measures `test_main` and every call of every function
and runtime routine on the way (from its first instruction to the instruction after the `JSL`)
and checks best ≤ measured ≤ worst (`Timing.check`); `ExamplesTimingTest` does the same for every
call of every example in its first 3,000 frames (and did for the 180,000 calls of Blubber
Blasen's benchmark while the game's code was here). A runtime bound set too low on purpose
(the `dampf_mul16` of that time, a loop in software, worst 300 instead of 367) failed 94 tests at
once; the measured maximum was 362.

### In the game

In Blubber Blasen's benchmark build (2026-11-18, after the LIR passes), 294 functions got a bound and
37 do not. The loops
left are those that run over memory (`while (s[n]) n++;`, a queue,
`while (stage_next < stage_count)`), whose counter is not stepped by a constant, or that could
really run forever in C; they need a hint. Examples: `abs16` 13–21 cycles, `floating` 27–34,
`level_solid_px` 21–144, `text_number` 215–3,114, `bg_clear` 107–65,786, `separate`
27–137,435.

### Not built yet

- Master cycles: memory speeds are not counted (the benchmark measures them in the emulator).
- Level 3: DRAM refresh, HDMA and interrupts at frame level.
- The naive code generator (`--backend naive`, or `--replace`) writes no report and checks no
  budget.
- `DAMPF_TRIPS(n)` for loops without a counter.
- The costliest path per C line, in the report and in the budget error message.
- Best and worst case in the assembly comments of lines with a branch.
- Checking the measured cycles of budgeted functions in the emulator tests.

## Design

The agreed design (design discussion, 2026-11-17). Where it goes beyond the list above, it is
not built.

### Why the 65816 can be timed exactly

The 65816 has no cache, no pipeline and no branch prediction. Every instruction takes a fixed,
documented number of cycles, which depends only on a few known conditions. The design counts in
master cycles, the only unit in which memory speed differences are exact, and reports both
master and CPU cycles; as built, everything is in CPU cycles.

### Level 1: exact to the cycle, per path

For code without branches, the run time is exact. Every condition that adds cycles is known:

| Extra cost | When | Known to dampf? |
| --- | --- | --- |
| 16-bit instead of 8-bit operand | Accumulator or index width (M and X flags) | Yes: dampf tracks the processor state at every instruction |
| Direct page access | The low byte of the direct page register D is not zero | Yes: dampf aligns D so this extra cycle does not occur |
| Memory region | FastROM, SlowROM, WRAM, I/O | Yes: every address is fixed at compile time (not counted yet: CPU cycles only) |
| Indexed read across a page boundary | With an 8-bit index register, base + index crosses a 256-byte page. With a 16-bit index, and for indexed writes, the extra cycle is always paid. | Index registers are 16 bit as built, so the cycle is always paid. With 8-bit indexes, arrays placed by their `DAMPF_RANGE` would never cross a page: the cycle disappears and the cost stops depending on the data. |
| Branch taken or not | Depends on data | Level 2 |

### Level 2: exact bounds for branches and loops

Once data decides the path, there is a best and a worst case, both exact. Bounds go function by
function, bottom-up over the call graph, which has no cycles since the subset has no recursion.
They are computed on the structure of the code; unrestricted `goto` would need a more general
analysis, which is why the subset rejects `goto` (ADR
[ADR-6F3CJVHC](adr/6F3CJVHC-goto-and-labels-stay-rejected-and-a-forward-and.md)). Loops need a bound: a constant trip count, or a hint checked as an assertion on the
PC ([hints.md](hints.md)).

The worst case may count paths that never occur together (two branches never both taken). The
report is meant to show the costliest path line by line so a human can judge it; annotations
that exclude impossible combinations can come later. The examples' checks add the measured cost
in real game situations (`dampf.emu.ExampleProfile`), and comparing it with the static worst case
shows how pessimistic the bound is.

### Level 3: what happens outside the CPU (not built)

Some time is lost without any instruction causing it. All of it is fixed and can be accounted for
per frame:

- DRAM refresh: the CPU is halted for 40 master cycles on every scanline, about 3 % of the time.
- HDMA: a small fixed cost per scanline for each active channel. Blubber Blasen uses HDMA for its
  colour windows.
- Interrupts: NMI (start of vertical blank) and IRQ arrive at known times, and their handlers'
  costs are known.

A function's own bound excludes interrupt time; frame-level budgets add the handlers' costs.

### Frame numbers

- One NTSC frame: 262 scanlines × 1364 master cycles = 357,368 master cycles, roughly
  45,000–55,000 CPU cycles depending on how much code runs from FastROM and how often WRAM is
  accessed. On the console every other frame has one scanline 4 master cycles shorter, so frames
  average 357,366; dampf's emulator (`ExampleCheck.FRAME`) counts 357,368, `dampf profile` and
  `bench` divide by the average.
- Vertical blank: scanlines 225–261, roughly 6,000–8,000 CPU cycles. Everything that talks to
  the graphics hardware (sprite table via DMA, tiles, palettes) must fit in here.
- Sound runs on its own CPU (the SPC700) and costs nothing here.
- The goal: game logic in Blubber Blasen's busiest scene uses at most 60 % of a frame, after
  vertical blank work and a safety margin ([optimizations.md](optimizations.md)).

### What the budgets are for

Typical budgets in a SNES game: the vertical blank handler (must fit into vertical blank), the
per-frame update of the game logic (the 60 % target), and any IRQ handler that runs at a fixed
scanline. Without the check, an overrun shows only when the screen flickers or the game slows
down; with it, the build refuses the change that causes it.

## Open questions

- How to express impossible path combinations, should the worst-case bounds turn out too
  pessimistic in practice.
- How much HDMA the game uses in the end, since it changes the frame-level budget.
