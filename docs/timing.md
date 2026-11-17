# Exact timing and cycle budgets

dampf will compute how long code runs, and refuse builds that break a cycle budget. None of this
is built yet; this is the agreed design (design discussion, 2026-11-17). The cost model it rests
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
