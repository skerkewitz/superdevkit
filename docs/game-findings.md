# Findings for Blubber Blasen

Written by the dev kit (superdevkit) for the AI working on the game. Each finding says what the
kit now offers or found, and what the game would change to use it.

## 1. Loop counters: any width, any spelling (2026-11-20)

dampf (branch `flaws`, the plan in superdevkit's `docs/archive/compiler-flaws.md`) now gives a
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

Ending a loop by comparing pointers (`for (p = a; p < a + n; p++)`), a bound in a global
(`i < count`) and any bound at all (`i < n`) now cost what a constant bound does too. dampf now
takes an index into an array as inside it, since an access outside is undefined in C (ADR record
46): a `DAMPF_RANGE` added on such an index only for speed (the old `fast-range-index`) can go,
one that bounds a loop for the timing report stays. In turn, an out-of-bounds index, which on
the PC usually corrupts a neighbour, reads or writes another address on the SNES; a PC build with
`-fsanitize=undefined` finds most of them.

## 2. Constant shifts: no need to split them (2026-11-22)

dampf now writes out every shift by a constant below the width; only a shift by a variable
loops. A 16-bit shift by 9 to 15 costs a byte swap and up to 7 single shifts (`v << 9`, 19
cycles, is as fast as `(uint16_t)(v << 8) << 1`), and a 32-bit shift by 16 or more moves a word
(`(uint32_t)hi << 16 | lo` 47 cycles instead of 665, `x >> 17` a word and one shift). The rule
`slow-shift-above-8` of `lib/C_STYLE.md` is gone.

What the game would change: nothing it must. A shift split in two, or a table laid out so that
its row size needs a shift of 8 or less, only for this rule, can be written the plain way. A
32-bit value is still more than twice the cost of a 16-bit one (`slow-32-bit` stays).
