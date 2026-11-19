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
2026-11-19.

## Slow

These forms cost more than the alternative that computes the same result. Each pair shows the
two functions of the rule's test file.

**`slow-u8-counter`: in a loop over an array of bytes, count with `uint16_t`, not `uint8_t`.**
An 8-bit `i++` switches the accumulator to [8 bits and back](/glossary#_8-bit-and-16-bit-mode),
and the counter is masked again for the compare. Summing a `uint8_t data[200]` takes 8,430
cycles with the `uint8_t` counter and 5,025 with the `uint16_t` one.

```c [compiler/src/test/resources/style/slow-u8-counter.c]
uint16_t sum_u8_counter(void)
{
    uint16_t s = 0;
    uint8_t i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + data[i]);
    }
    return s;
}

uint16_t sum_u16_counter(void)
{
    uint16_t s = 0;
    uint16_t i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + data[i]);
    }
    return s;
}
```

**`slow-u16-counter-words`: in a loop over an array of `uint16_t`, count with `uint8_t`.** Here
it is the other way round, as long as the count fits in a byte: with an 8-bit counter dampf
drops the counter and steps the index register by 2, while it keeps a 16-bit counter in memory
beside it. Filling 64 `uint16_t` elements takes 1,945 cycles with the `uint16_t` counter and
1,233 with the `uint8_t` one.

```c [compiler/src/test/resources/style/slow-u16-counter-words.c]
void fill_u8_counter(void)
{
    uint8_t i;
    for (i = 0; i < 64; i++) {
        a[i] = (uint16_t)(1 | (3 << 10));
    }
}

void fill_u16_counter(void)
{
    uint16_t i;
    for (i = 0; i < 64; i++) {
        b[i] = (uint16_t)(1 | (3 << 10));
    }
}
```

These two rules are a gap in dampf, not in the 65816: both forms should cost the same. In other
loops, such as one over a table of two dimensions or one that uses the counter in 8-bit
arithmetic, the difference is small and goes either way. Keep the form that reads better there,
and in a hot loop measure both with the timing report.

**`slow-word-array`: store values in the narrowest type they fit.** An element of `uint16_t`
costs a doubling of the index on every access. Summing 200 `uint16_t` elements takes 7,033
cycles, summing 200 `uint8_t` elements 5,025.

```c [compiler/src/test/resources/style/slow-word-array.c]
uint16_t sum_bytes(void)
{
    uint16_t s = 0;
    uint16_t i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + bytes[i]);
    }
    return s;
}

uint16_t sum_words(void)
{
    uint16_t s = 0;
    uint16_t i;
    for (i = 0; i < 200; i++) {
        s = (uint16_t)(s + words[i]);
    }
    return s;
}
```

**`slow-pointer-walk`: index an array instead of walking it with a pointer.** Moving 32
structs of two `int16_t` fields through `o->x` and `o++` takes 1,943 cycles, with `a[i].x`
1,881.

```c [compiler/src/test/resources/style/slow-pointer-walk.c]
void move_by_index(void)
{
    uint16_t i;
    for (i = 0; i < 32; i++) {
        a[i].x = (int16_t)(a[i].x + 2);
        a[i].y = (int16_t)(a[i].y - 1);
    }
}

void move_by_pointer(void)
{
    Obj *o = b;
    uint16_t i;
    for (i = 0; i < 32; i++) {
        o->x = (int16_t)(o->x + 2);
        o->y = (int16_t)(o->y - 1);
        o++;
    }
}
```

**`slow-multiply-variable`: multiply by a constant where the factor is one.** A product of two
variables calls a routine of the [runtime](/glossary#runtime); a product with a constant
becomes shifts and adds. `va * vb` takes 411 cycles, `va * 40` takes 31.

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
`va / vb` takes 816 cycles, `va / 8` takes 17.

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
values. A `DAMPF_RANGE` that proves `v` is not negative does not remove it today; the cast to
`uint16_t` does. The signed form takes 265 cycles, the unsigned one 17.

```c [compiler/src/test/resources/style/slow-signed-divide.c]
int16_t divide_signed(void)
{
    int16_t v = sv;
    DAMPF_RANGE(v, 0, 1000);
    return (int16_t)(v / 8);
}

int16_t divide_unsigned(void)
{
    int16_t v = sv;
    DAMPF_RANGE(v, 0, 1000);
    return (int16_t)((uint16_t)v / 8);
}
```

**`slow-hand-shifts`: write a multiplication by a constant as a multiplication.** dampf finds
the shifts itself, and better than by hand: `(va << 5) + (va << 3)` takes 45 cycles,
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

**`slow-shift-above-8`: split a shift by a constant above 8.** A shift by 8 is a byte move,
while a shift by a constant above 8 is a loop of single shifts. Where a row of a table is 512
bytes, write `(uint16_t)(v << 8) << 1` instead of `v << 9`: `v << 9` takes 130 cycles, the split
form 19.

```c [compiler/src/test/resources/style/slow-shift-above-8.c]
uint16_t shift_by_9(void)
{
    return (uint16_t)(v << 9);
}

uint16_t shift_by_8_then_1(void)
{
    return (uint16_t)((uint16_t)(v << 8) << 1);
}
```

**`slow-32-bit`: keep 32-bit values out of hot code.** Every operation on a 32-bit value goes
through memory and costs more than twice a 16-bit one. A value that is only combined bit by
bit, such as a hash, can be kept as two `uint16_t` halves: the 32-bit XOR takes 84 cycles, the
two 16-bit ones 36.

```c [compiler/src/test/resources/style/slow-32-bit.c]
void xor_32(void)
{
    hash32 ^= key32;
}

void xor_16_halves(void)
{
    hash_lo ^= key_lo;
    hash_hi ^= key_hi;
}
```

**`slow-compare-sum`: compare with `(int16_t)(y + 32)` where the sum fits.** C adds an
`int16_t` and a constant in `int`, which has 32 bits, so a comparison with the sum is a 32-bit
comparison: the sum may not fit 16 bits. Where it does, a cast to `int16_t` says so, and the
comparison becomes a 16-bit one: 162 cycles without the casts, 96 with them. Two `uint8_t`
added stay 16-bit, because dampf sees that they cannot overflow.

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

**`fast-array-per-field`: one array per field.** In hot code, separate arrays such as `px[i]`
and `py[i]` are a little cheaper than an array of structs (`objs[i].x`), because the element
is reached without the struct's size in the index. Moving 32 objects takes 1,721 cycles with
two arrays and 1,881 with an array of structs. The gain is small: keep the struct elsewhere.

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

**`fast-range-index`: a `DAMPF_RANGE` on the index of a large array.** An array of more than
256 elements reached with a 16-bit index goes through a 24-bit pointer, unless dampf knows the
index stays inside the array. A `DAMPF_RANGE` on the index tells it, and the store becomes a
direct indexed access. With four `uint8_t` arrays of 1,000 elements, storing one pair of
values takes 103 cycles without the hint and 56 with it.

```c [compiler/src/test/resources/style/fast-range-index.c]
void store_without_range(uint8_t f, uint8_t t)
{
    uint16_t n = count_a;
    from_a[n] = f;
    to_a[n] = t;
    count_a = (uint16_t)(n + 1);
}

void store_with_range(uint8_t f, uint8_t t)
{
    uint16_t n = count_b;
    DAMPF_RANGE(n, 0, N - 1);
    from_b[n] = f;
    to_b[n] = t;
    count_b = (uint16_t)(n + 1);
}
```

A hint states a fact: a wrong range makes wrong code on the SNES and stops the program in the
[PC build](/glossary#pc-build). [Hints](/reference/hints) has the details.

A multiplication or an unsigned division by a constant is cheap as well, as
`slow-multiply-variable` and `slow-divide-variable` show: write it as such, in the clear form.

## No difference

Write whichever reads better; these pairs cost the same, within 5 %:

- **`same-declaration-place`**: a local declared at the top of the function or where it is
  first used, 5,025 and 5,025 cycles.
- **`same-increment-form`**: `i++` or `++i`, 5,025 and 5,025 cycles.
- **`same-index-form`**: `a[i]` or `*(a + i)`, 5,025 and 5,025 cycles.
- **`same-loop-form`**: a `for` loop or the same loop as `while`, 5,025 and 5,025 cycles.
- **`same-named-temporary`**: a named temporary or the expression written inline, 45 and 45
  cycles. A value used once stays in the accumulator either way.
- **`same-multiply-width`**: a product of two bytes or of two 16-bit values, 417 and 411
  cycles. Both call the runtime, because the SNES's 8 x 8 hardware multiplier is not used, so
  narrowing the factors buys nothing.

## How the rules are checked

Each rule is one file in `compiler/src/test/resources/style/`, named after the rule. The file holds a
pair of functions that compute the same thing in two ways, and a `test_main` that calls both.
Its first comment names the rule and states the claim, the relation of the two functions'
worst cases, as `slower`, `faster` or `same`:

```c [compiler/src/test/resources/style/slow-hand-shifts.c]
/* rule: slow-hand-shifts
 * claim: times_40_by_shifts slower times_40
 * Writing a multiplication by a constant as shifts by hand costs more than writing * 40: dampf
 * makes the shifts itself. */
```

`StyleGuideTest` builds every file with dampf and checks four things. The claim must hold on
the worst cases of the timing report, where `same` means within 5 %. The program must give the
same results on the PC, compiled with gcc, and in dampf's emulator of the SNES, so a fast form
that changes what the code computes cannot get in. The cycles measured in the emulator must lie
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

To add a rule, write its file in `compiler/src/test/resources/style/` with the `rule:` and `claim:`
lines and a `test_main` that calls both functions, add the rule to its part of `lib/C_STYLE.md`
as an entry that starts with its name in bold, `` **`slow-u8-counter`** ``, and run the
command above to add its row to the table.
