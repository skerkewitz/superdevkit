# ADR-6F0R6752: `gcc -E` preprocesses, without host headers or host macros

**Context.** The same source must mean the same thing in the PC build and in dampf's build,
including the hints, which are macros (ADR-6F0R68AN). Host headers and host macros (glibc's
`<stdint.h>`, `__x86_64__`) would let the dampf build see other code than the PC build tests.

**Decision.** dampf preprocesses with `gcc -E -nostdinc -undef -D__DAMPF__=1` and its own
include directory, which has `<stdint.h>` and `<dampf.h>`. The line markers (`# 142 "bubble.c"`)
stay, for the source positions. Anything `gcc -E` accepts is allowed. The PC build reaches
`<dampf.h>` with `gcc -idirafter <dampf>/include`, so the host's own `<stdint.h>` comes first
there.

**Consequences.** Using dampf needs gcc, which on Windows means MinGW or MSYS only for this.
M5 plans a preprocessor of dampf's own in Java, with `gcc -E` as the oracle in the tests
([roadmap.md](../roadmap.md)). There is no `<string.h>` or other library header unless dampf
ships one.
