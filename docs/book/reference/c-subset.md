<!-- GENERATED from doc-specs/reference/c-subset.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# The C subset

dampf does not compile all of C. It compiles a [subset](/glossary#subset) that it defines
itself and checks, so that every assumption the optimizer makes holds for every program it
accepts. Everything outside the subset is rejected with a message that names the construct and
the place in the source, as `file:line:column: error: ...`. Nothing outside it is compiled
silently. Inside the subset, a program means exactly what it means to gcc on x86-64, so the same
source builds and runs on the PC for testing.

This page lists what is accepted, what is rejected with which message, and what the accepted
constructs mean. For an introduction with examples, read
[Writing C for dampf](/guide/writing-c) first.

Most messages below come from `dampf check`, which runs the front end on each file on its own
and then checks the files that pass together. Checks that need the
[whole program](/glossary#whole-program), such as recursion and functions that are called but
never defined, run in that second step, and again at `dampf build`. Both commands are described
on [Command line](/reference/command-line). Many messages start with `not supported yet:`; the
construct is outside the subset today.

## Types

The subset has these types:

- **Fixed-width integers** from dampf's `<stdint.h>`: `int8_t`, `uint8_t`, `int16_t`,
  `uint16_t`, `int32_t`, `uint32_t`.
- **`char`**, meant for text: string tables, names, small buffers. It is a signed 8-bit type,
  as on x86-64 (see [Open decisions](#open-decisions)).
- **`void`**, **pointers**, **arrays** (also multi-dimensional), **structs**, **enums**, and
  `typedef`s of these.
- **`const`** and **`static`** as in C99. `volatile` is accepted on what a pointer points to,
  such as a hardware register: `*(volatile uint8_t *)0x2100 = 0x0F;`.

Plain integer types are rejected, because their size differs between compilers: `int` is 32 bit
with gcc on the PC and 16 bit with the other 65816 compilers (816-tcc,
[Calypsi](/glossary#calypsi)). Code for dampf says which width it means. The message names the
type to use instead:

```
int.c:2:1: error: 'int' is not allowed: use int32_t (plain C integer types differ between the PC and the SNES)
```

The same holds for `short`, `unsigned`, `signed`, and for `signed char` and `unsigned char`
(`use int8_t`, `use uint8_t`). The one exception is the return type of `int main(void)`, which C
requires for the PC build; on the SNES the return value is ignored.

| Rejected | Message |
|---|---|
| `long` | `'long' is not supported: use int32_t or uint32_t (long is 64 bit on the PC)` |
| `float`, `double` | `'float' is not supported: dampf has no floating point` |
| `_Bool` | `not supported yet: _Bool` |
| `union` | `unions are not supported` |
| bit fields | `bit fields are not supported` |
| `volatile` on a variable itself | `not supported yet: volatile variables (only pointers to volatile memory)` |
| `register`, `auto` | `not supported yet: 'register'`, `not supported yet: 'auto'` |
| enums with a fixed underlying type | a syntax error |

### Sizes

dampf gives the types these sizes today:

| Type | Size in bytes |
|---|---|
| `char`, `int8_t`, `uint8_t` | 1 |
| `int16_t`, `uint16_t` | 2 |
| `int32_t`, `uint32_t` | 4 |
| an enum | 4 |
| a pointer | 4 |
| a struct | the sum of its fields |

An enum type is `unsigned int` when none of its enumerators is negative and `int` otherwise, as
with gcc, so an enum object takes 4 bytes. A pointer takes 4 bytes: a 16-bit address, the
[bank](/glossary#bank), and a zero byte. Struct fields are laid out one after the other without
padding.

::: warning Two sizes differ from the PC
A pointer is 8 bytes and `struct { uint8_t kind; int16_t x; }` is 4 bytes with gcc on x86-64;
dampf gives 4 and 3.
:::

Because the result must match gcc on x86-64 bit for bit, a `sizeof` whose value differs from
the PC's is rejected, with a message that gives both values. For this file:

```c [sizes.c]
#include <stdint.h>

uint8_t *cursor;
uint32_t cursor_bytes;

void game_tick(void) {
    cursor_bytes = sizeof cursor;
}
```

For it, `dampf check` reports:

```
sizes.c:7:20: error: sizeof uint8_t * is 4 in dampf but 8 on the PC (dampf's pointers are 4 bytes, 8 on x86-64, and dampf does not pad struct fields): the program would compute other values than its PC build. Only the element count sizeof a / sizeof a[0] is allowed for such types
```

The same holds for a struct gcc pads, and for an array of pointers or of such structs. The one
exception is a division whose quotient is the same on both sides: the element count
`sizeof names / sizeof names[0]` is accepted for `const char *names[3]`, which is 12 bytes in
dampf and 24 on the PC, and gives 3 on both. A struct without padding, such as
`struct { uint8_t kind; uint8_t flags; int16_t x; }`, has the same size on both sides, and its
`sizeof` is accepted anywhere.

A typedef name in parentheses works as in C: `sizeof(uint8_t)`, or `sizeof` of a struct type
made with `typedef`. Where a variable has the same name as the typedef, the variable wins.

## Semantics

The arithmetic is exactly that of gcc on x86-64:

- Integer constants and the integer promotions use a 32-bit `int`; a constant has the C type it
  has there. `0xFFFFFFFF` is an `unsigned int`, a decimal constant above 2147483647 would be a
  64-bit `long` and is rejected, and so is the `l` suffix.
- Overflow of unsigned types wraps; conversion to a narrower type truncates.
- Signed division rounds towards zero.
- `>>` of a negative value is an arithmetic shift.
- Character constants have type `int`. Those above 127 are rejected, because their value would
  depend on whether `char` is signed.

dampf gets the same results on the 65816 and uses
[8-bit or 16-bit](/glossary#_8-bit-and-16-bit-mode) operations only where it can prove that the
result is the same.

dampf ships its own `<stdint.h>` with the same underlying types as the PC: `int8_t` is
`signed char`, `int16_t` is `short`, `int32_t` is `int`, and the unsigned types alike. The
header also defines the limits (`INT8_MIN` to `UINT32_MAX`) and the macros `INT8_C` to
`UINT32_C`. The only other header dampf provides is `<dampf.h>`, which holds the hints (see
[Whole program](#whole-program)).

| Rejected | Message |
|---|---|
| `100000L` | `the 'l' suffix is not supported: on the PC it makes a 64-bit long` |
| `3000000000` | `integer constant '3000000000' does not fit in 32 bits (on the PC it would be a 64-bit long)` |
| `'\xe9'` | `character constants above 127 are not supported (their value depends on the signedness of char)` |
| `'ab'` | `multi-character constants are not supported` |

## Functions

**No recursion.** No function may call itself, directly or through other functions. dampf gives
every local a fixed address in a static frame, which is only correct without recursion. The
message names the cycle. For this file:

```c [bubbles.c]
#include <stdint.h>

static void pop_chain(uint8_t index);

static void update_bubble(uint8_t index) {
    pop_chain(index);
}

static void pop_chain(uint8_t index) {
    if (index > 0) {
        update_bubble(index - 1);
    }
}

void game_tick(void) {
    update_bubble(3);
}
```

`dampf build` stops with:

```
bubbles.c:6:5: error: recursion is not supported: pop_chain → update_bubble → pop_chain
```

**No variadic functions.** A parameter list with `...` is rejected:
`variadic functions are not supported`.

**Structs only by pointer.** A struct parameter is rejected with
`structs passed by value are not supported: pass a pointer`, a struct return type with
`structs returned by value are not supported`. An array parameter is a pointer, as in C.

**Parameters.** There is no fixed limit on their number. Every parameter of a function
definition needs a name, and a call must pass exactly as many arguments as the function takes.

**`inline`** is accepted and ignored: whether a function is inlined is dampf's decision.

Old-style (K&R) parameter declarations are rejected.

## Function pointers

Function pointers are not accepted, neither as variables nor in constant tables:

```
not supported yet: this kind of declarator (function pointers, bit fields)
```

Using a function's name as a value gives `not supported yet: function pointers`, and calling the
result of an expression gives `not supported yet: calls through expressions`.

The reason is the call graph: dampf must know every call of the whole program, both to prove
that there is no recursion and to give each function's frame a fixed address. A free function
pointer could call anything. Function pointers in constant tables known at compile time would
keep the call graph complete and are allowed by the design of the subset, but they are not built
until a program needs them.

## Memory

There is no `malloc` and no heap of any kind. All objects are static, and the total memory a
program uses is known at compile time. Locals and parameters live in a frame at a fixed address,
so taking the address of a local (`&x`) works.

A static object's initializer must be a constant. A static pointer can be initialized only with
a constant address (`&global`, an array name, a string literal, plus a constant offset) or 0;
`uint8_t *p = (uint8_t *)0x2100;` at file scope is rejected with
`the initializer of a static pointer must be a constant address`.
[Runtime and memory](/reference/runtime-and-memory) describes where objects are placed.

## Statements

Every statement of C99 is accepted except these:

| Rejected | Message |
|---|---|
| `goto` and labels | `not supported yet: labels and goto` |
| variable-length arrays | `an array size must be a positive integer constant` |
| `case` or `default` not directly in the switch body | `not supported yet: 'case' labels that are not directly in the switch body` |
| a `switch` body that is not a block | `not supported yet: a switch body that is not a block` |
| `typedef` inside a function | `not supported yet: typedef inside a function` |
| `extern` inside a function | `not supported yet: 'extern' inside a function` |
| a function declaration inside a function | `not supported yet: function declarations inside a function` |
| `_Static_assert` | `not supported yet: _Static_assert` |
| inline assembly | `not supported yet: inline assembly` |

`setjmp` and `longjmp` are not available, since dampf has no header that declares them.

## Expressions

Every expression of C99 is accepted except these:

| Rejected | Message |
|---|---|
| compound literals | `not supported yet: compound literals` |
| designated initializers | `not supported yet: designated initializers` |
| the comma operator outside the first and third part of a `for` | `not supported yet: the comma operator` |
| casts from pointers to integers | `not supported yet: casts from pointers to integers` |
| `+=`, `++` and the like on an lvalue with side effects, such as `buf[i++] += 1` | `not supported yet: compound assignment to an lvalue with side effects` |

The comma operator is accepted where loops use it, as in
`for (i = 0, j = 3; i < j; i++, j--)`.

Struct assignment is accepted: `bubbles[1] = bubbles[0];` copies the whole struct. A local array
or struct with an initializer is accepted too; it is copied from a constant copy in ROM.

## Preprocessor

dampf preprocesses every file with `gcc -E -nostdinc -undef -D__DAMPF__=1` and its own include
directory, which holds only `<stdint.h>` and `<dampf.h>`. Anything `gcc -E` accepts is
allowed. Host headers and host macros never reach the code: `__GNUC__` and `__x86_64__` are not
defined, and `#include <stdio.h>` fails. A failure of the preprocessor is an ordinary error at
the place gcc names, with gcc's message. For a file `stdio.c` whose second line is
`#include <stdio.h>`:

```
stdio.c:2:10: error: stdio.h: No such file or directory
```

`__DAMPF__` is defined as 1, so code can tell the dampf build from the PC build:

```c
#ifdef __DAMPF__
    /* SNES only */
#else
    /* PC only */
#endif
```

Use `-I` and `-D` to add include directories and macros
([Command line](/reference/command-line)).

## Whole program

dampf compiles all `.c` files of a program as one [whole program](/glossary#whole-program);
there is no separate compilation. `static` names stay local to their file: two files can each
have a `static uint8_t phase;`, and they are two objects. A static local is local to its block
in the same way.

Because dampf sees every file, it reports what a linker would on the PC:

```
missing.c:3:18: error: function 'missing' is called but never defined
```

A variable that is declared `extern` but defined nowhere gives
`'name' is declared extern but never defined`. A variable without `static` that is defined in two
files is an error, as it is for gcc's linker since gcc 10; within one file it may be repeated:

```
b.c:2:9: error: redefinition of 'phase' (first defined at a.c:2:9); declare it extern in all files but one
```

These checks, like the check for recursion, run when `dampf check` checks the files together,
and at `dampf build`.

The header `<dampf.h>` defines two [hints](/glossary#hint), which tell dampf what it cannot
infer: `DAMPF_RANGE(v, lo, hi)`, a statement of its own for a local variable or parameter, and
`DAMPF_BUDGET(n)`, which stands only in front of a function definition. Intrinsics for the SNES
hardware are not built yet; see [Limits](/reference/limits).

## Open decisions

Four questions about the subset are not settled. Until they are, dampf does what was
recommended for each:

- **Plain `char` for text.** The recommendation is to give `char` the x86-64 meaning, a signed
  8-bit type, and to reject arithmetic on `char` beyond comparisons and copying, so its
  signedness can never change a result. dampf treats `char` as signed 8 bit today but does not
  enforce the restriction: arithmetic on `char` is accepted.
- **Size of enum objects.** On the PC an enum object takes 4 bytes. The recommendation is to
  allow only values of an enum type to be stored in an object of that type, so that the object
  could be stored in the smallest width that holds its enumerators. dampf keeps 4 bytes today
  and does not check what is stored.
- **Pointer width.** Some pointers must be 24 bit, because data in ROM spans more than one bank.
  dampf makes every pointer 4 bytes today (address, bank, zero byte). Narrower pointers are an
  optimization of the [code generator from MIR](/glossary#code-generator-from-mir), such as
  [pointer compression](/glossary#pointer-compression); they do not change `sizeof`.
- **`goto`.** The recommendation is to forbid it until a program needs it, since unrestricted
  `goto` would need a more general timing analysis. dampf rejects it today.
