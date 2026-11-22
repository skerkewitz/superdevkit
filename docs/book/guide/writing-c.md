<!-- GENERATED from doc-specs/guide/writing-c.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Writing C for dampf

dampf does not compile all of C. It compiles a [subset](/glossary#subset) that it defines
itself, and it rejects everything outside it with a message that names the construct and the
place; nothing outside the subset is ever compiled silently. The subset is what a game needs:
it was taken from the C of [Blubber Blasen](/glossary#blubber-blasen), plus a little room
around it. Because the same source must also build with gcc on the PC, every rule below keeps
the code valid C99 that gives the same result in both builds.

## Integer types say their width

Declare integers with the types of `<stdint.h>`: `int8_t`, `uint8_t`, `int16_t`, `uint16_t`,
`int32_t` and `uint32_t`. dampf rejects plain `int`, `short`, `long`, `unsigned`, `signed` and
`long long`, because their size differs between compilers: `int` is 32 bits with gcc on the PC
and 16 bits with the other 65816 compilers, 816-tcc and [Calypsi](/glossary#calypsi). A
declaration such as `int lives;` in line 3 of `lives.c` stops with:

```
lives.c:3:1: error: 'int' is not allowed: use int32_t (plain C integer types differ between the PC and the SNES)
```

The rule also covers casts: `(unsigned)a` is rejected in the same way as a declaration. The
one exception is `int main(void)`, which the PC build needs; on the SNES its return value is
ignored.

dampf ships its own `<stdint.h>`, and its typedefs use the same underlying types as gcc on the
PC (`int32_t` is `int`, `int16_t` is `short`), so each type means the same thing on both sides.
It also defines the limits (`INT8_MIN` to `UINT32_MAX`) and the `INT8_C` to `UINT32_C` macros.

Plain `char` is allowed, for text such as string tables and name buffers. dampf gives it the
meaning it has with gcc on x86-64, a signed 8-bit type.

## Same arithmetic as gcc on the PC

The [PC build](/glossary#pc-build) is the reference: what your code computes on the SNES must
match what gcc computes on an x86-64 PC, bit for bit. So dampf follows C's rules exactly as gcc
applies them there. Integer constants and the integer promotions use a 32-bit `int`: an
`int8_t` or `uint16_t` operand becomes an `int` before the operation. Overflow of an unsigned
type wraps, a conversion to a narrower type truncates, signed division rounds towards zero, and
`>>` of a negative value is an arithmetic shift.

Two functions of the execution test `compiler/src/test/resources/exec/calls.c` show the
promotion; the block leaves out the rest of the file:

```c [compiler/src/test/resources/exec/calls.c]
static uint8_t inc8(int8_t x)
{
    return x + 1;               /* int arithmetic, then truncated to uint8_t */
}

static int8_t neg8(uint8_t x)
{
    return -x;                  /* -(int)x, then converted to int8_t */
}
```

In `neg8`, `-x` is not the negation of an unsigned byte: `x` becomes an `int` first, so
`neg8(127)` computes the `int` value -127 and converts it to `int8_t`, which gives -127. In the
same way `inc8(127)` computes 128 as an `int` and truncates it to `uint8_t`, which gives 128,
and `inc8(-1)` gives 0. The promotion shows wherever the intermediate value is used before it
is truncated: for an `int8_t x` holding 127, `x + 1 > 127` is true.

Computing everything in 32 bits would be slow on the 65816. dampf narrows an operation to 8 or
16 bits, but only where it can prove the result is the same as in 32 bits.

One rule of C itself lets the two builds differ: the order in which the arguments of a call are
evaluated is left open, and dampf need not choose gcc's. Two calls with side effects in the
arguments of one call, as in `spawn(random16() & 31, random16() & 15)`, may run in one order on
the SNES and in the other on the PC, and from then on the two builds compute different values.
Give each such call a statement of its own:

```c
void spawn_enemy(void)
{
    uint8_t col = random16() & 31;
    uint8_t row = random16() & 15;
    spawn(col, row);
}
```

## What is left out

The constructs the subset rejects most often:

| Construct | Why it is left out |
| --- | --- |
| `float`, `double` | the 65816 has no floating point, and the game needs none |
| Recursion | every local has a fixed address, which is only correct without recursion |
| Function pointers | dampf must see the complete call graph |
| `union`, bit fields | not used by the game |
| Structs passed or returned by value | not used by the game; pass a pointer instead |
| `goto` and labels | would need a more general timing analysis |
| `malloc`, any heap | all objects are static, so memory use is known at compile time |
| Library functions other than `memset` and `memcpy` | dampf ships no other library |

Recursion is reported with the cycle of calls, for example
`recursion is not supported: count_down → count_down`. Struct assignment is allowed: it is a
copy of known size. A pointer to an array, such as `const uint8_t (*rows)[16]` for a table of
16-byte rows, is allowed as well; only a pointer to a function is rejected.

The one library header is dampf's `<string.h>`, with `memset` and `memcpy` for clearing and
copying memory. Their value is a byte and their size a 16-bit number, since there is no plain
`int` and no object is larger than 32 KB, so a call written for the standard header, such as
`memset(tiles, 0, sizeof tiles)`, works the same. The PC build uses the host's `<string.h>`.

Write a function without parameters as `void game_init(void)`. An empty list,
`void game_init()`, is rejected with `missing parameter type`.

A `sizeof` whose value differs from gcc's on the PC is rejected as well, because the program
would compute other values than its PC build. That is a `sizeof` of a pointer (4 bytes in dampf,
8 on x86-64) or of a struct that gcc pads (dampf does not pad struct fields). The message gives
both values. The element count `sizeof a / sizeof a[0]` is allowed for any array, because its
value is the same on both sides. To copy a struct that gcc pads, use a struct assignment
instead of `memcpy`, since its `sizeof` is rejected.

The complete list, with every construct and the counts from the scan of the game, is in
[The C subset](/reference/c-subset). The kit's own guide `lib/C_STYLE.md` lists each rejected
construct with the message dampf gives for it and what to write instead.

[Hints](/glossary#hint), macros such as `DAMPF_RANGE` that tell dampf something it cannot
infer, come from dampf's header `<dampf.h>`; in the PC build they become checked assertions
or nothing. [Hints](/reference/hints) describes them. Intrinsics, functions for
SNES hardware such as the hardware multiplier and divider or DMA, are not built yet; see
[Limits](/reference/limits).

## One program, not separate files

dampf compiles all `.c` files of a game together, as one
[whole program](/glossary#whole-program). There is no separate compilation and no linking of
object files: you name every `.c` file on the command line at once. `static` names stay local
to their file as in C, so two files, or two functions, can each have a static variable of the
same name.

Seeing the whole program lets dampf know every call and every use of every variable. It builds
the complete call graph, which is possible because there are no free function pointers, checks
that it has no cycle, and so can give every local of every function a fixed address.

## The preprocessor

dampf preprocesses each file with a preprocessor of its own, a subset of C99's, as the language
it compiles is a subset of C. Using dampf needs nothing but Java, no gcc. The preprocessor
searches only dampf's own include directory and the directories you give with `-I`, in that
order, and a `"..."` include first in the directory of the file that includes it. So
`#include <stdint.h>` finds dampf's own header, and `#include <stdio.h>` fails. The failure is
an ordinary error at the header's name in the line of the `#include`:
`stdio.h: No such file or directory`. The host's predefined macros such as `__x86_64__` are not
defined either. dampf defines `__DAMPF__` as 1, C99's `__STDC__`, `__STDC_VERSION__`
(`199901L`) and `__STDC_HOSTED__`, and the macros of your `-D` options. Line markers are kept,
so every message points to the line in your source.

The preprocessor has what game code uses: `#include`, object-like, function-like and variadic
macros with `#` and `##`, `#if`, `#elif`, `#ifdef`, `#ifndef`, `#else` and `#endif` with
`defined`, `#error`, and `#pragma` with `#pragma once`. Features beyond these, such as `#line`,
`#include_next`, `#warning` and gcc's other directives, `__VA_OPT__`, `_Pragma`,
`__has_include`, `__COUNTER__`, `__DATE__` and `__TIME__`, are rejected with an error that names
them: `#warning is not supported by dampf's preprocessor`. The complete list is in
[The C subset](/reference/c-subset#preprocessor).

Use `__DAMPF__` to keep a branch apart that only one build may see. Here the PC build prints a
trace with `printf`, and the dampf build never sees `<stdio.h>` or the `unsigned` cast:

```c
#include <stdint.h>

#ifndef __DAMPF__
#include <stdio.h>
#define TRACE_SCORE(s) printf("score %u\n", (unsigned)(s))
#else
#define TRACE_SCORE(s) ((void)0)
#endif

uint16_t score;

void add_points(uint8_t points)
{
    score = score + points;
    TRACE_SCORE(score);
}
```

The same works for macros that another compiler needs, such as an `__attribute__` for Calypsi:
give dampf its own `#ifdef __DAMPF__` branch.

## Checking your code

Run `dampf check` on a set of files to see whether they are in the subset, without building a
ROM. It takes the same `-I` and `-D` options as `dampf build`, checks each file on its own and
prints one line per file, then checks the files that pass together. Here `hud.c` has three
problems:

```c [hud.c]
#include <stdint.h>

float scale;
uint8_t lives;
long total;

void hud_draw(void)
{
    lives = 3;
    goto done;
done:
    return;
}
```

```sh
dampf check score.c hud.c
```

```
ok      score.c
FAILS   hud.c:3:1: error: 'float' is not supported: dampf has no floating point
        hud.c:5:1: error: 'long' is not supported: use int32_t or uint32_t (long is 64 bit on the PC)
        hud.c:10:5: error: not supported yet: 'goto'
1 of 2 file(s) pass the front end
the 1 file(s) together: ok
```

Every error is reported, at most one for each declaration or function at file scope: within
`hud_draw` the check stops at the `goto`, so the label after it gives no second line. Because
each file is checked on its own, one unsupported construct in one file does not hide the
problems of the others. `dampf build` reports the errors of all files in the same way.

The last line covers what only the whole program shows: recursion, a function that is called
but defined in none of the files, and a definition in two files. These checks take only the
files without errors, and `dampf build` runs them only once no other error is left. Here a
function in `rec.c` calls itself:

```c [rec.c]
#include <stdint.h>

void count_down(uint8_t n)
{
    if (n > 0) {
        count_down(n - 1);
    }
}
```

```sh
dampf check score.c rec.c
```

```
ok      score.c
ok      rec.c
2 of 2 file(s) pass the front end
FAILS   rec.c:6:9: error: recursion is not supported: count_down → count_down   (the 2 file(s) together)
```

The exit status is 0 only when every file passes and the files pass together.

All options are on [Command line](/reference/command-line#check).
