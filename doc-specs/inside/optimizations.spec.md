---
title: Optimizations
sources:
  - reference/README.md
  - docs/ADR.md
  - docs/adr/*.md
  - docs/optimizations.md
  - docs/optimizations-log.md
  - docs/subrange-types.md
  - docs/architecture.md
  - compiler/src/main/java/dampf/Compiler.java
  - compiler/src/main/java/dampf/mir/*.java
  - compiler/src/main/java/dampf/backend/MirCodeGen.java
  - compiler/src/main/java/dampf/backend/*Selection*.java
  - compiler/src/main/java/dampf/backend/FrameLayout.java
  - compiler/src/main/java/dampf/backend/PlaceColouring.java
  - compiler/src/main/java/dampf/backend/RegisterChoice.java
  - compiler/src/main/java/dampf/backend/Weights.java
  - compiler/src/main/java/dampf/lir/*.java
audience: Readers who want to know why dampf's code is fast, or why a line compiled the way it did
---

## Purpose

What dampf's optimizer does, pass by pass and in the code generator, why each matters on the
65816, and what each brought on Blubber Blasen.

## Sections

The page opens, before the first H2, with the budget (game logic in 60 % of a frame) and the
latest overall figure against the naive code.

1. **Why the 65816 needs a different optimizer** – 8 and 16-bit mode as processor state, few
   registers, the direct page; why C's 32-bit `int` promotions make naive code slow.
2. **The passes on MIR** – one H3 per pass that is built, in the order `dampf.mir.Optimizer`
   runs them: what it does, a small before/after where it helps, what it brought (from the
   measurements). Before the first H3, the order of the rounds and the analysis of counting
   loops that several passes share (`CountedLoops`).
3. **In the code generator** – what the code generator from MIR does beyond translating:
   frames in the direct page, places coloured on the code, results in A, X for indices and loop
   counters, X and Y chosen by cost (`RegisterChoice`), byte loads, lazy byte masks, compare and
   branch, loops over bytes in 8 bits, and the others that are built. Briefly each.
4. **Value ranges** – the ranges dampf infers, what uses them, pointer compression; what is
   planned (`DAMPF_RANGED`) in one sentence.
5. **The measurements** – a table of the steps with frames per tick during play, from
   `docs/optimizations-log.md`; a table of the steps since 2026-11-19 with their change on the
   examples; the current figures from *Where things stand* in `docs/optimizations.md`.
6. **How far it can go** – the comparison with hand-written assembly (`level_solid_px` first, now the examples' functions in `reference/`), and
   where a gap remains.

## Pay attention to

- Take each pass and its order from `Optimizer.java` (which `Compiler` runs), and the code
  generator's steps and LIR passes from `MirCodeGen.java` and `Selection.java`, not from the
  documents. Every pass the page names must exist in `compiler/src/main/java/dampf/` (`mir/`,
  `lir/`, `backend/`) and be run by the pipeline; where a document names a pass the code does
  not run, say it is not built or leave it out.
- The current figures only as *Where things stand* in `docs/optimizations.md` gives them, with
  their date; what a single step brought comes from `docs/optimizations-log.md`.
- Name the catalogue letters (A, B, H, I, ...) of `docs/optimizations.md` once in a table, so
  that a reader can find each in the document.
- Before/after examples from `dampf mir` and `dampf mir -O`, run.
- No figures of Calypsi or 816-tcc, in the text or in a table, even where the sources still have
  them: the kit no longer uses or measures them. dampf is compared with the naive code and with
  hand-written assembly only.

## Out of scope

- The optimizations not built yet (M9 and M12): one line linking [Roadmap](/inside/roadmap).
- Exact timing: [Exact timing](/inside/timing).
