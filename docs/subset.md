# The dampf C subset

Status: first draft from milestone M0, 2026-11-17. Based on Blubber Blasen's `src/game` as of that
day: 14 `.c` files and 4 headers, about 8,000 lines, 291 functions. The scan results below are
from then. The rules in *The subset* and *Decisions* were checked against what the front end
accepts on 2026-11-18.

dampf is not a general C compiler: it compiles a subset of C that it defines itself, and a checker
enforces it, so every assumption the optimizer makes is guaranteed. C is the contract that keeps
the same source compiling on the PC with gcc or clang, which is what makes the code testable.

The subset is what Blubber Blasen uses, plus a little room around it. Everything outside it is
rejected with a message that names the construct and the place. Nothing outside the subset is
ever compiled silently. `dampf check` runs these checks without building a ROM: the front end on
each file, then over all files together recursion, functions called but defined nowhere, and
definitions in two files.

## How the game was scanned

Two tools, both repeatable when the game changes:

- `dev/subset_scan.py` parses each file with clang (`-ast-dump=json`) and counts the constructs
  that matter for the subset and the optimizer, with their places (`--details`).
- `gcc -fcallgraph-info` gives the complete call graph (609 edges), which was checked for cycles
  and indirect calls.

```bash
BB=/path/to/BlubberBlasen
dev/subset_scan.py --details -I$BB/src -I$BB/build/generated $BB/src/game/*.c
```

The scan is of the release build: `BB_DEBUG` is not defined, so `debug.c` and the debug
helpers are left out. The SNES build will not contain them either. With
`-Wall -Wextra -Wconversion -Wsign-conversion`, gcc reports no warnings in the game code.

## What the game does not use

None of these appear in the release build of `src/game`, so the subset leaves them out:

| Construct | Found |
| --- | --- |
| `float`, `double`, `long long` | none |
| `malloc`, any heap, any library function | none; the only system headers are `<stdint.h>` and `<dampf.h>` (the hints) |
| Recursion | none: the call graph has no cycle |
| Function pointers, indirect calls | none |
| Plain `int`, `short`, `long`, `unsigned` in declarations | none |
| `union`, bit fields | none |
| Structs passed or returned by value | none |
| Struct assignment | none (one in `debug.c`, which is not part of the SNES build) |
| `goto`, variadic functions, inline assembly | none |
| Compound literals, designated initializers | none |
| `bool` / `_Bool` | none |

The deepest call chain is 9 calls:
`game_tick → update_play → check_door → next_round → begin_transition → player_enter_round →
player_killed → text_number → text_digits → bg_mark_row`. Optimization A (static frames in the
direct page) needs nothing more than that.

## What the game uses

Counts are from the scan. They are places in the source, not how often the code runs.

