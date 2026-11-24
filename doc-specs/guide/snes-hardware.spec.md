---
title: The SNES hardware
sources:
  - lib/SNES_HARDWARE.md
  - lib/README.md
  - lib/include/sdevkit/snes.h
  - lib/rachael/rachael.h
  - lib/rachael/ppu.c
  - lib/rachael/dsp.c
  - docs/sound.md
  - lib/splash/splash.h
  - lib/desktop/main.c
  - lib/desktop/view.c
  - tools/web_check/README.md
  - compiler/src/test/java/dampf/lib/SnesHardwareGuideTest.java
  - compiler/src/test/java/dampf/lib/LibCostTest.java
audience: Game programmers who write a game with the dev kit and test it on the desktop, on the web and in Mesen
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
   draw and the DSP writes she does not play, the desktop check's `undrawn` and `unheard` counts,
   and that she counts the same on the web and in the window. Where the cost of each API call is
   (the table in `lib/README.md`), with two or three examples from it.
2. **Video memory** – the rules, each with its kind.
3. **Sprites** – the rules, each with its kind.
4. **CPU and memory** – the rules, each with its kind.
5. **Sound: what the SNES can play** – for a reader who knows sound from the PC and not the
   SNES: the sound unit and the ports, BRR (blocks, what a sample costs at which rate, pitch and
   how far one sample stretches, loops, the Gaussian dullness), the eight voices and what each
   can do, echo and its price, what the DSP cannot do, the audio RAM and loading samples between
   levels, what the sound player does and why games ran it on the SPC700, and what the kit uses
   of it today. Keep the numbers (the table of rates, the pitch range, the echo's size): a
   reader chooses a game's sample rates with them.
6. **The console is not the emulator** – start values (also after the splash screen, and of
   save RAM) and PAL.
7. **Not usable yet** – one short paragraph, and what to do instead today.

## Pay attention to

- Every rule says how it is checked, in the guide's three words.
- The messages of the desktop check exactly as `lib/desktop/main.c` prints them, and the check
  run as a reader runs it: `superdevkit build linux` in the game's folder, then the program in
  `build/linux/` with `--check`.

## Out of scope

- How dampf compiles C, and what is slow there: [Writing fast C](/guide/writing-fast-c).
- The console API function by function: [The console API](/reference/console-api); the table
  of features links its functions there; those of sound and save RAM link to
  [Graphics and sound](/guide/graphics-and-sound) and to the `sram` key of
  [The superdevkit command](/reference/superdevkit#sram).
- The check's file format and options: [The desktop](/guide/the-desktop); the web's check:
  [The web](/guide/the-web).
