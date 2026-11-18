# 27. `goto` and labels stay rejected, and a forward and outward form is the most dampf will allow

**Context.** The subset left `goto` open since M0 ([subset.md](../subset.md), decision 4), and
dampf rejects it: a `goto` with "not supported yet: 'goto'", a label with "not supported yet:
labels and goto" (`AstBuilder`). Blubber Blasen does not use `goto`, and its author has not used
it in twenty years. The one use that would make sense in game code is leaving nested loops. A
flag does that for a few cycles, and a helper function with `return` costs nothing once dampf
inlines it (14 cycles for `JSL` and `RTL` when it does not). The other common uses do not apply:
cleanup after errors (`goto cleanup`) needs resources that a game without `malloc` or files does
not have, and computed `goto` (`goto *p`) is a GNU extension, not C99.

What matters for dampf is the control flow a `goto` can create. A `goto` that only jumps forward
and out of statements (out of two loops to a label after them, say) keeps the control flow
reducible: every loop has exactly one entry, and everything dampf does today keeps working: loop
detection through dominators (`dampf.mir.Loops`), induction variables, loop counters in X,
hoisting invariant code, and the worst-case bounds in `Timing`. An unrestricted `goto` can jump
into the middle of a loop. That loop then has two entries and the control flow is irreducible:
dampf would not recognise it as a loop, none of the loop optimizations apply, and `Timing` finds
no bound, so the function's worst case is unbounded. Fixing that needs node splitting (copying
code until every loop has a single entry) or an error message.

**Decision.** dampf keeps rejecting `goto` and labels. The question is deferred, not closed: if
real code turns up that needs `goto`, dampf may allow the forward and outward form (option B
below), and no more than that. Unrestricted `goto` will not be built.

**Alternatives.**

- A, keep rejecting `goto`: no work, and the structured control flow guarantees that every
  analysis applies. Code taken from elsewhere has to be rewritten without `goto`. Chosen.
- B, `goto` only forward and outward, never into a block or a loop: covers the one useful case
  and keeps every analysis valid, for about a day of work (labels and `goto` mapped onto MIR
  blocks in the lowering, a checker for the restriction with a clear message, an execution
  test). The cost is a rule of dampf's own ("`goto` yes, but only like this") that users have to
  learn. The most dampf will ever allow.
- C, unrestricted `goto`: full C99, but slow loops without timing bounds wherever it creates a
  second loop entry, and a lot of work for a case nobody needs. Ruled out.

**Consequences.** Nothing changes in dampf today. The question comes back when code that dampf
should compile needs `goto` and rewriting it is not reasonable, most likely after M5, when users
bring code from elsewhere; then option B is built, and nothing else in dampf has to change for
it. The timing analysis can go on relying on loops with a single entry.