| Construct | Count | Where, and what it means for dampf |
| --- | --- | --- |
| `uint8_t`, `int8_t`, `uint16_t`, `int16_t` (and `fx8_8` = `int16_t`) | everywhere | The core of the subset. |
| `uint32_t`, `int32_t` | 2 objects, 3 expressions | The sound effect bit mask `g_sfx` (with the `sfx_bit[32]` table) and two intermediate products in `vacuum.c` `shape()`. 32-bit arithmetic is needed, but none of it is hot. |
| `char` | 27 declarations | Only text: string tables (`const char *const story_text[3][6]`), names, and `char s[2]` buffers. 136 string literals. See *Decisions* below. |
| `enum` | 22 definitions, 1 enum-typed variable | Enumerators are used as constants into `uint8_t` fields. The only enum-typed variable is `g_state` (`GameState`), plus one parameter. See *Decisions*. |
| `struct`, `typedef` | 19 struct types | Object tables (`players[2]`, `bubbles[...]`), accessed through pointers: 1,516 `->` against 329 `.`. That is the pattern optimization D (point D at the current object) is for. |
| Pointers | 151 pointer parameters, 135 pointer variables, 4 pointer fields | Mostly `Bubble *b` style. The 4 pointer fields are in `LevelDef` (`cells`, `enemies`, `nav`, `nav_edges`) and point into level data in ROM. |
| Pointer arithmetic, `p++` | 5, 23 | Writing OAM entries, text and window tables. |
| Address of a local | 11 | `&x`, `&y` passed to helpers in `cpu.c`, `effect.c`, `player.c`. A local in a static direct page slot has a fixed address, so this works with optimization A. |
| `static` locals | 16 | Mostly constant tables. `cpu.c` `first_hop()` has three `static uint8_t[255]` arrays (765 bytes) for its breadth-first search: they belong in WRAM, not the direct page. |
| Multi-dimensional arrays | 11 | String tables and byte tables; `video.c` `window_table[2][2][451]` is the largest. |
| `*` variable by variable | 14 | `cpu.c` (CPU partner: `add_danger`, `add_coming_shot`, `evaluate`) and `vacuum.c`. The CPU partner is a known hot spot, so the hardware multiplier matters there. |
| `*` by a constant | 34 | Shifts and adds (optimization I). |
| `/` and `%` | 4 | All in `vacuum.c` `shape()`: signed division by 2, 4 and 40, and one signed division by a variable (`/ ry`). The dividends there are never negative and `ry >= 1`, which range analysis or a `DAMPF_RANGE` hint can prove, and then the unsigned hardware divider can do it. |
| `>>` | 280 unsigned by a constant, 29 signed by a constant, 8 unsigned by a variable | 8.8 fixed point (`PX(v)` is `>> 8`). The 29 signed shifts must be arithmetic shifts, as on the PC. |
| `<<` by a variable | 16 | Bit masks such as `1u << player`. |
| `switch` | 22 | State machines; candidates for jump tables (optimization L). |
| `?:` | 225 | Common; should become branches, not materialised values (optimization G). |
| Loops | 198 `for`, 15 `while`, 1 `do` | |
| Comma operator | 9 | In `for` headers. |
| `sizeof` | 4 | Constant only. A `sizeof` whose value differs from gcc's on x86-64 (pointers, structs gcc pads) is an error, except in the element count `sizeof a / sizeof a[0]` (decision 5). The game's uses are all fine. |
| Most parameters of one function | 6 | |

### Calls out of the game code

`video.c` is the only file that talks to the hardware. It calls 19 functions of the PVSnesLib-shaped
API in `src/snes/snes.h`: `bgInitTileSet`, `bgSetDisable`, `bgSetGfxPtr`, `bgSetMapPtr`,
`bgSetScroll`, `dmaCopyCGram`, `dmaCopyVram`, `oamInit`, `oamInitGfxAttr`, `setBrightness`,
`setColorEffect`, `setColorIntensity`, `setMode`, `setPaletteColor`, `setScreenOff`,
`snesDmaFits`, `snesHdmaEnable`, `snesHdmaSet`, `snesRegWrite`. On the PC these are the pseudo
SNES; for dampf they are a platform layer in C (`lib/snes/snes.c`), which
reaches the hardware through volatile pointers (*Hardware access* below) and needs no intrinsics.

The platform calls into the game through `game_init`, `game_tick` and the getters
in `game.h`. These are the roots of the call graph.

## The subset

**Types.** `int8_t`, `uint8_t`, `int16_t`, `uint16_t`, `int32_t`, `uint32_t`, `char` (text
only, see below), `void`, pointers, arrays (also multi-dimensional), pointers to arrays
(`const uint8_t (*rows)[16]`), structs, enums and `typedef`s of these. `const`, `static` and `extern` as in C99. Plain `int`, `short`, `long`,
`unsigned`, `signed`, `long long`, `float`, `double`, `_Bool`, unions and bit fields are rejected, in casts as
well; the one exception is `int main(void)`, which the PC build needs (on the SNES the return value
is ignored). There are no 64-bit types (`<stdint.h>` has no `uint64_t`). Also rejected today:
`restrict`, `register`, enums with a fixed underlying type, flexible array members, anonymous
struct members, typedefs of function types, and attributes other than those of `DAMPF_BUDGET`,
`DAMPF_ASM` ([hints.md](hints.md)) and `DAMPF_INTERRUPT`. Plain
integer types are rejected because their size differs between compilers: `int` is 32 bit on the
PC and 16 bit with the other 65816 compilers (816-tcc, Calypsi). dampf itself gives `int` its PC
meaning in the integer promotions, but game code says what width it means.

**Semantics.** Exactly what gcc and clang do on the PC (x86-64): integer constants and the
integer promotions use a 32-bit `int`, overflow of unsigned types wraps, conversions to narrower
types truncate, signed division rounds towards zero, `>>` of a negative value is an arithmetic
shift. A constant that is a 64-bit `long` on the PC (such as `3000000000` written in decimal)
is rejected. dampf gets the same results on the 65816 and narrows operations only where it can
prove the result is identical. dampf ships its own `<stdint.h>` with the same underlying types as the
PC (`int32_t` is `int`), so the types mean the same thing on both sides.

