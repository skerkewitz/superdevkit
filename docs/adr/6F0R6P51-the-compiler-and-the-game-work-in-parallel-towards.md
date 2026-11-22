# ADR-6F0R6P51: The compiler and the game work in parallel towards the frame budget

**Context.** After M2 the goal of 0.6 frames per tick needed the naive code to get 9 to 11
times faster on average and 14 to 20 times on the worst tick. The optimizations were expected to
bring 3 to 6 times, and the first reference function agreed (4.5 times). Even with hand-written
quality everywhere, play would have stayed at about 1.2 to 1.7 frames on average, and the worst
tick with the CPU partner at 3 to 4 frames. The compiler alone cannot close the gap.

**Decision.** Decided at the checkpoint after M2 (2026-11-17): compiler and game in parallel.
dampf goes on with M3; the game works on the hot spots no compiler can fix, the CPU partner and
the pairwise bubble separation. Relaxing the requirement (for example 30 Hz game logic on the
heaviest ticks) stays a fallback, decided on measured numbers if rare worst ticks still do not
fit.

**Alternatives.** The compiler first and a decision after M3; relaxing the requirement now.

**Consequences.** What dampf finds worth changing in the game goes to
[game-findings.md](../game-findings.md), and the game's changes happen in its own repository;
dampf refreshes its snapshot and measures. Whether the budget is met depends on work outside
dampf.
