<!-- GENERATED from doc-specs/reference/hints.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Hints

A hint tells dampf something about your program that it cannot work out from the C code, such
as the largest value a counter reaches or how many cycles a function may take. You write it as
a macro from dampf's header `<dampf.h>`, so the same source still builds on the PC with gcc.
Two hints are built: `DAMPF_RANGE` and `DAMPF_BUDGET`.

## How hints work

Include `<dampf.h>` in the files that use hints. dampf finds it on its own, because it comes
with dampf next to its `<stdint.h>`. When dampf compiles, it defines `__DAMPF__`, and each macro
expands to a built-in that dampf's front end reads. The distribution also has the header as
`include/dampf.h` next to the `bin/` folder, for the [PC build](/glossary#pc-build) of your game.
Give gcc that folder with `-idirafter`:

```sh
gcc -std=c99 -Wall -Wextra -idirafter path/to/dampf/include -c names.c
```

`-idirafter` searches the folder after gcc's own system folders, so `<dampf.h>` comes from dampf
while `<stdint.h>` still comes from the PC.

On the PC a hint that states a fact becomes an `assert`. A wrong hint would make dampf produce
wrong code without any message, so the PC build checks every fact your tests and replays reach;
defining `NDEBUG` switches the checks off, as for any `assert`. A hint that only asks for a check
at build time, such as `DAMPF_BUDGET`, expands to nothing on the PC.

## The hints

### DAMPF_RANGE

`DAMPF_RANGE(v, lo, hi)` states that the local variable or parameter `v` lies between `lo` and
`hi` at this point, both included. It is a statement of its own. Both bounds must be constants
that fit `v`'s type.

dampf uses the range from this point on, wherever the code that follows can only be reached
through it. Its main use is a loop that dampf cannot bound by itself, such as a loop that runs
until it finds a zero byte:

```c
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

Without the hint the loop's length depends on the data, and the timing report of that build
(the same program as `nohint.c`, `nohint.timing.txt`) has no worst case for it:

```
          24    unbounded  _test_main   (loop at nohint.c:10)
          55    unbounded  _text_len   (loop at nohint.c:10)
```

With the hint, `n` is at most 10 inside the loop and at most 11 after the step, so the loop
runs a bounded number of times (`names.timing.txt`):

```
          24         1977  _test_main
          55          605  _text_len
```

The [value ranges](/glossary#value-range) dampf infers use the hint as well, for narrower code.
The hint costs nothing on the SNES: dampf leaves out the check it stands for. On the PC it is
an `assert`. A wrong hint stops the program, here `DAMPF_RANGE(n, 0, 3)` in a copy `wrong.c`,
on names of four and five letters:

```
pc: wrong.c:11: text_len: Assertion `(unsigned long long)(n) - (unsigned long long)(0) <= (unsigned long long)(3) - (unsigned long long)(0) && "DAMPF_RANGE(" "n" ", " "0" ", " "3" ")"' failed.
```

The check is written as one unsigned comparison so that a hint on an unsigned variable with
the lower bound 0 gives no "comparison is always true" warning under `-Wextra`.

A misused hint is a compile error:

| Use | Message |
| --- | --- |
| a global variable | `DAMPF_RANGE needs a local variable or parameter of an integer type` |
| a bound that is not a constant | `the bounds of DAMPF_RANGE must be constants` |
| `lo` above `hi` | `DAMPF_RANGE: the lowest value is above the highest` |
| a bound outside the type | `DAMPF_RANGE: 300 is outside the range of uint8_t` |

### DAMPF_BUDGET

`DAMPF_BUDGET(n)` in front of a function definition gives the function a budget of `n` CPU
cycles, from its entry to its return, with the functions it calls. `n` must be a number. After
it has computed the timing, `dampf build` compares the function's worst case with the budget:

```c
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

The loop takes up to 1201 cycles, more than the budget, and the build fails:

```
sum.c:7:42: error: 'sum' takes up to 1201 CPU cycles, over its budget of 1000 (DAMPF_BUDGET); best case 17, see the .timing.txt file
```

A worst case above 90 % of the budget builds, with a warning before the line that names the
files written. With `DAMPF_BUDGET(1300)`:

```
sum2.c:7:42: warning: 'sum' takes up to 1201 CPU cycles, 92 % of its budget of 1300 (DAMPF_BUDGET)
```

A function without a worst case cannot keep a budget, because dampf cannot guarantee it. The
error names the loop or call that has no bound, here a loop that runs until `t[i]` is zero:

```
sum4.c:7:42: error: 'sum' has no worst case (loop at sum4.c:10), so its budget of 5000 cycles cannot be guaranteed (DAMPF_BUDGET)
```

A `DAMPF_RANGE` on the loop's counter gives such a loop a bound. Budgets are in CPU cycles, as
in the timing report, and only the [code generator from MIR](/glossary#code-generator-from-mir)
checks them; with `--backend naive` there is no timing. dampf checks the function as compiled on
its own; where it is inlined into a caller, its code counts towards the caller. On the PC the
macro is empty. In front of a prototype instead of a definition it is an error:
`DAMPF_BUDGET belongs in front of a function definition`.

## Not built yet

The other hints of the design are not built:

- `DAMPF_LIKELY` and `DAMPF_UNLIKELY`, for the common side of a branch;
- `DAMPF_PIN_X`, `DAMPF_SPLIT`, `DAMPF_DP` and `DAMPF_TABLE`, which ask for a transformation;
- a hint that two pointers never overlap;
- subrange types, `DAMPF_RANGED`, a type with a range of its own.

`DAMPF_TRIPS(n)`, a bound on how often a loop runs, is postponed: on the PC it would have to
count the rounds of each entry into the loop, and a macro cannot see when a loop is entered or
left through `break` or `return`. A `DAMPF_RANGE` on a counter in the loop does the same job;
a loop without a counter can be given one. What is planned and when is on
[Limits](/reference/limits).
