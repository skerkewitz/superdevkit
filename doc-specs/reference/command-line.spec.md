---
title: Command line
sources:
  - compiler/src/main/java/dampf/ConfigFile.java
  - docs/adr/6F3CJW9N-options-can-be-kept-in-an-argument-file-given-by.md
  - compiler/src/main/java/dampf/Main.java
  - compiler/src/main/java/dampf/Compiler.java
  - compiler/src/main/java/dampf/tools/*.java
  - compiler/src/main/java/dampf/frontend/Diagnostic.java
  - compiler/src/main/java/dampf/frontend/Preprocessor.java
  - compiler/src/main/java/dampf/frontend/pp/Preprocess.java
  - compiler/src/main/java/dampf/util/PassLimit.java
  - README.md
audience: Users looking up a command or an option
---

## Purpose

Every command of `dampf` and every option, with what it does, its default, and the exit status.
This page owns the options: other pages link here.

## Sections

The page opens, before the first H2, with the usage lines exactly as dampf prints them, and one
sentence that sends the reader who builds a whole game to
[The superdevkit command](/reference/superdevkit), which calls dampf.

1. **build** – compile to a ROM. Each option: `-I`, `-D` (both also written joined, `-Idir`;
   what dampf's own preprocessor does with them), `--entry`, `--backend`, `--replace`, `--sram`,
   `--opt-limit`, `-o`. Defaults, which are required, what is printed on success. The rule for
   the default of `--backend` with and without `--replace`.
2. **check** – the front end only, every file on its own, every problem of each file (the first after FAILS, the others below it); the lines
   it prints and its exit status.
3. **parse** – preprocess and parse; what it prints.
4. **profile** – its arguments, what the profile file is and where it comes from, what the
   optional `top` does.
5. **mir** – print the MIR or run it in the MIR interpreter: `-O`, `--check-ranges`, `--bench`.
6. **bench** – run the benchmark ROM in dampf's emulator, `--profile`.
7. **Messages** – the format of a message (file, line, column, severity, text) as printed, with
   one real example; the preprocessor's messages.
8. **Exit status** – 0, 1 and 2, and when each happens, for each command.

## Pay attention to

- Give each command and option an H2 or H3 so that other pages can link to it
  (`/reference/command-line#build`).
- Check every usage line and every message by running dampf.
- `mir`, `bench` and `--opt-limit` are tools for working on dampf and on the benchmark; say so
  in a sentence.
- dampf preprocesses with its own preprocessor; gcc is not needed for any command.

## Out of scope

- What the generated files contain: [The generated files](/reference/output-files).
- Which C is accepted: [The C subset](/reference/c-subset).
- The `superdevkit` command and the project file:
  [The superdevkit command](/reference/superdevkit).
