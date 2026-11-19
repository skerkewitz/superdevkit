---
title: Writing fast C
sources:
  - lib/C_STYLE.md
  - compiler/src/test/resources/style/*.c
  - compiler/src/test/java/dampf/exec/StyleGuideTest.java
audience: Game programmers who write C for dampf and want to know what costs time on the 65816 with it
---

## Purpose

Tell a reader which ways of writing C are slow, fast or make no difference with dampf today, with
the measured cost of each, so that they write the clear form and change it only where it pays.
The page is made from `lib/C_STYLE.md`, the guide that ships with the kit; it says so in one
sentence and that the guide is the place for the complete rules.

## Sections

The page opens, before the first H2, with the general rule: write the clear form, and apply a
speed rule only in code the timing report or the profile shows to be hot, or that has a
`DAMPF_BUDGET`. And that every speed rule is a measurement that a test checks, so the numbers are
today's and change with dampf.

1. **Slow** – the rules of part 3 of the guide, each with its name, the form to avoid and the form
   to use as a short C pair, and the measured cycles of both from the guide's table.
2. **Fast** – part 4, the same way.
3. **No difference** – part 5, as a short list with the cycles.
4. **How the rules are checked** – one file per rule in `compiler/src/test/resources/style/`, the `claim`
   line, what `StyleGuideTest` checks (the claim on the timing report's worst cases, the same
   results on the PC and the SNES, the guide's table), what happens when dampf changes and a
   claim no longer holds, and how to write a new rule.

## Pay attention to

- The cycles only from the guide's table of measurements, with its date; never other figures.
- Show each pair as it is in its file in `compiler/src/test/resources/style/`, cut to the two functions.

## Out of scope

- What dampf rejects and allows: [Writing C for dampf](/guide/writing-c) and
  [The C subset](/reference/c-subset); one sentence and the links.
- The hints in detail: [Hints](/reference/hints).
- The hardware: [The SNES hardware](/guide/snes-hardware).
