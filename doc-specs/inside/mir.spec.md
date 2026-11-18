---
title: MIR
sources:
  - docs/ADR.md
  - docs/adr/*.md
  - docs/architecture.md
  - docs/testing.md
  - src/main/java/dampf/mir/Instr.java
  - src/main/java/dampf/mir/Printer.java
  - src/main/java/dampf/mir/Lower.java
  - src/main/java/dampf/mir/Verifier.java
  - src/main/java/dampf/mir/Interpreter.java
  - src/test/java/dampf/mir/MirTextTest.java
audience: Readers who want to read dampf's MIR, with `dampf mir`, or work on a pass
---

## Purpose

What MIR is, how to read its text format, and what guarantees hold on it, so that a reader can
look at `dampf mir` output and understand it.

## Sections

1. **What MIR is** – the middle of the three IR levels, SSA form (explain SSA and φ in a few
   words), what it keeps from C (widths, signedness) and what it drops.
2. **Reading the text format** – a small C function and its MIR as `dampf mir` prints it, before
   and after `-O`, with the parts explained.
3. **Instructions** – the kinds of instruction, grouped, in a table.
4. **Verifier and interpreter** – what the verifier checks, what the interpreter is used for,
   that the text format parses back.

## Pay attention to

- Run `dampf mir` on the example and copy the output.
- Keep the instruction table to what `Instr.java` defines.

## Out of scope

- Each pass: [Optimizations](/inside/optimizations).
