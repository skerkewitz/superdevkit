---
title: The generated files
sources:
  - README.md
  - docs/architecture.md
  - compiler/src/main/java/dampf/Compiler.java
  - compiler/src/main/java/dampf/snes/RomBuilder.java
  - compiler/src/main/java/dampf/asm/DbgWriter.java
  - compiler/src/main/java/dampf/backend/AsmWriter.java
  - compiler/src/main/java/dampf/target/Cycles65816.java
audience: Users who open the files dampf writes next to the ROM
---

## Purpose

Each file `dampf build` writes, what it contains and what reads it; and how to read the
generated assembly, which is meant to be read by people.

## Sections

1. **The files** – a table: file, what it contains, what reads it (Mesen, the tests, a person);
   with `--assembler ca65` also the object files, the linker configurations and ld65's map.
2. **Reading the assembly** – the parts of one C line in the `.s` file: the source line header,
   the processor state, the instruction comments, the cycle counts (and "not taken/taken" for a
   branch), the sum for straight-line code. Show a real excerpt for a few lines of
   `examples/m1/calls.c`.
3. **Cycles** – what the numbers count (CPU cycles, not master cycles), and what is not built
   yet (best and worst case per line with branches).
4. **The debug file** – what the `.dbg` file gives Mesen (dampf writes it in ld65's format), and
   the relative source paths.

## Pay attention to

- Build `examples/m1/calls.c` and take every excerpt from the files dampf writes; name the
  backend that wrote them.
- The target format in `docs/architecture.md` is partly a plan; describe what the files contain
  today.

## Out of scope

- How to use the debug file in Mesen: [Debugging in Mesen](/guide/debugging).
