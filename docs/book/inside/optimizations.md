<!-- GENERATED from doc-specs/inside/optimizations.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->

# Optimizations

The game logic of [Blubber Blasen](/glossary#blubber-blasen) has a budget: in its busiest scene
it may use at most 60 % of an NTSC [frame](/glossary#frame), about 0.6 frames per
[tick](/glossary#tick). A frame is 357,366 [master clocks](/glossary#master-clock) (the SNES's
21.477 MHz clock), and the work in the [vertical blank](/glossary#vertical-blank) and a safety
margin come off that; how many CPU cycles that leaves is on [Exact timing](/inside/timing). The
game's benchmark runs the same game code with a fixed input for 3,610 ticks and measures every
tick. Most figures on this page are from *play*, the ticks in which the game is played, as
opposed to the title screen, the story and the round intro.

During play, the code dampf now generates takes 0.79 frames per tick (worst tick 1.42) with
[FastROM](/glossary#fastrom-and-slowrom), which is the default: the ROM read at 3.58 MHz instead
of 2.68 MHz. These figures were measured on 2026-11-18. dampf's own
[naive code generator](/glossary#naive-code-generator) takes 4.37 frames per tick (worst 7.26)
for the same code, also FastROM, about 5.5 times as long. Against [Calypsi](/glossary#calypsi),
on the same version of the game and with FastROM for both, dampf is 2.14 times as fast: in the
102 play ticks where both play the same scene, Calypsi takes 1.42 frames per tick and dampf
0.66. dampf is past Calypsi, but still about 1.3 times away from the budget. This page explains,
pass by pass, where that speed comes from.

The figures below come from different times and in two units. Until FastROM came, every step
was measured at SlowROM speed, in frames per tick; dampf's code from MIR took 1.31 (worst 2.25)
then. FastROM took play from 1.31 to 1.10 frames per tick, and every step after it is FastROM.
So a step's figure in frames per tick is SlowROM down to 1.31 and FastROM from 1.10 on. The
steps of 2026-11-18 were measured in master clocks per tick, all with FastROM; the few
master-clock figures from before them say so where they appear.

## Why the 65816 needs a different optimizer

The 65816 runs its accumulator and its index registers either 8 or 16 bits wide, and the width
is processor state: the [`M` and `X` flags](/glossary#m-and-x-flags), set with `REP` and `SEP`.
The same `LDA` loads one byte or two depending on the mode
([8-bit and 16-bit mode](/glossary#_8-bit-and-16-bit-mode)), and switching costs cycles. A
32-bit value needs two 16-bit operations for everything; an i32 operation costs about twice an
i16 one.

There are few registers: one accumulator A, where all arithmetic happens, and the index
registers X and Y. The [direct page](/glossary#direct-page) takes the place of a register file:
a 256-byte window that one-byte addresses reach, where a load or store costs 4 cycles instead of
5 and a pointer can be used as `[place],y` without copying it first. For dampf, "register
allocation" therefore mostly means storing and loading less. The CPU has no multiply
instruction either: dampf's [runtime](/glossary#runtime) multiplies in a loop of 16 or 32 steps,
about 300 and 1,250 cycles.

C makes this worse. After the integer promotions, C computes in 32-bit `int`, even where every
operand is a `uint8_t`. dampf must match gcc on x86-64 bit for bit, so it cannot drop the wide
arithmetic until it has shown that the narrow result is the same. The code generator from
[MIR](/glossary#mir), before any pass, computes in 32 bits everywhere the C source does: it took
18.93 frames per tick during play, 3.7 times slower than the naive code generator, which
already narrows to 16 bits from the operand types. Most of what follows exists to undo the
promotions and to keep values out of RAM.

## The passes on MIR

Each [pass](/glossary#pass) reads MIR, dampf's intermediate form in [SSA](/glossary#ssa) form,
and writes better MIR. `dampf.mir.Optimizer` runs them on every function in a fixed order,
round after round, until a round changes nothing (at most 20 rounds). It then gives parameters
the ranges their arguments have at every call and, if that narrowed any, runs the rounds again.
Then it inlines small functions, running the rounds on a function as soon as something was
inlined into it, and compresses pointer parameters
([pointer compression](/glossary#pointer-compression)); if either changed anything, it runs the
rounds again, followed by both parameter passes once more. Scheduling comes last. Every pass
is exact: it changes how a value is computed, never the value.

With `dampf mir file.c`, dampf prints the MIR as it comes from the lowering, with
`dampf mir -O file.c` after the passes; the examples below are their output.

dampf's catalogue of optimizations, the working document behind this page, names each
optimization with a letter. The letters map to this page as follows:

| Letter | Optimization | Where it is built |
| --- | --- | --- |
| A | Static frames in the direct page | code generator: frames, shared places, values not stored |
| B | Width analysis (8/16 bit) | `Narrow`, `RangeNarrow`, switches on bytes in 16 bits, 8 bit kept between byte load and byte store, `ByteStretches`; full `REP`/`SEP` tracking not built |
| C | Inlining and calling convention | `Inline`, arguments in place, returns copied into each path; parameters in registers and tail calls not built |
| D | Data layout (one array per field) | not built |
| E | 8.8 fixed point as byte accesses | `ByteLoads`; `<<8` not built |
| F | Loops | `Induction`, `Licm`, loop counters in X, `INC`/`DEC` steps, loop rotation; unrolling not built |
| G | Branches | compare and branch, jump threading, `BitSelect`, `RangeChecks`, block layout (also by a profile), short branches, `BRA` |
| H | Standard optimizations | `Simplify`, `Gvn`, `DeadCode`; loop-invariant code under F |
| I | Multiply and divide | `StrengthReduce`, multiplication in A; hardware multipliers not built |
| J | Banks and placement | FastROM for all code and constant data, registers at constant addresses |
| K | Peephole | A remembers every place it equals, `INC`/`DEC`, `STZ`, no `CMP #0` where the flags are set, `Peephole`, `KnownCarry` |
| L | `switch` as jump table | not built |
| M | Copy and fill with `MVN`/`MVP` or DMA | not built |
| N | Values in X/Y | loop counters in X, a second index in Y, loop-invariant indexes in Y, an index through a pointer in Y, what A, X and Y hold across blocks |

The optimizations not started yet are on the [Roadmap](/inside/roadmap).

### Simplify

Constants fold, computed with the interpreter's arithmetic so that they mean the same as at run
time. Identities such as `x + 0`, `x & -1` and `x - x` simplify, a branch on a constant becomes
a jump, unreachable blocks go, a φ whose operands are all the same value becomes that value, and
a block that only one block jumps to is merged into it. Jump threading sends a branch to a block
that only jumps straight to the final target, and skips a block that only branches on a φ for
every predecessor that gives the φ a constant: the shape that `a || b` and an inlined function
returning a truth value leave behind.

A `switch` on a `uint8_t`, `int8_t` or `uint16_t` compares 16 bits when every case lies in the
value's range: C widens the value to `int`, and the 32-bit switch compared a high word that is
known to be 0 for every case. A case outside the range keeps the 32 bits, since it could equal
an in-range case in 16 bits (65,545 is 9). Play went from 1.06 to 1.04 frames per tick.

The pass also writes a comparison with a constant in a canonical form, `x < c` or `x >= c`
with the constant on the right: `x > c` becomes `x >= c + 1`, `x <= c` becomes `x < c + 1`. The
code generator then compares with an immediate operand directly. In the example under
[Inline](#inline), `lives > 0` comes out as `cmp uge i8 %0, 1`.

Together with `Gvn` and an extension of `Narrow` through φs, it took play from 6.12 to 5.17
frames per tick. The canonical comparisons, together with a change in the code generator, took
it from 1.93 to 1.76 frames per tick, and jump threading over constant φs from 1.74 to
1.67 frames per tick.

### RangeChecks

In MIR, `||` is control flow: `x == 2 || x == 3` is a block that branches on `x == 2`, and a
second block that does nothing but branch on `x == 3` to the same target. `RangeChecks` makes
the two one test of an interval, as LLVM's InstCombine does: `(x - 2) <u 2`, the difference
compared unsigned. Where the values are an aligned block of a power of two, as 2 and 3 are, it
writes `(x & ~1) == 2` instead. On the 65816 that is one load and compare instead of a second
block with its own load, compare and often a jump. Longer chains merge step by step, and `!=`
with `&&` is the same with the branches swapped.

```c
#include <stdint.h>

uint8_t floating_count;

void count_floating(uint8_t state) {
    if (state == 2 || state == 3) {
        floating_count++;
    }
}
```

Before the passes, two blocks test `state`:

```
b0_entry:
    %0 = zext i8 %state to i32                  ; floating.c:6
    %1 = cmp eq i32 %0, 2                       ; floating.c:6
    br %1, b1_then, b3_or                       ; floating.c:6
; ...
b3_or:                    ; from b0_entry
    %6 = zext i8 %state to i32                  ; floating.c:6
    %7 = cmp eq i32 %6, 3                       ; floating.c:6
    br %7, b1_then, b2_endif                    ; floating.c:6
```

After them, one mask and one comparison:

```
b0_entry:
    %0 = and i8 %state, -2                      ; floating.c:6
    %1 = cmp eq i8 %0, 2                        ; floating.c:6
    br %1, b1_then, b2_endif                    ; floating.c:6
```

The values 3 and 4 are not an aligned block, so the same test on them comes out as the
difference compared unsigned:

```c
#include <stdint.h>

uint8_t trapped_count;

void count_trapped(uint8_t state) {
    if (state == 3 || state == 4) {
        trapped_count++;
    }
}
```

```
b0_entry:
    %0 = sub i8 %state, 3                       ; trapped.c:6
    %1 = cmp ult i8 %0, 2                       ; trapped.c:6
    br %1, b1_then, b2_endif                    ; trapped.c:6
```

Blubber Blasen has this test in `floating()`, which the game's function `separate` asks for
every pair of bubbles; 13 chains merged. The subtraction took play from 324,844 to 324,256
master clocks per tick, the mask later from 309,008 to 306,298: its `#$00FE` also clears the
high byte of the 16-bit load, so the `SEC`, the `SBC` and the byte mask go.

### BitSelect

Game code builds bytes of flags bit by bit: Blubber Blasen's sprite code writes
`((attr & SPR_ATTR_FLIP_Y) ? 0x80u : 0u) | ...` for the attribute byte of a sprite. Each such
choice is a branch and a φ of two constants. When the test is a single bit (`x & M`, with `M` a
power of two) and the φ chooses between one bit `K` and 0, the φ is the masked bit itself,
shifted from the place of `M` to the place of `K`. `(x & M) ? 0 : K` is the same with the bit
inverted. The branch then has nothing left to decide, and `Simplify` removes it.

```c
#include <stdint.h>

#define FLIP_Y 0x04

uint8_t attr_flip_y(uint8_t attr) {
    return (attr & FLIP_Y) ? 0x80u : 0u;
}
```

Before the passes, a branch and a φ:

```
b0_entry:
    %0 = zext i8 %attr to i32                   ; flip.c:6
    %1 = and i32 %0, 4                          ; flip.c:6
    %2 = cmp ne i32 %1, 0                       ; flip.c:6
    br %2, b1_then, b2_else                     ; flip.c:6
b1_then:                    ; from b0_entry
    jump b3_cond                                ; flip.c:6
b2_else:                    ; from b0_entry
    jump b3_cond                                ; flip.c:6
b3_cond:                    ; from b1_then, b2_else
    %3 = phi i32 [b1_then, 128], [b2_else, 0]   ; flip.c:6
```

After them, a mask and a shift:

```
b0_entry:
    %0 = and i8 %attr, 4                        ; flip.c:6
    %1 = shl i8 %0, 5                           ; flip.c:6
    ret i8 %1                                   ; flip.c:6
```

Play went from 1.41 to 1.39 frames per tick.

### ByteLoads

Blubber Blasen keeps positions as 8.8 fixed-point values and takes their integer part with
`>> 8` everywhere. A 16-bit load whose only use shifts it right by 8 or more becomes a byte load
of its high byte, the address plus one, widened again; on the 65816 that saves the `XBA` and the
mask the shift costs. Volatile loads stay as they are, because a hardware register may care how
it is read.

```c
#include <stdint.h>

int16_t player_x;

int16_t player_px(void) {
    return player_x >> 8;
}
```

After the passes:

```
    %0 = ptradd @_player_x, i16 1               ; fixed.c:6
    %1 = load i8 %0                             ; fixed.c:6
    %2 = sext i8 %1 to i16                      ; fixed.c:6
    ret i16 %2                                  ; fixed.c:6
```

Play went from 1.49 to 1.48 frames per tick.

### Narrow

Width narrowing works from the types alone, with rules that are exact for every input. A
truncated sum, product, bit operation or left shift by a constant is computed at the narrow
width, because the low bits of the result depend only on the low bits of the operands.
Operations and comparisons on zero- or sign-extended operands become narrow, casts of casts
collapse, and the narrowing reaches through φs. Pointer offsets become 16 bits, because an
object is at most 32 KB and never crosses a [bank](/glossary#bank).

```c
#include <stdint.h>

uint8_t add_speed(uint8_t x, uint8_t speed) {
    return x + speed;
}
```

Before the passes, the addition is done in 32 bits, as C says:

```
func @_add_speed(i8 %x, i8 %speed) -> i8 {
b0_entry:
    %0 = zext i8 %x to i32                      ; speed.c:4
    %1 = zext i8 %speed to i32                  ; speed.c:4
    %2 = add i32 %0, %1                         ; speed.c:4
    %3 = trunc i32 %2 to i8                     ; speed.c:4
    ret i8 %3                                   ; speed.c:4
}
```

After them, only the low 8 bits are computed, because only they are returned:

```
func @_add_speed(i8 %x, i8 %speed) -> i8 {
b0_entry:
    %0 = add i8 %x, %speed                      ; speed.c:4
    ret i8 %0                                   ; speed.c:4
}
```

From the types alone, play went from 18.93 to 16.08 frames per tick. The 16-bit pointer offsets
brought most: before them, 61 % of the time went into the runtime's 32-bit multiplication, for
the index arithmetic in front of every array access. With them, play took 8.16 frames per tick.

### RangeNarrow

Some values the types cannot narrow. `RangeNarrow` uses the [value ranges](#value-ranges): a
comparison whose outcome the ranges decide becomes a constant, and an i32 operation or
comparison whose operands (and result, where it matters) fit 16 bits is done in 16 bits. Values
that are never negative also get cheaper operations: `ashr`, `sdiv` and `srem` become `lshr`,
`udiv` and `urem`, and a signed comparison becomes unsigned.

```c
#include <stdint.h>

uint16_t tile_row(int16_t sy) {
    if (sy < 16) {
        return 0;
    }
    return (sy - 16) >> 3;
}
```

For an arbitrary `int16_t`, `sy - 16` needs 17 bits. Only the branch before it shows that
`sy` is at least 16 there, so `sy - 16` lies in 0..32751. After the passes:

```
b2_endif:                    ; from b0_entry
    %1 = sub i16 %sy, 16                        ; row.c:7
    %2 = lshr i16 %1, 3                         ; row.c:7
    ret i16 %2                                  ; row.c:7
```

This is the shape of `level_solid_px`, a hot function of the game: the value ranges took it
from 3,276 to 2,433 master clocks per call, and play from 4.48 to 4.38 frames per tick. The
cheaper operations for values that are never negative took `level_solid_px` from 1,463 to
1,230 master clocks per call and play from 1.76 to 1.74 frames per tick. All four figures are
SlowROM.

### Licm

An instruction whose operands are all defined outside a loop gives the same value in every
round, so `Licm` moves it to the block before the loop, which must jump straight to the loop's
head and be its only way in from outside. Only cheap instructions without side effects move:
arithmetic other than multiplication and division (which call the runtime), shifts by a
constant, casts and pointer additions. Comparisons stay with their branches. And they move only
from blocks that every round passes, so no work moves out of a branch that rarely runs.

```c
#include <stdint.h>

uint8_t column[16];

void fill_column(uint8_t tile) {
    for (uint8_t i = 0; i < 16; i++) {
        column[i] = tile + 8;
    }
}
```

After the passes, `tile + 8` is computed once, before the loop:

```
b0_entry:
    %0 = add i8 %tile, 8                        ; column.c:7
    jump b1_for                                 ; column.c:6
b1_for:                    ; from b0_entry, b2_body
    %1 = phi i8 [b0_entry, 0], [b2_body, %5]    ; column.c:6
    %2 = cmp ult i8 %1, 16                      ; column.c:6
    br %2, b2_body, b4_endfor                   ; column.c:6
b2_body:                    ; from b1_for
    %3 = zext i8 %1 to i16                      ; column.c:7
    %4 = ptradd @_column, i16 %3                ; column.c:7
    store i8 %4, %0                             ; column.c:7
```

Play hardly changed (478,185 to 478,018 master clocks per tick, at SlowROM speed); the title
screen went from 0.56 to 0.54 frames per tick.

### Induction

In a loop whose counter grows by a constant per round, a product of the counter and a constant
becomes a variable of its own that grows by the constant times the step: an addition per round
instead of a multiplication. The product is typically the offset of `array[i]` in an array of
structs. The rule is exact modulo 2<sup>16</sup>; an 8-bit counter must not wrap around on the
way back into the loop, which the value ranges have to show.

```c
#include <stdint.h>

typedef struct {
    int16_t x;
    int16_t y;
    uint8_t state;
    uint8_t timer;
} Bubble;

Bubble bubbles[16];

void bubbles_move(void) {
    for (uint8_t i = 0; i < 16; i++) {
        bubbles[i].x = bubbles[i].x + 1;
    }
}
```

The lowering computes `i * 6` (the size of `Bubble`) in 32 bits, twice:

```
b2_body:                    ; from b1_for
    %3 = zext i8 %0 to i32                      ; bubbles.c:14
    %4 = mul i32 %3, 6                          ; bubbles.c:14
    %5 = ptradd @_bubbles, i32 %4               ; bubbles.c:14
    %6 = zext i8 %0 to i32                      ; bubbles.c:14
    %7 = mul i32 %6, 6                          ; bubbles.c:14
    %8 = ptradd @_bubbles, i32 %7               ; bubbles.c:14
    %9 = load i16 %8                            ; bubbles.c:14
; ...
```

After the passes, the offset is a φ `%0` of its own that starts at 0 and grows by 6; `Gvn` has
merged the two address computations, and `Simplify` the step block into the body. The counter
`i` was only compared with 16 and stepped, so the test moved to the offset (`i < 16` became
`offset < 96`) and `i` is gone:

```
b1_for:                    ; from b0_entry, b2_body
    %0 = phi i16 [b0_entry, 0], [b2_body, %5]   ; bubbles.c:14
    %1 = cmp ult i16 %0, 96                     ; bubbles.c:13
    br %1, b2_body, b4_endfor                   ; bubbles.c:13
b2_body:                    ; from b1_for
    %2 = ptradd @_bubbles, i16 %0               ; bubbles.c:14
    %3 = load i16 %2                            ; bubbles.c:14
    %4 = add i16 %3, 1                          ; bubbles.c:14
    store i16 %2, %4                            ; bubbles.c:14
    %5 = add i16 %0, 6                          ; bubbles.c:14
    jump b1_for                                 ; bubbles.c:13
```

The new φ carries the range of the product, because indexed addressing in the code generator
needs to know that it is never negative. Play went from 1.61 to 1.57 frames per tick. The test
moves only for an 8-bit counter that stays in its byte, with an element size of at most 257, so
that the offset never wraps (unsigned comparisons and equality); with it, play went from 1.00
to 0.92 frames per tick.

### PointerOffsets

A pointer φ whose values all point into the same global object or frame slot becomes the
object's address plus a 16-bit offset φ. The 4-byte pointer, its copies and its additions go,
and loads and stores through it index the object with X.

```c
#include <stdint.h>

uint8_t oam[64];

void clear_sprites(void) {
    uint8_t *o = oam;
    for (uint8_t n = 0; n < 16; n++) {
        o[0] = 0;
        o[1] = 240;
        o += 4;
    }
}
```

Before the passes, `o` is a `ptr` φ; after them, it is an `i16` offset into `@_oam`, and the
loop tests it instead of `n`, as under [Induction](#induction):

```
b1_for:                    ; from b0_entry, b2_body
    %0 = phi i16 [b0_entry, 0], [b2_body, %4]   ; sprites.c:6
    %1 = ptradd @_oam, i16 %0                   ; sprites.c:6
    %2 = cmp ult i16 %0, 64                     ; sprites.c:7
; ...
    %4 = add i16 %0, 4                          ; sprites.c:10
```

The code generator writes the two stores as `stz _oam,x` and `sta _oam+1,x`. Together with
`PointerParams`, this took play from 1.56 to 1.54 frames per tick; the indirect accesses in the
game's `build_sprites` fell from 148,000 to 53,000 in the measured play ticks.

### StrengthReduce

Most multiplications in game code are by a constant: the size of a struct. For i32,
`StrengthReduce` writes a multiplication by a constant as shifts and adds, the constant in
non-adjacent form (signed binary digits, at most four of them nonzero). Unsigned division and
remainder by 2<sup>k</sup> become a shift and a mask; signed ones get C's rounding towards zero.
In 8 and 16 bits a multiplication by a constant stays a `mul` in MIR, and the code generator
multiplies in A (see below).

```c
#include <stdint.h>

int32_t scale(int32_t distance) {
    return distance * 12;
}
```

After the passes:

```
    %0 = shl i32 %distance, 2                   ; scale.c:4
    %1 = shl i32 %distance, 4                   ; scale.c:4
    %2 = sub i32 %1, %0                         ; scale.c:4
```

When the pass was built, it reduced multiplications of every width, 8 and 16 bits included;
with that, play went from 8.16 to 6.12 frames per tick, and the share of the runtime's
multiplication in the time from 61 % to 4.5 %. The 8 and 16-bit multiplications moved to the
code generator later, for the reason under [Why it works this way](#why-it-works-this-way).

### Gvn

A pure computation (arithmetic, comparison, cast, `ptradd`) that a dominating one already did
is replaced by it. A load is reused when nothing in between may have written its address, and a
store forwards its value to later loads; "in between" is followed through straight-line code. A
store kills only what it may overlap: two different globals or frame slots never overlap. A call
kills nothing if the called function writes no memory, which dampf knows because it sees the
[whole program](/glossary#whole-program). Volatile accesses are never reused. Its gain is part of
the 6.12 to 5.17 under [Simplify](#simplify).

### DeadCode

Instructions whose result nobody needs go. Live are the instructions with an effect (stores,
copies, calls, volatile loads, the ends of blocks) and everything they use, so φs that only feed
each other go too. No separate measurement exists for it.

### ParamRanges

An integer parameter lies in the join of the ranges its argument has at every call. dampf sees
the whole program, and C functions are only called directly, so the calls in the program are
all there are. Callers are handled before callees, so a caller's own parameter ranges are known
when its arguments are evaluated; a function nobody calls, such as the entry function, keeps
the whole range of each type. The [value ranges](#value-ranges) then start from these ranges.
No separate measurement exists for it.

### Inline

A call costs about 30 cycles besides the work: `JSL` and `RTL`, the arguments copied into the
callee's frame, the result copied back. `Inline` copies functions of at most 60 MIR instructions,
and functions with a single call site of at most 150, into their callers, as long as a caller
stays below 1,500. It works bottom-up, leaves first; the call graph has no cycles, because the
subset has no recursion. A function that something was inlined into goes through the rounds of
passes before its own callers decide, so its size is that of the simplified body, as in LLVM's
inliner. Then the passes run again and see both sides of the call.

```c
#include <stdint.h>

uint8_t lives;
uint8_t game_over;

static uint8_t is_dead(uint8_t n) {
    return n == 0;
}

void lose_life(void) {
    if (lives > 0) {
        lives = lives - 1;
    }
    if (is_dead(lives)) {
        game_over = 1;
    }
}
```

Before the passes, `lose_life` calls `is_dead` and tests its truth value. After them, the
callee's comparison is the caller's branch, and the second load of `lives` in the `then` block
is gone:

```
b0_entry:
    %0 = load i8 @_lives                        ; inline.c:11
    %1 = cmp uge i8 %0, 1                       ; inline.c:11
    br %1, b1_then, b2_endif                    ; inline.c:11
b1_then:                    ; from b0_entry
    %2 = sub i8 %0, 1                           ; inline.c:12
    store i8 @_lives, %2                        ; inline.c:12
    jump b2_endif                               ; inline.c:11
b2_endif:                    ; from b0_entry, b1_then
    %3 = load i8 @_lives                        ; inline.c:14
    %4 = cmp eq i8 %3, 0                        ; inline.c:7
    br %4, b3_then, b4_endif                    ; inline.c:14
```

Inlining came together with three smaller changes (casts that only copy bytes get no code,
fewer byte masks, jump threading); together they took
play from 2.72 to 2.55 frames per tick. The frames of the whole game grew from 214 to 410
bytes.

Measuring sizes on the simplified bodies took play from 306,298 to 301,962 master clocks per
tick: `ground_px`, a small function of the game, 29 instructions once simplified, had counted
as more than the old limit of 30 and is now inlined at all its 58 call sites. Raising that
limit from 30 to 60 took play to 290,433 master clocks per tick, and the game's code from
99,925 to 113,859 bytes. At 80, the benchmark no longer matches the PC build: the larger
inlining exposes a function the code generator gets wrong, so the limit stays at 60 until that
bug is found.

### PointerParams

A pointer parameter that every call fills with a pointer into the same global object becomes a
16-bit offset into that object. The callee adds the object's address back at its start, so
indexed addressing applies there, and the callers pass two bytes instead of four. Callers are
handled before callees, and the pass runs once more after the rounds that follow it, because
pointers that became offsets in callers may reach more callees. Its gain is counted with
[PointerOffsets](#pointeroffsets).

### Schedule

The last pass orders the instructions of each block for the single accumulator: an instruction
whose value only one later instruction of the same block uses moves right before that user.
Then the code generator can hand the value over in A without storing it. A load does not move
past a store, a copy, a call or a volatile access, and instructions with an effect never move.
Together with more hand-overs in A in the code generator, play went from 1.67 to 1.61 frames per
tick.

## In the code generator

The [code generator from MIR](/glossary#code-generator-from-mir) does more than translate one
instruction at a time. It selects the instructions of each function once, as
[LIR](/glossary#lir): 65816 instructions in basic blocks, with the accumulator width each runs
in. Passes on the LIR (`dampf.lir`) then remove loads of what a register holds
(`RedundantLoads`, `IndexInY`), apply peephole rules (`Peephole`), run short 16-bit stretches
between byte stores in 8 bits (`ByteStretches`), drop a `CLC` or `SEC` where the carry is known
(`KnownCarry`), remove stores nobody reads (`DeadStores`), choose the branch forms
(`BranchRelax`) and number the local labels (`LocalLabels`). The loop of the
[Induction](#induction) example comes out like this, without the comment and debug lines:

```asm
b2_body:
        lda     _bubbles,x          ;                                                           6
        adc     #$0001              ;                                                           3
        sta     _bubbles,x          ;                                                           6
        txa                         ;                                                           2
        clc                         ;                                                           2
        adc     #$0006              ;                                                           3
        tax                         ;                                                           2
b1_for:
        cpx     #$0060              ;                                                           3
        bcc     b2_body             ;                                                         2/3
```

The offset lives in X for the whole loop and is all that is left of the counter, the comparison
branches on the flags, and the loop test sits after the body. The first `ADC` has no `CLC`:
the body is reached only by the `BCC`, so the carry is clear there. What each of these is, with
what it brought during play:

- **Frames in the direct page.** dampf has no recursion, so the frames form a compiled stack: a
  function's frame starts after the frames of all the functions it calls. The leaves of the call
  graph, small and called most often, start at `$0020`, in the direct page (D stays 0). Play went
  from 3.85 to 2.90 frames per tick.
- **Shared places.** Values whose lifetimes do not overlap share a place in the frame
  (`FrameLayout`: liveness, an interference graph, greedy colouring by byte ranges), and a φ
  shares its operands' places where the lifetimes allow, so the copy at the end of a loop body
  disappears. Play 2.90 to 2.72 frames per tick for the shared places.
- **Results in A.** A 1-word result whose only reader is the next instruction, which starts by
  loading it, is never stored. The code generator also remembers what X and Y hold, and every
  place A equals (after `LDA x` and `STA y`, both), and leaves out a load of the same thing. A
  write to a place drops it and its byte neighbours. Remembering every place instead of one
  took play from 1.50 to 1.49 frames per tick. A 1-word value that every use takes from A, in
  whichever block, is not stored at all: once the function is written, `DeadStores` removes
  every store to one of its own value places (its values, parameters and φ places) that no path
  reads afterwards, byte by byte, backwards over the blocks. Stores to globals, frame slots, a
  callee's parameters or the runtime's registers stay. Leaving such values unstored first took
  play from 1.08 to 1.06 frames per tick; as a pass on LIR, on top of what was left out already,
  it took play from 326,592 to 325,572 master clocks per tick.
- **What A, X and Y hold where a block starts.** A block starts with what every way into it
  brings: the branches and jumps to it, and the code that falls through. Selection assumes
  nothing there; `RedundantLoads` finds what holds by a data flow analysis over the LIR
  (`RegContents`), where ways meet keeping what all of them bring, and removes the loads of a
  value a register holds already. A load also sets the N (negative) and Z (zero) flags: where a
  branch reads them, an `LDA` becomes `CMP #0`, which sets them the same for the value in A,
  unless the carry it changes is read too. The pass also writes `LDA x` followed by `ADC y`,
  with A holding `y`, as `ADC x`. Knowing what the registers hold where a block starts first
  took play from 1.36 to 1.34 frames per tick; as a pass on LIR it took play from 325,572 to
  324,897 master clocks per tick.
- **φs written directly.** The predecessors of a block write its φs into their places, without a
  copy through a second place, where liveness shows that this is safe; also before a branch.
  On a loop's exit edge that would write them in every round for an edge taken once, so just
  before code generation such an edge gets a block of its own (`ExitEdges`), where they are
  written once, at the cost of one jump when the loop ends.
- **Returns copied into each path.** A function that returns the value of an inlined function
  that returns from several places ends in a block holding only φs and a `ret`. Just before
  code generation (`Returns`), that block is copied into each predecessor: one that jumps there
  returns itself, a branch or a switch gets a block of its own for the edge. Each path returns
  its own value, without φ copies or a jump to the shared return. Play 1.04 to 1.02 frames per
  tick.
- **Arguments in place.** An argument of a call later in its block, with no other call on the
  way and no other use except between its definition and the call, is computed straight into the
  callee's parameter place instead of its own place and then copied. This is safe because frames
  on a call path never overlap, and nothing reads the place after the call, where the callee may
  have changed its parameter. Together with the exit edges, play went from 1.38 to 1.37 frames
  per tick.
- **Compare and branch.** A comparison that only its block's branch uses sets the flags and
  branches on them, without a truth value. A comparison with 0 needs no `CMP`, and a 16-bit test
  for `< 0` or `>= 0` is the sign flag of the load (`bmi`/`bpl`). Play 5.17 to 4.48 frames per
  tick. Later also where the value is already in A: the code generator follows whether N and Z
  still describe it (loads and arithmetic set that, stores and jumps keep it, `ldx`, `cmp`, a
  call or a label clears it), and writes `cmp #0` only if not. Play 1.10 to 1.08 frames per
  tick. The `Peephole` pass on LIR also removes a `CMP #0` right after an instruction that set N
  and Z from A in the same width, where the carry it sets is not read: mostly the case `0` of a
  `switch` after a load. Play 284,286 to 283,728 master clocks per tick.
- **X and Y for indices.** A load or store at a global plus a 16-bit index that the value ranges
  show to be non-negative is `ldx` and `lda symbol+constant,x`: no pointer is computed. A
  negative index would carry into the bank, because X adds as an unsigned number. Where X holds
  another index, as for the two bubbles of a pair, the access uses Y instead, for the
  instructions that have an absolute,Y form; an index that does not change in a loop prefers Y,
  and leaves X to the index that changes. Through a pointer, an index that is never negative
  goes into Y for `lda [dp],y`, so the sum of pointer and index is not computed. `IndexInY`, a
  pass on LIR, turns `LDX p` and `LDA table,X` into `LDA table,Y` where Y holds `p` on every way
  in. Arrays indexed with X took play from 2.35 to 2.29 frames per tick; each use of Y brought
  about 0.01 frames per tick more, and `IndexInY` 365 master clocks per tick.
- **Loop counters in X.** A φ of a loop head can live in X for the whole loop when it is only
  compared with constants, indexes the loop's array accesses and is stepped by a constant
  (`INX`, `DEX`, or `TXA`/`ADC`/`TAX`). Loops with calls, the runtime's multiplication or
  division, or an array access with another index are left out, and the innermost loop gets X.
  The gain was small: play 1.54 to 1.53 frames per tick. An access with another index that can
  use Y instead (`lda` and `sta` at an absolute address, not `stz`) no longer keeps X out: in
  the game's pair loop, the loop of `separate` over every pair of bubbles, one bubble's offset
  lives in X and the other's in Y. Play 1.01 to 1.00 frames per tick.
- **Byte loads.** A byte load that is not volatile reads 16 bits and masks the high byte:
  `lda` and `and #$00FF` instead of switching to 8 bits and back. The byte after the object is in
  the same bank, because the last byte of every bank stays free; I/O registers are read one byte
  exactly. Between a byte load and a byte store the accumulator stays 8 bit.
- **Lazy byte masks.** An 8-bit value may carry any high byte unless one of its readers needs it
  clean: comparisons, extensions, right shifts, divisions, calls, returns and switches. Only those
  values get an `and #$00FF`, and none after an 8-bit add whose value range stays below 256, as
  for the counter in the example. Play 1.57 to 1.56 frames per tick.
- **Multiplication in A.** A multiplication by a constant in 8 or 16 bits is done in A, Horner's
  way over the non-adjacent form: a shift per digit, an add or subtract where the digit is ±1.
  Together with indexed addressing, play went from 2.35 to 2.07 frames per tick.
- **Shifts.** Shifts by a constant up to 8 are written out instead of looping; shifts by 8 or
  more of a 16-bit value start with `XBA`, which swaps the bytes of A.
- **Block layout.** Blocks are written in chains, each followed by a successor not yet written,
  so most branches skip one jump instead of choosing between two. Without a profile, the
  successor that leads to the next round of one of the block's loops comes first, then one that
  only this block reaches, then the true side of the branch. A small loop head is placed after
  the block that jumps back to it, so a round costs one branch instead of a jump and a branch.
  With block counts from a profile
  ([`--block-counts`](/reference/command-line#block-counts)), the successor that ran more often
  follows, and blocks that never ran go to the end of the function; only the order follows the
  counts, so counts of other code make the code slower, never wrong. The two rules for the order
  without a profile took play from 286,947 to 284,286 master clocks per tick, and a profile from
  1.38 to 1.37 frames per tick.
- **Short branches and `BRA`.** A conditional branch to a block that is not the next one either
  reaches it or skips a `JMP` to it. Selection writes every jump and branch between blocks in
  its long form; `BranchRelax` then gives the short form, in rounds, to every one that reaches
  its target in the code of the round before, until nothing changes. Shorter code only brings
  targets closer, so a branch that reached once keeps reaching. It runs after the other LIR
  passes, whose shorter code lets more branches reach. A jump that reaches its block is `BRA`,
  as fast as `JMP` and one byte shorter, so more branches reach. Play 1.48 to 1.47 frames per
  tick. A branch over a jump becomes one inverted branch to the jump's target anywhere in a
  block, not only at its end, and a `switch` writes its jumps the same way: a matching case is a
  taken `BEQ` (3 cycles) instead of a `BNE` not taken and a `JMP` (5). Play 322,222 to 321,043
  master clocks per tick.
- **`INC` and `DEC`.** A step `x + 1` or `x - 1` whose result goes back to the place of `x`,
  such as a loop counter, is `INC` or `DEC` on the place: 7 cycles instead of 13 for `CLC`,
  `LDA`, `ADC`, `STA`. It needs `x` really stored there, not only handed on in A. Play 1.47
  to 1.43 frames per tick. An 8-bit step whose result must have a clean high byte, such as a
  counter a comparison reads, would still need an `and #$00FF`. If the old value is clean too
  (a parameter, or a φ of clean values), dampf writes `sep #$20`, an 8-bit `inc` or `dec` and
  `rep #$20` instead, which keeps the high byte 0: 11 cycles instead of 16. Play 1.39 to 1.38
  frames per tick.
- **`STZ`.** A zero stored to a fixed address (direct page, absolute or indexed with X, not
  long or through a pointer) is `STZ` instead of `LDA #0` and `STA`, and so is a zero a φ gets,
  unless A holds 0 already. In the [PointerOffsets](#pointeroffsets) example, `o[0] = 0` is
  `stz _oam,x`. Play 1.43 to 1.41 frames per tick.
- **Peephole rules.** `Peephole` replaces a few instructions in a row within a block by cheaper
  ones. `STA p` followed by `LDX p` becomes `STA p` and `TAX` (and `LDY` becomes `TAY`), 2 cycles
  instead of 3 or 4; if nothing reads `p` afterwards, `DeadStores` removes the store. Play
  321,043 to 318,591 master clocks per tick. `LSR A` k times and then `ASL A` m times, m > k,
  becomes an `AND` with a mask and m - k shifts (`(slot >> 3) << 5` in `build_sprites`, the
  game's function that builds the sprite table: 7 cycles instead of 16); `REP #$20` and
  `SEP #$20` where A was 8 bit before both go; `LDA x`, `XBA`, `AND #$00FF` becomes `LDA x+1`,
  `AND #$00FF`. Play 318,591 to 316,639 master clocks per tick.
- **The carry where it is known.** `KnownCarry` follows the carry over the blocks: a branch on
  the carry tells it on both ways, `CLC` and `SEC` set it, arithmetic, compares, shifts and
  calls make it unknown. A `CLC` where it is clear already goes, likewise a `SEC` where it is
  set; the typical case is `CMP #n` and `BCC body`, then `CLC` and `ADC` in the body, as in the
  loop above. 313 of the game's 1,488 `CLC` go. Play 316,639 to 315,047 master clocks per tick.
- **8-bit stretches.** In `SEP #$20`, `STA o,Y`, `REP #$20`, `LDA x`, `SEC`, `SBC #1`,
  `SEP #$20`, `STA o+1,Y`, only the low byte of the stretch between `REP` and `SEP` is stored,
  so `ByteStretches` removes the pair and computes the stretch in 8 bits (`SBC #$01`). Every
  instruction allowed in the stretch gives the same low byte in 8 bits; stores, right shifts,
  compares, `XBA`, `TAX`, calls, a carry from inside the stretch and loads of hardware registers
  are not allowed, and no flag or high byte of A it leaves behind may be read afterwards. 417 of
  the game's 1,576 `REP #$20` go. Play 290,433 to 286,947 master clocks per tick.
- **Pointer sums where they are needed.** `Item *it = &g_items[i]` in a loop is a sum of a
  global and an index; its loads and stores are addressed with the index in X or Y, and the sum
  itself is never computed. But one call that takes `&it->x`, on a rare way through the loop,
  made the code generator compute the whole pointer, bank word included, in every round. Just
  before code generation, `PointerRemat` gives each use of such a sum's value its own copy of
  the sum, right before the use; never in a loop the original is not in, and never for a φ. The
  original then only addresses loads and stores. Play 315,047 to 309,008 master clocks per tick.

## Value ranges

For every integer value of a function, `dampf.mir.Ranges` infers an interval. The intervals come
from constants, from the operations (a result that may leave its type's range gets the whole
range, because it wraps), and from the conditions of branches: in a block reached only when
`sy >= 16`, `sy` lies in 16..32767, in that block and every block it dominates. A loop head
whose other predecessors are all back edges gets the condition of the one edge that enters the
loop, because a value tested before the loop does not change inside it. A φ joins its incoming
values. Loops are iterated until nothing changes, and a value whose interval keeps growing is
widened to its type's whole range after 24 changes, so the iteration ends.

A φ takes each incoming value with the condition of the edge it comes along: in
`x < 0 ? -x : x`, the `x` comes along the edge where `x < 0` is false, so it is not negative
there. With that, the absolute value of a difference of two bytes lies in 0..255, and the
comparisons with it become unsigned; the game's signed comparisons (`EOR #$8000`) fell from 184
to 160. Play went from 324,256 to 322,222 master clocks per tick.

Widening overshoots: the counter of `for (i = 0; i < 768; i++)` passes 24 changes long before
768 and got its type's whole range, which kept it from being narrowed and from indexing with X.
After the widened result, up to eight rounds of narrowing evaluate every instruction again from
ranges that now hold. That can only take back what widening added, and the counter gets 0..768.
Play went from 1.53 to 1.50 frames per tick.

A condition between two values, such as `r < n`, bounds each by the other's range: the analysis
runs a second time and takes the other value's range from the first run. A parameter starts
with the range its callers pass ([ParamRanges](#paramranges)).

A [hint](/glossary#hint) `DAMPF_RANGE(i, 0, 15)` states the range of a variable at one point.
dampf lowers it to comparisons that branch to a block ending in `unreachable` when the hint is
wrong: the analysis learns the range from the branches, and the MIR interpreter fails if the
hint does not hold. The code generator computes the ranges with these checks in place and then
takes them out (`dampf.mir.Hints`), so the SNES code does not pay for them.

Several parts of dampf use the ranges:

- **`RangeNarrow`** decides comparisons, does i32 work in 16 bits and chooses unsigned
  operations, as in the `tile_row` example above.
- **`Induction`** checks that an 8-bit counter does not wrap.
- **The code generator** indexes with X only when the index is never negative, leaves out byte
  masks where a sum stays below 256, and keeps an 8-bit loop counter in X only when it cannot
  wrap.
- **The timing analysis** takes loop bounds from them ([Exact timing](/inside/timing)).

A wrong range would give wrong code without any sign. The tests therefore check the analysis
itself: the interpreter reports every value it computes and every parameter it receives, and
each must lie in its inferred interval, in all execution tests, the random programs, the replay
of the game, and `dampf mir -O --check-ranges --bench`.

[Pointer compression](/glossary#pointer-compression) is the other use of C's rules about
ranges. A pointer must stay within its object or point one past its end, so an offset into
one object lies between 0 and the object's size. `PointerOffsets` and `PointerParams` give the
new φs and parameters exactly that range, which indexed addressing needs. What is not compressed
are pointers that may point into several objects (a `Bubble *` into either of two arrays) and
pointers loaded from memory.

Declared ranges, subrange types written with `DAMPF_RANGED`, are designed but not built yet; see
the [Roadmap](/inside/roadmap).

## The measurements

Every step was measured on Blubber Blasen's benchmark, all on 2026-11-17; the benchmark run has
to match the checksums of the [PC build](/glossary#pc-build) on every tick. The figures are frames
per tick during play, and during play with the [CPU partner](/glossary#cpu-partner), the part of
the benchmark where the budget is hardest to meet; the worst tick is in parentheses where it was
measured. The rows down to 1.31 are SlowROM, the row of FastROM and those after it FastROM.

| Step | Play | Play, CPU partner |
| --- | --- | --- |
| naive code generator | 5.17 (8.58) | 6.55 (12.23) |
| code from MIR, no passes | 18.93 (33.76) | 23.13 (46.17) |
| B, from the types | 16.08 | 20.55 |
| B, with 16-bit pointer offsets | 8.16 (14.34) | 9.92 (19.96) |
| I, constant multiplication and division | 6.12 (10.65) | 7.11 (13.54) |
| H, standard optimizations | 5.17 (8.77) | 6.10 (11.22) |
| compare and branch in one | 4.48 (7.58) | 5.37 (10.12) |
| value ranges | 4.38 (7.50) | 5.15 (9.13) |
| fewer loads | 4.04 | 4.81 |
| φs written directly | 3.85 (6.59) | 4.61 (8.22) |
| frames in the direct page | 2.90 (4.93) | 3.68 (6.92) |
| shared places within a function | 2.72 (4.64) | 3.45 (6.38) |
| inlining, casts without code, fewer masks, jump threading | 2.55 (4.28) | 3.31 (6.08) |
| block layout, shifts with `XBA` | 2.50 | 3.27 |
| results kept in A | 2.40 | 3.18 |
| φs share their operands' places | 2.35 (3.88) | 3.12 (5.69) |
| arrays indexed with X | 2.29 | 3.05 |
| multiplication in A | 2.07 (3.51) | 2.79 (5.33) |
| 8 bit between byte copies | 2.05 | 2.77 |
| 16-bit byte loads | 1.98 | 2.69 |
| more results in A | 1.95 (3.31) | 2.66 (5.12) |
| short branches, two peepholes | 1.93 (3.29) | 2.64 (5.09) |
| φs written before a branch, canonical comparisons | 1.76 (3.00) | 2.45 (4.71) |
| unsigned operations from value ranges | 1.74 (2.98) | 2.43 (4.68) |
| jump threading over constant φs | 1.67 (2.87) | 2.36 (4.62) |
| scheduling, more hand-overs in A | 1.61 (2.80) | 2.30 (4.52) |
| induction variables | 1.57 (2.74) | 2.26 (4.50) |
| lazy byte masks | 1.56 | |
| pointer compression | 1.54 (2.68) | 2.24 (4.47) |
| loop counters in X | 1.53 (2.65) | 2.22 (4.46) |
| narrowing after widening | 1.50 | |
| A remembers every place it equals | 1.49 | |
| byte loads for 8.8 values | 1.48 | |
| short branches wherever they reach, `BRA` | 1.47 | |
| `INC` and `DEC` for steps | 1.43 | |
| `STZ` for zeros | 1.41 | |
| a bit chosen by a bit (`BitSelect`) | 1.39 | |
| 8-bit `INC` and `DEC` for clean steps | 1.38 | |
| arguments in place, a block for each loop exit | 1.37 | |
| a second index in Y | 1.36 | |
| what A, X and Y hold where a block starts | 1.34 (2.33) | 2.02 (4.15) |
| loop-invariant code, loop-invariant indexes in Y | 1.33 | |
| labels inside blocks, assumptions kept after a contradiction | 1.31 (2.25) | 1.98 (4.08) |
| FastROM: the ROM in banks `$80` and up at 3.58 MHz (`MEMSEL`) | 1.10 (1.88) | 1.69 (3.51) |
| no `cmp #0` where N and Z already describe A | 1.08 (1.86) | 1.68 (3.49) |
| values every use takes from A not stored | 1.06 (1.83) | 1.66 (3.48) |
| a `switch` on a byte compares 16 bits | 1.04 (1.80) | 1.64 (3.45) |
| returns copied into each path | 1.02 (1.78) | 1.62 (3.42) |
| an index through a pointer in Y | 1.01 (1.76) | 1.60 (3.40) |
| loop counters in X next to an index in Y | 1.00 (1.74) | 1.60 (3.40) |
| the loop test on the offset | 0.92 (1.64) | 1.51 (3.31) |
| a row of a 2D array as the pointer for an index in Y | 0.91 (1.60) | 1.48 (3.18) |

The steps that moved the back end to LIR were measured on 2026-11-18 in dampf's emulator, in
master clocks per tick with FastROM. Together they took play from 326,596 to 324,844, both
0.91 frames per tick, and play with the CPU partner from 528,446 to 527,096. Writing each
function once, with the LIR passes in place of the repeated writing, cost 312 master clocks per
tick in play (0.1 %), because selection no longer knew that Y held an index where a block
starts; `IndexInY` won that back and more.

The steps after them were also measured on 2026-11-18 in dampf's emulator, one at a time, in
master clocks per tick with FastROM (357,366 make a frame):

| Step | Play | Play, CPU partner |
| --- | --- | --- |
| before them | 324,844 | 527,096 |
| neighbouring constants as one range check (`RangeChecks`) | 324,256 | 526,568 |
| φ ranges with the conditions of their edges | 322,222 | 525,415 |
| branch relaxation everywhere, switches too | 321,043 | 524,276 |
| `STA p` / `LDX p` as `STA p` / `TAX` | 318,591 | 522,294 |
| peephole rules for shifts, `REP`/`SEP`, high bytes | 316,639 | 520,629 |
| no `CLC` where the carry is clear (`KnownCarry`) | 315,047 | 518,756 |
| pointer sums where their value is needed (`PointerRemat`) | 309,008 | 512,257 |
| range checks of aligned blocks as a mask | 306,298 | 509,646 |
| functions simplified before their callers inline them | 301,962 | 504,774 |
| inlining up to 60 instructions | 290,433 | 493,872 |
| 8-bit stretches between byte stores (`ByteStretches`) | 286,947 | 490,828 |
| block order: a successor only this block reaches | 286,244 | 490,087 |
| block order: the way to the next round first | 284,286 | 486,715 |
| no `CMP #0` where N and Z are set | 283,728 | 486,210 |
| loop rotation by the loop's own latch | 282,498 | 484,490 |

Together they took play from 0.91 to 0.79 frames per tick, 13 %. Measured outside the game, on
dampf's execution tests and 50 random programs (master cycles of the whole program), the same
steps took the execution tests from 6,223,446 to 6,164,334 (−0.9 %) and left the random
programs where they were: their cycles go to the runtime's division routines, which no step
touched. The general steps help elsewhere too, much less than in the game.

The current state, measured on 2026-11-18 after these steps: play takes 0.79 frames per tick
(worst 1.42), play with the CPU partner 1.36 (worst 3.03). The other parts of the benchmark take
0.37 frames per tick on the title screen, 0.16 in the story and 1.41 in the round intro, and the
whole benchmark 0.81, all FastROM. On the console, the playable ROM took 1.57 frames per tick
during a minute of play, measured before these steps; that includes waiting for the vertical
blank and the [platform layer](/glossary#platform-layer), so it is not comparable with the
benchmark.

The steps are cumulative: each row includes everything above it. The first loop rotation, of
2026-11-17, is not a row of its own in the first table; it brought about 0.5 %, measured on the
benchmark's bare loop (801 to 783 master clocks per pass, SlowROM). Neither is the block layout
by a profile: all figures are measured without block counts, and with them play took 1.37
frames per tick where it took 1.38 without (SlowROM). The early steps brought the most,
because they removed whole classes of waste: 32-bit arithmetic, the runtime multiplication, RAM
accesses outside the direct page. The later ones each take a few percent.

Where the time went at 1.50 frames per tick (SlowROM), the last time it was broken down by
instruction: in the measured play ticks, `sta dp` took 18.8 % of the cycles in the game code,
`lda dp` 16.5 %, `cmp #imm` 7.4 %, `lda abs,x` 7.2 % and `jmp abs` 5.6 %. Values going to and
from the direct page are still a third of the time. Most `JMP`s left are where a branch cannot
reach its target because a large loop body lies between them; placing the rarely run blocks at
the end of the function needs to know which are rare, which block counts from a profile tell
(see *Block layout* above).

## How far it can go

One function has been written by hand in 65816 assembly as a yardstick: `level_solid_px`, a
tile collision test of the game. Measured in master clocks per call, from the benchmark's micro
part, at SlowROM speed:

| Code | Clocks per call | Times the hand-written code |
| --- | --- | --- |
| dampf, naive code generator | 3,849 | 4.5 |
| 816-tcc | 2,449 | 2.9 |
| Calypsi | 931 | 1.1 |
| dampf, code from MIR | 1,149 | 1.34 |
| hand-written | 856 | 1.0 |

The 816-tcc and Calypsi figures come from older runs; no FastROM figure of either exists for
this function. With FastROM, measured on 2026-11-18 after the steps under
[The measurements](#the-measurements), dampf's code takes 827 master clocks per call and the
hand-written code 724: 1.14 times. On a small function like this one, Calypsi already came
close to hand-written code at SlowROM speed.

A gap remains because a skilled assembly programmer knows things about the program that are not
in the C code:

- **Data chosen for the CPU.** A state flag in bit 7, so that `BIT` and `BMI` test it without a
  compare, or a table in ROM instead of a calculation.
- **Conventions across functions.** "X always holds the index of the current bubble during the
  update phase", or "this whole routine runs in 8-bit mode".
- **Flags as data.** The carry flag as a yes/no return value, the overflow flag after `BIT` as a
  second test bit.
- **Instruction-level tricks.** `XBA`, moving the direct page with `TCD`, `PEI` and `PEA` for
  fast copies.

dampf only applies the tricks it has been taught; the hand-written reference functions are how
it is taught new ones. Where the reference is faster, the reason becomes a new optimization
rule.

## Why it works this way

**Multiplying in A instead of in MIR.** Pass I first turned every multiplication by a constant
into shifts and adds in MIR. After `j * 23` had become `(j << 5) - (j << 3) - j`, the value ranges
lost the connection between the three parts and could no longer show that an index was never
negative, so indexed addressing was lost. In 8 and 16 bits the multiplication now stays a `mul`
in MIR, where its range is exact, and the code generator does the shifts and adds in A. Pass I
keeps the rule for i32 only. (`docs/optimizations-log.md`, *Arrays indexed with X,
multiplication in A*)

**Pointer compression only into one object.** dampf compresses a pointer only where every value
it can take points into the same object, which it can see within a function or across the calls
of a parameter. The alternative, a points-to analysis over the whole program, would also cover a
`Bubble *` into either of two arrays and pointers loaded from memory. The indirect accesses that
are left did not justify it, so those pointers stay full 4-byte pointers used as `[dp],y`.
(`docs/optimizations-log.md`, *Pointer compression*)

**Trusting C's rules, and checking them.** The value ranges and pointer compression rely on C's
rules: a pointer stays within its object, an index within its array. Other compilers draw
conclusions from such undefined behaviour that surprise programmers; dampf draws them too, but
makes them rules of the subset that the tests check: every value the interpreter computes must
lie in its inferred range. That check caught a wrong range in the first version of `Induction`
and a pointer stepped past the end of its array in a test program. The cost: on a path the
tests never run, a broken rule gives wrong SNES code without any sign. (`docs/subrange-types.md`,
*The array bounds rule*; `docs/optimizations.md`, *Value ranges (B)*;
`docs/optimizations-log.md`, *Induction variables* and *Pointer compression*)
