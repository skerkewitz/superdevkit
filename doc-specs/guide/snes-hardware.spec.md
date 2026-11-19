---
title: The SNES hardware
sources:
  - lib/SNES_HARDWARE.md
  - lib/README.md
  - lib/rachael/rachael.h
  - lib/rachael/ppu.c
  - lib/desktop/main.c
  - src/test/java/dampf/lib/SnesHardwareGuideTest.java
  - src/test/java/dampf/lib/LibCostTest.java
audience: Game programmers who write a game with the dev kit and test it on the desktop and in Mesen
---

## Purpose

Tell a reader what the SNES allows and punishes, which of its features the dev kit has on the
SNES and on the desktop, and how each rule is checked, so that a game that passes the desktop and
Mesen checks also works on a console. The page is made from `lib/SNES_HARDWARE.md`, the guide that
ships with the kit; it says so in one sentence.

## Sections

The page opens, before the first H2, with why a feature is usable only when both platforms have
it, and the three kinds of rule (enforced, measured, documented).

1. **What the dev kit has** – the table of features with their state on the SNES and on
   [Rachael](/glossary#rachael), and what "reported" means: the register writes Rachael does not
   draw, and the desktop check's `undrawn` count. Where the cost of each API call is (the table in
   `lib/README.md`), with two or three examples from it.
2. **Video memory** – the rules, each with its kind.
3. **Sprites** – the rules, each with its kind.
4. **CPU and memory** – the rules, each with its kind.
5. **The console is not the emulator** – start values and PAL.
6. **Not usable yet** – one short paragraph, and what to do instead today.

## Pay attention to

- Every rule says how it is checked, in the guide's three words.
- The message of the desktop check exactly as `lib/desktop/main.c` prints it.

## Out of scope

- How dampf compiles C, and what is slow there: [Writing fast C](/guide/writing-fast-c).
- The console API function by function: it has no page of its own; `lib/README.md` and the
  header are the reference.
