<!-- GENERATED from doc-specs/reference/hints.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Hints

A [hint](/glossary#hint) tells dampf something about your program that it cannot work out from
the C code, such as the largest value a counter reaches or how many cycles a function may take.
You write it as a macro from dampf's header `<dampf.h>`, so the same source still builds on the
PC with gcc. Three hints are built: `DAMPF_RANGE`, `DAMPF_BUDGET` and `DAMPF_ASM`.

## How hints work

Include `<dampf.h>` in the files that use hints. dampf finds it on its own, because it comes
with dampf in its own include folder. When dampf compiles, it defines `__DAMPF__`, and each
macro expands to a built-in, a name only dampf's [front end](/glossary#front-end) knows and
reads. The [superdevkit home](/glossary#superdevkit-home) also has the header in its `include`
folder, for the [PC build](/glossary#pc-build) of your game. Give gcc that folder with
`-idirafter`:

```sh
gcc -std=c99 -Wall -Wextra -idirafter <home>/include -c names.c
```

`-idirafter` searches the folder after gcc's own system folders, so `<dampf.h>` comes from dampf
while `<stdint.h>` still comes from the PC.

The header also defines `DAMPF_INTERRUPT` and `DAMPF_HAS_INTERRUPTS`. They are not hints: they
make a function the handler of an interrupt, as described in
[The C subset](/reference/c-subset#interrupt-handlers).

dampf trusts a hint and never checks it on the SNES: it builds its code on the assumption that
the hint holds. A wrong hint would make dampf produce wrong code without any message. On the PC
a hint that states a fact therefore becomes an `assert`, so the PC build checks every fact your
tests reach; defining `NDEBUG` switches the checks off, as for any `assert`. A hint that only
asks for a check at build time, such as `DAMPF_BUDGET`, expands to nothing on the PC.
`DAMPF_ASM` expands to nothing there as well, so the PC compiles the function's C body; dampf
checks that hint itself, in every build with the
[code generator from MIR](/glossary#code-generator-from-mir).

The kit's desktop and web builds, `superdevkit build linux` and `superdevkit build web`, compile
a game's files without the host's headers (`-nostdinc -ffreestanding`) and give the C compiler
dampf's include folder with `-isystem`, so there `<stdint.h>` is dampf's as well. Such a build
has no `<assert.h>`. There a wrong hint calls `__builtin_trap()` instead, which stops the
program without saying which hint failed; `NDEBUG` switches this check off as well. The `win64`
build does the same with MSVC's `/X` and `/I`, and there a wrong hint calls `__debugbreak()`.
The builds are described in [The superdevkit command](/reference/superdevkit#build).

## The hints

### DAMPF_RANGE

`DAMPF_RANGE(v, lo, hi)` states that the local variable or parameter `v` lies between `lo` and
`hi` at this point, both included. It is a statement of its own inside a function body. Both
bounds must be constants that fit `v`'s type.

dampf takes the range as true at the hint and uses it in all code that can only be reached by
passing the hint. Its main use is a loop that dampf cannot bound by itself, such as a loop that
runs until it finds a zero byte:

```c [names.c]
#include <stdint.h>
#include <dampf.h>

const char names[3][12] = {"ALPHA", "BETA", "GAMMA"};
uint8_t lens[3];

static uint8_t text_len(const char *s)
{
    uint8_t n = 0;
    while (s[n]) {
        DAMPF_RANGE(n, 0, 10);
        n++;
    }
    return n;
}

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 3; i++) {
        lens[i] = text_len(names[i]);
    }
}
```

Choose the bounds as the values `v` can really have at the hint. A name has at most 11
characters, since it ends with a zero in 12 bytes, so inside the loop `n` is at most 10.

`dampf build` writes the [timing report](/glossary#timing-report) next to the ROM, as
`<rom>.timing.txt`: for every function its best and its worst case in CPU cycles, from its entry
to its return, calls included ([Command line](/reference/command-line)). The `dampf_` lines are
the [runtime](/glossary#runtime)'s routines for multiplication and division. Without the hint
the loop's length depends on the data: `nohint.c`, which is `names.c` without the
`DAMPF_RANGE` line, gets a report with no worst case for it (`nohint.timing.txt`):

```
# dampf timing: best and worst case per function in CPU cycles, from entry to return,
# calls included, interrupts and DMA not (docs/timing.md). "unbounded": a loop without
# a known bound on the way; the first such loop in the function, or the first call
# of a function without a bound, is named after it. "never": the function never returns.
        best        worst  function
          28    unbounded  _test_main   (loop at nohint.c:10)
          31    unbounded  _text_len   (loop at nohint.c:10)
        1940         2594  dampf_sdiv32
        1888         2432  dampf_udiv32
         564          772  dampf_udiv16
         398          400  dampf_mul32
         108          108  dampf_mul16
```

With the hint, `n` is at most 10 each time the loop body runs, so the body runs at most 11
times, for `n` from 0 to 10, and the loop has a worst case (`names.timing.txt`):

```
# dampf timing: best and worst case per function in CPU cycles, from entry to return,
# calls included, interrupts and DMA not (docs/timing.md). "unbounded": a loop without
# a known bound on the way; the first such loop in the function, or the first call
# of a function without a bound, is named after it. "never": the function never returns.
        best        worst  function
        1940         2594  dampf_sdiv32
        1888         2432  dampf_udiv32
          26         1130  _test_main
         564          772  dampf_udiv16
         398          400  dampf_mul32
          26          235  _text_len
         108          108  dampf_mul16
```

The [value ranges](/glossary#value-range) dampf infers use the hint as well, for narrower code:
8-bit operations instead of 16-bit ones where that is enough. The hint costs nothing on the
SNES: dampf leaves out the check it stands for. On the PC it is an `assert`. A wrong hint stops
the program. In `wrong.c`, a copy of `names.c` with `DAMPF_RANGE(n, 0, 3)` and the names
`"ABCD"`, `"ABCDE"` and `"AB"`, the hint fails at `n` = 4 on `"ABCDE"`:

```
pc: wrong.c:11: text_len: Assertion `DAMPF_RANGE_HOLDS(n, 0, 3) && "DAMPF_RANGE(" "n" ", " "0" ", " "3" ")"' failed.
```

The check, `DAMPF_RANGE_HOLDS`, is one unsigned comparison, so that a hint on an unsigned
variable with the lower bound 0 gives no "comparison is always true" warning under `-Wextra`.

A misused hint is a compile error in dampf; gcc on the PC accepts these uses without a message:

| Use | Message |
| --- | --- |
| a global or `static` local variable, or a variable that is not an integer | `DAMPF_RANGE needs a local variable or parameter of an integer type` |
| a bound that is not a constant | `the bounds of DAMPF_RANGE must be constants` |
| `lo` above `hi` | `DAMPF_RANGE: the lowest value is above the highest` |
| a bound outside the type | `DAMPF_RANGE: 300 is outside the range of uint8_t` |
| fewer than three arguments | `macro "DAMPF_RANGE" passed 2 arguments, but takes 3` |

The last message comes from dampf's own preprocessor; gcc rejects this use as well, with a
message of its own.

### DAMPF_BUDGET

`DAMPF_BUDGET(n)` in front of a function definition gives the function a budget of `n` CPU
cycles, from its entry to its return, with the functions it calls. It may also stand in front
of `static`, as in `DAMPF_BUDGET(2000) static uint8_t text_len(const char *s)`. `n` must be a
number. After it has computed the timing, `dampf build` compares the function's worst case with
the budget:

```c [sum.c]
#include <stdint.h>
#include <dampf.h>

uint8_t t[32];
uint16_t s;

DAMPF_BUDGET(1000) void sum(void)
{
    uint8_t i;
    for (i = 0; i < 32; i++) {
        s = (uint16_t)(s + t[i]);
    }
}

void test_main(void)
{
    sum();
}
```

The loop takes up to 1137 cycles, more than the budget, and the build fails:

```
sum.c:7:42: error: 'sum' takes up to 1137 CPU cycles, over its budget of 1000 (DAMPF_BUDGET); best case 17, see the .timing.txt file
```

A worst case above 90 % of the budget builds, with a warning before the line that names the
files written. In `sum2.c`, which is `sum.c` with `DAMPF_BUDGET(1200)`:

```
sum2.c:7:42: warning: 'sum' takes up to 1137 CPU cycles, 95 % of its budget of 1200 (DAMPF_BUDGET)
```

A function without a worst case cannot keep a budget, because dampf cannot guarantee it. The
error names the loop that has no bound, or the call of a function that has no worst case
itself. In `sum4.c`, which is `sum.c` with `DAMPF_BUDGET(5000)` and the loop
`for (i = 0; t[i] != 0; i++)`, the loop runs until `t[i]` is zero:

```
sum4.c:7:42: error: 'sum' has no worst case (loop at sum4.c:10), so its budget of 5000 cycles cannot be guaranteed (DAMPF_BUDGET)
```

A `DAMPF_RANGE` on the loop's counter gives such a loop a bound. Budgets are in CPU cycles, as
in the timing report, and only the [code generator from MIR](/glossary#code-generator-from-mir)
checks them. The [naive code generator](/glossary#naive-code-generator) (chosen with
`--backend naive`, see [Command line](/reference/command-line#backend)) writes no timing report
and does not check budgets at all. dampf checks the function as compiled on its own; where it
is inlined into a caller, its code counts towards the caller. On the PC the macro is empty.

A build with a pass limit, `--opt-limit n`, lets at most n applications of the optimizer's
passes run, to find the pass that broke a program ([Command line](/reference/command-line)).
Such a build reports a budget that is not kept as a warning instead, so the program still
builds: the passes that prove a loop's bound may not have run. With `--opt-limit 0`, even
`sum.c`'s loop has no bound:

```
sum.c:7:42: warning: 'sum' has no worst case (loop at sum.c:10), so its budget of 1000 cycles cannot be guaranteed (DAMPF_BUDGET); an error without the pass limit
```

Misuse is a compile error in dampf:

| Use | Message |
| --- | --- |
| in front of a prototype instead of a definition | `DAMPF_BUDGET belongs in front of a function definition` |
| a budget that is not a number, such as a variable | `DAMPF_BUDGET needs a number of CPU cycles` |

### DAMPF_ASM

`DAMPF_ASM("file.s")` after the parameter list of a function's definition states that the
function is written by hand in 65816 assembly, in that file. The path counts from the folder of
the C file. dampf links the file in place of the code it would compile from the body; the PC
build compiles the body, since the macro is empty there. The body stays what the function has
to compute, and dampf checks the assembly against it. Parameters and results are integers or
pointers.

```c [bits.c]
#include <stdint.h>
#include <dampf.h>

uint16_t counts[8];

uint16_t count_bits(uint16_t x) DAMPF_ASM("asm/count_bits.s")
{
    uint16_t n = 0;
    uint8_t i;
    for (i = 0; i < 16; i++) {
        n = (uint16_t)(n + (x & 1));
        x = (uint16_t)(x >> 1);
    }
    return n;
}

void test_main(void)
{
    uint8_t i;
    for (i = 0; i < 8; i++) {
        counts[i] = count_bits((uint16_t)(i * 9001u));
    }
}
```

```asm [asm/count_bits.s]
        .p816
        .smart  off
        .a16
        .i16
        .importzp _count_bits__x, _count_bits__locals
        .export _count_bits

        .segment "C_count_bits"
.proc _count_bits
        lda     z:_count_bits__x
        sta     z:_count_bits__locals   ; the bits not counted yet
        ldy     #0                      ; the count
        ldx     #16
@loop:  lsr     z:_count_bits__locals
        bcc     @zero
        iny
@zero:  dex
        bne     @loop                   ; dampf: at most 16
        tya
        rtl
.endproc
```

The file follows a convention, the same for both code generators:

- **Label and segment.** The function's label is the C name with an underscore in front,
  `_count_bits`, which the file exports. Its code lies in a segment of its own whose name starts
  with `C_`, by convention `C_` and the function's name.
- **Parameters.** The [frame of the function](/glossary#frame), the memory of its parameters
  and locals, starts at `dampf_frame`, `$0020` in the [direct page](/glossary#direct-page),
  below the frame of every caller. The parameters lie there in their order, 2 bytes for 8 and
  16 bits, 4 for 32 bits and pointers (address, bank, 0). dampf exports each one's place as
  `_name__param`, here `_count_bits__x`; the file imports it with `.importzp` and reaches it as
  `z:_count_bits__x`. An 8-bit parameter is the low byte of its place, and the byte above it is
  undefined.
- **Locals.** The function's own bytes start at `_name__locals`, the first byte after the
  parameters. dampf counts them from the code: the highest byte that an access to
  `_name__locals+n` touches. An array belongs in a segment of the file, such as `BSS`.
- **Result.** The result is in A. An 8-bit result is in A's low byte, and the caller extends it
  as its type says; a 32-bit result or a pointer has its high word in X.
- **CPU state.** dampf calls the function with `JSL`, and it returns with `RTL`. On entry and on
  exit A, X and Y are [16 bit](/glossary#_8-bit-and-16-bit-mode), the [DBR](/glossary#dbr) is
  `$7E`, D is 0, the interrupt flag is as it was and nothing is left on the stack. The function
  may change A, X, Y, the flags and the [runtime](/glossary#runtime)'s scratch registers
  `dampf_regs` and `dampf_ptr`. It calls nothing.

dampf assembles the file with an assembler of its own, which reads the part of
[ca65](/glossary#ca65-and-ld65)'s syntax that dampf writes itself: instructions; labels, also
cheap local ones (`@loop`) and unnamed ones (`:`, `:+`); a label and an instruction on one line;
`.proc` and `.endproc`, `.segment`, `.import`, `.importzp`, `.export`, `.global`, `.byte`,
`.word`, `.res`, equates, `.p816`, `.smart off`, `.a8`, `.a16`, `.i8` and `.i16`. Macros,
`.include`, `.if` and every other directive are an error at their line, here a macro
`nothing` that starts in line 2:

```
asm/count_bits.s:2:1: error: unknown directive .macro (.macro nothing) (dampf's assembler reads the part of ca65 that dampf writes: instructions, labels, .proc, .segment, .import, .export, .byte, .word, .res, equates, .a8/.a16/.i8/.i16; no macros, no .include)
```

From the function's label on, dampf follows the machine code along every way it can take. Each
branch or jump backwards needs a bound, a comment on its line: `; dampf: at most N` says that
the loop starting at the branch's target runs at most N rounds each time it is entered. Loops
nest and are entered at their first instruction. Without the comment in line 18:

```
asm/count_bits.s:18:1: error: a loop without a bound: write how many rounds it runs at most next to the branch back, as '; dampf: at most 16'
```

The walk rejects what breaks the convention or what dampf cannot follow, each with its line. A
few of the messages, for `asm/count_bits.s` with one line added in front of `tya`:

| Line added | Message |
| --- | --- |
| `jsl dampf_mul16`, a call | `calls dampf_mul16: an assembly function is a leaf and calls nothing (DAMPF_ASM)` |
| `sep #$20`, which leaves A 8 bit | `returns with an 8-bit accumulator; dampf's code expects A, X and Y 16 bit (REP #$30)` |
| `phx`, which leaves 2 bytes on the stack | `returns with 2 byte(s) still on the stack` |
| `xce` | `XCE: the function runs in native mode and stays there` |

The same holds for `RTS` in place of `RTL`, a jump out of the function's segment, an indirect
jump, `BRK`, `COP`, `STP`, `WAI`, `TXS`, `TCS`, `MVN` and `MVP`, and a return with another DBR
or D, with the interrupt flag changed or in decimal mode; `PHP` and `PLP`, `PHB` and `PLB`,
`PHD` and `PLD` restore what they saved. dampf also rejects ways that meet with different
register widths or stacks, and an immediate assembled with another width than its register has
there: `.a8`, `.a16`, `.i8` and `.i16` must follow `SEP` and `REP`. In the frames, the code may
touch only its own parameters and locals. A fixed address there, `dampf_frame`, another
function's place, an access beyond a parameter's bytes and an indexed access to its own places
are errors. With `sta $40` in place of `sta z:_count_bits__locals` in line 11:

```
asm/count_bits.s:11:1: error: a fixed address in the frames ($0020-$1DFF): an assembly function uses its parameters (_count_bits__x) and _count_bits__locals+n
```

Globals, I/O registers and the scratch registers are allowed, as for C.

The same walk gives the function's best and worst case in CPU cycles. The timing report lists
it with its file, and its callers' bounds and `DAMPF_BUDGET` take it like any other function.
From `bits.timing.txt`:

```
          36          277  _count_bits   (assembly, asm/count_bits.s)
```

dampf checks the assembly against the C body in every build with the code generator from MIR.
It compiles the body as well, links it with the program and the assembly into a second ROM in
memory, and calls both in its emulator, from the state after the program's start-up, on 256
inputs (64 for a function without parameters). The inputs are the edge values of each
parameter, then random values from a fixed seed, within the range of a `DAMPF_RANGE` on the
parameter at the start of the body. The globals the body reads start as after start-up, then
all zero, all ones and random. A pointer parameter points into [bank](/glossary#bank) `$7F`,
which programs do not use and the check fills with random bytes, and the byte above an 8-bit
parameter is not 0.
After both calls the result and all of RAM from `$7E:2000` to `$7F:FFFF` must be the same, and
the assembly's cycles must lie within the bounds dampf computed from the file, so a bound
comment set too low fails as well. The check does not see writes to I/O registers, and the ROM
dampf writes does not contain the C body. It takes about 0.1 s for a small program, which is why
it runs in every build and not only in `dampf check`. With `bcs` in place of `bcc` in line 15,
the assembly counts the bits that are 0:

```
bits.c:6:10: error: the assembly does not compute what the C body of 'count_bits' computes: for x = 0 ($0000), the C body returns 0 ($0000), the assembly 16 ($0010) (asm/count_bits.s, DAMPF_ASM)
```

The [naive code generator](/glossary#naive-code-generator) links the same file and does not
run the check. On the PC the body runs, so the PC build checks nothing about the assembly.

Use `--replace name=file.s` on `dampf build` to link an assembly function without a
`DAMPF_ASM` in the source ([Command line](/reference/command-line#replace)). It follows the same
convention, works with both code generators and wins over a `DAMPF_ASM` of the same function.
Its path counts from the folder dampf runs in.

An [interrupt](/reference/c-subset#interrupt-handlers) handler cannot reach an assembly
function yet, since the convention assumes the main code's direct page and frames
([Limits](/reference/limits)). With a handler `on_irq` that calls `count_bits`:

```
bits.c:6:10: error: 'count_bits' is written in assembly (asm/count_bits.s) and reached from the IRQ handler 'on_irq': an assembly function assumes the direct page 0 and the main code's frames and may use the CPU's multiplier, so interrupt code cannot call it yet (docs/hints.md, DAMPF_ASM)
```

Misuse is a compile error in dampf; gcc on the PC accepts these uses without a message:

| Use | Message |
| --- | --- |
| on a prototype instead of a definition | `DAMPF_ASM belongs on a function's definition, after its parameter list` |
| a file that does not exist, such as `asm/countbits.s` | `the assembly of 'count_bits', asm/countbits.s, does not exist (DAMPF_ASM names it relative to the C file)` |
| on the entry function | `the entry function 'test_main' cannot be written in assembly` |
| something else than one string | `DAMPF_ASM needs one string, the assembly file: DAMPF_ASM("asm/f.s")` |
| two `DAMPF_ASM` on one function | `a function has at most one DAMPF_ASM` |

## Not built yet

The other hints of the design are not built:

- `DAMPF_LIKELY` and `DAMPF_UNLIKELY`, for the common side of a branch;
- an unroll hint, `DAMPF_PIN_X`, `DAMPF_SPLIT`, `DAMPF_DP` and `DAMPF_TABLE`, which ask for a
  transformation;
- `restrict`, for two pointers that never overlap: dampf rejects it, since measurements showed
  that such a hint would gain nothing;
- subrange types, `DAMPF_RANGED`, a type with a range of its own;
- `DAMPF_AT_MOST(n)`, for a branch that runs at most n times per call of its function, however
  often the loop around it runs; it would give a loop over all pairs of objects, with a rare hit
  inside, a usable worst case.

A bound on how often a loop runs, `DAMPF_TRIPS(n)`, is postponed: on the PC it would have to
count the rounds of each entry into the loop, and a macro cannot see when a loop is entered or
left through `break` or `return`. A `DAMPF_RANGE` on a counter in the loop does the same job;
a loop without a counter can be given one. What is planned and when is on
[Limits](/reference/limits).
