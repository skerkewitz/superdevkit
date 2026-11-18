# 18. Every function gets a best and a worst case, and `DAMPF_BUDGET` fails the build

**Context.** The 65816 has no cache and no pipeline, so the cost of each instruction is known
from its opcode, addressing mode, widths and the memory it touches. A game has a frame budget,
and code that is usually fast enough but sometimes not shows up as a stutter on a rare tick.

**Decision.**

- `Timing` computes a best and a worst case in CPU cycles for every function the code generator
  from MIR writes, from entry to return, calls included: per block from the cycle table, across
  blocks with the loops collapsed innermost first, and loop bounds from the code or from a
  `DAMPF_RANGE` hint. `dampf build` writes them to `<rom>.timing.txt`, and names the loop or
  call that leaves a function unbounded.
- `DAMPF_BUDGET(n)` in front of a function definition gives it a budget in CPU cycles. A worst
  case above it is a compile error, so is a function without a worst case, and a worst case
  above 90 % is a warning.
- The tests check best ≤ measured ≤ worst on every call in the emulator (record 17).

**Consequences.** A budget can be guaranteed only for bounded code, so loops over memory need a
hint. The figures are CPU cycles, not master cycles, and leave out DMA, HDMA and interrupts.
What is open is in [timing.md](../timing.md).
