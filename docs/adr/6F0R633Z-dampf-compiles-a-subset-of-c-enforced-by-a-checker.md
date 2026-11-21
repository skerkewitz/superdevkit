# ADR-6F0R633Z: dampf compiles a subset of C, enforced by a checker after a broad parser

**Context.** An optimizer for all of C can assume little. Blubber Blasen's `src/game` (14 `.c`
files and 4 headers, about 8,000 lines, 291 functions) was scanned in M0 with
`tools/subset_scan.py` (clang's AST) and `gcc -fcallgraph-info`. It uses no `float`, no heap or
library function, no recursion, no function pointers, no unions or bit fields, no structs by
value, no `goto` and no variadic functions.

**Decision.** dampf compiles a subset of C that it defines itself: what the game uses, plus a
little room around it. [subset.md](../subset.md) lists it. A checker enforces it: everything
outside the subset is rejected with a message that names the construct and the place, and
nothing outside it is ever compiled silently. The parser accepts more C than the subset on
purpose, so that a construct outside it is reported by the checker ("recursion is not
supported: f → f") rather than by a parse error. `dampf check` runs these checks without
building a ROM.

**Consequences.** Every assumption the optimizer makes is guaranteed by the checker. The subset
grows when a program needs more; its open questions are in [subset.md](../subset.md). Code
written for another compiler may need small changes: for Blubber Blasen, two casts with plain
`(unsigned)` in `video.c`.
