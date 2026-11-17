# The dampf C subset

Status: first draft from milestone M0, 2026-11-17. Based on Blubber Blasen's `src/game` as of that
day: 14 `.c` files and 4 headers, about 8,000 lines, 291 functions.

The subset is what Blubber Blasen uses, plus a little room around it. Everything outside it is
rejected by the subset checker with a message that names the construct and the place. Nothing
outside the subset is ever compiled silently.

## How the game was scanned

Two tools, both repeatable when the game changes:

- `tools/subset_scan.py` parses each file with clang (`-ast-dump=json`) and counts the constructs
  that matter for the subset and the optimizer, with their places (`--details`).
- `gcc -fcallgraph-info` gives the complete call graph (609 edges), which was checked for cycles
  and indirect calls.

```bash
BB=/path/to/BlubberBlasen
tools/subset_scan.py --details -I$BB/src -I$BB/build/generated $BB/src/game/*.c
```

The scan is of the release build: `BB_DEBUG` is not defined, so `debug.c` and the debug
helpers are left out. The SNES build will not contain them either. With
`-Wall -Wextra -Wconversion -Wsign-conversion`, gcc reports no warnings in the game code.

## What the game does not use

None of these appear in the release build of `src/game`, so the subset leaves them out:

| Construct | Found |
| --- | --- |
| `float`, `double`, `long long` | none |
| `malloc`, any heap, any library function | none; the only system header is `<stdint.h>` |
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
| `sizeof` | 4 | Constant only. |
| Most parameters of one function | 6 | |

### Calls out of the game code

`video.c` is the only file that talks to the hardware. It calls 19 functions of the PVSnesLib-shaped
API in `src/snes/snes.h`: `bgInitTileSet`, `bgSetDisable`, `bgSetGfxPtr`, `bgSetMapPtr`,
`bgSetScroll`, `dmaCopyCGram`, `dmaCopyVram`, `oamInit`, `oamInitGfxAttr`, `setBrightness`,
`setColorEffect`, `setColorIntensity`, `setMode`, `setPaletteColor`, `setScreenOff`,
`snesDmaFits`, `snesHdmaEnable`, `snesHdmaSet`, `snesRegWrite`. On the PC these are the pseudo
SNES; for dampf they become a small runtime library or intrinsics. The kickoff lists this mapping
as an open question; this is the list it has to cover.

The platform calls into the game through `game_init`, `game_tick`, `game_vblank` and the getters
in `game.h`. These are the roots of the call graph.

## The subset

**Types.** `int8_t`, `uint8_t`, `int16_t`, `uint16_t`, `int32_t`, `uint32_t`, `char` (text
only, see below), `void`, pointers, arrays (also multi-dimensional), structs, enums and
`typedef`s of these. `const` and `static` as in C99. Plain `int`, `short`, `long`, `unsigned`,
`signed`, `long long`, `float`, `double`, `_Bool`, unions and bit fields are rejected.

**Semantics.** Exactly what gcc and clang do on the PC (x86-64): integer constants and the
integer promotions use a 32-bit `int`, overflow of unsigned types wraps, conversions to narrower
types truncate, signed division rounds towards zero, `>>` of a negative value is an arithmetic
shift. dampf gets the same results on the 65816 and narrows operations only where it can prove
the result is identical. dampf ships its own `<stdint.h>` with the same underlying types as the
PC (`int32_t` is `int`), so the types mean the same thing on both sides.

**Functions.** No recursion (the checker names the cycle), no variadic functions, no
`inline` requirement (inlining is the compiler's decision), structs only by pointer. At most
6 parameters is what the game needs; there is no fixed limit.

**Function pointers.** Not used by the game. The kickoff allows them in constant tables known
at compile time; that stays allowed but is not built until something needs it.

**Statements.** Everything in C99 except `goto`, labels, `setjmp`/`longjmp` and variable-length
arrays.

**Expressions.** Everything in C99 except compound literals. Struct assignment is allowed (it is
a copy of known size, a candidate for `MVN`), although the release build does not use it today.
Designated initializers are not used and stay out for now; they are easy to add if the game
wants them.

**Preprocessor.** Anything `gcc -E` accepts. dampf preprocesses with `-nostdinc -undef
-D__DAMPF__=1` and its own `include/`, so host headers and host macros never reach the code.

**Whole program.** All `.c` files are compiled as one program. `static` names stay local to
their file (`phase` exists as a static local in two functions, for example).

## Decisions

These need a decision before the checker is written. Each has a recommendation.

1. **Plain `char` for text.** The game uses `char` only for strings and name buffers. Plain
   `char` is signed with gcc and clang on x86-64 and with MSVC, unsigned on ARM. Recommendation:
   allow `char` and give it the x86-64 meaning (signed 8 bit), and have the checker reject
   arithmetic on `char` beyond comparisons and copying, so the signedness can never change a
   result. The alternative is a `typedef uint8_t text_t` in the game, which touches 27 places.

2. **Size of enum-typed objects.** On the PC an `enum` object is 4 bytes. dampf wants 1 byte for
   `g_state`. Recommendation: the checker allows only enumerators of the same enum (or values of
   that enum type) to be stored into an enum-typed object; then storing it in the smallest width
   that holds all enumerators gives the same behaviour. The game already keeps to it: `g_state`
   is assigned in three places, always a `GameState` value.

3. **Pointer width.** The `LevelDef` pointer fields and the `const char *` string tables point
   into ROM, and the ROM data is larger than one bank (the PVSnesLib build has 310 KB of ROM).
   So some pointers must be 24 bit. This is a compiler design decision, not a subset rule, but it
   comes from the game: whole-program analysis can tell which pointers only ever point into
   bank 0 RAM (the object pointers like `Bubble *b`) and keep those at 16 bit.

## What the game has to change

Very little:

1. `BB_FAST` in `internal.h` expands to `__attribute__((tiny))` for Calypsi and to nothing
   otherwise. It needs a `__DAMPF__` branch that expands to dampf's direct page hint, once that
   hint has a name.
2. If decision 1 goes the other way: replace `char` in the 27 text declarations.

Generated data (`build/generated/level_data.c`, `gfx_data.c`) is plain `const` C arrays and fits
the subset. For the PVSnesLib and Calypsi builds, `map2c --snes` and `gfx2c --snes` write the arrays as
assembly instead, because those compilers copy initialised data into RAM or cannot spread it
over banks; with dampf the C arrays can be compiled directly and placed in ROM banks
by dampf, which makes the `--snes` variants unnecessary.
