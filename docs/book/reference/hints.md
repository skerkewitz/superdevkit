<!-- GENERATED from doc-specs/reference/hints.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Hints

A [hint](/glossary#hint) tells dampf something about your program that it cannot work out from
the C code, such as the largest value a counter reaches or how many cycles a function may take.
You write it as a macro from dampf's header `<dampf.h>`, so the same source still builds on the
PC with gcc. Two hints are built: `DAMPF_RANGE` and `DAMPF_BUDGET`.

## How hints work

Include `<dampf.h>` in the files that use hints. dampf finds it on its own, because it comes
with dampf in its own include folder. When dampf compiles, it defines `__DAMPF__`, and each
macro expands to a built-in, a name only dampf's [front end](/glossary#front-end) knows and
reads. The distribution also has the header in its `include` folder, for the
[PC build](/glossary#pc-build) of your game. Give gcc that folder with `-idirafter`:

```sh
gcc -std=c99 -Wall -Wextra -idirafter path/to/dampf/include -c names.c
```

`-idirafter` searches the folder after gcc's own system folders, so `<dampf.h>` comes from dampf
while `<stdint.h>` still comes from the PC.

dampf trusts a hint and never checks it on the SNES: it builds its code on the assumption that
the hint holds. A wrong hint would make dampf produce wrong code without any message. On the PC
a hint that states a fact therefore becomes an `assert`, so the PC build checks every fact your
tests reach; defining `NDEBUG` switches the checks off, as for any `assert`. A hint that only
asks for a check at build time, such as `DAMPF_BUDGET`, expands to nothing on the PC.

The kit's desktop build, `tools/build_desktop.sh`, compiles a game without the host's headers
(gcc's `-nostdinc -ffreestanding`) and gives gcc dampf's include folder with `-isystem`, so
there `<stdint.h>` is dampf's as well. Such a build has no `<assert.h>`. There a wrong hint calls
`__builtin_trap()` instead, which stops the program without a message; `NDEBUG` switches this
check off as well.

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
          24    unbounded  _test_main   (loop at nohint.c:10)
          31    unbounded  _text_len   (loop at nohint.c:10)
        1940         2594  dampf_sdiv32
        1888         2432  dampf_udiv32
        1168         1968  dampf_mul32
         564          772  dampf_udiv16
         287          367  dampf_mul16
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
        1168         1968  dampf_mul32
          21         1134  _test_main
         564          772  dampf_udiv16
         287          367  dampf_mul16
          31          317  _text_len
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
| fewer than three arguments | `macro ‘DAMPF_RANGE’ requires 3 arguments, but only 2 given` |

The last message comes from the preprocessor, which gcc and dampf share.

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

Misuse is a compile error in dampf:

| Use | Message |
| --- | --- |
| in front of a prototype instead of a definition | `DAMPF_BUDGET belongs in front of a function definition` |
| a budget that is not a number, such as a variable | `DAMPF_BUDGET needs a number of CPU cycles` |

## Not built yet

The other hints of the design are not built:

- `DAMPF_LIKELY` and `DAMPF_UNLIKELY`, for the common side of a branch;
- `DAMPF_PIN_X`, `DAMPF_SPLIT`, `DAMPF_DP` and `DAMPF_TABLE`, which ask for a transformation;
- a hint that two pointers never overlap;
- subrange types, `DAMPF_RANGED`, a type with a range of its own.
- `DAMPF_AT_MOST(n)`, for a branch that runs at most n times per call of its function, however
  often the loop around it runs; it would give pair loops a usable worst case.

A bound on how often a loop runs, `DAMPF_TRIPS(n)`, is postponed: on the PC it would have to
count the rounds of each entry into the loop, and a macro cannot see when a loop is entered or
left through `break` or `return`. A `DAMPF_RANGE` on a counter in the loop does the same job;
a loop without a counter can be given one. What is planned and when is on
[Limits](/reference/limits).
