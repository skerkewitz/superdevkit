---
title: Compiling a game
sources:
  - compiler/src/main/java/dampf/ConfigFile.java
  - cli/src/main/java/superdevkit/project/Build.java
  - examples/ertuep/superdevkit.toml
  - examples/ertuep/README.md
  - docs/adr/6F3CJW9N-options-can-be-kept-in-an-argument-file-given-by.md
  - README.md
  - compiler/src/main/java/dampf/Main.java
  - compiler/src/main/java/dampf/Compiler.java
  - compiler/src/main/java/dampf/emu/ExampleProfile.java
  - reference/README.md
  - lib/snes/README.md
audience: Users moving from one example file to the many files of a real game, and users of `superdevkit build snes` who call dampf on their game themselves
---

## Purpose

Show how a game of many files is compiled with dampf, with Er-Tüp (`examples/ertuep`, the dev
kit's largest example) as the example: the call `superdevkit build snes` makes, the argument file
it writes, finding what does not fit the subset, and measuring where the cycles go.

## Sections

1. **Many files, one call** – all `.c` files on one command line, `-I` for include directories
   and `-D` for defines, the entry function; then the same kept in an argument file
   (ADR-6F3CJW9N): `superdevkit build snes` converts the game's PNG files and writes
   `build/snes/<name>.args`, the argument file of what it compiled (its lines, absolute paths,
   every file named); the `dampf build @examples/ertuep/build/snes/ertuep.args` call with an
   output of its own, and what it prints; the rules for writing one by hand (paths relative to
   the file, folders standing for their `.c` files). Before it, the shortest call for a game of
   the reader's own (its `.c` files, `-I`, `-o`, the entry function by default `main`), with the
   launcher from [Getting started](/guide/getting-started).
2. **Find what does not fit first** – `dampf check` over the game's files before the first
   build (`dampf check @examples/ertuep/build/snes/ertuep.args`), and what to do with each
   report (change the code, keeping the PC build working).
3. **Where the time goes** – `dampf.emu.ExampleProfile`: it plays the ROM with the input of a
   `check.txt` in dampf's emulator and charges the cycles of a range of ticks to functions and,
   with the `.dbg` file, to C lines; the call, and a short excerpt of real output on Er-Tüp.
4. **Replacing a function with assembly** – `--replace fn=file.s`, what it is for (comparing a
   hand-written function with dampf's, as `reference/` does with Er-Tüp's `random16`), and that
   it works with the naive code generator only.

## Pay attention to

- Commands run as the *Using dampf* section of `README.md` and Er-Tüp's README show, from the
  top of the repository, after `superdevkit build snes examples/ertuep` has written the argument
  file; run them and copy what they print, cut where long. The argument file and dampf's output
  name absolute paths; show them cut (`.../examples/ertuep/...`) and say so.
- A reader compiling their own game passes its paths; say so.
- Say in one sentence that a ROM of a game needs a [platform layer](/glossary#platform-layer)
  for the library it calls, compiled with it (`lib/snes` in Er-Tüp's argument file).
- Budgets and the timing file come from the code generator from MIR; say so where `--replace`
  needs the naive one.

## Out of scope

- Every option: [Command line](/reference/command-line).
- The `superdevkit` command and the project file:
  [The superdevkit command](/reference/superdevkit).
- How the examples check dampf against the PC: [Testing](/inside/testing).
