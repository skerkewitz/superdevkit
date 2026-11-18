---
title: Getting started
sources:
  - README.md
  - build.gradle.kts
  - src/main/java/dampf/Main.java
  - examples/m1/calls.c
  - src/main/resources/dampf/snes/runtime.s
audience: A first-time user with a Linux or WSL machine
---

## Purpose

Take a reader from nothing to a ROM built from `examples/m1/calls.c` that they can open in
Mesen, so that they have seen every tool dampf needs work once.

## Sections

1. **What you need** – the prerequisites of the README, cut to what this page needs (JDK, gcc,
   Mesen 2 for looking at the result). ca65 and ld65 are needed only to develop dampf (its tests
   compare with them), not to use it; say so in one sentence and point to the README.
2. **Build dampf** – `./gradlew installDist`, where the launcher ends up, and that the first
   `./gradlew build` also runs the tests (and when they need Mesen).
3. **Compile a first program** – show `examples/m1/calls.c` in full, then the `dampf build`
   command for it with `--entry test_main`, and the line dampf prints. Say what `--entry` does
   and why this example needs it (the runtime calls the entry function after start-up).
4. **What you get** – the files written next to the ROM, in a short list, each with a phrase;
   link [The generated files](/reference/output-files).
5. **Look at it in Mesen** – open the ROM with the `.dbg` file next to it, step through
   `test_main` in the C source; link [Debugging in Mesen](/guide/debugging) for more.
6. **When something goes wrong** – the two or three failures a new user meets: gcc not found
   (the preprocessor), a construct outside the subset (show the message
   format with one real example from `dampf check`). Link
   [Command line](/reference/command-line).

## Pay attention to

- Run every command on the page and copy what it prints.
- Commands run from the top of the repository with the launcher at
  `build/install/dampf/bin/dampf`; say once that the reader may put it on their `PATH` and that
  the page writes `dampf` for it.
- From WSL, the Windows Mesen works; say how the ROM is opened from there, as the README does.

## Out of scope

- The other commands (`check`, `parse`, `profile`, `mir`, `bench`): the command line page.
- Running the test suite with Mesen: [Testing](/inside/testing).
