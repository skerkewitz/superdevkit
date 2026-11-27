# Exact timing and cycle budgets

dampf computes how long code runs and refuses builds that break a cycle budget. The cost model
underneath (memory speeds, multipliers) is in [optimizations.md](optimizations.md), section
"Knowing the machine".

## As built

`dampf.backend.Timing` computes, for every function the code generator from MIR writes, a best
and a worst case in **master cycles** from its entry to its return, calls included (ADR-6FVMDV79;
up to 0.10 they were CPU cycles). `dampf build`
writes them to `<rom>.timing.txt`, most expensive first. After each unbounded function the
report names the first loop in it without a bound (`(loop at game.c:395)`) or else the first
call of a function without one (`(calls _level_fall)`). A function that never returns has no best
case either; the report shows `never` for it.

A function inlined at every call has no code of its own in the ROM (ADR-6FVMCY48): its body is
still compiled, placed and timed as if it were called, and only that code is left out of the
ROM. The report keeps its line with the note `(inlined at every call, no code of its own)`, and
its `DAMPF_BUDGET` is checked as before; there is no label for it in the `.lbl` and `.dbg` files,
since its copies are part of their callers (their source lines are still the callee's). A
function keeps its code where a call of it is left (the inliner found it too large, or the pass
limit stopped it), where hand-written assembly names it, and as the entry or an interrupt
handler.

The assembly comments (`dampf.backend.AsmWriter`) give every instruction its master cycles in the
column on the right (`30`; a conditional branch `12/18`, not taken and taken; an access through a
pointer `48-52`, the memory it reaches not being known) and every C line without a branch or label
inside its sum (`[ 72 mc ]`, or `[ 258 mc + calls ]` when it calls a function); a line with a
branch gets no sum.

### Master cycles

A master cycle is a tick of the SNES's 21.477 MHz clock. Each CPU cycle is a memory access or an
internal operation, and the SNES makes the CPU wait for slow memory, so a CPU cycle takes 6, 8 or
12 master cycles by where it goes (`dampf.target.MasterCycles`, the same table dampf's emulator
uses):

| Where | Master cycles |
| --- | --- |
| an internal operation | 6 |
| the code and constant data in ROM from bank `$80` | 6 (FastROM), 8 with `--slowrom` |
| WRAM: the direct page, the stack, the variables | 8 |
| the I/O registers at `$2000-$3FFF` and `$4200-$5FFF` (PPU, DMA, the multiplier) | 6 |
| the old joypad registers at `$4000-$41FF` | 12 |

Each instruction's cycles are sorted by where they go: reads of the code (opcode and operand),
the stack, the direct page, the data its operand reaches, a table read, internal operations
(`MasterCycles.accesses`, checked against the emulator for every opcode in both widths by
`MasterCyclesAgainstEmulatorTest`). The data's place comes from the operand as the code generator
writes it ([memory-map.md](memory-map.md)): an absolute address is WRAM (DBR is `$7E`); a long
address given as a number is the register at its own speed, and with an index every address from it
to the end of its bank counts (`sta f:$002100,x` is 6 to 12); a long address of a symbol is constant
data in ROM; an access through a pointer is 6 to 8, the best case for an I/O register or FastROM, the
worst for RAM. The old joypad registers through a pointer are not counted: dampf's code reads the
pads through the automatic read at `$4218`. In dampf's code a CPU cycle averages about 6.4 master
cycles (the worst cases of the examples' functions: 6.2 to 6.6 times what they were in CPU
cycles).

Not counted, as before: DRAM refresh (40 master cycles a scanline), DMA and HDMA, which stop the
CPU whatever it runs (level 3 below).

### How the bounds are computed

- **Per block** (level 1): the code as written, instruction by instruction, with the cycle table
  and the places above (accumulator width followed through the code; index registers 16 bit).
  Inside a block there are only forward branches over a few instructions and the code generator's
  small loops (shifts, copies), which it marks with their bound. A conditional branch costs 2 CPU
  cycles (12 master cycles from FastROM) when not taken, 3 (18) when taken. The jump table of a `switch` (`JMP (table,X)`) is a way out to every entry of
  the table, each at the same cost.
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
- **Interrupts**: a program with interrupt handlers in C (`DAMPF_INTERRUPT`, ADR-6FM9TXV4) gets a
  line per interrupt in the report, `dampf_irq` and `dampf_nmi`: from the interrupt being taken to
  the end of its `RTI`, the runtime's entry stub and the handler. The stub takes 448 master cycles
  to the handler's first instruction (478 for the NMI, which reads RDNMI in 8 bits; 66 and 71 CPU
  cycles), of which the interrupt itself takes 60 and the stub's first instruction, in bank `$00`,
  32; and 212 from its return (31 CPU cycles), so an IRQ costs at least 660 master cycles beyond
  its handler (with `--slowrom` 498, 536 and 224). Mesen measured the 388 from the stub's first
  instruction to the handler's (ADR-6FRX1GYC). Not counted: the end of the instruction the
  interrupt came in, and an NMI that comes while the IRQ handler runs. Interrupt code multiplies in
  software, `dampf_mul16_soft` 146–3,526 master cycles (one round per bit of the second factor)
  and `dampf_mul32_soft` 8,046–13,614, where the main code takes 678 and 2,530–2,542 on the CPU's
  multiplier.
