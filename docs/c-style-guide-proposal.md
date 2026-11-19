# Proposal: a style guide for C with dampf

Status: built, 2026-11-19, as [lib/C_STYLE.md](../lib/C_STYLE.md) with `StyleGuideTest`; the
measurements below are from dampf at commit `1e8c0c6`, and the guide's table has today's (some
differ: see the guide). `superdevkit init`'s pointer waits for M7.

## Why

Whoever writes C for a superdevkit game needs four answers: what dampf accepts, what it rejects,
what is slow on the 65816, and what is fast. A fifth is just as useful: what makes no difference,
so nobody rewrites code for nothing. Today these answers are spread over
[subset.md](subset.md) (allowed and rejected), [optimizations.md](optimizations.md), the
README of Steinschlag ("What costs time on the 65816") and conversations. The book's page
"Writing C for dampf" covers what is allowed, not what is fast.

Almost all of the code is written by an AI (Claude), for Blubber Blasen, for the examples and
for Schach. So the guide has a first reader with particular needs, and a human second reader.
An AI follows a rule it is given exactly and everywhere, also where it does not pay: a rule
like "use `uint8_t` for counters" that was true once becomes code that is slower everywhere
once the compiler changes. An AI also cannot tell a rule of thumb from a measurement unless the
text says which it is. And it reads the whole guide into its context every time, so the guide
must be short.

The hints show what happens without such a guide: Blubber Blasen uses none, `DAMPF_BUDGET` is
used nowhere, and Schach uses `DAMPF_RANGE` twelve times without anyone having measured whether
it helps. In the measurement below it does not do what one would expect.

## What the first measurements show

A small program of paired functions, built with `dampf build`, read from the timing report
(worst case in CPU cycles, `docs/timing.md`). Each pair computes the same thing two ways.

| Question | Variant | Worst case (cycles) |
| --- | --- | --- |
| Loop counter over 200 bytes | `uint8_t i` | 8430 |
| | `uint16_t i` | 5025 |
| Element width, same loop | `uint8_t a[200]` | 8430 |
| | `uint16_t a[200]` | 8230 |
| Where the locals are declared | all at the top (C89 style) | 8430 |
| | where first used (C99 style) | 8430 |
| 32 objects, two fields each | `objs[i].x` (array of structs) | 1585 |
| | `px[i]`, `py[i]` (one array per field) | 1617 |
| | `Obj *o`, `o++`, `o->x` (pointer walk) | 1647 |
| Multiplication | `va * vb`, 16 bit | 411 |
| | `ua * ub`, 8 bit | 417 |
| | `va * 40` | 31 |
| | `(va << 5) + (va << 3)` by hand | 45 |
| Division | `va / vb` | 816 |
| | `va / 8`, unsigned | 17 |
| | `v / 8`, signed | 322 |
| | `v / 8`, signed, with `DAMPF_RANGE(v, 0, 1000)` | 265 |
| 32 bit | `(uint32_t)va + ((uint32_t)vb << 16)` | 665 |

Several of these go against what one would write from general 65816 knowledge:

- An 8-bit loop counter is slower than a 16-bit one. dampf counts `i++` of a `uint8_t` with
  `sep #$20` / `inc` / `rep #$20` and then loads `i` again for the compare; the 16-bit counter
  needs none of that. The same holds for the `and #$00FF` after each byte load.
- A pointer walk is slower than indexing, and an array per field is not faster than an array of
  structs.
- A multiplication of two bytes costs as much as one of two 16-bit values: both call the
  runtime's `dampf_mul16`; the SNES's 8 x 8 multiplier is not used.
- Writing a multiplication by a constant as shifts by hand is slower than writing `* 40`.
- A signed division by 8 computes its rounding correction with two loops of `lsr` (by 15 and
  by 13), and `DAMPF_RANGE(v, 0, 1000)`, which proves `v` is never negative, does not remove
  that correction.
- A 32-bit shift by 16 is a loop of 16 `asl`/`rol` instead of moving one word.

Most of these are gaps in dampf, not facts about the 65816. They go to the optimizer's list
(roadmap M9 and M12) as well. Until they are fixed, they are what the guide has to say, and the
guide must notice when they are fixed. That decides its form.

## What the guide is

One file, the source of truth, in five parts:

