# Hints

Hints tell dampf what a human assembly programmer would know about the program but the C code
does not say. None of them is built yet; this is the agreed design (kickoff and design discussion,
2026-11-17). Where they fit into the milestones is in [roadmap.md](roadmap.md).

## The idea

Most tricks of an assembly programmer rest on knowledge: "this value is always below 16", "these
two pointers never overlap", "this branch is rare". Knowledge can be written down. dampf lets the
programmer state it in the C source as hints, so the compiler knows what the human would know.

Hints are macros. In the dampf build they expand to built-ins the dampf parser recognises (for
example `__dampf_range(...)`). In the PC build they expand to plain C: a checked assertion for
hints that state a fact, nothing for the others (see the rule below; the kickoff first said
"nothing" for all of them, the design discussion changed that). The source stays valid C, and one
preprocessor (`gcc -E`) serves both builds, so both builds see exactly the same code. Inside dampf
a fact hint becomes an `assume` operation in the IR ([architecture.md](architecture.md)).

## Catalogue (working names)

| What a human knows | Hint in C | What dampf does with it |
| --- | --- | --- |
| "i is always below 16" | `DAMPF_RANGE(i, 0, 15)` | 8 bit instead of 16 where that is enough, no range checks, index directly in X, arrays can be aligned so indexed reads never cross a page ([timing.md](timing.md)) |
| "these pointers never overlap" | `restrict` (or a dampf macro) | Values stay in registers instead of being reloaded after a store through a pointer |
| "this branch is rare" | `DAMPF_UNLIKELY(cond)` / `DAMPF_LIKELY(cond)` | The common path falls through without a jump. Often unnecessary once profile data from replays is used ([optimizations.md](optimizations.md)). |
| "this loop runs at most 16 times" | `DAMPF_TRIPS(16)` | Unrolling, an 8-bit counter, and a loop bound for the timing analysis |
| "unroll this small loop" | an unroll hint (from the kickoff; `DAMPF_TRIPS` with a small bound may cover it) | The loop is unrolled |
| "X holds the current bubble during the whole update" | `DAMPF_PIN_X(cur)` for a block or a module | The value stays in X, also across function boundaries |
| "store this array of structs as one array per field" | `DAMPF_SPLIT` on the array | One array per field, accessed as `lda bub_x,x` |
| "this function only ever gets 256 different inputs" | `DAMPF_TABLE` on the function | dampf evaluates the function for every input **at compile time** and places the results as a table in ROM. At run time the call becomes one indexed read. |
| "this variable belongs in the direct page" | `DAMPF_DP` | A fixed direct page slot |
| "this function must finish within N cycles" | `DAMPF_BUDGET(n)` | A compile error if its worst case is higher ([timing.md](timing.md)) |

`DAMPF_TABLE` shows how far this goes: with a known input range, the compiler can do what a human
would do with a hand-made table or a generator script.

Blubber Blasen's `BB_FAST` macro (the direct page placement for Calypsi) maps onto `DAMPF_DP`.

## Representation decisions stay in C

Some knowledge is not a fact about the program but a decision about how it represents things,
such as using bit 7 as a flag. Those decisions are written in C by the programmer, and dampf
recognises the patterns. If a state is defined as `0x80`, then `if (flags & 0x80)` becomes `BIT` +
`BMI` without a compare. The division of work: the programmer decides *what* the code represents;
dampf takes care of the instructions.

## Rule: every hint that states a fact is a checked assertion on the PC

A wrong hint produces wrong code, silently. If `i` ever reaches 16 despite
`DAMPF_RANGE(i, 0, 15)`, the SNES build misbehaves without any error. Therefore every hint that
states a fact is checked in the PC and test builds:

- `DAMPF_RANGE(i, 0, 15)` asserts `0 <= i && i <= 15` on the PC.
- `DAMPF_TRIPS(16)` counts loop iterations on the PC and asserts the count stays within 16.
- `restrict` can be checked on the PC by an address-overlap assertion where practical.
- `DAMPF_PIN_X`, `DAMPF_SPLIT`, `DAMPF_DP` and `DAMPF_TABLE` do not state facts, they request a
  transformation; they need no assertion. (`DAMPF_TABLE` does require the function to be pure;
  the subset checker verifies that.)
- `DAMPF_BUDGET` expands to nothing on the PC; the emulator test builds can check the measured
  cycles against it ([timing.md](timing.md)).

The replays run with these assertions enabled, so a wrong hint is caught on the PC before it can
do damage on the SNES.

## Limits of hints

1. **Knowledge alone is not enough.** dampf must also know the matching trick. The reference
   assembly benchmarks ([optimizations.md](optimizations.md)) are the loop that keeps teaching it.
2. **Too many hints make code unreadable.** Hints belong only where measurement shows the code is
   hot. Most of the code should carry none.

## Open questions

- How `restrict` is checked on the PC in practice, and whether a dampf-specific no-alias hint is
  clearer than `restrict`.
- The exact names and the set of hint macros are decided in M0's follow-up (see
  [roadmap.md](roadmap.md)).
