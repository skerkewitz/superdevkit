---
title: Compiling a game
sources:
  - compiler/src/main/java/dampf/ConfigFile.java
  - examples/ertuep/superdevkit.toml
  - examples/ertuep/README.md
  - docs/adr/0028-options-can-be-kept-in-an-argument-file-given-by.md
  - README.md
  - compiler/src/main/java/dampf/Main.java
  - compiler/src/main/java/dampf/Compiler.java
  - compiler/src/main/java/dampf/emu/ExampleProfile.java
  - reference/README.md
  - lib/snes/README.md
audience: Users moving from one example file to the many files of a real game
---

## Purpose

Show how a game of many files is compiled with dampf, with Er-Tüp (`examples/ertuep`, the dev
kit's largest example) as the example: an argument file with include directories and the
entry function, the converted graphics, finding what does not fit the subset, and measuring where
the cycles go.

## Sections

1. **Many files, one call** – all `.c` files on one command line, `-I` for include directories
   and `-D` for defines, the entry function; then the same kept in an argument file
   (`@ertuep.args`, ADR record 28): its lines, folders standing for their `.c` files, and the
   `dampf build` call Er-Tüp's `build.sh` makes after converting its PNG files, and what it
   prints. Before it, the shortest call for a game of the reader's own (its `.c` files, `-I`,
   `-o`, the entry function by default `main`), with the launcher from
   [Getting started](/guide/getting-started).
2. **Find what does not fit first** – `dampf check` over the game's files before the first
   build (`dampf check @ertuep.args`), and what to do with each report (change the code, keeping
   the PC build working).
3. **Where the time goes** – `dampf.emu.ExampleProfile`: it plays the ROM with the input of a
   `check.txt` in dampf's emulator and charges the cycles of a range of ticks to functions and,
   with the `.dbg` file, to C lines; the call, and a short excerpt of real output on Er-Tüp.
4. **Replacing a function with assembly** – `--replace fn=file.s`, what it is for (comparing a
   hand-written function with dampf's, as `reference/` does with Er-Tüp's `random16`), and that
   it works with the naive code generator only.

## Pay attention to

- Commands run as Er-Tüp's README and `build.sh` show (from the example's folder for the
  argument file's relative paths, or from the top with `@examples/ertuep/ertuep.args`); run them
  and copy what they print, cut where long.
- A reader compiling their own game passes its paths; say so.
- Say in one sentence that a ROM of a game needs a [platform layer](/glossary#platform-layer)
  for the library it calls, compiled with it (`lib/snes` in Er-Tüp's argument file).
- Budgets and the timing file come from the code generator from MIR; say so where `--replace`
  needs the naive one.

## Out of scope

- Every option: [Command line](/reference/command-line).
- How the examples check dampf against the PC: [Testing](/inside/testing).
