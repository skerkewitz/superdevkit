<!-- GENERATED from doc-specs/inside/timing.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Exact timing

dampf computes how long your code runs. For every function the
[code generator from MIR](/glossary#code-generator-from-mir) writes, it works out a best and a
worst case in CPU cycles, from the function's entry to its return with the functions it calls,
writes them to a timing report, and refuses a build in which a function breaks the cycle budget
you gave it. The [naive code generator](/glossary#naive-code-generator) does none of this.

## Why the 65816 can be timed exactly

The 65816 has no cache, no pipeline and no branch prediction. Every instruction takes a fixed
number of cycles, documented per addressing mode in the WDC data sheet, and only a few known
conditions add to it. dampf's table of these counts is `dampf.target.Cycles65816`; each entry
is the cost in [8-bit mode](/glossary#_8-bit-and-16-bit-mode), and the processor state adds:

| Condition | Extra cost | In dampf's code |
| --- | --- | --- |
| 16-bit accumulator ([`M` flag](/glossary#m-and-x-flags) clear) | +1 for loads, stores, arithmetic and immediates; +2 for read-modify-write on memory | followed through the code from every `sep #$20` and `rep #$20` |
| 16-bit index registers (`X` flag clear) | +1 for loads, stores, compares, pushes and pulls of X and Y | always paid: X and Y are always 16 bit |
| Indexed read across a page boundary | +1 | always paid: with 16-bit index registers the 65816 takes it on every indexed read |
| [Direct page](/glossary#direct-page) register `D` with a low byte other than zero | +1 for direct page accesses | never paid: dampf keeps the low byte of `D` at zero |
| Conditional branch taken | +1 | depends on the data |

Only the last row depends on the data. Everything else is fixed by the code dampf writes, so a
run of instructions without a branch costs exactly the sum of its entries, and the branches are
where a best and a worst case come in.

dampf counts CPU cycles, and they are not all equally long: in master cycles, the clock of the
SNES, a CPU cycle takes 6 for FastROM and internal operations and 8 for SlowROM and work RAM.
One NTSC frame is 357,366 master cycles, roughly 45,000 to 55,000 CPU cycles depending on how
much code runs from FastROM and how often work RAM is accessed. Counting in master cycles, and
the time that DMA, HDMA and interrupts take from the CPU, are not built, nor a hint for the trip
count of a loop without a counter (`DAMPF_TRIPS`); see [Limits](/reference/limits).

## Bounds for a function

The analysis, `dampf.backend.Timing`, works on the code as the code generator wrote it, the
function's [LIR](/glossary#lir) with the cycles of every instruction, in two steps.

**Within a block.** Each basic block of the function's [MIR](/glossary#mir) becomes straight
code, apart from forward branches over a few instructions and the small loops the code
generator writes for shifts and copies, which it marks with their number of rounds. dampf walks
the block instruction by instruction and gives every way out of it, to another block or to the
return, an interval of cycles. A conditional branch costs 2 cycles when it is not taken and 3
when it is.

**Across blocks.** dampf takes the loops of the function, the innermost first, and collapses
each into one node. If the loop's head runs at most *n* times, the worst case through the loop
is (*n* − 1) times the longest way round plus the longest way out; the best case is the
shortest way out, without a round. What is left is free of cycles, and the bounds of the
function are its shortest and longest path from the entry to the return.

Take this program, saved as `game.c`:

```c [game.c]
#include <stdint.h>

uint8_t scores[32];
uint16_t total;
uint16_t width, height, area;
int16_t speed;

int16_t abs16(int16_t v)
{
    if (v < 0) {
        return (int16_t)-v;
    }
    return v;
}

void sum_scores(void)
{
    uint16_t sum = 0;
    for (uint8_t i = 0; i < 32; i++) {
        sum = (uint16_t)(sum + scores[i]);
    }
    total = sum;
}

void wait_ready(void)
{
    while ((*(volatile uint8_t *)0x4212 & 0x80) == 0) {
    }
}

void halt(void)
{
    for (;;) {
    }
}

int main(void)
{
    wait_ready();
    sum_scores();
    speed = abs16(speed);
    area = (uint16_t)(width * height);
    if (total == 0xFFFF) {
        halt();
    }
    return 0;
}
```

Build it (`dampf` is the launcher `build/install/dampf/bin/dampf`;
[Command line](/reference/command-line) has the options):

```sh
dampf build -o build/game.sfc game.c
```

The code of `sum_scores` in `build/game.s`, cut to the instructions, with each one's cycles in
the column on the right:

```asm
.proc _sum_scores
b0_entry:
        stz     z:dampf_frame+2     ;                                                           4
        ldx     #$0000              ;                                                           3
        bra     b1_for              ;                                                           3
b2_body:
        lda     _scores,x           ;                                                           6
        and     #$00FF              ;                                                           3
        clc                         ;                                                           2
        adc     z:dampf_frame+2     ;                                                           4
        sta     z:dampf_frame+2     ;                                                           4
        inx                         ;                                                           2
b1_for:
        cpx     #$0020              ;                                                           3
        bcc     b2_body             ;                                                         2/3
b4_endfor:
        lda     z:dampf_frame+2     ;                                                           4
        sta     _total              ;                                                           5
        rtl                         ;                                                           6
.endproc
```

The entry costs 10 cycles. One way round the loop, from the test at `b1_for` through the body
and back, costs 3 + 3 + 19 + 2 = 27; the way out, the test with the branch not taken and the
rest of the function, costs 3 + 2 + 9 + 6 = 20. The head runs at most 33 times, 32 rounds and
the test that ends the loop, so the worst case is 10 + 32 × 27 + 20 = 894 cycles. The best case
is 10 + 20 = 30: it is the shortest way out and does not use the loop's bound, so for a loop
that always runs the same number of rounds it lies far below what the function takes.

### Loop bounds

dampf finds the bound of a loop in the code. It looks for a counter that every way back steps
by the same constant *c*, and takes the smallest bound of these rules:

- **A finite [value range](/glossary#value-range).** If the counter's range in the head is
  *lo* to *hi* and the step cannot wrap around, the head runs at most (*hi* − *lo*) / |*c*| + 1
  times.
- **An ordered test.** A test that every round passes and that keeps the loop going while the
  counter is on the near side of another value (`i < n`, `i > rank`, `v >= 100`, signed or
  unsigned, also with the counter zero-extended) bounds it by that value's extreme, if the step
  cannot wrap there: at most (extreme − start) / |*c*| + 2 head runs. The test may stand further
  down in the loop, as long as every way back passes it.
- **An equality test.** With *c* = 1 or −1 and a test like `k != 0` that keeps the loop going,
  the counter meets the constant after at most its distance modulo 2<sup>*w*</sup> for a
  *w*-bit counter.

A loop that none of these rules bounds makes the worst case unbounded. That is not always
pessimism: `for (y = t; y <= b; y++)` with an 8-bit `b` never ends when `b` is 255. The loops
that remain without a bound run over memory (`while (s[n]) n++;`), step their counter by
something other than a constant, or could really run forever in C. A
[hint](/glossary#hint), `DAMPF_RANGE` on the counter inside the loop, gives such a loop a bound
through the value ranges ([Hints](/reference/hints)). In the benchmark build of
[Blubber Blasen](/glossary#blubber-blasen) of 2026-11-18, 294 functions get a bound and 37 do
not.

### Calls

A call costs its own cycles plus the bounds of the function it calls. dampf computes the
callees first, which works because the subset has no recursion. The multiplication and division
routines of the [runtime](/glossary#runtime) have fixed bounds, counted by hand from
`runtime.s`: their loops run a fixed 16 or 32 rounds. A function that dampf has inlined is part
of its caller's code and counts there; the report still lists the function as compiled on its
own.

### Functions without a bound

A loop without a bound, such as the wait for bit 7 of `$4212` in `wait_ready`, leaves the
function with a best case but no worst case. A function that never returns, such as `halt`, has
neither. A call of a function that never returns makes the caller's worst case unbounded, and
the report names that call (`calls _fail` for a function `fail`). Where dampf has inlined such a
function, as it did with `halt` in `main`, its loop has no way out, and the caller's bounds
count only the ways that reach the return.

## The timing report

`dampf build` writes the bounds next to the ROM, as `build/game.timing.txt` for the build
above, the most expensive function first:

```
# dampf timing: best and worst case per function in CPU cycles, from entry to return,
# calls included, interrupts and DMA not (docs/timing.md). "unbounded": a loop without
# a known bound on the way; the first such loop in the function, or the first call
# of a function without a bound, is named after it. "never": the function never returns.
        best        worst  function
       never    unbounded  _halt   (loop at game.c:33)
         417    unbounded  _main   (loop at game.c:27)
          22    unbounded  _wait_ready   (loop at game.c:27)
        1940         2594  dampf_sdiv32
        1888         2432  dampf_udiv32
        1168         1968  dampf_mul32
          30          894  _sum_scores
         564          772  dampf_udiv16
         287          367  dampf_mul16
          13           21  _abs16
```

Each line holds the best case, the worst case and the function's label, the C name with an
underscore in front. The five `dampf_` lines are the runtime's routines, listed whether the
program calls them or not. A function without a worst case says `unbounded` and comes first;
after its name stands the reason, the first loop without a bound in it (`loop at` with the C
file and line) or else the first call of a function without a bound (`calls` with its label).
`never` as the best case marks a function that never returns.

`_main` names the loop on line 27 although that line is in `wait_ready`: dampf inlined
`wait_ready`, `sum_scores`, `abs16` and `halt` into `main`, so the loop is part of `main`'s
code. Its best case of 417 includes the 287 cycles of `dampf_mul16`, which the multiplication
on line 42 calls.

The assembly carries the same cycle counts at a finer grain. Every instruction has its cycles in
the column on the right, a conditional branch as *not taken*/*taken* (`2/3`). The comment that
starts a C line gets the sum of its instructions when the line is straight code:

```asm
; game.c:42   area = (uint16_t)(width * height);                                [ 43 cyc + calls ]
        .dbg    line, "../game.c", 42
                                    ; %16 = load i16 @_height
        lda     _height             ;                                                           5
        sta     z:dampf_frame+0     ;                                                           4
                                    ; %17 = load i16 @_width
        lda     _width              ;                                                           5
        sta     z:dampf_frame+2     ;                                                           4
                                    ; %18 = mul i16 %17, %16
        lda     z:dampf_frame+0     ;                                                           4
        sta     z:R0                ;                                                           4
        lda     z:dampf_frame+2     ;                                                           4
        jsl     dampf_mul16         ;                                                           8
                                    ; store i16 @_area, %18
        sta     _area               ;                                                           5
```

A sum with `+ calls` counts the `jsl` itself, not the function it calls; the report has that
function's bounds. A line with a branch, a jump or a label inside gets no sum, because its cost
depends on the path; the header of the `for` line in `sum_scores` above is one.

## Budgets

A budget states how many cycles a function may take at most: a vertical blank handler has to
fit into the vertical blank, the game logic of a [tick](/glossary#tick) into its share of a
[frame](/glossary#frame). Without a check, an overrun shows only when the screen flickers or
the game slows down. Write `DAMPF_BUDGET(n)` from `<dampf.h>` in front of a function definition
to give the function a budget of `n` CPU cycles, calls included:

```c [budget.c]
#include <stdint.h>
#include <dampf.h>

uint8_t scores[32];
uint16_t total;

DAMPF_BUDGET(800) void sum_scores(void)
{
    uint16_t sum = 0;
    for (uint8_t i = 0; i < 32; i++) {
        sum = (uint16_t)(sum + scores[i]);
    }
    total = sum;
}

int main(void)
{
    sum_scores();
    return 0;
}
```

After it has computed the timing, `dampf build` compares the function's worst case with the
budget. The worst case of 894 cycles is above 800, and the build fails:

```
budget.c:7:41: error: 'sum_scores' takes up to 894 CPU cycles, over its budget of 800 (DAMPF_BUDGET); best case 30, see the .timing.txt file
```

dampf writes the timing report before it checks the budgets, so `build/budget.timing.txt` is
there to read even when the build fails. A function without a worst case is an error as well,
since no budget can be guaranteed for it, and a worst case above 90 % of the budget builds with
a warning. dampf checks the function as compiled on its own. On the PC the macro is empty.
[Hints](/reference/hints) has the details and the other messages.

## How the bounds are checked

The tests hold every bound against a measurement in dampf's own emulator
([Testing](/inside/testing)). Every execution test and every random program built with the code
generator from MIR measures `test_main` and every call of every function and runtime routine on
the way, from the function's first instruction to the instruction after the `JSL` that called
it, and checks that best ≤ measured ≤ worst. With the
Gradle property `-Preplays`, `GameTimingTest` does the same for the benchmark of Blubber Blasen:
the whole game runs in the emulator and each of its calls, about 180,000, must lie within the
bounds of its function.

The check catches a wrong bound at once. A bound of `dampf_mul16` set too low on purpose, a
worst case of 300 instead of 367, failed 94 tests; the largest measured call took 362 cycles.

## Why it works this way

**Bounds per function, bottom-up.** dampf computes the bounds of one function at a time, the
callees first, on the structure of its code: blocks, then loops collapsed innermost first. This
needs a call graph without cycles, which the subset gives by having no recursion, and code
whose loops have a structure; unrestricted `goto` would need a more general analysis, which is
why the subset rejects `goto`. (`docs/timing.md`, *Level 2: exact bounds for branches and
loops*)

**A worst case that may be too high.** The worst case adds up the costliest way through every
branch, also where two branches can never both be taken. Annotations that exclude impossible
combinations were left for later; the cost is a bound that may be pessimistic, and the replays
show how far: they measure the cost in real game situations, which can be compared with the
static worst case. (`docs/timing.md`, *Level 2: exact bounds for branches and loops*)

**Budgets as build errors.** An overrun of a frame could be found by playing the game and
watching for flicker or slowdown. dampf checks budgets at build time instead, so the build
refuses the change that causes the overrun. The price is that a budgeted function needs a worst
case: every loop in it must have a bound dampf can find, or a hint that gives one.
(`docs/timing.md`, *What the budgets are for*)
