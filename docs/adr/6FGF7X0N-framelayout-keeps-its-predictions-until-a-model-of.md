# ADR-6FGF7X0N: FrameLayout keeps its predictions until a model of the whole function replaces them

**Context.** ADR-6F8NBG4P expected the part of `FrameLayout` that only predicts to go once φs went
through their incoming places. ADR-6F8NBHZ6 built that, and selection still reads the predicted
places for three decisions: whether a value is stepped in place with `INC` or `DEC`, whether a φ's
operand is already in the φ's place, and whether a pointer lies in the direct page for `[dp],y`.

Measured on 2026-11-23 against `af009ad`
([log](../optimizations-log.md#framelayouts-predictions-measured-and-kept-2026-11-23)): with the
first two decisions made without the predictions, the examples do 1.1 % more work (Steinschlag
4.9 %, hello 2.1 %), and 104 worst cases grow, by up to 8 %, while 14 shrink. The third decision
has real cases: 5 accesses through a pointer in Er-Tüp are predicted outside the direct page and
go through `dampf_ptr`. Selection also relies on that test to keep a value in A over an address
(`addressNeedsNoA`).

**Decision.**

- `FrameLayout` keeps its liveness, interference and colouring, as proposals for these three
  decisions.
- They are looked at again when the register allocator gets a cost model of the whole function
  (row 2 of the roadmap's table), which `findXCounters` and `findYIndexes` are waiting for too.

**Alternatives.** Replace only the step in place, with a pass after the colouring that turns load,
add, store into `INC` or `DEC`: about a day of work, but the pointer decision still needs the
predicted addresses, so almost no code would go. Remove all three: that also needs every pointer
access written for the direct page, and a new selection of a function whose pointer does not fit,
or a rewrite on the LIR through `dampf_ptr` that preserves A. It is a few days in the code where
the miscompilations of November 2026 were, with no speed to gain, and a whole-function allocator
would replace much of it.

**Consequences.** This amends the last sentence of ADR-6F8NBG4P: the predictions stay after the φ
change. Liveness is computed twice for a function's places, on MIR for the proposal and on LIR for
the addresses. A wrong prediction still costs at most a different address, so the cost of keeping
them is code to maintain.
