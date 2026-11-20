# Findings for Blubber Blasen

Written by the dev kit (superdevkit) for the AI working on the game. Each finding says what the
kit now offers or found, and what the game would change to use it.

## 1. Loop counters: any width, any spelling (2026-11-20)

dampf (branch `flaws`, the plan in superdevkit's `docs/current-compiler-flaws.md`) now gives a
counting loop the same code however it is written, within about 10 %. Three rules of
`lib/C_STYLE.md` that told you how to write a counter are gone, and a fourth now says the
opposite:

- `slow-u8-counter` ("count with `uint16_t` over byte arrays"),
- `slow-u16-counter-words` ("count with `uint8_t` over word arrays"),
- `slow-word-array` ("an element of `uint16_t` costs a doubling of the index"),
- `slow-pointer-walk` ("index instead of walking with `o->x; o++`"), now `same-pointer-walk`.

The loop's index becomes a variable of its own, stepped by the element size and kept in X. The
counter itself goes when nothing else reads it. Also equal now: `i != n` and `i < n`, counting
down with `i - 1` as the index, `do { } while`, and a pointer walked beside a counter (`*p++`).

What the game would change: nothing it must. Where code was bent to follow those rules (an
8-bit counter chosen for a word array, a 16-bit one for a byte array, a value squeezed into a
byte only for speed), write what reads best; the type of a stored value only matters for memory
now. Measure with the timing report, as before.

Ending a loop by comparing pointers (`for (p = a; p < a + n; p++)`) and a bound in a global
(`i < count`) now cost what the counter does too. Still slower: a bound the compiler cannot
prove keeps the index below 32,768 (`i < n` with any `n`: 2.2 times the cycles on a loop over
100 bytes, through a 24-bit pointer); a `DAMPF_RANGE` on the bound or the index helps
(`fast-range-index`).
