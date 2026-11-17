---
title: Hints
status: planned
sources:
  - docs/hints.md
  - docs/subrange-types.md
audience: Users who want to tell dampf what it cannot infer
---

## Purpose

The hint macros, what each tells dampf, and how each is checked in the PC build.

## Sections

1. **How hints work** – macros, built-ins with dampf, checked assertions on the PC.
2. **The hints** – one H3 per hint: what it states, an example, the PC check.
3. **Subrange types** – `DAMPF_RANGED`.

## Pay attention to

- Only hints that are built.

## Out of scope

- How the optimizer uses them: [Optimizations](/inside/optimizations).
