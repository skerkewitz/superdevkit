# ADR-6F60ZDMF: M5 is concluded as 0.6.0, and a version between milestones may carry features

**Context.** ADR-6F3CJT1P releases milestone N as 0.N.0 and lets only fixes raise the last number.
M5 was set as 0.5.0, but the kit reached 0.5.0 before M5 was done, and 0.5.1 and 0.5.2 followed on
2026-11-19 with new features of M7 (sound effects, ADR-6F60ZC7C; the BRR Lab's editor and its
tests). On the same day Blubber Blasen showed M5's goal reached: its SNES ROM, built from its own
repository with a local kit (dampf 0.5.2, `lib/snes`, `superdevkit sfx`), played four recordings
of 36,000 ticks each (Arcade, with the CPU partner, Plus, Plus with the partner) with the PC's
checksum on every tick (sprite table and both tilemaps), and its DSP writes equalled Rachael's.
0.6 has no milestone of its own: M6 was done ahead of M5 and is part of 0.4.0. The transition
plan still listed steps under M5 that its goal does not need.

**Decision.**

- M5 is concluded as version 0.6.0. A concluded milestone raises the middle number; since 0.5 was
  in use before M5 was done and 0.6 was free, M5's conclusion takes 0.6.0. M7 stays 0.7.0, and
  milestone N from M7 on is 0.N.0 as before.
- Between two milestones the last number rises for any change worth naming, fixes and features
  alike; features of a later milestone that are built early (as M7's were during M5) do not wait
  for its version.
- M5's remaining steps leave it: random programs and execution tests that reach the optimizer go
  to M9; `hello` as the book's getting-started example goes to the release work, with the book
  (ADR-6F60ZB4N); the ROM on a real console waits for the hardware and stays a risk.

**Alternatives.** Concluding M5 with 0.5.2, the version current when its goal was shown: it keeps
0.N for milestone N, but a milestone's end would not show in the number. Keeping ADR-6F3CJT1P as
written: 0.5.1 and 0.5.2 would have been outside the rule, and M7's features would have had to
wait for 0.7.0 while the game already used them.

**Consequences.** The roadmap marks M5 as done with 0.6.0 and moves its section to the history.
Until M7 is concluded as 0.7.0 the last number of 0.6 goes on rising. ADR-6F3CJT1P is amended; its
rule that milestone N is 0.N.0 stays for M7 on.
