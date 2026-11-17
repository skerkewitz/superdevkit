---
title: Writing C for dampf
sources:
  - docs/subset.md
  - src/main/resources/dampf/include/stdint.h
  - src/main/resources/dampf/include/dampf.h
  - src/main/java/dampf/frontend/Preprocessor.java
  - src/main/java/dampf/frontend/SystemHeaders.java
  - src/main/java/dampf/check/CallGraph.java
  - src/main/java/dampf/Main.java
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
   matters (from `examples/m1/calls.c`, `inc8` or `neg8`), with the result. Why: the PC build
   is the reference. Say that dampf narrows to 8 or 16 bit only where the result is the same.
3. **What is left out** – a short table of what the subset rejects most often (floating point,
   recursion, function pointers, unions and bit fields, structs by value, `goto`, the heap and
   library functions), each with the reason in a phrase. Link [The C subset](/reference/c-subset)
   for the complete list.
4. **One program, not separate files** – every `.c` file is compiled together; there is no
   separate compilation and no linking of object files; `static` names stay local. What that
   allows (dampf sees every call and every use).
5. **The preprocessor** – `gcc -E` with `-nostdinc -undef -D__DAMPF__=1`, dampf's own include
   directory; host headers and macros never reach the code. Show how to use `__DAMPF__` to
   keep a PC-only or compiler-specific branch apart, with a short example.
6. **Checking your code** – `dampf check` on a set of files, what it prints, and that it reports
   the first problem per file. Link [Command line](/reference/command-line#check).

## Pay attention to

- Check every example with `dampf check` and with `gcc -std=c99 -Wall -Wextra` before it goes
  on the page.
- The decisions of `docs/subset.md` that are still open (plain `char`, `goto`): say what dampf
  does today, not what may be decided.
- Hints: one sentence that `<dampf.h>` provides them, link [Hints](/reference/hints).
  Intrinsics are not built: link [Limits](/reference/limits).

## Out of scope

- The full rules, every construct, the counts from the game scan: [The C subset](/reference/c-subset).
- How the subset helps the optimizer: [Optimizations](/inside/optimizations).
