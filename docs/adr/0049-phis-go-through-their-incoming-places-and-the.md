# 49. φs go through their incoming places, and the colouring coalesces the copies

**Context.** Record 48 made the LIR colouring give the places their addresses, with
`FrameLayout`'s prediction as each place's first choice. `FrameLayout` still decided which
blocks get their φs written directly by the predecessors ("direct"), with conditions on the
branches and on φs that read each other; three of the miscompilations of November 2026 were
there (`b33eeba`, `02fa308`, `50d5c04`). Without direct φs every φ costs two copies, one at the
end of each predecessor and one on entry: 8.5 % more work in the examples' benchmark.

**Decision.**

- Every φ goes through its incoming place. The two copies are `LCopy` items, between owned
  places; once the places have addresses each becomes `LDA`, `STA` or, where both got the same
  address, no code. `FrameLayout` has no direct φs any more.
- The colouring coalesces: the two places of every copy become one where they do not conflict,
  before colouring (aggressive coalescing). A copy conflicts only in the bytes it does not copy.
- The colouring gives each place the lowest free address in the order of the code, with no
  prediction as first choice.
- `RedundantLoads` moves a load that starts a block, redundant on some ways in and not on ways
  that only jump there, to the end of those (a loop head's counter, which the copy on entry used
  to leave in A).

**Alternatives.** Keep direct φs and their prediction: the source of the bugs above. Remove the
copies after the fact (a store of what memory holds, a load nobody reads): two more LIR passes,
and the copies would still cost places.

**Consequences.** The examples do 0.3 % less work than with direct φs (Ertüp 1.4 % less). A φ
copy is no code wherever the liveness on the code allows it, not only where `FrameLayout`
guessed so. `FrameLayout` still predicts places for selection's decisions: a value stepped in
place, a pointer in the direct page, a value handed on in A.
