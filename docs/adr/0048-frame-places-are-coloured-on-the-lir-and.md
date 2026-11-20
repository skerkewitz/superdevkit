# 48. Frame places are coloured on the LIR, and FrameLayout only proposes

**Context.** Record 47 made selection write places that name their owners, and left open how they
get their addresses. `FrameLayout` coloured the places on MIR before any code existed, predicting
what selection would write; four miscompilations in November 2026 were wrong predictions, and the
place check of B2 (docs/current-compiler-flaws.md) found a fifth on its first run. Selection
still decides a few things from the predicted places: that a value is stepped in its operand's
place, that a φ's operand is in the φ's place, that a pointer lies in the direct page for
`[dp],y`.

**Decision.**

- After selection, the places of each function are coloured on its LIR: liveness of their bytes
  over the code, a place conflicts with every other place live where it is written, unless the
  write copies the same byte of that place (then the two may share exactly one address), and
  greedy colouring by byte ranges.
- What selection relied on sharing it marks (`LMark`); marked places are one. Parameters, frame
  slots, φ incoming places, callee parameters and the values computed in them keep fixed places.
- Each place takes the address `FrameLayout` predicted if it is free there, else the lowest free
  one; pointers read as `[place],y` are coloured first and must lie in the direct page.
- Functions are coloured callee first: a frame begins where the highest frame of everything it
  calls ends, so a frame that shrinks moves its callers down.
- What selection assumes about A, X and Y is matched by owner, not by address, so a load it
  leaves out stays right wherever the places end up.

**Alternatives.** Colour on LIR without the prediction as first choice: tried, and correct (the
execution and random tests pass), but it changes the code of every program at once; it is the
next step, measured on its own. Keep colouring on MIR and check it with the place check: the
check finds a wrong prediction, but only as a compile error.

**Consequences.** A place selection never writes (a value handed on in A) takes no room. A wrong
prediction now costs at most a different address. `FrameLayout`'s colouring is a proposal; what
remains of it that only predicts goes when the φs go through their incoming places (B2).
