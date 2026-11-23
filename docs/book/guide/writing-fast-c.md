<!-- GENERATED from doc-specs/guide/writing-fast-c.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Writing fast C

Write the clear form first. A speed rule costs readability, so apply one only in code that is
hot: a function that the [timing report](/glossary#timing-report) (`<rom>.timing.txt`, written
by `dampf build`) or `dampf profile` shows to be expensive, or one with a `DAMPF_BUDGET`
[hint](/glossary#hint). Elsewhere the rule buys nothing.

Every speed rule on this page is a measurement, and a test checks it on every build. The
numbers are what today's dampf does; when dampf changes, the numbers and sometimes the rules
change with it. This page is made from `lib/C_STYLE.md`, the style guide that ships with the
kit, which is the place for the complete rules. What dampf rejects and what it allows is a
different matter, covered in [Writing C for dampf](/guide/writing-c) and
[The C subset](/reference/c-subset).

All figures below are the worst case of a function in CPU cycles, from its entry to its
return with calls included, as the timing report gives it for the
[code generator from MIR](/glossary#code-generator-from-mir). They were measured on
2026-11-23.

## Slow

These forms cost more than the alternative that computes the same result. Each pair shows the
two functions of the rule's test file.

**`slow-index-list`: walk all slots with a counter and skip the free ones.** Where a loop
visits only the slots of an array that are in use, walk every slot and skip the free ones
(`if (!used(&a[j])) continue;`) rather than a list of the used slots' indexes
(`&a[list[k]]`), unless most slots are free. dampf steps a counter's offset into the array by
the size of an element, while an index loaded from a list is multiplied by that size on every
element. The test file has 12 `Ball` structs of 10 bytes, 2 of them free, and `used(b)` tests
`b->state == 2`; the [`DAMPF_RANGE`](/reference/hints) on `n` tells dampf that the list holds
at most 12 indexes, so the worst case of the loops over it is that of 12 slots. Testing every
pair of used balls takes 17,505 cycles with the list and 15,493 with the counter. The same was
measured in [Blubber Blasen](/glossary#blubber-blasen). If you need a list, let it hold byte
offsets, which cost what the counter costs (`same-list-of-offsets`, under
[No difference](#no-difference)).

```c [compiler/src/test/resources/style/slow-index-list.c]
void pairs_by_counter(void)
{
    uint8_t i, j;
    for (i = 0; i < BALLS; i++) {
        Ball *a = &balls[i];
        if (!used(a)) {
            continue;
        }
        for (j = (uint8_t)(i + 1); j < BALLS; j++) {
            Ball *b = &balls[j];
            if (!used(b)) {
                continue;
            }
            if ((uint16_t)(a->x - b->x + 0x1000u) < 0x2000u && (uint16_t)(a->y - b->y + 0x1000u) < 0x2000u) {
                near_pairs++;
            }
        }
    }
}

void pairs_by_list(void)
{
    uint8_t list[BALLS], n = 0, li, lj, i;
    for (i = 0; i < BALLS; i++) {
        if (used(&balls[i])) {
            list[n++] = i;
        }
    }
    DAMPF_RANGE(n, 0, BALLS);
    for (li = 0; li < n; li++) {
        Ball *a = &balls[list[li]];
        for (lj = (uint8_t)(li + 1); lj < n; lj++) {
            Ball *b = &balls[list[lj]];
            if ((uint16_t)(a->x - b->x + 0x1000u) < 0x2000u && (uint16_t)(a->y - b->y + 0x1000u) < 0x2000u) {
                near_pairs++;
            }
        }
    }
}
```

**`slow-multiply-variable`: multiply by a constant where the factor is one.** A product of two
variables calls a routine of the [runtime](/glossary#runtime); a product with a constant
becomes shifts and adds. `va * vb` takes 140 cycles, `va * 40` takes 31.

```c [compiler/src/test/resources/style/slow-multiply-variable.c]
uint16_t multiply_variables(void)
{
    return (uint16_t)(va * vb);
}

uint16_t multiply_constant(void)
{
    return (uint16_t)(va * 40);
}
```

**`slow-divide-variable`: divide by a constant power of two where you can.** A division by a
variable calls the runtime too, while an unsigned division by a power of two is a shift.
`va / vb` takes 804 cycles, `va / 8` takes 17.

```c [compiler/src/test/resources/style/slow-divide-variable.c]
uint16_t divide_variables(void)
{
    return (uint16_t)(va / vb);
}

uint16_t divide_constant(void)
{
    return (uint16_t)(va / 8);
}
```

**`slow-signed-divide`: divide a value that cannot be negative as unsigned.** C rounds a
signed division towards zero, so `v / 8` on an `int16_t` computes a correction for negative
values, unless dampf knows that `v` is not negative from a comparison before it or a
`DAMPF_RANGE`. Where it does not know, the cast to `uint16_t` removes the correction. The signed
form takes 101 cycles, the unsigned one 17.

```c [compiler/src/test/resources/style/slow-signed-divide.c]
int16_t divide_signed(void)
{
    int16_t v = sv;
    return (int16_t)(v / 8);
}

int16_t divide_unsigned(void)
{
    int16_t v = sv;
    return (int16_t)((uint16_t)v / 8);
}
```

**`slow-hand-shifts`: write a multiplication by a constant as a multiplication.** dampf finds
the shifts itself, and better than by hand: `(va << 5) + (va << 3)` takes 41 cycles,
`va * 40` takes 31.

```c [compiler/src/test/resources/style/slow-hand-shifts.c]
uint16_t times_40(void)
{
    return (uint16_t)(va * 40);
}

uint16_t times_40_by_shifts(void)
{
    return (uint16_t)((va << 5) + (va << 3));
}
```

**`slow-32-bit`: keep 32-bit arithmetic out of hot code.** An addition of two `uint32_t` goes
through memory and costs more than twice a 16-bit one: 86 cycles against 31. Bit operations are
the exception (`same-32-bit-bits`, under [No difference](#no-difference)).

```c [compiler/src/test/resources/style/slow-32-bit.c]
void add_32(void)
{
    sum32 += step32;
}

void add_16(void)
{
    sum16 = (uint16_t)(sum16 + step16);
}
```

**`slow-compare-sum`: compare with `(int16_t)(y + 32)` where the sum fits.** C adds an
`int16_t` and a constant in `int`, which has 32 bits, and the sum may not fit 16 bits. dampf
then tests whether it fits before a 16-bit comparison. Where it does fit, a cast to `int16_t`
says so and saves that test: 104 cycles without the casts, 86 with them. A known range of `y`
saves it too. Two `uint8_t` added stay 16-bit, because dampf sees that they cannot overflow.

```c [compiler/src/test/resources/style/slow-compare-sum.c]
void compare_sum(void)
{
    if (ship_y > enemy_y + 32) {
        steer = 4;
    } else if (ship_y < enemy_y - 32) {
        steer = -4;
    }
}

void compare_sum_narrowed(void)
{
    if (ship_y > (int16_t)(enemy_y + 32)) {
        steer = 4;
    } else if (ship_y < (int16_t)(enemy_y - 32)) {
        steer = -4;
    }
}
```

## Fast

These forms are cheaper than the one you would write first. Use them in hot code.

**`fast-multiply-bytes`: multiply two values that fit a byte as bytes.** A product of two
`uint8_t` (`ua`, `ub`) goes straight to the SNES's 8 x 8 multiplier, without a call, and takes
62 cycles. Two `uint16_t` (`va`, `vb`) call the runtime and take 140, unless dampf can prove
that both are below 256.

```c [compiler/src/test/resources/style/fast-multiply-bytes.c]
uint16_t multiply_bytes(void)
{
    return (uint16_t)(ua * ub);
}

uint16_t multiply_words(void)
{
    return (uint16_t)(va * vb);
}
```

**`fast-array-per-field`: one array per field.** In hot code, separate arrays such as `px[i]`
and `py[i]` are a little cheaper than an array of structs (`objs[i].x`), because the element
is reached without the struct's size in the index. Moving 32 objects takes 1,361 cycles with
two arrays and 1,521 with an array of structs. The gain is small: keep the struct elsewhere.

```c [compiler/src/test/resources/style/fast-array-per-field.c]
void move_array_of_structs(void)
{
    uint16_t i;
    for (i = 0; i < 32; i++) {
        objs[i].x = (int16_t)(objs[i].x + 2);
        objs[i].y = (int16_t)(objs[i].y - 1);
    }
}

void move_array_per_field(void)
{
    uint16_t i;
    for (i = 0; i < 32; i++) {
        px[i] = (int16_t)(px[i] + 2);
        py[i] = (int16_t)(py[i] - 1);
    }
}
```

A multiplication or an unsigned division by a constant is cheap as well, as
`slow-multiply-variable` and `slow-divide-variable` show: write it as such, in the clear form.

## No difference

Write whichever reads better; these pairs cost the same, within 5 %:

- **`same-declaration-place`**: a local declared at the top of the function or where it is
  first used, 5,025 and 5,025 cycles.
- **`same-increment-form`**: `i++` or `++i`, 5,025 and 5,025 cycles.
- **`same-index-form`**: `a[i]` or `*(a + i)`, 5,025 and 5,025 cycles.
- **`same-loop-form`**: a `for` loop or the same loop as `while`, 5,025 and 5,025 cycles.
- **`same-pointer-walk`**: an array walked with a pointer beside the loop's counter
  (`o->x`, `o++`) or indexed (`a[i].x`), 1,521 and 1,521 cycles. The pointer's offset and the
  counter become one variable in the X register.
- **`same-list-of-offsets`**: a list of the used slots that holds their byte offsets
  (`(Ball *)((uint8_t *)balls + list[k])`) or a counter over all slots, 15,385 and 15,493
  cycles, as long as the offsets fit the list's type.
- **`same-named-temporary`**: a named temporary or the expression written inline, 45 and 45
  cycles. A value used once stays in the accumulator either way.
- **`same-32-bit-bits`**: a value only combined bit by bit (a hash, a mask tested with `!= 0` or
  `==`) as one `uint32_t` or as two `uint16_t` halves, 36 and 36 cycles. dampf splits it into
  the halves itself.

## How the rules are checked

Each rule is one file in the repository's `compiler/src/test/resources/style/`, named after
the rule. The file holds a pair of functions that compute the same thing in two ways, and a
`test_main` that calls both. Its first comment names the rule and states the claim, the
relation of the two functions' worst cases, as `slower`, `faster` or `same`:

```c [compiler/src/test/resources/style/slow-hand-shifts.c]
/* rule: slow-hand-shifts
 * claim: times_40_by_shifts slower times_40
 * Writing a multiplication by a constant as shifts by hand costs more than writing * 40: dampf
 * makes the shifts itself. */
```

`StyleGuideTest` builds every file with dampf and checks four things. The claim must hold on
the worst cases of the timing report, where `same` means within 5 %. The program must give the
same results on the PC as in dampf's emulator of the SNES, so a fast form that changes what
the code computes cannot get in. The cycles measured in the emulator must lie
within the bounds of the timing report. And the guide must name every rule and contain exactly
the table of measurements the test computes.

When a change to dampf makes a claim false, for example because an optimization makes the slow
form as fast as the other, the build fails and names the rule. The rule then changes or goes in
the same commit as the change that broke it. When only the numbers move, the test fails too,
until the table is written again with

```sh
STYLE_GUIDE=update ./gradlew test --tests dampf.exec.StyleGuideTest
```

which also puts the new date in it.

To add a rule, write its file in `compiler/src/test/resources/style/` with the `rule:` and
`claim:` lines and a `test_main` that calls both functions, add the rule to its part of
`lib/C_STYLE.md` as an entry that starts with its name in bold, `` **`slow-hand-shifts`** ``,
and run the command above to add its row to the table.
