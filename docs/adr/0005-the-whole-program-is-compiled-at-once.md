# 5. The whole program is compiled at once

**Context.** With separate compilation, the optimizer of one file sees neither the callers of
its functions nor the uses of its variables in other files. A game the size of Blubber Blasen
compiles in seconds.

**Decision.** All `.c` files of a program are compiled as one: there is no separate
compilation. `static` names stay local to their file. `dampf check` runs the front end on every
file alone, then on all of them together (recursion, functions called but defined nowhere,
definitions in two files).

**Consequences.** Every optimization sees the complete call graph and all uses of every
variable. That is what fixed frame places (record 6), parameter ranges from all call sites,
inlining and pointer compression across calls rest on. A change in one file compiles everything
again. Code compiled by another compiler cannot be linked in; hand-written assembly comes in
through `dampf build --replace`.
