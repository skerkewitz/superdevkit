# ADR-6FD2DGP0: clang is the tests' reference on the PC

**Context.** The tests compare every program dampf compiles with the PC (ADR-6F0R6G1M): built with
the undefined behaviour sanitizer, it must run without a report, and a plain optimized build gives
the values the SNES must match. Both builds were gcc's. gcc 16.2.1's bounds check
(`-fsanitize=bounds`) gets `a[i] op= (... ? a[i] : ...)` wrong: it computed wrong values (random
loop program, seed 1157), and it reports an index the program never computes, optimized (seed
15241) and, on 2026-11-22, unoptimized as well (seed 53266: an index of 42 into 16 bytes in one
run, 737 in the next). The build takes 50 new loop programs every day, so a false report fails
every build of that day, CI included, until the date changes. clang's sanitizer and gcc's without
the bounds check find nothing in these programs, and clang's catches a real index out of bounds.
ADR-6F0R64GD already promises the results gcc and clang give on x86-64; they differ only where C
leaves the result open, which the subset and the test programs avoid.

**Decision.** clang builds the tests' programs on the PC: with `-fsanitize=undefined` at `-O0` for
the check, and at `-O1` for the values (`PcReference`, `SchachTest`; `LibNamesTest` compiles the
library with it too). The rest of ADR-6F0R6G1M stays: the ROMs, the MIR interpreter, every global
compared. clang is a tool for developing the kit; using it does not need clang.

**Alternatives.**

- gcc for the values and clang only for the check: two compilers where one does both jobs.
- gcc, and clang to check again when gcc's bounds check reports: the result would depend on
  whether clang is installed.
- Random programs without the shape gcc gets wrong: gives up tests of dampf because of a bug in
  the tool.

**Consequences.** Developing the kit needs clang (the README's prerequisites, the CI workflow
installs it); dampf still preprocesses with `gcc -E` (ADR-6F0R6752) until it has its own
preprocessor. Measured before the switch, with clang's sanitizer and clang's values
next to gcc's: the execution tests, the build's random programs and 2,000 programs of each
generator (seeds 53000 to 54999, 53266 among them) gave no report, and clang's values were gcc's
in every program.
