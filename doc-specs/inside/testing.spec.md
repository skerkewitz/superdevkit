---
title: Testing
sources:
  - docs/testing.md
  - docs/timing.md
  - dev/README.md
  - README.md
  - build.gradle.kts
  - compiler/src/test/java/dampf/exec/*.java
  - compiler/src/test/java/dampf/emu/*.java
  - compiler/src/test/java/dampf/lib/*.java
audience: Contributors, and users who want to know why they can trust dampf's output
---

## Purpose

How dampf makes sure its code computes what gcc computes on the PC: the test layers, what each
catches, and how to run them.

## Sections

1. **The principle** – every program built with both code generators and compared with gcc on
   the PC; UBSan on the PC side (explain it in a few words).
2. **The layers** – execution tests, random programs, the examples (a checksum per tick on the
   SNES and the desktop), the static timing on the examples, the reference functions, the
   examples' benchmark; for each, what it runs, where, and what it compares. The examples in
   Mesen (`dev/check_example.sh`, `dev/compare_example.sh`: lost PPU bytes, the pictures). One
   sentence that Blubber Blasen's code was a layer of its own until the examples replaced it.
3. **dampf's emulator and Mesen** – why dampf has its own emulator, how it is validated (the
   SingleStepTests suite, Mesen), when Mesen runs.
4. **Running the tests** – the Gradle calls with their properties, as a table.
5. **Adding an execution test** – what a test program in `compiler/src/test/resources/exec/` looks like
   and what makes it pass.
6. **When something hangs** – from `docs/testing.md`.

## Pay attention to

- The properties exactly as `build.gradle.kts` reads them.

## Out of scope

- The MIR text format: [MIR](/inside/mir).
