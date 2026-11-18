---
title: How a compile works
sources:
  - docs/architecture.md
  - src/main/java/dampf/Compiler.java
  - src/main/java/dampf/frontend/Preprocessor.java
  - src/main/java/dampf/frontend/Parser.java
  - src/main/java/dampf/snes/RomBuilder.java
  - src/main/java/dampf/asm/Assembler.java
  - src/main/java/dampf/asm/Linker.java
  - src/main/antlr/dampf/parser/README.md
  - src/main/java/dampf/mir/Optimizer.java
  - src/main/java/dampf/Main.java
  - src/main/java/dampf/frontend/AstBuilder.java
audience: Readers who want to know what happens between the C files and the ROM, and new contributors
---

## Purpose

Follow one `dampf build` from the C files to the ROM, phase by phase, so that a reader knows
which part of dampf does what and where to look when something goes wrong.

## Sections

The page opens, before the first H2, by saying what it follows and that nobody needs it to use
dampf.

1. **The big picture** – a Mermaid flowchart of the phases (preprocess, parse, AST, checks,
   lowering to MIR, passes, instruction selection to LIR, LIR passes, assembling and linking),
   with the naive code generator as
   the second path straight from the AST. One line per phase.
2. **Preprocess and parse** – `gcc -E`, the ANTLR grammar from grammars-v4 (one sentence on what
   ANTLR is), source positions kept through the preprocessor.
3. **The typed AST** – one AST for the whole program, types and promotions resolved, where
   subset violations are found.
4. **Checks on the whole program** – the call graph, no recursion.
5. **MIR and its passes** – lowering to MIR, the passes in the order they run; link
   [MIR](/inside/mir) and [Optimizations](/inside/optimizations).
6. **Code generation** – the code generator from MIR (instruction selection, written once per
   function) and the naive one, what each is for; LIR, the 65816 code in basic blocks whose text
   is the assembly file, and its passes (redundant loads, dead stores, branch forms).
7. **Assembling and linking** – dampf's own assembler and linker (the subset of ca65 it reads,
   one link because every segment's size is known, the bank packing), the files written, and
   `--assembler ca65`, which gives the same ROM with ca65 and ld65.

## Pay attention to

- Name the classes where a contributor would look (`Compiler`, `Preprocessor`, `AstBuilder`,
  `Lower`, `MirCodeGen`, `NaiveCodeGen`, `RomBuilder`), once each.
- The three IR levels of `docs/architecture.md`: say which exist today (MIR and LIR) and which are
  planned, in one sentence.

## Out of scope

- The passes in detail: [Optimizations](/inside/optimizations).
- How the result is tested: [Testing](/inside/testing).
