---
title: The C subset
sources:
  - compiler/src/main/resources/dampf/include/string.h
  - docs/ADR.md
  - docs/adr/*.md
  - docs/subset.md
  - compiler/src/main/resources/dampf/include/stdint.h
  - compiler/src/main/resources/dampf/include/dampf.h
  - compiler/src/main/java/dampf/frontend/AstBuilder.java
  - compiler/src/main/java/dampf/frontend/Preprocessor.java
  - compiler/src/main/java/dampf/frontend/pp/*.java
  - compiler/src/test/resources/exec/pointer_to_int.c
  - compiler/src/main/java/dampf/check/CallGraph.java
  - compiler/src/test/java/dampf/frontend/*.java
audience: Users who need to know whether a construct is accepted, and what it means
---

## Purpose

The complete list of what dampf accepts and rejects, and the meaning it gives to what it
accepts. A reader with a construct in mind finds it here.

## Sections

The page opens, before the first H2, with the principle: a subset dampf defines and checks;
everything outside it is rejected with a message naming the construct and the place; nothing is
compiled silently. Link [Writing C for dampf](/guide/writing-c) for the introduction.

1. **Types** – accepted and rejected types, with the reason for rejecting plain integer types.
   The sizes dampf uses for each accepted type, including enums and pointers, as it does today.
2. **Semantics** – the arithmetic rules, which are those of gcc on x86-64; dampf's `<stdint.h>`.
3. **Functions** – no recursion (the message names the cycle; show it), no variadic functions,
   structs only by pointer, parameters, `inline`.
4. **Function pointers** – not accepted, and why the call graph must stay complete.
5. **Memory** – no heap; all objects static.
6. **Statements** – what is accepted and what is not (`goto`, labels, VLAs).
7. **Expressions** – compound literals and designated initializers not accepted; struct
   assignment accepted.
8. **Hardware access** – for platform code, not game code: pointers to `volatile` memory (one
   access each, in order, exact width; a `volatile` variable itself is rejected), and the cast
   from a pointer to an integer (the SNES address `bank << 16 | address`; on the PC another
   number, so only distances within one object agree). One short example writing a PPU
   register; point to `lib/snes` as the code that uses both.
9. **Preprocessor** – dampf's own, a subset of C99's, so using dampf needs no C compiler: what
   it has, what it rejects (with the message), the predefined `__DAMPF__`.
10. **Whole program** – one program from all files, `static` names local to their file.
11. **Open decisions** – the decisions of `docs/subset.md` that are not settled, each with what
    dampf does today.

## Pay attention to

- Where `docs/subset.md` and the code (`AstBuilder`, its tests) disagree on what is accepted,
  the code wins; report the disagreement.
- For each rejected construct, the message dampf prints, where a test or a run shows it. Run
  `dampf check` on a small file for each and copy the message.
- Leave out the scan counts of the game; the page is about the subset, not about Blubber Blasen.

## Out of scope

- Hints: only that `<dampf.h>` provides them and where they may stand; the rest is on
  [Hints](/reference/hints). Intrinsics are not built: link [Limits](/reference/limits).
