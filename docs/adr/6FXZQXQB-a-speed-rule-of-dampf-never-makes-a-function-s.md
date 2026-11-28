# ADR-6FXZQXQB: A speed rule of dampf never makes a function's worst case larger

**Context.** A game's frame budget is a worst case: `DAMPF_BUDGET` checks a function's static
worst case from the timing report (ADR-6FVMDV79), and a tick that is fast on average still drops
a frame when its worst path runs. Some of dampf's speed rules gain on average and lose on the
worst path. Parts of loops in registers, chosen by the weights of the branches alone, made the
examples do 1.8 % less work instead of 1.4 %, and Er-Tüp 4.9 % instead of 2.9 %, but 40 worst
cases grew by up to 6.9 %. The signed comparisons by subtraction need their second operand in
memory, where the biased form keeps both in X and Y, and made 11 worst cases larger, by up to
48 master cycles. Asked on 2026-11-27 whether to keep the larger average gain, the user decided:
the gains, but no worst case larger.

**Decision.**

- A new speed rule in dampf's optimized code generator may not make the static worst case of
  any function larger than `develop`'s, as the timing report gives it. A rule that would is
  applied only where it does not.
- Where that cannot be told before the code is finished, dampf writes the function both ways
  and keeps the one with the smaller worst case: `MirCodeGen.finishedWorst` for the parts of
  loops in registers, and the biased signed comparisons (`BranchSelection.biased`) against the
  subtraction. When both are equal, the faster on average is kept.
- A pull request that changes dampf's output compares the worst case of every function in the
  timing reports of the execution tests, the random programs and the examples
  (`BackendOutputTest`'s output) against `develop`, and says how many are smaller, the same and
  larger. Larger is a failure of the change, not a trade-off to weigh.
- The rule is about dampf's choices for the same C. A change of the C, by a person or by a
  finding for the game, is weighed on its own.

**Alternatives.**

- Weighing the average gain against the growth of the worst cases, rule by rule: more speed on
  average (the parts of loops above), but a budget that held could fail with a new kit, and a
  game would find out only at its next build.
- A worst case that may grow within a margin (1 %, say): budgets are set close to what a function
  takes, so any growth can break one, and many small margins add up.

**Consequences.** A rule can gain less than it would without the check: the parts of loops gave
1.4 % instead of 1.8 % on the examples. Writing a function twice costs compile time (Er-Tüp 14.0
to 17.4 seconds with the parts of loops, 19.9 to 22.5 with the signed comparisons), which the
user accepted. A game's budgets that hold with one kit hold with the next for the same C, unless
the timing model itself changes, as with master cycles (ADR-6FVMDV79). The naive code generator
is unchanged.
