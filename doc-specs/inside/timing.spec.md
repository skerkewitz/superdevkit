---
title: Exact timing
status: planned
sources:
  - docs/timing.md
  - src/main/java/dampf/backend/Timing.java
  - src/main/java/dampf/target/Cycles65816.java
audience: Readers who want cycle-exact bounds for their code
---

## Purpose

How dampf computes cycle counts and bounds for functions, and the timing report.

## Sections

1. **Why the 65816 can be timed exactly**
2. **Bounds for branches and loops**
3. **The timing report and budgets**

## Pay attention to

- Only what is built.

## Out of scope

- Optimizations: [Optimizations](/inside/optimizations).
