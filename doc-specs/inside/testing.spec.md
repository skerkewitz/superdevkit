---
title: Testing
sources:
  - docs/testing.md
  - docs/timing.md
  - dev/README.md
  - README.md
  - compiler/build.gradle.kts
  - .github/workflows/build.yml
  - tools/web_check/README.md
  - docs/adr/6FD2DGP0-*.md
  - docs/adr/6FD1Y6W2-*.md
  - compiler/src/test/java/dampf/ExamplesTest.java
  - compiler/src/test/java/dampf/exec/*.java
  - compiler/src/test/java/dampf/emu/*.java
  - compiler/src/test/java/dampf/lib/*.java
  - compiler/src/test/java/dampf/frontend/pp/*.java
  - compiler/src/test/java/dampf/PublishedDataTest.java
  - compiler/src/test/java/dampf/IrqCheckTest.java
  - compiler/src/test/java/dampf/AsmDeclarationTest.java
audience: Contributors, and users who want to know why they can trust dampf's output
---

## Purpose

How dampf makes sure its code computes what the PC computes (clang, the tests' reference): the
test layers, what each catches, and how to run them.

## Sections

1. **The principle** – every program built with both code generators and compared with clang on
   the PC (gcc and clang give the same results for the subset); UBSan on the PC side (explain it
   in a few words).
2. **The layers** – execution tests, random programs (both generators: `RandomProgram` with fixed
   seeds, `RandomLoopProgram` with new seeds every day), the preprocessor against `clang -E`, the
   examples (a checksum per tick on the SNES, the desktop and the web, and the same sound), the
   static timing on the examples, the reference functions, the examples' benchmark; for each, what
   it runs, where, and what it compares. The examples in Mesen (`dev/check_example.sh`,
   `dev/compare_example.sh`: lost PPU bytes, the pictures). One sentence that Blubber Blasen's
   code was a layer of its own until the examples replaced it.
3. **dampf's emulator and Mesen** – why dampf has its own emulator, how it is validated (the
   SingleStepTests suite, Mesen), when Mesen runs.
4. **Running the tests** – the Gradle calls with their properties, as a table. That the test
   classes run in parallel JVMs, and what that asks of a test that writes files. The build of
   every pull request on GitHub Actions, and what it leaves out.
5. **Adding an execution test** – what a test program in `compiler/src/test/resources/exec/` looks like
   and what makes it pass.
6. **When something hangs** – from `docs/testing.md`.

## Pay attention to

- The properties exactly as `compiler/build.gradle.kts` reads them.
- Every test class the page names exists under `compiler/src/test` or `cli/src/test`.

## Out of scope

- The MIR text format: [MIR](/inside/mir).
