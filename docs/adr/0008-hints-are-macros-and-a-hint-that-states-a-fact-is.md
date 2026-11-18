# 8. Hints are macros, and a hint that states a fact is checked on the PC

**Context.** Most tricks of an assembly programmer rest on knowledge the C code does not say:
"this value is always below 16", "these two pointers never overlap", "this branch is rare". A
wrong statement of such a fact produces wrong code, silently: if `i` reaches 16 despite
`DAMPF_RANGE(i, 0, 15)`, the SNES build misbehaves without any error.

**Decision.**

- Hints are macros from `<dampf.h>`. In the dampf build they expand to built-ins the front end
  reads, in the PC build to plain C, so the source stays valid C and one preprocessor serves
  both builds (record 7). The working names are the names.
- A hint that states a fact is a checked assertion in the PC and test builds
  (`DAMPF_RANGE(i, 0, 15)` asserts `0 <= i && i <= 15`), and the replays run with the assertions
  on. Inside dampf it becomes an assumption in the IR, which the MIR interpreter checks as well.
- A hint that asks for a transformation (`DAMPF_PIN_X`, `DAMPF_SPLIT`, `DAMPF_DP`,
  `DAMPF_TABLE`) states no fact and needs no assertion.
- Decisions about how a program represents things stay in C, written by the programmer, and
  dampf recognises the patterns: a state defined as `0x80` makes `if (flags & 0x80)` a `BIT`
  and `BMI`.

**Consequences.** A wrong hint is caught on the PC before it can do damage on the SNES.
Knowledge alone does not make code faster: dampf must also know the matching trick, which the
reference functions in `reference/` keep teaching it. Hints belong only where a measurement
shows the code is hot. The catalogue and what is built are in [hints.md](../hints.md).