- **Functions written in assembly** (`DAMPF_ASM`, [hints.md](hints.md)): `dampf.asm.AsmFunction`
  follows the machine code from the function's label with the same cost model, the register
  widths through `SEP`, `REP`, `PHP` and `PLP`; a conditional branch costs 2 or 3 CPU cycles as
  above, and with 8-bit index registers an indexed read may cross a page, which the worst case
  counts. The places: an absolute address is WRAM while DBR is as it was (anything, 6 to 12, after
  a `PLB`), a long address the speed of its number, a symbol reached long or a pointer 6 to 8. Every
  backward branch carries its bound as a comment, `; dampf: at most N` (the loop from its target
  runs at most N rounds per entry); the loops are collapsed innermost first with the formula
  above. The report marks such a function `(assembly, file.s)`. The check against the C body
  measures every call it makes and stops the build if one lies outside these bounds, so a bound
  comment set too low is found.

### Budgets

`DAMPF_BUDGET(n)` from `<dampf.h>`, in front of a function definition
(`DAMPF_BUDGET(45000) void vblank_handler(void) { ... }`), gives the function a budget in master
cycles, calls included (a frame has 357,368). It expands to `__attribute__((dampf_budget(n)))` for dampf and to nothing
on the PC; `n` must be a number. After the timing, `dampf build` checks it on the function as
compiled on its own:

- worst case above the budget: a compile error at the function, with the worst and the best
  case (`b.c:5:42: error: 'sum' takes up to 7468 master cycles, over its budget of 7000
  (DAMPF_BUDGET); best case 108, see the .timing.txt file`, for a loop over 32 bytes);
- no worst case: a compile error that names the loop or the call without a bound, since a budget
  can only be guaranteed for bounded code;
- worst case above 90 % of the budget: a warning, printed by `dampf build`.

With `--opt-limit` (a build that stops the passes early, to find the one that broke a program)
the two errors are warnings: such a build is slower on purpose.

The budget is in master cycles, like the report and the frame numbers below (ADR-6FVMDV79). Up
to dampf 0.10 it was in CPU cycles: a budget written then is about a sixth of what it means
now, and the build fails on it. The error says so when the worst case is at most 8 times the
budget (`...; DAMPF_BUDGET counts master cycles now, not CPU cycles as it did up to dampf 0.10: a
budget written in CPU cycles needs about 6.5 times its number`): multiply an old budget by 6.5, or
take the worst case from the new report and keep the margin it had.

### Checked against the emulator

