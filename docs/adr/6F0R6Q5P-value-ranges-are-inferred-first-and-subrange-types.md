# ADR-6F0R6Q5P: Value ranges are inferred first, and subrange types wait for a profile

**Context.** The design for value ranges ([subrange-types.md](../subrange-types.md)) has two
parts: ranges dampf infers from the code, and subrange types declared in the source
(`DAMPF_RANGED`, with the checked conversion `DAMPF_AS`), which change the language.

**Decision.** Decided 2026-11-17: the parts that need no language change come first, inferred
ranges and pointer compression in M3, page-aligned arrays with 8-bit index registers in M4.
Declared subrange types are built only when a profile shows a hot value whose range dampf cannot
infer and that costs real cycles.

**Consequences.** Inferred ranges (`Ranges`, `RangeNarrow`, `ParamRanges`) are built, and the
tests check every value the MIR interpreter computes against its range. `DAMPF_RANGED`,
`DAMPF_AS`, their checker and the debug ROM are not scheduled.
