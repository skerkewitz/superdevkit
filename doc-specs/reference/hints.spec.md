---
title: Hints
sources:
  - docs/ADR.md
  - docs/adr/*.md
  - docs/hints.md
  - docs/timing.md
  - docs/subrange-types.md
  - compiler/src/main/resources/dampf/include/dampf.h
  - compiler/src/main/java/dampf/frontend/AstBuilder.java
  - compiler/src/main/java/dampf/Compiler.java
  - compiler/src/test/resources/exec/hint_range.c
  - compiler/src/test/java/dampf/BudgetTest.java
  - compiler/src/test/resources/exec/asm_functions.c
  - compiler/src/test/resources/exec/asm/asm_functions.s
  - compiler/src/test/java/dampf/AsmDeclarationTest.java
  - compiler/src/main/java/dampf/asm/AsmFunction.java
  - compiler/src/main/java/dampf/emu/AsmCheck.java
  - compiler/src/main/java/dampf/frontend/pp/Expander.java
  - cli/src/main/java/superdevkit/project/Desktop.java
  - cli/src/main/java/superdevkit/project/Web.java
  - cli/src/main/java/superdevkit/project/Kit.java
audience: Users who want to tell dampf what it cannot infer
---

## Purpose

The hint macros, what each tells dampf, and how each is checked in the PC build.

## Sections

1. **How hints work** – the header `<dampf.h>` and how a game's PC build reaches it
   (`-idirafter <home>/include`), and how the kit's desktop and web builds do (`superdevkit build
   linux` and `web`, `Desktop.java` and `Web.java`: `-nostdinc -ffreestanding`, dampf's include
   folder with `-isystem`, so `<stdint.h>` is dampf's there too, and a wrong hint traps instead of
   asserting; `win64` with MSVC's `/X` and `/I`, where it calls `__debugbreak()`; link
   [The superdevkit command](/reference/superdevkit#build)); macros that become built-ins with
   dampf, checked assertions on the PC (and `NDEBUG`); that dampf trusts a hint and never checks
   it on the SNES, and why a wrong hint is dangerous.
2. **The hints** – one H3 per built hint (`DAMPF_RANGE`, `DAMPF_BUDGET`, `DAMPF_ASM`): what it states, where
   it may stand, an example with what dampf does with it (a loop bound, a build error or
   warning), the PC check, and the messages for misuse (a wrong number of arguments is a message
   of dampf's own preprocessor; with `--opt-limit` a budget error is only a warning). Before the
   first example that shows a timing report, say that `dampf build` writes it next to the ROM
   (`<rom>.timing.txt`), what its columns are (best and worst case in CPU cycles), and link
   [Command line](/reference/command-line). For `DAMPF_ASM`: the convention the file follows
   (label, segment, the parameters' places and `_name__locals`, the result, the CPU state), the
   part of ca65 dampf's assembler reads, the bound comment `; dampf: at most N`, what the analysis
   rejects (a few real messages), the line of the timing report, and the check against the C body
   with a real message; `--replace` as the command-line override; that the naive code generator
   links the file but does not check it.
3. **Not built yet** – the other hints of the catalogue in one short list, `DAMPF_TRIPS`
   postponed and why, and subrange types (`DAMPF_RANGED`); link [Limits](/reference/limits).

## Pay attention to

- Describe only hints that are built in section 2; everything else stays in section 3.
- Show real messages and timing figures, produced by dampf for the page's examples.
- dampf preprocesses with a preprocessor of its own (ADR-6FD4GDN5), not with gcc; only the PC,
  desktop and web builds use their C compiler's preprocessor.

## Out of scope

- How the optimizer uses them: [Optimizations](/inside/optimizations).
