# ADR-6F0R61EZ: dampf is a compiler of its own for one target, the SNES

**Context.** Stage 3 of Blubber Blasen, the SNES port, needs a C compiler for the 65816 whose
code is fast enough for the game logic to fit into a frame. The existing ones were tried.
PVSnesLib with 816-tcc made very slow code, and the data layout needed workarounds because the
compiler threw errors on ordinary code. Calypsi works as intended, but took 2.72 frames per tick
during play on an older version of the game. llvm-mos makes good 6502 code but no native 16-bit
65816 code; LLVM assumes many uniform registers and values of fixed width, while on the 65816 the
operand width (8 or 16 bit) is processor state set by `REP` and `SEP`. Hand-written assembly for
the whole game was tried years ago and failed.

**Decision.** dampf is a compiler of its own for a subset of C, with exactly one target: the
65816 in the SNES. The aim is a compiler that makes SNES games fast, not a general C compiler,
and it may assume what a general compiler cannot:

- one CPU and one machine, no portability layers;
- a subset of C that dampf defines and a checker enforces (ADR-6F0R633Z);
- the whole program compiled at once (ADR-6F0R65FW);
- hints in the source for knowledge it cannot infer (ADR-6F0R68AN);
- C as the contract that keeps the same source compiling on the PC with gcc or clang, which
  makes the code testable (ADR-6F0R64GD).

The goals: the game logic of Blubber Blasen's busiest scene fits into 60 % of a frame; the same
source behaves bit-identically on the PC and the SNES; the C source can be debugged in Mesen;
the generated assembly is commented so a human can follow it; every optimization is checked
automatically against a reference; in the end nothing to install but Java. Not goals: full C or
conformance beyond the subset, other CPUs or targets, a debugger of its own, floating point,
fast compilation.

**Alternatives.** The four tools above. Hand-written assembly for a few hot functions stays
possible (`dampf build --replace`), but not as the plan.

**Consequences.** dampf starts with one user and test case, Blubber Blasen, and runs as a
project of its own in parallel with the game. The code keeps the 65816 core and the SNES target
apart, but nothing is built for a second target.
