---
title: Hints
sources:
  - docs/ADR.md
  - docs/adr/*.md
  - docs/hints.md
  - docs/timing.md
  - docs/subrange-types.md
  - compiler/src/main/resources/dampf/include/dampf.h
  - compiler/src/main/java/dampf/frontend/AstBuilder.java
  - compiler/src/main/java/dampf/Compiler.java
  - compiler/src/test/resources/exec/hint_range.c
  - compiler/src/test/java/dampf/BudgetTest.java
  - cli/src/main/java/superdevkit/project/Desktop.java
audience: Users who want to tell dampf what it cannot infer
---

## Purpose

The hint macros, what each tells dampf, and how each is checked in the PC build.

## Sections

1. **How hints work** – the header `<dampf.h>` and how a game's PC build reaches it
   (`-idirafter`), and how the kit's desktop build does (`superdevkit build linux`, `Desktop.java`: `-nostdinc
   -ffreestanding`, dampf's include folder with `-isystem`, so `<stdint.h>` is dampf's there too,
   and a wrong hint traps instead of asserting); macros that become built-ins with dampf, checked
   assertions on the PC (and `NDEBUG`); that dampf trusts a hint and never checks it on the SNES,
   and why a wrong hint is dangerous.
2. **The hints** – one H3 per built hint (`DAMPF_RANGE`, `DAMPF_BUDGET`): what it states, where
   it may stand, an example with what dampf does with it (a loop bound, a build error or
   warning), the PC check, and the messages for misuse. Before the first example that shows a
   timing report, say that `dampf build` writes it next to the ROM (`<rom>.timing.txt`), what its
   columns are (best and worst case in CPU cycles), and link
   [Command line](/reference/command-line).
3. **Not built yet** – the other hints of the catalogue in one short list, `DAMPF_TRIPS`
   postponed and why, and subrange types (`DAMPF_RANGED`); link [Limits](/reference/limits).

## Pay attention to

- Describe only hints that are built in section 2; everything else stays in section 3.
- Show real messages and timing figures, produced by dampf for the page's examples.

## Out of scope

- How the optimizer uses them: [Optimizations](/inside/optimizations).
