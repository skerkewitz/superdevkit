---
title: Limits
sources:
  - lib/README.md
  - docs/ADR.md
  - docs/adr/*.md
  - README.md
  - docs/roadmap.md
  - docs/subset.md
  - docs/hints.md
  - docs/timing.md
  - docs/optimizations.md
  - lib/snes/README.md
audience: Users who need to know what dampf does not do yet before they rely on it
---

## Purpose

One place that lists what dampf does not do, so that other pages can link here instead of
repeating plans: what is not built yet, and what will never be.

## Sections

1. **Not built yet** – what the SNES platform layer of the playable ROM leaves out (sound, save
   RAM), hints, intrinsics, timing in master cycles and outside the CPU (DMA, interrupts), an
   own preprocessor (M10; gcc is needed until then), other planned items of the roadmap that a user would notice.
   For each, a sentence on what it will do and what to do meanwhile, if the sources say.
   Also what a user must provide for a ROM of their own game: a
   [platform layer](/glossary#platform-layer) for the library its code calls (the one in
   `lib/snes` implements Blubber Blasen's API only).
2. **Never planned** – the README's non-goals that limit a user: full C, other targets, an own
   debugger, floating point.
3. **Known differences from gcc** – anything where dampf's result can differ from the PC build,
   if the sources name any; otherwise one sentence that none is known and that the tests compare
   every program with gcc. A known miscompilation counts here: say whether a default build
   meets it.

## Pay attention to

- Name the milestone each planned item belongs to, and link [Roadmap](/inside/roadmap).
- No dates or promises beyond what the roadmap says.

## Out of scope

- The full roadmap: [Roadmap](/inside/roadmap).
