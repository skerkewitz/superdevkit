---
title: Writing C for dampf
sources:
  - compiler/src/main/resources/dampf/include/string.h
  - docs/ADR.md
  - docs/adr/*.md
  - docs/subset.md
  - compiler/src/main/resources/dampf/include/stdint.h
  - compiler/src/main/resources/dampf/include/dampf.h
  - compiler/src/main/java/dampf/frontend/Preprocessor.java
  - compiler/src/main/java/dampf/frontend/pp/*.java
  - compiler/src/main/java/dampf/frontend/SystemHeaders.java
  - compiler/src/main/java/dampf/check/CallGraph.java
  - compiler/src/main/java/dampf/Main.java
  - compiler/src/test/resources/exec/calls.c
  - lib/C_STYLE.md
audience: Game programmers who write C and want it to compile with dampf and gcc alike
---

## Purpose

Show a reader how to write C that dampf accepts and that gives the same result on the PC: the
handful of rules they meet every day, with the reason for each, and the link to the full list.

## Sections

The page opens, before the first H2, by saying that dampf compiles a subset of C, that
everything outside it is rejected with a message, and that the subset is what a game needs.

1. **Integer types say their width** – `<stdint.h>` types only, plain `int`, `short`, `long`,
   `unsigned` rejected, and why (sizes differ between compilers). dampf ships its own
   `<stdint.h>`.
2. **Same arithmetic as gcc on the PC** – integer promotions to a 32-bit `int`, wrapping,
   truncation, division towards zero, arithmetic `>>`. One short example where the promotion
   matters (from `compiler/src/test/resources/exec/calls.c`, `inc8` or `neg8`), with the result. Why: the PC build
   is the reference. Say that dampf narrows to 8 or 16 bit only where the result is the same.
   The one place where C itself lets the two builds differ: the order in which a call's
   arguments are evaluated (part 2 of `lib/C_STYLE.md`), and what to write instead.
3. **What is left out** – a short table of what the subset rejects most often (floating point,
   recursion, function pointers, unions and bit fields, structs by value, `goto`, the heap and
   library functions), each with the reason in a phrase. Link [The C subset](/reference/c-subset)
   for the complete list.
4. **One program, not separate files** – every `.c` file is compiled together; there is no
   separate compilation and no linking of object files; `static` names stay local. What that
   allows (dampf sees every call and every use).
5. **The preprocessor** – dampf's own, a subset of C99's, with dampf's own include
   directory and `__DAMPF__` defined; host headers and macros never reach the code, and no gcc
   is needed. What it rejects, in a sentence, with a link to
   [The C subset](/reference/c-subset#preprocessor). Show how to use `__DAMPF__` to
   keep a PC-only or compiler-specific branch apart, with a short example.
6. **Checking your code** – `dampf check` on a set of files, what it prints, and that it reports
   every problem of each file (at most one per top-level declaration). Link [Command line](/reference/command-line#check).

## Pay attention to

- Check every example with `dampf check` and with `gcc -std=c99 -Wall -Wextra` before it goes
  on the page.
- The decisions of `docs/subset.md` that are still open (plain `char`): say what dampf
  does today, not what may be decided.
- The messages the page quotes are dampf's as it prints them today; `lib/C_STYLE.md`, which
  ships with the kit, lists each rejected construct with its message and may be named once.
- Hints: one sentence that `<dampf.h>` provides them, link [Hints](/reference/hints).
  Intrinsics are not built: link [Limits](/reference/limits).

## Out of scope

- The full rules, every construct, the counts from the game scan: [The C subset](/reference/c-subset).
- How the subset helps the optimizer: [Optimizations](/inside/optimizations).
