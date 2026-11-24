# ADR-6FM063YZ: Plain `char` is text: a `char` value is only copied, tested and compared with `==` and `!=`

**Context.** The subset left plain `char` open since M0 ([subset.md](../subset.md), decision 1).
Blubber Blasen uses `char` only for text: string tables, names and small buffers. Plain `char`
is signed with gcc and clang on x86-64 and with MSVC, and unsigned with gcc on ARM. A program
that does arithmetic on a `char` can therefore compute a different result on two PCs, as soon as
a byte from 128 up is in it: `c - '0'`, `c < 'a'`, `table[c]` and `int16_t x = c` all see -56 on
one and 200 on the other. dampf's results have to match gcc on x86-64 bit for bit, and that is
only half a promise if the same source does something else on an ARM machine. dampf gave `char`
the x86-64 meaning (signed 8 bit) and did not restrict it. Before this decision the types `char`
and `int8_t` were the same in the front end; `signed char` and `unsigned char` were, and stay,
rejected outside dampf's own `<stdint.h>`.

**Decision.** Plain `char` stays a signed 8-bit type, as on x86-64, and is for text only: the
front end lets a `char` value be used only in ways whose result is the same whether `char` is
signed or not. Allowed:

- copying it: assignment to a `char`, a `char` parameter, a `char` result, an element of a `char`
  array, and string literals and `char` arrays and pointers as such (pointer arithmetic is on
  the pointer, not on the char);
- storing any number into a `char` (`s[0] = (char)('0' + d)`): the byte is the same on every PC;
- converting it to another 8-bit type, implicitly or with a cast (`uint8_t u = c`, `(int8_t)c`):
  also the same byte;
- testing it for zero (`if (c)`, `while (*s)`, `!c`, `&&`, `||`, the condition of `?:`);
- `==` and `!=` against another `char` or against a constant from 0 to 127 (a character
  constant, `'\0'`, 0); a `switch` on a `char` whose case values are all from 0 to 127;
- `sizeof` and `&`.

Rejected, with a message that says what to write instead (`uint8_t` or `int8_t` for a number, or
a cast to `(uint8_t)` first): arithmetic (`+ - * / %`, unary `-`, `+`, `~`), shifts and bit
operations, `< <= > >=`, `==` and `!=` against anything else (`c == -1`, a `uint8_t` variable),
`++`, `--` and compound assignment on a `char`, a `char` as an index or a pointer offset, a
conversion to a wider type (`int16_t x = c`, a `char` passed to an `int16_t` parameter, a cast
`(int16_t)c`), and a `?:` with a `char` branch (its type is `int`). Digit arithmetic is among
the rejected forms: `c - '0'` is the same on both PCs only for ASCII digits, and the front end
cannot know that `c` holds one, so it is written `(uint8_t)c - '0'`. A cast to an 8-bit type
first is always the way out. Decided by the user on 2026-11-24, as
the subset's recommendation proposed.

**Alternatives.**

- A, `char` as text with this rule: the same source gives the same result on every PC and on the
  SNES, and string literals keep their type. Code that turns a letter into a tile number needs a
  cast. Chosen.
- B, allow also the arithmetic whose result is cut back to 8 bits (`(uint8_t)(c - ' ')`): `+`,
  `-`, `*`, `&`, `|`, `^` and `<<` give the same low byte whether the operands were signed or not,
  so this is exact too and would leave more existing code alone. But the front end would have to
  look at where a value goes, not only at the operator, and the rule would be harder to state.
  It can still come later without breaking anything that the chosen rule accepts.
- C, `char` unrestricted, signed as now: nothing to check, and a program can differ between an
  x86-64 PC and an ARM one without any warning.
- D, `char` unsigned (as on ARM): the same problem the other way round, and against what most
  users' PCs do.
- E, no plain `char` at all, text as `uint8_t` (`typedef uint8_t text_t` in the game): string
  literals have type `char[]` in C, so every string would need a cast, and the game would have
  to change 27 declarations.

**Consequences.** The front end tells plain `char` from `int8_t` (`Type.CHAR`, a flag on the
integer type that equality ignores), so nothing changes for the code generators and the
generated code of an accepted program stays the same. Two examples, Ertüp and Schach, did digit
or tile arithmetic on a `char` and now cast to `uint8_t` first, with the same results. Game code
that computes with a letter, such as a tile index from a character, gets an error at each place
and needs the same cast; the message says so. `signed char` and `unsigned char` stay rejected
in game code; `int8_t` and `uint8_t` are the number types. Rule B stays possible.
