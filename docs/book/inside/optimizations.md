<!-- GENERATED from doc-specs/inside/optimizations.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->

# Optimizations

The game logic of [Blubber Blasen](/glossary#blubber-blasen) has a budget: in its busiest scene
it may use at most 60 % of an NTSC [frame](/glossary#frame), about 0.6 frames per
[tick](/glossary#tick). A frame has roughly 45,000 to 55,000 CPU cycles, and the work in the
vertical blank and a safety margin come off that. The game's benchmark runs the same game code
with a fixed input for 1,800 ticks and measures every tick in Mesen.

During play, the code dampf now generates takes 1.53 frames per tick (worst tick 2.67). dampf's
own [naive code generator](/glossary#naive-code-generator) takes 5.17 frames per tick for the
same code, and [Calypsi](/glossary#calypsi) took 2.72, measured on an older version of the game.
dampf is past Calypsi and about 3.4 times as fast as its naive code, but still about 2.5 times
away from the budget. This page explains, pass by pass, where that speed comes from.

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
round after round, until a round changes nothing (at most 20 rounds). Then it inlines small
functions and compresses pointer parameters, and if either changed anything, it runs the rounds
again. Scheduling comes last. Every pass is exact: it changes how a value is computed, never
the value.

`dampf mir file.c` prints the MIR as it comes from the lowering, `dampf mir -O file.c` after
the passes; the examples below are their output.

The working document `docs/optimizations.md` names each optimization with a letter in its
catalogue. The letters map to this page as follows:

| Letter | Optimization | Where it is built |
| --- | --- | --- |
| A | Static frames in the direct page | code generator: frames, shared places |
| B | Width analysis (8/16 bit) | `Narrow`, `RangeNarrow`; `REP`/`SEP` tracking not built |
| C | Inlining and calling convention | `Inline`; parameters in registers not built |
| D | Data layout (one array per field) | not built |
| E | 8.8 fixed point as byte accesses | not built |
| F | Loops | `Induction`, loop counters in X; the rest not built |
| G | Branches | compare and branch, block layout, short branches |
| H | Standard optimizations | `Simplify`, `Gvn`, `DeadCode`; loop-invariant code not built |
| I | Multiply and divide | `StrengthReduce`, multiplication in A; hardware multiplier not built |
| J | Banks and placement | not built |
| K | Peephole | a few, in the code generator |
| L | `switch` as jump table | not built |
| M | Copy and fill with `MVN`/`MVP` or DMA | not built |
| N | Values in X/Y | loop counters in X |

The optimizations not started yet are on the [Roadmap](/inside/roadmap).

### Simplify

Constants fold, computed with the interpreter's arithmetic so that they mean the same as at run
time. Identities such as `x + 0`, `x & -1` and `x - x` simplify, a branch on a constant becomes
a jump, unreachable blocks go, a φ whose operands are all the same value becomes that value, and
a block that only one block jumps to is merged into it. Jump threading sends a branch to a block
that only jumps straight to the final target, and skips a block that only branches on a φ for
every predecessor that gives the φ a constant: the shape that `a || b` and an inlined function
returning a truth value leave behind.

`Simplify` also writes a comparison with a constant in a canonical form, `x < c` or `x >= c`
with the constant on the right: `x > c` becomes `x >= c + 1`, `x <= c` becomes `x < c + 1`. The
code generator then compares with an immediate operand directly. In the example under
[Inline](#inline), `lives > 0` comes out as `cmp uge i8 %0, 1`.

Together with `Gvn` and an extension of `Narrow` through φs, it took play from 6.12 to 5.17
frames per tick. The canonical comparisons, together with a change in the code generator, took
it from 1.93 to 1.76, and jump threading over constant φs from 1.74 to 1.67.

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
1,230 clocks and play from 1.76 to 1.74.

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

After the passes, the offset is a φ `%1` of its own that starts at 0 and grows by 6; `Gvn` has
merged the two address computations, and `Simplify` the step block into the body:

```
b1_for:                    ; from b0_entry, b2_body
    %0 = phi i8 [b0_entry, 0], [b2_body, %6]    ; bubbles.c:13
    %1 = phi i16 [b0_entry, 0], [b2_body, %7]   ; bubbles.c:14
    %2 = cmp ult i8 %0, 16                      ; bubbles.c:13
    br %2, b2_body, b4_endfor                   ; bubbles.c:13
b2_body:                    ; from b1_for
    %3 = ptradd @_bubbles, i16 %1               ; bubbles.c:14
    %4 = load i16 %3                            ; bubbles.c:14
    %5 = add i16 %4, 1                          ; bubbles.c:14
    store i16 %3, %5                            ; bubbles.c:14
    %6 = add i8 %0, 1                           ; bubbles.c:13
    %7 = add i16 %1, 6                          ; bubbles.c:14
    jump b1_for                                 ; bubbles.c:13
```

The new φ carries the range of the product, because indexed addressing in the code generator
needs to know that it is never negative. Play went from 1.61 to 1.57 frames per tick.

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

Before the passes, `o` is a `ptr` φ; after them, it is an `i16` offset into `@_oam`:

```
b1_for:                    ; from b0_entry, b2_body
    %0 = phi i8 [b0_entry, 0], [b2_body, %6]    ; sprites.c:7
    %1 = phi i16 [b0_entry, 0], [b2_body, %5]   ; sprites.c:6
    %2 = ptradd @_oam, i16 %1                   ; sprites.c:6
; ...
    %5 = add i16 %1, 4                          ; sprites.c:10
```

The code generator writes the two stores as `sta _oam,x` and `sta _oam+1,x`. Together with
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

Play went from 8.16 to 6.12 frames per tick, and the share of the runtime's multiplication in
the time from 61 % to 4.5 %.

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
each other go too. The sources give no separate measurement for it.

### Inline

A call costs about 30 cycles besides the work: `JSL` and `RTL`, the arguments copied into the
callee's frame, the result copied back. `Inline` copies functions of at most 30 MIR instructions,
and functions with a single call site of at most 150, into their callers, as long as a caller
stays below 1,500. It works bottom-up, leaves first; the call graph has no cycles, because the
subset has no recursion. Then the passes run again and see both sides of the call.

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
instruction at a time. The loop of the [Induction](#induction) example comes out like this,
without the comment and debug lines:

```asm
b2_body:
        lda     _bubbles,x          ;                                                           6
        clc                         ;                                                           2
        adc     #$0001              ;                                                           3
        sta     _bubbles,x          ;                                                           6
        clc                         ;                                                           2
        lda     z:dampf_frame+0     ;                                                           4
        adc     #$0001              ;                                                           3
        sta     z:dampf_frame+0     ;                                                           4
        txa                         ;                                                           2
        clc                         ;                                                           2
        adc     #$0006              ;                                                           3
        tax                         ;                                                           2
b1_for:
        lda     z:dampf_frame+0     ;                                                           4
        cmp     #$0010              ;                                                           3
        bcc     b2_body             ;                                                         2/3
```

The offset lives in X for the whole loop, the counter `i` in the direct page, the comparison
branches on the flags, and the loop test sits after the body. What each of these is:

- **Frames in the direct page.** dampf has no recursion, so the frames form a compiled stack: a
  function's frame starts after the frames of all the functions it calls. The leaves of the call
  graph, small and called most often, start at `$0020`, in the direct page (D stays 0). Play went
  from 3.85 to 2.90 frames per tick.
- **Shared places.** Values whose lifetimes do not overlap share a place in the frame
  (`FrameLayout`: liveness, an interference graph, greedy colouring by byte ranges), and a φ
  shares its operands' places where the lifetimes allow, so the copy at the end of a loop body
  disappears. Play 2.90 to 2.72 for the shared places.
- **Results in A.** A 1-word result whose only reader is the next instruction, which starts by
  loading it, is never stored. The code generator also remembers what A, X and Y hold and leaves
  out a load of the same thing.
- **φs written directly.** The predecessors of a block write its φs into their places, without a
  copy through a second place, where liveness shows that this is safe; also before a branch.
- **Compare and branch.** A comparison that only its block's branch uses sets the flags and
  branches on them, without a truth value. A comparison with 0 needs no `CMP`, and a 16-bit test
  for `< 0` or `>= 0` is the sign flag of the load (`bmi`/`bpl`). Play 5.17 to 4.48.
- **X for indices.** A load or store at a global plus a 16-bit index that the value ranges show
  to be non-negative is `ldx` and `lda symbol+constant,x`: no pointer is computed. A negative
  index would carry into the bank, because X adds as an unsigned number.
- **Loop counters in X.** A φ of a loop head can live in X for the whole loop when it is only
  compared with constants, indexes the loop's array accesses and is stepped by a constant
  (`INX`, `DEX`, or `TXA`/`ADC`/`TAX`). Loops with calls, the runtime's multiplication or
  division, or an array access with another index are left out, and the innermost loop gets X.
  The gain was small: play 1.54 to 1.53.
- **Byte loads.** A byte load that is not volatile reads 16 bits and masks the high byte:
  `lda` and `and #$00FF` instead of switching to 8 bits and back. The byte after the object is in
  the same bank, because the last byte of every bank stays free; I/O registers are read one byte
  exactly. Between a byte load and a byte store the accumulator stays 8 bit.
- **Lazy byte masks.** An 8-bit value may carry any high byte unless one of its readers needs it
  clean: comparisons, extensions, right shifts, divisions, calls, returns and switches. Only those
  values get an `and #$00FF`, and none after an 8-bit add whose value range stays below 256, as
  for the counter in the example. Play 1.57 to 1.56.
- **Multiplication in A.** A multiplication by a constant in 8 or 16 bits is done in A, Horner's
  way over the non-adjacent form: a shift per digit, an add or subtract where the digit is ±1.
  Together with indexed addressing, play went from 2.35 to 2.07.
- **Shifts.** Shifts by a constant up to 8 are written out instead of looping; shifts by 8 or
  more of a 16-bit value start with `XBA`, which swaps the bytes of A.
- **Block layout.** Blocks are written in chains, each followed by a successor not yet written,
  so most branches skip one jump instead of choosing between two. A small loop head is placed
  after the block that jumps back to it, so a round costs one branch instead of a jump and a
  branch. Each function is generated twice; the first pass gives the distances, so that branches
  within reach go straight to their block.

## Value ranges

`dampf.mir.Ranges` infers an interval for every integer value of a function. The intervals come
from constants, from the operations (a result that may leave its type's range gets the whole
range, because it wraps), and from the conditions of branches: in a block reached only when
`sy >= 16`, `sy` lies in 16..32767, in that block and every block it dominates. A φ joins its
incoming values. Loops are iterated until nothing changes, and a value whose interval keeps
growing is widened to its type's whole range after 24 changes, so the iteration ends.

Several parts of dampf use the ranges:

- **`RangeNarrow`** decides comparisons, does i32 work in 16 bits and chooses unsigned
  operations, as in the `tile_row` example above.
- **`Induction`** checks that an 8-bit counter does not wrap.
- **The code generator** indexes with X only when the index is never negative, leaves out byte
  masks where a sum stays below 256, and keeps an 8-bit loop counter in X only when it cannot
  wrap.

A wrong range would give wrong code without any sign. The tests therefore check the analysis
itself: the interpreter reports every value it computes, and each must lie in its inferred
interval, in all execution tests, the random programs, the replay of the game, and
`dampf mir -O --check-ranges --bench`.

[Pointer compression](/glossary#pointer-compression) is the other use of C's rules about
ranges. A pointer must stay within its object or point one past its end, so an offset into
one object lies between 0 and the object's size. `PointerOffsets` and `PointerParams` give the new φs and
parameters exactly that range, which indexed addressing needs. What is not compressed are
pointers that may point into several objects (a `Bubble *` into either of two arrays) and
pointers loaded from memory.

Declared ranges, subrange types written with `DAMPF_RANGED`, are designed but not built yet; see
the [Roadmap](/inside/roadmap).

## The measurements

Every step was measured on Blubber Blasen's benchmark, all on 2026-11-17; the benchmark run has
to match the checksums of the [PC build](/glossary#pc-build) on every tick. The figures are frames
per tick during play, and during play with the CPU partner, the part of the benchmark where the
budget is hardest to meet; the worst tick is in parentheses where the source gives it.

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
| loop counters in X | 1.53 (2.67) | 2.22 (4.46) |

The steps are cumulative: each row includes everything above it. Loop rotation is not in the
table; it brought about 0.5 %, measured on the benchmark's bare loop (801 to 783 clocks per
pass). The early steps brought the most, because they removed whole classes of waste: 32-bit
arithmetic, the runtime multiplication, RAM accesses outside the direct page. The later ones
each take a few percent.

Where the time goes now: in the measured play ticks, loads and stores of values in the direct
page are 38 % of the cycles, `JMP` 5 %, comparisons and masks 5 % each. The full account of
every step is in `docs/optimizations.md`.

## How far it can go

One function has been written by hand in 65816 assembly as a yardstick: `level_solid_px`, a
tile collision test of the game. Measured in master clocks per call, from the benchmark's micro
part:

| Code | Clocks per call | Times the hand-written code |
| --- | --- | --- |
| dampf, naive code generator | 3,849 | 4.5 |
| 816-tcc | 2,449 | 2.9 |
| Calypsi | 931 | 1.1 |
| dampf, code from MIR | 1,230 | 1.4 |
| hand-written | 856 | 1.0 |

The 816-tcc and Calypsi figures come from older runs on the game's side. On a small function
like this one, Calypsi already comes close to hand-written code, and dampf is behind it there.

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
it is taught new ones. Where the reference is faster, the reason becomes a new optimization rule
or a hint.

## Why it works this way

**Multiplying in A instead of in MIR.** Pass I first turned every multiplication by a constant
into shifts and adds in MIR. After `j * 23` had become `(j << 5) - (j << 3) - j`, the value ranges
lost the connection between the three parts and could no longer show that an index was never
negative, so indexed addressing was lost. In 8 and 16 bits the multiplication now stays a `mul`
in MIR, where its range is exact, and the code generator does the shifts and adds in A. Pass I
keeps the rule for i32 only. (`docs/optimizations.md`, *Arrays indexed with X, multiplication
in A*)

**Pointer compression only into one object.** dampf compresses a pointer only where every value
it can take points into the same object, which it can see within a function or across the calls
of a parameter. The alternative, a points-to analysis over the whole program, would also cover a
`Bubble *` into either of two arrays and pointers loaded from memory. The indirect accesses that
are left did not justify it, so those pointers stay full 4-byte pointers used as `[dp],y`.
(`docs/optimizations.md`, *Pointer compression*)

**Trusting C's rules, and checking them.** The value ranges and pointer compression rely on C's
rules: a pointer stays within its object, an index within its array. Other compilers draw
conclusions from such undefined behaviour that surprise programmers; dampf draws them too, but
makes them rules of the subset that the tests check: every value the interpreter computes must
lie in its inferred range. That check caught a wrong range in the first version of `Induction`
and a pointer stepped past the end of its array in a test program. The cost: on a path the
tests never run, a broken rule gives wrong SNES code without any sign. (`docs/subrange-types.md`,
*The array bounds rule*; `docs/optimizations.md`, *Value ranges* and *Induction variables*)
