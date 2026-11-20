# Writing C for dampf

How to write the C of a superdevkit game: what dampf rejects, what it allows that is easy to
avoid without need, what is slow on the 65816 with dampf today, what is fast, and what makes no
difference. Written for whoever writes the code, an AI first: follow it as written.

**Two kinds of rule.** The rules in parts 1 and 2 are the language: they hold until the subset
changes ([docs/subset.md](../docs/subset.md)). The rules in parts 3 to 5 are measurements of
today's dampf. Each is a test (`StyleGuideTest`, the pairs in `compiler/src/test/resources/style/`, one
file per rule); the table at the end is written by it. When dampf changes and a claim no longer
holds, the build fails and the rule changes or goes in the same commit.

**Write the clear form.** Apply a speed rule (part 3) only in code that is hot: a function the
timing report (`<rom>.timing.txt`, written by `dampf build`) or `dampf profile` shows to be
expensive, or one with a `DAMPF_BUDGET`. Elsewhere it costs readability for nothing.

## 1. Rejected

`dampf check` rejects these, with the message shown. Use what follows the colon.

| Construct | Message |
| --- | --- |
| `int`, `short`, `unsigned` | `'int' is not allowed: use int32_t (plain C integer types differ between the PC and the SNES)` (and `int16_t`, `uint32_t`) |
| `long` | `'long' is not supported: use int32_t or uint32_t (long is 64 bit on the PC)` |
| `float`, `double` | `'float' is not supported: dampf has no floating point` |
| recursion | `recursion is not supported: f → f` |
| `malloc`, any heap | no `<stdlib.h>`; a declared `malloc` gives `function 'malloc' is called but never defined` |
| function pointers | `not supported yet: this kind of declarator (function pointers, bit fields)` |
| `goto` | `not supported yet: 'goto'` |
| unions, bit fields | `unions are not supported`, `bit fields are not supported` |
| a struct as parameter or result | `structs passed by value are not supported: pass a pointer`, `structs returned by value are not supported` |
| `f()`, K&R parameters | `missing parameter type` (write `f(void)`), `not supported yet: old-style (K&R) parameter declarations` |
| variable-length arrays | `an array size must be a positive integer constant` |
| compound literals, designated initializers | `not supported yet: compound literals`, `not supported yet: designated initializers` |
| `_Bool` | `not supported yet: _Bool` (use `uint8_t`) |
| a `volatile` variable | `not supported yet: volatile variables (only pointers to volatile memory)` |
| inline assembly | `not supported yet: this statement` |

The library is `<stdint.h>`, `<string.h>` (`memset`, `memcpy`) and `<dampf.h>`; a game reaches
the console only through `sdevkit/snes.h` and `sdevkit/loop.h`.

## 2. Allowed, and easy to miss

All of these pass `dampf check` and cost nothing extra: declarations anywhere, also in the `for`
(`for (uint8_t i = 0; ...)`); struct assignment (`b = a;`, the way to copy a struct); `static`
locals; `const` tables, also multi-dimensional (they stay in ROM); `switch`; `?:`; enums as
constants; `memset` and `memcpy`; the hints `DAMPF_RANGE` and `DAMPF_BUDGET` (part 6);
pointers to arrays (`const uint8_t (*rows)[16]`).

One rule of C that the checks find the hard way: **the order in which a call's arguments are
evaluated is open**, and dampf need not choose gcc's. Two calls with side effects in one call's
arguments (`spawn(random16() & 31, random16() & 15)`) may run in another order on the SNES than
on the PC, and the game the checks compare (ADR record 38) is no longer the same. Give each such
call its own statement.

## 3. Slow

- **`slow-u16-counter-words`** In a loop over an array of `uint16_t`, count with `uint8_t` (if the
  count fits): dampf then drops the counter and steps the index register by 2, where it keeps a
  16-bit counter in memory beside it. This rule is a gap in dampf, not the 65816: both forms
  should cost the same. Over an array of bytes they do. Elsewhere (a loop over a table of two
  dimensions, a counter used in 8-bit arithmetic) the difference is small and goes either way:
  keep what reads better, and measure a hot loop in both forms with the timing report.
- **`slow-word-array`** Store values in the narrowest type they fit: an element of `uint16_t`
  costs a doubling of the index on every access.
- **`slow-pointer-walk`** Index an array (`a[i].x`) instead of walking it with a pointer
  (`o->x; o++`).
- **`slow-index-list`** Walk all slots with a counter and skip the free ones
  (`if (!used(&a[j])) continue;`) rather than a list of the used slots' indexes
  (`&a[list[k]]`), unless most slots are free: dampf steps a counter's element offset by the
  element's size, where an index loaded from a list is multiplied by the size on every element.
  Measured in Blubber Blasen's pair loop too, where the list cost three times as much a line.
- **`slow-multiply-variable`** Multiply by a constant where the factor is one: `va * vb` calls
  the runtime, `va * 40` is shifts and adds.
- **`slow-divide-variable`** Divide by a constant power of two where you can: `va / vb` calls the
  runtime, `va / 8` of an unsigned value is a shift.
- **`slow-signed-divide`** Divide a value that cannot be negative as unsigned:
  `(uint16_t)v / 8`, not `v / 8` on an `int16_t`. The signed form computes a rounding correction
  for negative values, and a `DAMPF_RANGE` that proves `v` is not negative does not remove it.