1. **Rejected.** What `dampf check` rejects, one line each, with the checker's message as it
   prints it. Taken from [subset.md](subset.md), not repeated there in other words: plain
   `int`, `short`, `long`, `unsigned`; floating point; recursion; heap; function pointers;
   `goto`; unions and bit fields; structs by value as parameters or results; `f()` and K&R
   parameters; variable-length arrays; compound literals; designated initializers; `_Bool`;
   volatile variables (only pointers to volatile memory are allowed).
2. **Allowed, and easy to miss.** What an AI might avoid without need: declarations anywhere
   and in the `for`, struct assignment, `static` locals and constant tables, multi-dimensional
   arrays, `switch`, `?:`, enums as constants, the hints.
3. **Slow.** Each rule with the cost in cycles and the cheaper way, if there is one.
4. **Fast.** What dampf does well, so the clear form is also the fast form: multiplication and
   unsigned division by a constant, indexing an array of structs, a value used once (it stays in
   the accumulator and gets no place in memory).
5. **No difference.** Where the declaration is, `i++` or `++i`, `a[i]` or `*(a + i)`, `for` or
   `while`, a named temporary or the expression inline. To be measured; the first is above.

Each rule has a stable name (`slow-mul-var`, `same-decl-place`), one sentence in the
imperative, a pair of examples (the form to avoid and the form to use, or two forms that cost
the same), and the measured cycles with the date of the measurement. A rule about speed also
says how hot code must be for it to matter: the general rule is to write the clear form, and to
apply a speed rule only in a function the timing report or the profile shows to be hot, or one
that has a `DAMPF_BUDGET`.

The hints get their own short section with when to use them, from measurements, not hopes:
`DAMPF_BUDGET` on every function called each frame whose budget matters, and `DAMPF_RANGE` only
where a measurement shows it changes the code. Today that is nowhere the guide can name.

## Every speed rule is a test

The pairs of functions live in the repository, for example `src/test/resources/style/`, one
file per rule, each function marked with the rule and the relation it claims (`faster`,
`slower`, `same`). A test (`StyleGuideTest`) compiles them, reads the timing report and checks
each relation. Each pair is also an execution test: both functions must give the same result as
each other and as gcc on the PC, so a "fast" form that changes the meaning cannot get in.

When dampf improves and a relation no longer holds (the 8-bit counter becomes as fast as the
16-bit one, say), the test fails, and the rule is changed or dropped in the same commit as the
improvement. The guide therefore never says something the compiler no longer does, and an AI
following it to the letter does no harm. The numbers in the guide are written by the test,
not by hand.

## Where it lives

For the AI the guide has to be in reach in every game's repository, not only in dampf's. The
kit ships it as one file (for example `lib/C_STYLE.md`); `superdevkit init` (roadmap M7) writes
a line into the new project's `CLAUDE.md` or `AGENTS.md` that points to it, and Blubber Blasen
gets that line through `docs/game-findings.md`. The book gets a page "Writing fast C" in the
user guide, generated from the file by `/docs-gen` like the other pages, so the facts have one
home. This repository's `CLAUDE.md` points to it too, for
the examples.

## Steps

1. The measurement folder and `StyleGuideTest`, with the pairs above plus the ones of part 5 not
   measured yet. Every relation measured on today's dampf.
2. The guide file, written from the measurements and from `subset.md`; the pointer from
   `CLAUDE.md`.
3. The book page, its spec in `doc-specs/guide/`, generated.
4. The gaps found by the measurements (8-bit counters, the 8 x 8 multiplier, shifts by a
   constant as loops, `DAMPF_RANGE` and signed division, 32-bit shifts by 16) as rows in the
   roadmap's optimizer tables, each naming the rule it would retire.
5. When `superdevkit init` is built: the pointer in new projects.

## Open questions

- Is the timing report's worst case the right number for every rule, or do some need the
  measured cycles of a run in `dampf.emu` (a loop whose bound the analysis cannot see)?
- Should a failing relation fail the build, or only warn until the guide is updated? Failing is
  stricter and is what keeps the guide true; it also means an optimizer change cannot land
  without touching the guide.
- The console API's costs (what a DMA of n bytes costs in the vertical blank, what a sprite
  call costs) belong to the hardware guide: [snes-hardware-guide-proposal.md](snes-hardware-guide-proposal.md).
