---
title: Roadmap
sources:
  - lib/README.md
  - docs/ADR.md
  - docs/adr/*.md
  - docs/roadmap.md
  - README.md
audience: Users and contributors who want to know what comes next
---

## Purpose

The milestones, their state, and the open questions, in a form a user can read without the
working history of `docs/roadmap.md`.

## Sections

1. **Where dampf stands** – the current version and what the kit can do with it, in two or three
   sentences.
2. **Versions** – milestone N is 0.N.0, the version is the dev kit's, everything before 1.0 is
   a pre-release, publishing is a step of its own.
3. **Milestones** – a table: milestone, goal, version, state. Then one short section each for the
   milestones in progress (M9, the optimizer; M10, language, platform and usability), saying
   what of them is built and what is to come, one paragraph each for the planned milestones
   after them, and one short section each for what is not scheduled for the dev kit (with the
   roadmap's future ideas in a sentence or two) and for publishing.
4. **The checkpoint after M2** – the decision that the compiler and the game work in parallel,
   with the numbers that led to it.
5. **Risks and open questions** – from the roadmap, those a user would care about.

## Pay attention to

- Leave out the step-by-step history; keep what is decided and what is open. The concluded
  milestones get their row in the table, not a section.
- Plans are allowed here, unlike on the other pages. Write for a user who wants to know what
  they get and when; leave out the passes and classes a step is built in.
- Blubber Blasen's switch to the kit's desktop and web builds is the game's own work, handed
  over to it; say so where it matters, without describing the game's repository.
- No figures of Calypsi or 816-tcc (frames per tick, cycles, "times as fast"), and no open
  question about them (Calypsi's drift): the kit no longer uses or measures them. A milestone's
  goal may still name Calypsi in words, as the roadmap's table does.

## Out of scope

- What is not built, as a list for users: [Limits](/reference/limits).