- **`slow-hand-shifts`** Write a multiplication by a constant as a multiplication: `va * 40`, not
  `(va << 5) + (va << 3)`. dampf finds the shifts itself, and better.
- **`slow-shift-above-8`** Split a shift by a constant above 8: `(uint16_t)(v << 8) << 1`, not
  `v << 9` (a row of 512 bytes in a table). A shift by 8 is a byte move, one above 8 a loop of
  single shifts.
- **`slow-32-bit`** Keep 32-bit values out of hot code: every operation on one goes through
  memory, more than twice the cost of a 16-bit one. A value only combined bit by bit (a hash) can
  be two `uint16_t` halves.
- **`slow-compare-sum`** Compare with `(int16_t)(y + 32)`, not `y + 32`, where the sum fits: C adds
  an `int16_t` and a constant in `int`, which has 32 bits, so the comparison is a 32-bit one, as the
  sum may not fit 16 bits. The cast says it does. (Two `uint8_t` added stay 16-bit: dampf sees that
  they cannot overflow.)

## 4. Fast

- **`fast-array-per-field`** In hot code, one array per field (`px[i]`, `py[i]`) is a little
  cheaper than an array of structs (`objs[i].x`). The gain is small: keep the struct elsewhere.
- **`fast-range-index`** An array of more than 256 elements reached with a 16-bit index goes
  through a 24-bit pointer, unless dampf knows the index stays inside the array: a `DAMPF_RANGE`
  on the index (`DAMPF_RANGE(n, 0, N - 1); a[n] = v;`) makes it a direct indexed access.
- A multiplication or an unsigned division by a constant is cheap (`slow-multiply-variable`,
  `slow-divide-variable`): write it as such, in the clear form.

## 5. No difference

Write whichever reads better; these cost the same:

- **`same-declaration-place`** a local declared at the top or where it is first used;
- **`same-increment-form`** `i++` or `++i`;
- **`same-index-form`** `a[i]` or `*(a + i)`;
- **`same-loop-form`** `for` or the same loop as `while`;
- **`same-named-temporary`** a named temporary or the expression written inline: a value used
  once stays in the accumulator either way;
- **`same-multiply-width`** a multiplication of two bytes or of two 16-bit values: both call the
  runtime (the SNES's 8 x 8 multiplier is not used), so narrowing the factors buys nothing.

## 6. The hints

`<dampf.h>` has two (the book's page "Hints" has the details):

- **`DAMPF_BUDGET(n)`** in front of every function called each frame whose time matters, at
  least `game_tick` and `game_vblank` with their callees: a worst case above `n` CPU cycles is a
  build error, above 90 % a warning, so a slower change is found when it is made.
- **`DAMPF_RANGE(v, lo, hi)`** only where a measurement shows it changes the code. Today that is
  `fast-range-index`, and a loop the timing report calls unbounded (a hint on its counter gives
  it a bound). A hint states a fact: a wrong one makes wrong code on the SNES and stops the
  program on the PC.

## Measurements

The worst case of each function in CPU cycles, from the timing report; "is" is the claim the
test checks ("same": within 5 %).

<!-- measurements: written by StyleGuideTest -->
Measured 2026-11-20 (worst case of the timing report, code generator from MIR).

| Rule | This | CPU cycles | is | than this | CPU cycles |
| --- | --- | ---: | --- | --- | ---: |
| `fast-array-per-field` | `move_array_per_field` | 1,721 | faster | `move_array_of_structs` | 1,881 |
| `fast-range-index` | `store_with_range` | 56 | faster | `store_without_range` | 103 |
| `same-declaration-place` | `declared_at_top` | 5,025 | same | `declared_where_used` | 5,025 |
| `same-increment-form` | `count_postfix` | 5,025 | same | `count_prefix` | 5,025 |
| `same-index-form` | `read_indexed` | 5,025 | same | `read_pointer_arithmetic` | 5,025 |
| `same-loop-form` | `loop_for` | 5,025 | same | `loop_while` | 5,025 |
| `same-multiply-width` | `multiply_bytes` | 417 | same | `multiply_words` | 411 |
| `same-named-temporary` | `with_temporary` | 45 | same | `inline_expression` | 45 |
| `slow-32-bit` | `xor_32` | 84 | slower | `xor_16_halves` | 36 |
| `slow-compare-sum` | `compare_sum` | 164 | slower | `compare_sum_narrowed` | 98 |
| `slow-divide-variable` | `divide_variables` | 816 | slower | `divide_constant` | 17 |
| `slow-hand-shifts` | `times_40_by_shifts` | 45 | slower | `times_40` | 31 |
| `slow-index-list` | `pairs_by_list` | 20,858 | slower | `pairs_by_counter` | 15,590 |
| `slow-multiply-variable` | `multiply_variables` | 411 | slower | `multiply_constant` | 31 |
| `slow-pointer-walk` | `move_by_pointer` | 1,943 | slower | `move_by_index` | 1,881 |
| `slow-shift-above-8` | `shift_by_9` | 130 | slower | `shift_by_8_then_1` | 19 |
| `slow-signed-divide` | `divide_signed` | 265 | slower | `divide_unsigned` | 17 |
| `slow-u16-counter-words` | `fill_u16_counter` | 1,945 | slower | `fill_u8_counter` | 1,233 |
| `slow-word-array` | `sum_words` | 7,033 | slower | `sum_bytes` | 5,025 |
<!-- end of measurements -->
