# Hints

Hints tell dampf what a human assembly programmer would know about the program but the C code
does not say. This is the agreed design; where the hints fit into the milestones is in
[roadmap.md](roadmap.md).

| Hint | Built |
| --- | --- |
| `DAMPF_RANGE` | yes |
| `DAMPF_BUDGET` | yes ([timing.md](timing.md)) |
| `DAMPF_TRIPS` | no, postponed (see "As built") |
| `restrict` | no, rejected by the front end ("not supported yet") |
| `DAMPF_LIKELY` / `DAMPF_UNLIKELY`, unroll hint, `DAMPF_PIN_X`, `DAMPF_SPLIT`, `DAMPF_TABLE`, `DAMPF_DP` | no |
| subrange types (`DAMPF_RANGED`, `DAMPF_AS`) | no ([subrange-types.md](subrange-types.md)) |

## The idea

Most tricks of an assembly programmer rest on knowledge: "this value is always below 16", "these
two pointers never overlap", "this branch is rare". Knowledge can be written down. dampf lets the
programmer state it in the C source as hints, so the compiler knows what the human would know.

Hints are macros from `<dampf.h>`. In the dampf build they expand to built-ins the dampf parser
recognises (for example `__dampf_range(...)`). In the PC build they expand to plain C: a checked
assertion for hints that state a fact, nothing for the others (see the rule below). The source
stays valid C, and one preprocessor (`gcc -E`) serves both builds, so both builds see the same
code. Inside dampf a fact hint becomes an assumption in the IR ([architecture.md](architecture.md)).

## Catalogue

The working names are the names.

| What a human knows | Hint in C | What dampf does with it |
| --- | --- | --- |
| "i is always below 16" | `DAMPF_RANGE(i, 0, 15)` | 8 bit instead of 16 where that is enough, no range checks, index directly in X, arrays can be aligned so indexed reads never cross a page ([timing.md](timing.md)) |
| "these pointers never overlap" | `restrict` (or a dampf macro) | Values stay in registers instead of being reloaded after a store through a pointer |
| "this branch is rare" | `DAMPF_UNLIKELY(cond)` / `DAMPF_LIKELY(cond)` | The common path falls through without a jump. Often unnecessary once profile data from replays is used ([optimizations.md](optimizations.md)). |
| "this loop runs at most 16 times" | `DAMPF_TRIPS(16)` | Unrolling, an 8-bit counter, and a loop bound for the timing analysis |
| "unroll this small loop" | an unroll hint (`DAMPF_TRIPS` with a small bound may cover it) | The loop is unrolled |
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
`BMI` without a compare. The programmer decides *what* the code represents; dampf takes care of
the instructions.

## Rule: every hint that states a fact is a checked assertion on the PC

A wrong hint produces wrong code, silently. If `i` ever reaches 16 despite
`DAMPF_RANGE(i, 0, 15)`, the SNES build misbehaves without any error. Therefore every hint that
states a fact is checked in the PC and test builds:

- `DAMPF_RANGE(i, 0, 15)` asserts `0 <= i && i <= 15` on the PC. Ranges that belong to a type
  rather than to one point in the code are subrange types (`DAMPF_RANGED`, with the checked
  conversion `DAMPF_AS`), see [subrange-types.md](subrange-types.md).
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

## As built

`<dampf.h>` comes with dampf (in its own include directory, and as `include/dampf.h` next to the
launcher in the distribution). With dampf (`__DAMPF__` defined) its macros expand to built-ins
the front end reads; on the PC to `assert`. For the PC build, `gcc -idirafter <dampf>/include`
makes `<dampf.h>` reachable while the host's own `<stdint.h>` still comes first. The execution
tests compile the PC side that way.

**`DAMPF_RANGE(v, lo, hi)`**, a statement: the local variable or parameter `v` (an integer type)
lies in `lo..hi` at this point; both bounds are constants of `v`'s type. Misuse is a compile
error (a global or a `static` local, bounds that are not constants or do not fit the type, `lo`
above `hi`).

- **In MIR** the hint becomes comparisons that branch to a block ending in `unreachable` when
  `v` is outside the range (the role of LLVM's `llvm.assume`). The range analysis treats them
  like any condition: `v` has the range in every block the hint dominates, so
  `while (s[n]) { DAMPF_RANGE(n, 0, 10); n++; }` gives the loop a bound for the timing analysis
  and the counter a range for the narrowing.
- **The MIR interpreter** fails at `unreachable` ("a hint does not hold"), so the tests check
  every hint on the MIR level as well.
- **The code generator** computes the ranges first and then takes the checks out
  (`dampf.mir.Hints`): every branch to such a block becomes a jump to its other side. The hint
  costs no cycles.
- **On the PC** it is `assert`, written as one unsigned comparison so that `DAMPF_RANGE(n, 0,
  10)` on an unsigned `n` gives no "always true" warning under `-Wextra`. `NDEBUG` switches it
  off.
- **The naive code generator** ignores the hint.

**`DAMPF_BUDGET(n)`**, in front of a function definition: see [timing.md](timing.md). The naive
code generator has no timing and does not check budgets.

**`DAMPF_TRIPS` is postponed.** On the PC it would have to count the rounds per entry into the
loop, and a macro in front of or inside a loop cannot see the entry (nor a `break` or `return`
on the way out) without changing the loop. `DAMPF_RANGE` on a counter in the loop covers the
loops of the game that need a bound (the `while (s[n])` loop above, a queue index); a loop
without any counter can be given one.
