# ADR-6F0R6G1M: gcc with UBSan is the oracle, and the tests compare every global

**Context.** Correctness is the biggest risk. A miscompile does not crash; it makes a bubble pass
through a wall in round 37, sometimes. Every optimization has to be checked automatically against
a reference.

**Decision.**

- The reference is gcc on the PC. Each test program is built by gcc with the undefined behaviour
  sanitizer, so a program with undefined behaviour fails instead of passing by luck.
- dampf builds it into a ROM with each code generator (ADR-6F0R6FT6); each also runs in the MIR
  interpreter after the lowering and after the passes, with the verifier after every pass.
- When the entry function returns, the runtime writes to `$21FB`, and every global is read and
  compared, flattened into its scalar elements. Pointers (their values are addresses) and
  constant data in ROM are left out.
- The layers: unit tests, execution tests (`src/test/resources/exec`, one per language feature),
  random programs free of undefined behaviour by construction, the game's replays, and the game's
  benchmark. Every program test has a hard time limit.

**Consequences.** A new language feature needs an execution test. A wrong lowering or pass shows
up in the interpreter, independent of any 65816 code. Speed regressions are not caught this way;
`BackendOutputTest` and the measured numbers cover that ([testing.md](../testing.md)).
