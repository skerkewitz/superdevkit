---
title: Optimizations
sources:
  - docs/optimizations.md
  - docs/optimizations-log.md
  - docs/subrange-types.md
  - docs/architecture.md
  - src/main/java/dampf/Compiler.java
  - src/main/java/dampf/mir/*.java
  - src/main/java/dampf/backend/MirCodeGen.java
  - src/main/java/dampf/backend/*Selection*.java
  - src/main/java/dampf/backend/FrameLayout.java
  - src/main/java/dampf/lir/*.java
audience: Readers who want to know why dampf's code is fast, or why a line compiled the way it did
---

## Purpose

What dampf's optimizer does, pass by pass and in the code generator, why each matters on the
65816, and what each brought on Blubber Blasen.

## Sections

The page opens, before the first H2, with the budget (game logic in 60 % of a frame) and the
latest overall figure against the naive code and Calypsi.

1. **Why the 65816 needs a different optimizer** – 8 and 16-bit mode as processor state, few
   registers, the direct page; why C's 32-bit `int` promotions make naive code slow.
2. **The passes on MIR** – one H3 per pass that is built, in the order `Compiler` runs them:
   what it does, a small before/after where it helps, what it brought (from the measurements).
3. **In the code generator** – what the code generator from MIR does beyond translating:
   frames in the direct page, results in A, X for indices and loop counters, byte loads, lazy
   byte masks, compare and branch, and the others that are built. Briefly each.
4. **Value ranges** – the ranges dampf infers, what uses them, pointer compression; what is
   planned (`DAMPF_RANGED`) in one sentence.
5. **The measurements** – a table of the steps with frames per tick during play, from
   `docs/optimizations-log.md`; the current figures from *Where things stand* in
   `docs/optimizations.md`.
6. **How far it can go** – the comparison with hand-written assembly (`level_solid_px`), and
   where a gap remains.

## Pay attention to

- Take each pass and its order from `Compiler.java`, not from the document; where the document
  names a pass the code does not run, say it is not built or leave it out.
- Name the catalogue letters (A, B, H, I, ...) of `docs/optimizations.md` once in a table, so
  that a reader can find each in the document.
- Before/after examples from `dampf mir` and `dampf mir -O`, run.

## Out of scope

- The optimizations not started (M4): one line linking [Roadmap](/inside/roadmap).
- Exact timing: [Exact timing](/inside/timing).
