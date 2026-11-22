---
title: Exact timing
sources:
  - docs/timing.md
  - compiler/src/main/java/dampf/backend/Timing.java
  - compiler/src/main/java/dampf/target/Cycles65816.java
  - compiler/src/main/java/dampf/mir/CountedLoops.java
  - compiler/src/main/java/dampf/backend/AsmWriter.java
  - compiler/src/main/java/dampf/Compiler.java
  - compiler/src/main/resources/dampf/include/dampf.h
  - compiler/src/test/java/dampf/BudgetTest.java
  - compiler/src/test/java/dampf/exec/Timing.java
  - compiler/src/test/java/dampf/exec/ExamplesTimingTest.java
audience: Readers who want cycle-exact bounds for their code
---

## Purpose

How dampf computes cycle counts and bounds for functions, the timing report, and the cycle
budgets it checks at build time.

## Sections

1. **Why the 65816 can be timed exactly** – fixed cycle counts per instruction and addressing
   mode, what changes them (8/16 bit, page crossing, direct page alignment), and that dampf
   counts CPU cycles, not master cycles.
2. **Bounds for a function** – straight code within a block, loops and their bounds (the rules
   of the counted-loops analysis, `CountedLoops`, which `Timing` asks), calls, a function that
   never returns, a loop without a bound.
3. **The timing report** – `<rom>.timing.txt`, an example produced by dampf, how to read it,
   and the cycle counts in the assembly comments.
4. **Budgets** – `DAMPF_BUDGET` in short with the real error message, that a build with
   `--opt-limit` reports a broken budget as a warning, linking [Hints](/reference/hints) for
   the details.
5. **How the bounds are checked** – against dampf's emulator in the tests.

## Pay attention to

- Only what is built; what is not (master cycles, DMA and interrupts, `DAMPF_TRIPS`) in one
  sentence linking [Limits](/reference/limits).
- Examples and figures produced by dampf, run.

## Out of scope

- Optimizations: [Optimizations](/inside/optimizations).
- The cycle model's use for optimization decisions.
