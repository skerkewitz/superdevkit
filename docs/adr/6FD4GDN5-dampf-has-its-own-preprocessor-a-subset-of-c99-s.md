# ADR-6FD4GDN5: dampf has its own preprocessor, a subset of C99's

**Context.** dampf preprocessed with `gcc -E -nostdinc -undef -D__DAMPF__=1` (ADR-6F0R6752), so
using dampf needed gcc, on Windows MinGW or MSYS only for this; the first public release waits
for a preprocessor of dampf's own (ADR-6F60Z9QJ, roadmap M10). Since ADR-6FD2DGP0 the tests take
clang as the reference on the PC, so the kit would have needed two C compilers. A preprocessor
has gathered many features over the years; the C dampf compiles (the examples, the library,
dampf's headers, the test programs and the game) uses few of them: includes, object-like and
function-like macros, `#` once, `##` in a few places, `#if` with simple arithmetic and
`defined`, and `#pragma`. None of it uses `__VA_OPT__`, `__FILE__`, `__LINE__`, `__COUNTER__`,
`_Pragma`, `__has_include`, `#include_next`, a computed include, `#line` or gcc's extensions.

**Decision.** dampf preprocesses with a preprocessor of its own in Java (`dampf.frontend.pp`), a
subset of C99's, as the language is a subset of C:

- Built: `#include "..."` and `<...>`, searched as gcc with `-nostdinc -I` searches;
  `#define` and `#undef`; object-like, function-like and variadic macros (`__VA_ARGS__`); `#`
  and `##`; macro expansion exactly as C99 6.10.3 says it (Prosser's algorithm: arguments
  expanded before they are put in, except next to `#` and `##`; the result scanned again; a
  macro does not expand within itself); `#if`, `#elif`, `#ifdef`, `#ifndef`, `#else`, `#endif`
  and `defined`, the arithmetic in `intmax_t` and `uintmax_t`; `#error`; `#pragma`, passed on,
  and `#pragma once`; `__FILE__`, `__LINE__`, `__STDC__`, `__STDC_VERSION__` (199901L),
  `__STDC_HOSTED__` and `__DAMPF__`, and the macros given with `-D`.
- Rejected with an error that names them: `__VA_OPT__` (C23), `#line`, `#include_next`, an
  include of a macro, `#warning`, `#ident` and gcc's other directives, named variadic parameters
  (`args...`), `__COUNTER__`, `__DATE__`, `__TIME__`, `_Pragma`, `__has_include`.
- Not handled: trigraphs, digraphs and universal character names.
- What stays from ADR-6F0R6752: no host headers and no host macros, dampf's own include
  directory, line markers in the text for the source positions. A token from the file stands at
  its own line, as gcc writes it; a token out of a macro at the line of the invocation.

**Alternatives.**

- gcc or clang, whichever is installed: a C compiler stays a prerequisite of using dampf, and
  the release still waits for M10.
- A preprocessor with every feature of gcc's: about twice the work for features nothing uses.
- Keeping clang's line for the rest of a line after a comment or an invocation over several
  lines: gcc's places each token at its own line, which is right for the debug information and
  the error messages, and what dampf had.

**Consequences.** Using dampf needs nothing but Java. The tests compare the preprocessor with
`clang -E` on every C file dampf compiles in the repository (the same tokens at the same file and
line), on random macro programs (the same tokens, 300 in every build), and on the standard's
examples; the generated code of every test program and example stayed the same to the byte.
Supersedes ADR-6F0R6752. The desktop build still preprocesses with its own compiler: the two
agree because dampf's preprocessor agrees with clang's and gcc's on the subset.