Every execution test and random program measures `test_main` and every call of every function
and runtime routine on the way (from its first instruction to the instruction after the `JSL`),
in master cycles as dampf's emulator counts them for the CPU (`Cpu65816.master`: each access at
its address's speed, without DRAM refresh and DMA), and checks best ≤ measured ≤ worst
(`Timing.check`), leaving out the cycles of interrupts that came meanwhile, and measures each interrupt against its own bounds (`exec/interrupts.c`);
`ExamplesTimingTest` does the same for every
call of every example in its first 3,000 frames (and did for the 180,000 calls of Blubber
Blasen's benchmark while the game's code was here). A runtime bound set too low on purpose
(the `dampf_mul16` of that time, a loop in software, worst 300 instead of 367) failed 94 tests at
once; the measured maximum was 362.

### In the game

In Blubber Blasen's benchmark build (2026-11-18, after the LIR passes), 294 functions got a bound and
37 do not. The loops
left are those that run over memory (`while (s[n]) n++;`, a queue,
`while (stage_next < stage_count)`), whose counter is not stepped by a constant, or that could
really run forever in C; they need a hint. Examples, in CPU cycles as counted then: `abs16` 13–21, `floating` 27–34,
`level_solid_px` 21–144, `text_number` 215–3,114, `bg_clear` 107–65,786, `separate`
27–137,435.

### Not built yet

- Level 3: DRAM refresh, HDMA and interrupts at frame level.
- The naive code generator (`--backend naive`) writes no report and checks no budget.
- `DAMPF_TRIPS(n)` for loops without a counter.
- The costliest path per C line, in the report and in the budget error message.
- Best and worst case in the assembly comments of lines with a branch.
- Checking the measured cycles of budgeted functions in the emulator tests.

## Design

The agreed design (design discussion, 2026-11-17). Where it goes beyond the list above, it is
not built.

### Why the 65816 can be timed exactly

The 65816 has no cache, no pipeline and no branch prediction. Every instruction takes a fixed,
documented number of cycles, which depends only on a few known conditions. dampf counts in
master cycles, the only unit in which memory speed differences are exact.

### Level 1: exact to the cycle, per path

For code without branches, the run time is exact. Every condition that adds cycles is known:

| Extra cost | When | Known to dampf? |
| --- | --- | --- |
| 16-bit instead of 8-bit operand | Accumulator or index width (M and X flags) | Yes: dampf tracks the processor state at every instruction |
| Direct page access | The low byte of the direct page register D is not zero | Yes: dampf aligns D so this extra cycle does not occur |
| Memory region | FastROM, SlowROM, WRAM, I/O | Yes: every fixed address is known at compile time; through a pointer, 6 to 8 master cycles |
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
  costs are known (each interrupt's bounds are in the report already, see "As built").

A function's own bound excludes interrupt time; frame-level budgets add the handlers' costs.

### Frame numbers

- One NTSC frame: 262 scanlines × 1364 master cycles = 357,368 master cycles (the DRAM refresh
  takes 10,480 of them), roughly 45,000–55,000 CPU cycles depending on how much code runs from
  FastROM and how often WRAM is accessed. On the console every other frame has one scanline 4 master cycles shorter, so frames
  average 357,366; dampf's emulator (`ExampleCheck.FRAME`) counts 357,368, `dampf profile` and
  `bench` divide by the average.
- Vertical blank: scanlines 225–261, 37 × 1,364 = 50,468 master cycles (48,988 without the
  refresh), roughly 6,000–8,000 CPU cycles. Everything that talks to
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

- How to express impossible path combinations. The bounds did turn out too pessimistic for pair
  loops (Er-Tüp's `collide`); a hint for how often a path runs per call, or a bound per call
  chain, is a step of M11 ([roadmap.md](roadmap.md#m11-timing-and-debugging-0110)).
- How the frame-level budget learns which HDMA channels a game has on, since a game turns them
  on at run time.