**Warnings.** Where gcc warns without any warning option, dampf is to warn too, in gcc's form,
and the build goes on; `dampf build` and `dampf check` print the warnings. So far there is one,
gcc's `-Woverflow` on a constant converted to an integer type that cannot hold it, in an
initializer, an assignment, an argument or a return value. `uint8_t wait[] = { 420 };` stores
164 on both sides, and dampf says

```
cat.c:2:20: warning: unsigned conversion from 'int32_t' to 'uint8_t' changes value from '420' to '164' [-Woverflow]
```

The rule is gcc's: a constant gets the warning only when the type cannot hold it as a signed or
as an unsigned number of its width. `uint8_t x = -1` and `int8_t y = 255` (or `0x80`) are
silent, `uint8_t x = 256`, `uint8_t x = -129` and `uint16_t z = 65536` are not. A constant is
what dampf folds: literals, enumerators, macros, and arithmetic, casts and `?:` on them; a
`const` variable is not one. A cast to the target type says the truncation is meant and silences
the warning. gcc is also silent in code that is never evaluated (the operand of `sizeof`, the
right side of `0 &&`, the branch of `?:` that a constant condition does not take) and when the
constant overflowed while it was folded (`2147483647 + 1`), and so is dampf.
`ConversionWarningTest` compiles a file of cases with dampf and gcc and compares where each
warns. gcc folds a little more than dampf (`n * 0 + 300`, `n ? 300 : 300`), so there gcc warns
and dampf does not. gcc's warning about the overflow itself (`integer overflow in expression`)
is not built yet, and neither are gcc's other default warnings.

