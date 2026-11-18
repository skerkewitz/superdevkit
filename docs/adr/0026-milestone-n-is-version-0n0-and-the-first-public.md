# 26. Milestone N is version 0.N.0, and the first public version is an alpha

**Context.** dampf had no version a user could see; `build.gradle.kts` said `0.1.0-SNAPSHOT`.
With M5 it goes public and Blubber Blasen builds with it from its own repository, so a build of
the game has to name the dampf it was made with. On 2026-11-18 M0 to M4 and M6 were concluded,
and what was left of M3 and M4 moved to new milestones ([roadmap.md](../roadmap.md#history)).

**Decision.**

- The version is in `build.gradle.kts` and nowhere else; `dampf --version` prints it, and the
  distribution carries it in its name.
- Milestone N is released as 0.N.0; fixes between two milestones raise the last number. The
  state of 2026-11-18, with M0 to M4 and M6 concluded, is 0.4.0. M6 is part of it, so there is
  no 0.6.0.
- Every version before 1.0 is a pre-release: the subset, the command line and the files dampf
  writes may change between versions. 0.5.0, the end of M5, is the first public version and is
  called an alpha. What 1.0 needs is not decided.

**Alternatives.** Versions independent of the milestones, raised by what changed (semantic
versioning from the start): before 1.0 nearly every milestone breaks something, so the numbers
would say little more than the milestone does.

**Consequences.** A milestone's version is known before it starts, and the roadmap shows it
next to the milestone. A milestone that is split or merged changes the numbering of the ones
after it; numbers already released stay.

Records 1 to 25 name milestones of the plan before 2026-11-18. Where their work went: dampf's
own preprocessor (record 7) from M5 to M8, page-aligned arrays with 8-bit index registers
(record 23) from M4 to M7, subrange types (record 23) to M10, and better C debugging in Mesen,
planned "after M5" (record 20), to M9.
