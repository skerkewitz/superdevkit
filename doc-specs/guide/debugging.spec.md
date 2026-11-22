---
title: Debugging in Mesen
sources:
  - README.md
  - docs/architecture.md
  - compiler/src/main/java/dampf/backend/AsmWriter.java
  - compiler/src/test/resources/exec/calls.c
audience: Users who want to step through their C code on the SNES
---

## Purpose

Show how to debug a ROM built by dampf in Mesen (MesenCE) at the level of the C source, and how to read
the generated assembly beside it.

## Sections

1. **Set up Mesen** – the `.dbg` file next to the ROM, loading it automatically, how Mesen finds
   the C sources (relative paths), opening from WSL.
2. **Step through C** – breakpoints in C lines, stepping over a C statement, with
   `compiler/src/test/resources/exec/calls.c` as the example.
3. **Read the assembly beside it** – what each C line looks like in the `.s` file: the source
   line, the processor state, a comment and the cycles per instruction. Show a short excerpt of
   what dampf writes for one line of `calls.c`. Link
   [The generated files](/reference/output-files).
4. **When the PC and the SNES disagree** – the PC build is the reference; how to narrow the
   difference down (the same input on both sides, compare globals, try `--backend naive`).

## Pay attention to

- Build the example and copy the assembly from the `.s` file dampf writes.
- Mesen's own menus may change between versions; name settings as the README names them and do
  not describe screens beyond that.

## Out of scope

- How Mesen is used by the test suite: [Testing](/inside/testing).