**Functions.** No recursion (the checker names the cycle), no variadic functions, no `inline`
requirement (inlining is the compiler's decision; `inline` is accepted and ignored), structs only
by pointer. A function without parameters is written `f(void)`; an empty list `f()` and old-style
(K&R) parameters are rejected. The game needs at most 6 parameters; there is no fixed limit.

**Function pointers.** Not used by the game. Free function pointers are never allowed: the call
graph must stay complete. Function pointers in constant tables known at compile time are allowed
by design, but not built until something needs them.

**Memory.** No `malloc` and no heap of any kind: all objects are static, and total memory use is
known at compile time.

**Statements.** Everything in C99 except `goto`, labels, `setjmp`/`longjmp` and variable-length
arrays (`goto` is decision 4 below, decided). Inline assembly is rejected too; a whole function
can be written in assembly in a file of its own, with its C body kept as the specification that
dampf checks it against (`DAMPF_ASM`, [hints.md](hints.md)).

**Expressions.** Everything in C99 except compound literals and the comma operator outside the
first and third part of a `for`. A compound assignment whose left side has side effects
(`a[next()] |= bit`) is accepted as a statement of its own, where its address is taken once into a
hidden local; inside a larger expression it is rejected. `++` and `--` work on such a left side
anywhere. Struct assignment is allowed (it is
a copy of known size, a candidate for `MVN`), although the release build does not use it today.
Designated initializers are not used and stay out for now; they are easy to add if the game
wants them. The C11 additions (`_Static_assert`, `_Alignof`, `_Generic`) are rejected.

**Hardware access.** For platform code, not game code. A pointer to `volatile` memory reads and
writes I/O registers one access at a time, in order, with exactly the width of its type:
`*(volatile uint8_t *)0x2100 = 0x80` writes `INIDISP` in bank 0 (a variable declared `volatile`
itself is rejected). A cast from a pointer to an integer gives the SNES address of the pointer,
`bank << 16 | address`, which a DMA channel needs as its source; a cast to a narrower type
truncates that number. On the PC the number is something else, so only distances within one
object agree between both sides (`exec/pointer_to_int.c`). The SNES platform layer in
`lib/snes` is written this way. The checker does not tell platform code from game code: a
cast from an integer to a pointer is accepted anywhere.

**Interrupt handlers.** Built in the compiler, not yet for games: the kit's NMI and a game's IRQ
come with the data the tick publishes for them and with Rachael's IRQ (ADR-6FKTVDE8).
`DAMPF_INTERRUPT(nmi)` or `DAMPF_INTERRUPT(irq)` from `<dampf.h>`, in front of a function
definition, makes the function the handler of that interrupt. It must be `void name(void)`, there
is at most one per interrupt, the hardware calls it through the runtime's entry stub and C code
never does. The handler and every function it calls form a context of their own, with their own
frames, direct page and scratch registers, and they multiply without the CPU's multiplier
(ADR-6FM9TXV4, [memory-map.md](memory-map.md#interrupt-contexts)). A function called from two
contexts (the main code and a handler, or both handlers) is an error that names both call paths,
so a helper that both need is written twice. A function written in assembly (`DAMPF_ASM`,
`--replace`) cannot be reached from a handler yet ([hints.md](hints.md)). On the PC the macro is empty and the handler is an
ordinary function, which the program may call itself (`exec/interrupts.c` does, to compute the
handlers' results there).

**Library.** `<stdint.h>`, `<dampf.h>` (the hints) and `<string.h>` with `memset` and `memcpy`,
which take a byte value and a 16-bit size, since there is no plain `int` and no object is larger
than 32 KB. A call written for the standard header works the same way. A `sizeof` of a struct that
gcc pads is still rejected (decision 5), and no constant size is right on both sides (3 bytes in
dampf and 4 on the PC for `struct { uint8_t kind; int16_t x; }`), so such a struct is copied by a
struct assignment.

**Preprocessor.** dampf's own, a subset of C99's (ADR-6FD4GDN5), with its own `include/`, so
host headers and host macros never reach the code. It has `#include` with `""` and `<>`,
`#define` and `#undef` (object-like, function-like and variadic macros with `__VA_ARGS__`, `#`
and `##`), `#if`, `#elif`, `#ifdef`, `#ifndef`, `#else`, `#endif` and `defined` (arithmetic in
64 bits), `#error`, `#pragma` (passed on; `#pragma once`), `__FILE__`, `__LINE__`, `__STDC__`,
`__STDC_VERSION__` (199901L), `__STDC_HOSTED__` and `__DAMPF__`. Rejected, with an error that
names them: `__VA_OPT__`, `#line`, `#include_next`, an include of a macro, `#warning` and gcc's
other directives, named variadic parameters, `__COUNTER__`, `__DATE__`, `__TIME__`, `_Pragma`,
`__has_include`. Trigraphs, digraphs and universal character names are not handled.

**Whole program.** All `.c` files are compiled as one program; there is no separate compilation.
`static` names stay local to their file (`phase` exists as a static local in two functions, for
example).

**Hints.** Macros that give dampf knowledge it cannot infer (value ranges, no aliasing, loop
bounds, placement). In the dampf build they expand to built-ins, in the PC build to checked
assertions or nothing. See [hints.md](hints.md).

**Intrinsics.** For the SNES hardware: hardware multiply and divide, DMA, waiting for vertical
blank, placing constant data in ROM banks. In the PC build they are plain functions. Not built
yet.

## Decisions

Decision 3 is open; it has a recommendation, which dampf implements until it is
decided. Decisions 1, 2, 4 and 5 are decided. What dampf does today:

- decision 1: plain `char` is a signed 8-bit type for text: a `char` value is only copied,
  tested for zero and compared with `==` and `!=` ([ADR-6FM063YZ](adr/6FM063YZ-plain-char-is-text-a-char-value-is-only-copied.md));
- decision 2: an enum object takes only enumerators and values of its enum, and both code
  generators store it in the smallest width that holds its enumerators ([ADR-6FKZFDJ3](adr/6FKZFDJ3-enum-objects-take-only-values-of-their-enum-and.md));
- decision 3: pointers are 4 bytes in memory (address, bank, a zero byte) with both code
  generators; the code generator from MIR turns a pointer into one object into a 16-bit offset
  within a function and through parameters ([optimizations.md](optimizations.md#pointer-compression)); 16-bit
  pointers into RAM are not built;
- decision 4: `goto` and labels are rejected ([ADR-6F3CJVHC](adr/6F3CJVHC-goto-and-labels-stay-rejected-and-a-forward-and.md));
- decision 5: a `sizeof` that differs from the PC is rejected, except the element count.

1. **Plain `char` for text** (decided 2026-11-24). The game uses `char` only for strings and
   name buffers. Plain `char` is signed with gcc and clang on x86-64 and with MSVC, unsigned on
   ARM. `char` keeps the x86-64 meaning (signed 8 bit), and the front end lets a `char` value be
   used only where its signedness cannot change the result. Allowed: copying it (assignment,
   parameters, results, arrays, string literals), storing any number into a `char`, converting
   it to another 8-bit type (`uint8_t u = c`, `(int8_t)c`), testing it for zero (`if (c)`,
   `while (*s)`, `!c`), `==` and `!=` against another `char` or a constant from 0 to 127, and a
   `switch` whose case values are all from 0 to 127. Rejected: arithmetic, shifts and bit
   operations, `<`, `<=`, `>`, `>=`, `++`, `--` and compound assignment on a `char`, a `char`
   as an index or a pointer offset, a conversion or cast to a wider type (`int16_t x = c`), and
   a `?:` with a `char` branch. Digit arithmetic is rejected too: `c - '0'` is written
   `(uint8_t)c - '0'`. The message says the same: "plain char is text, ... use uint8_t or int8_t
   for a number, or cast the char: (uint8_t)c". ADR
   [ADR-6FM063YZ](adr/6FM063YZ-plain-char-is-text-a-char-value-is-only-copied.md), with the options weighed.

2. **Size of enum-typed objects** (decided 2026-11-24). On the PC an `enum` object is 4 bytes;
   dampf wanted 1 byte for `g_state`. The checker allows only enumerators of the same enum and
   values of that enum type to be stored into an enum object, and a cast to an enum only from
   such a value or from a constant that is one of its enumerators; `++`, `--` and compound
   assignments on an enum object are rejected. dampf stores the object in the smallest width
   that holds all enumerators: unsigned 8 or 16 bit, or signed when an enumerator is negative,
   32 bit only beyond that. Its value is still promoted to `unsigned int` or `int` as on the PC.
   So `sizeof` of an enum object now differs from the PC and is rejected (decision 5), except
   the element count. The game keeps to the rule: `g_state` is assigned in three places, always
   a `GameState` value. The rule with the options weighed: [ADR-6FKZFDJ3](adr/6FKZFDJ3-enum-objects-take-only-values-of-their-enum-and.md).

3. **Pointer width.** The `LevelDef` pointer fields and the `const char *` string tables point
   into ROM, and the ROM data is larger than one bank (the PVSnesLib build has 310 KB of ROM).
   So some pointers must be 24 bit. This is a compiler design decision, not a subset rule, but it
   comes from the game: whole-program analysis can tell which pointers only ever point into
   bank 0 RAM (the object pointers like `Bubble *b`) and keep those at 16 bit.

4. **`goto`** (decided 2026-11-18). `goto` and labels stay rejected. If real code needs `goto`,
   dampf may allow a form that only jumps forward and out of statements, which keeps every loop
   with a single entry; unrestricted `goto` will not be built: ADR
   [ADR-6F3CJVHC](adr/6F3CJVHC-goto-and-labels-stay-rejected-and-a-forward-and.md), with the options weighed.

5. **`sizeof` of pointers and padded structs** (decided 2026-11-17). A `sizeof` that differs
   from gcc's on x86-64 is rejected, except the element count `sizeof a / sizeof a[0]`: ADR
   [ADR-6F0R6BSK](adr/6F0R6BSK-structs-have-no-padding-and-a-sizeof-that-differs.md), with the options
   weighed.

## What the game has to change

Very little:

1. `video.c`, lines 339 and 340, cast with plain `(unsigned)`. The scan above only looked at
   declarations and missed these casts; dampf's front end found them. Use `uint32_t` (or a
   narrower type). See the game's finding 5 (forwarded to it). Done: the snapshot has `(uint8_t)`
   there.
2. `BB_FAST` in `internal.h` expands to `__attribute__((tiny))` for Calypsi and to nothing
   otherwise. It needs a `__DAMPF__` branch that expands to `DAMPF_DP` ([hints.md](hints.md))
   once that hint is built.
3. Where the game computes with a `char` (a tile number from a letter, the value of a digit),
   cast it to `uint8_t` first (decision 1). dampf names each place.

Generated data (`build/generated/level_data.c`, `gfx_data.c`) is plain `const` C arrays and fits
the subset. For the PVSnesLib and Calypsi builds, `map2c --snes` and `gfx2c --snes` write the
arrays as assembly instead, because those compilers copy initialised data into RAM or cannot
spread it over banks. dampf compiles the C arrays directly and places them in ROM banks, so it
needs no `--snes` variants.
