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
on [Command line](/reference/command-line). A run reports every error of every file, at most one
for each declaration or function at file scope: within one of them, the first error ends the
check. `dampf build` checks the whole program, recursion included, only once the files have no
other error. Many messages start with `not supported yet:`; the construct is outside the
subset today.

## Types

The subset has these types:

- **Fixed-width integers** from dampf's `<stdint.h>`: `int8_t`, `uint8_t`, `int16_t`,
  `uint16_t`, `int32_t`, `uint32_t`.
- **`char`**, for text: string tables, names, small buffers. It is a signed 8-bit type, as on
  x86-64, and a `char` value may only be copied, tested and compared (see
  [Plain char is text](#plain-char-is-text)).
- **`void`**, **pointers**, **arrays** (also multi-dimensional), **pointers to arrays** such
  as `const uint8_t (*rows)[16]`, **structs**, **enums**, and `typedef`s of these.
- **`const`**, **`static`** and **`extern`** as in C99. `volatile` is accepted on what a
  pointer points to, such as a hardware register: `*(volatile uint8_t *)0x2100 = 0x0F;` (see
  [Hardware access](#hardware-access)).

Plain integer types are rejected, because their size differs between compilers: `int` is 32 bit
with gcc on the PC and 16 bit with the other 65816 compilers (816-tcc,
[Calypsi](/glossary#calypsi)). Code for dampf says which width it means. The message names the
type to use instead:

```
int.c:2:1: error: 'int' is not allowed: use int32_t (plain C integer types differ between the PC and the SNES)
```

The same holds for `short`, `unsigned`, `signed`, and for `signed char` and `unsigned char`
(`use int8_t`, `use uint8_t`), also in casts such as `(unsigned)level`. The one exception is the
return type of `int main(void)`, which C requires for the PC build; on the SNES the return value
is ignored.

| Rejected | Message |
|---|---|
| `long`, `long long` | `'long' is not supported: use int32_t or uint32_t (long is 64 bit on the PC)` |
| `float`, `double` | `'float' is not supported: dampf has no floating point` |
| `_Bool` | `not supported yet: _Bool` |
| `union` | `unions are not supported` |
| bit fields | `bit fields are not supported` |
| `volatile` on a variable itself | `not supported yet: volatile variables (only pointers to volatile memory)` |
| `register`, `auto` | `not supported yet: 'register'`, `not supported yet: 'auto'` |
| `restrict` | `not supported yet: 'restrict' on a pointer itself` |
| enums with a fixed underlying type | a syntax error |
| flexible array members, `uint8_t data[];` | `field 'data' has an incomplete type` |
| anonymous struct members | `not supported yet: anonymous struct members` |
| a typedef of a function type | `not supported yet: this kind of typedef` |
| `__attribute__` in front of a function | `not supported yet: '__attribute__((noinline))'` (with the attribute written) |
| `__attribute__` after a name | `not supported yet: attributes and extensions in declarators` |

There are no 64-bit types: dampf's `<stdint.h>` has no `uint64_t`, so a declaration with it is a
syntax error. The only attributes dampf accepts are those that `DAMPF_BUDGET`, `DAMPF_ASM` and
`DAMPF_INTERRUPT` from `<dampf.h>` expand to (see [Whole program](#whole-program) and
[Interrupt handlers](#interrupt-handlers)).

### Plain char is text

Plain `char` is signed with gcc and clang on x86-64 and with MSVC, but unsigned with gcc on ARM.
A program that computes with a `char` can therefore give two results on two PCs as soon as a
byte from 128 up is in it: `c - '0'`, `c < 'a'` and `table[c]` see -56 on one and 200 on the
other. dampf keeps the x86-64 meaning, a signed 8-bit type, and accepts a `char` value only
where its signedness cannot change the result. These are accepted:

- copying it: assignment, parameters, results, array elements, string literals, and `char`
  arrays and pointers (pointer arithmetic is on the pointer, not on the `char`);
- storing any number into a `char`, such as `s[0] = (char)('0' + d)`;
- converting it to another 8-bit type: `uint8_t u = c`, `(int8_t)c`;
- testing it for zero: `if (c)`, `while (*s)`, `!c`, `&&`, `||`, the condition of `?:`;
- `==` and `!=` against another `char` or a constant from 0 to 127, and a `switch` whose case
  values are all from 0 to 127;
- `sizeof` and `&`.

Everything else is rejected: arithmetic, shifts and bit operations, `<`, `<=`, `>` and `>=`,
`==` and `!=` against anything else (`c == -1`, a `uint8_t` variable), `++`, `--` and compound
assignment on a `char`, a `char` as an index or a pointer offset, a conversion to a wider type
(`int16_t x = c`, a `char` passed to an `int16_t` parameter), and a `?:` with a `char` branch,
whose type is `int`. Digit arithmetic is among them, since `c - '0'` is the same on both PCs only
for an ASCII digit, and dampf cannot know that `c` holds one. For this file:

```c [digits.c]
#include <stdint.h>

const char *score_text = "042";
uint8_t first_digit;

void game_tick(void) {
    first_digit = score_text[1] - '0';
}
```

For it, `dampf check` reports:

```
digits.c:7:33: error: plain char is text, and whether it is signed differs between PCs, so a char may only be copied and compared with == or != against a char or a constant from 0 to 127: use uint8_t or int8_t for a number, or cast the char: (uint8_t)c
```

A cast to an 8-bit type first is always the way out: `(uint8_t)score_text[1] - '0'` is accepted.
Two forms get messages of their own:

| Rejected | Message |
|---|---|
| a cast to a wider type, `(int16_t)letter` | `a cast of a plain char to int16_t depends on whether char is signed, which differs between PCs: cast to uint8_t or int8_t first, as in (int16_t)(uint8_t)c` |
| a case value outside 0 to 127 in a switch on a `char`, `case 200:` | `case 200 on a plain char:`, the rule as in the message above, then `; only case values from 0 to 127 are allowed, or switch on (uint8_t)` |

For numbers, use `int8_t` and `uint8_t`; `signed char` and `unsigned char` stay rejected.

### Enum objects

On the PC an enum object takes 4 bytes, and every access to it would be 32-bit work on the
65816, although most enums have a handful of enumerators. dampf stores the object in fewer bytes
(see [Sizes](#sizes)), which is safe only if it never holds a value outside its enumerators. C
does not promise that, so dampf checks it: an object of an enum type takes only an enumerator of
that enum and a value of that enum type, such as another such object, a call of a function that
returns the enum, or a `?:` whose two branches are such values. For this file:

```c [state.c]
#include <stdint.h>

typedef enum { STATE_TITLE, STATE_PLAY, STATE_OVER } GameState;

GameState state;
uint8_t lives;

void game_tick(void) {
    if (lives == 0) {
        state = 2;
    }
}
```

For it, `dampf check` reports:

```
state.c:10:15: error: an object of type GameState takes only its enumerators and values of GameState, not the constant 2 (dampf keeps enum objects in fewer bytes than the PC, ADR-6FKZFDJ3)
```

Write `state = STATE_OVER;` instead. A cast `(GameState)x` is accepted from a value of
`GameState` or from a constant that equals one of its enumerators. A store through a `void *`,
as `memset` and `memcpy` make, is not checked.

| Rejected | Message |
|---|---|
| `++`, `--` on an enum object, `state++` | `'++' cannot be used on an object of type GameState: it takes only its enumerators and values of GameState (ADR-6FKZFDJ3)` |
| compound assignment, `state += 1` | the message for a store above, ending `not a value of type uint32_t (...)` |
| a cast from another value, `(GameState)lives` | `a cast to GameState needs a value of that type or a constant that is one of its enumerators, not a value of type uint8_t (enum objects hold only their enumerators, ADR-6FKZFDJ3)` |
| a pointer to an enum as a pointer to another type, `(uint8_t *)&state` | `a pointer to GameState cannot become a pointer to uint8_t: enum objects take only their enumerators (ADR-6FKZFDJ3)` |

A pointer to an enum object may become a `void *`.

### Sizes

dampf gives the types these sizes today:

| Type | Size in bytes |
|---|---|
| `char`, `int8_t`, `uint8_t` | 1 |
| `int16_t`, `uint16_t` | 2 |
| `int32_t`, `uint32_t` | 4 |
| an enum object | 1, 2 or 4 |
| a pointer | 4 |
| a struct | the sum of its fields |

An enum type is `unsigned int` when none of its enumerators is negative and `int` otherwise, as
with gcc, and an enum value is promoted to that type in every computation, so the results are
gcc's. The object itself takes the smallest width that holds all its enumerators: unsigned 8 or
16 bit when none is negative, signed 8 or 16 bit otherwise, and 32 bit when 16 bits do not reach.
`enum { S_A, S_B = 255 }` takes 1 byte, `enum { W_A, W_B = 256 }` 2. This is safe because an
enum object takes only values of its enum (see [Enum objects](#enum-objects)). A struct field or
an array element of an enum type takes the same width. A pointer takes 4 bytes: a 16-bit
address, the [bank](/glossary#bank), and a zero byte. Struct fields are laid out one after the
other without padding.

::: warning Three sizes differ from the PC
A pointer is 8 bytes, `struct { uint8_t kind; int16_t x; }` 4 bytes and an object of
`enum { STATE_TITLE, STATE_PLAY, STATE_OVER }` 4 bytes with gcc on x86-64; dampf gives 4, 3
and 1.
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
sizes.c:7:20: error: sizeof uint8_t * is 4 in dampf but 8 on the PC (dampf's pointers are 4 bytes, 8 on x86-64, its enum objects 1 or 2 bytes for most enums, 4 on x86-64, and dampf does not pad struct fields): the program would compute other values than its PC build. Only the element count sizeof a / sizeof a[0] is allowed for such types
```

The same holds for a struct gcc pads, for an enum type or object and a struct with an enum
field, and for an array of pointers or of such structs. The one
exception is a division whose quotient is the same on both sides: the element count
`sizeof names / sizeof names[0]` is accepted for `const char *names[3]`, which is 12 bytes in
dampf and 24 on the PC, and gives 3 on both. A struct without padding, such as
`struct { uint8_t kind; uint8_t flags; int16_t x; }`, has the same size on both sides, and its
`sizeof` is accepted anywhere. To copy a struct that gcc pads, use a struct assignment, since
`memcpy(&saved, &bubble, sizeof bubble)` is rejected for it.

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
`UINT32_C`. The other headers dampf provides are `<dampf.h>`, which holds the hints (see
[Whole program](#whole-program)), and `<string.h>`.

The `<string.h>` of dampf has only `memset` and `memcpy`, as static functions in the header.
Their value is a `uint8_t` and their size a `uint16_t`, since there is no plain `int` and no
object is larger than 32 KB; a call written for the standard header, such as
`memset(tiles, 0, sizeof tiles)`, works the same. The PC build uses the host's `<string.h>`.
dampf has no other library function.

| Rejected | Message |
|---|---|
| `100000L` | `the 'l' suffix is not supported: on the PC it makes a 64-bit long` |
| `3000000000` | `integer constant '3000000000' does not fit in 32 bits (on the PC it would be a 64-bit long)` |
| `'\xe9'` | `character constants above 127 are not supported (their value depends on the signedness of char)` |
| `'ab'` | `multi-character constants are not supported` |

### Warnings

dampf warns where gcc warns without any warning option, in gcc's form, and the build goes on;
`dampf check` and `dampf build` print the warnings. So far there is one, gcc's `-Woverflow`: a
constant converted to an integer type that cannot hold it, in an initializer, an assignment, an
argument or a return value. For this file:

```c [delays.c]
#include <stdint.h>

uint8_t delays[] = { 60, 420 };
```

dampf prints:

```
delays.c:3:26: warning: unsigned conversion from 'int32_t' to 'uint8_t' changes value from '420' to '164' [-Woverflow]
```

On both sides `delays[1]` holds 164. The rule is gcc's: a constant gets the warning only when
the type cannot hold it as a signed or as an unsigned number of its width. So
`uint8_t all_bits = -1` and `int8_t top = 255` (or `0x80`) are silent, while `uint8_t x = 256`,
`uint8_t x = -129` and `uint16_t z = 65536` warn. A constant is what dampf folds: literals,
enumerators, macros, and arithmetic, casts and `?:` on them; a `const` variable is not one. A
cast to the target type, `(uint8_t)420`, says the truncation is meant and silences the warning.

gcc is also silent in code that is never evaluated (the operand of `sizeof`, the right side of
`0 &&`, the branch of `?:` that a constant condition does not take) and when the constant
overflowed while it was folded (`2147483647 + 1`), and so is dampf. gcc folds a little more than
dampf, such as `n * 0 + 300` or `n ? 300 : 300`, and warns there where dampf does not. gcc's
warning about the overflow itself (`integer overflow in expression`) and its other default
warnings are not built.

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

A function without parameters is written `f(void)`. An empty list, `void f();`, is rejected with
`missing parameter type`, and old-style (K&R) parameter declarations with
`not supported yet: old-style (K&R) parameter declarations`.

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
| `goto` | `not supported yet: 'goto'` |
| labels | `not supported yet: labels and goto` |
| variable-length arrays | `an array size must be a positive integer constant` |
| `case` or `default` not directly in the switch body | `not supported yet: 'case' labels that are not directly in the switch body` |
| a `switch` body that is not a block | `not supported yet: a switch body that is not a block` |
| `typedef` inside a function | `not supported yet: typedef inside a function` |
| `extern` inside a function | `not supported yet: 'extern' inside a function` |
| a function declaration inside a function | `not supported yet: function declarations inside a function` |
| `_Static_assert` at file scope | `not supported yet: _Static_assert` |
| `_Static_assert` inside a function | a syntax error |
| inline assembly at file scope | `not supported yet: inline assembly` |
| inline assembly inside a function | `not supported yet: this statement` |

`setjmp` and `longjmp` are not available, since dampf has no header that declares them.

To write in assembly, write a whole function: `DAMPF_ASM("file.s")` after the parameter list of
a function definition links the hand-written assembly in the file instead of compiling the body.
The C body stays what the PC compiles and what dampf checks the assembly against; the rules are
on [Hints](/reference/hints).

The rejection of `goto` and labels is decided and stays. A `goto` into a loop would give the
loop a second entry, and then dampf's loop optimizations and its worst-case timing bounds would
no longer apply. If real code needs `goto`, dampf may allow a form that only jumps forward and
out of statements, such as out of two loops to a label after them, which keeps every loop with
a single entry. Unrestricted `goto` will not be built.

## Expressions

Every expression of C99 is accepted except these:

| Rejected | Message |
|---|---|
| compound literals | `not supported yet: compound literals` |
| designated initializers | `not supported yet: designated initializers` |
| the comma operator outside the first and third part of a `for` | `not supported yet: the comma operator` |
| `+=` and the like on an lvalue with side effects inside a larger expression, such as `x = (buf[i++] += 1)` | `not supported yet: compound assignment to an lvalue with side effects` |
| `_Alignof` | `not supported yet: '_Alignof'` |
| `_Generic` | `not supported yet: this expression` |

The comma operator is accepted where loops use it, as in
`for (i = 0, j = 3; i < j; i++, j--)`.

A compound assignment to an lvalue with side effects is accepted as a statement of its own, such
as `table[next()] |= bit;`: the lvalue is evaluated once. `++` and `--` before or after such an
lvalue are accepted anywhere.

Struct assignment is accepted: `bubbles[1] = bubbles[0];` copies the whole struct. A local array
or struct with an initializer is accepted too; it is copied from a constant copy in ROM.

A cast from a pointer to an integer is accepted, but its value differs from the PC's; it belongs
to [Hardware access](#hardware-access).

## Hardware access

Two constructs exist for platform code, the layer that talks to the SNES hardware, and not for
game code. The SNES platform layer in `lib/snes` is written with both of them.

**Pointers to `volatile` memory** reach the I/O registers. Every access through such a pointer
is one access of exactly the width of its type, in the order the program gives. This writes to
`INIDISP` at `$2100` in bank 0, a register of the PPU, the SNES's picture chip:

```c [screen.c]
#include <stdint.h>

#define INIDISP (*(volatile uint8_t *)0x2100)

void screen_on(uint8_t brightness) {
    INIDISP = brightness;
}
```

Only what a pointer points to can be `volatile`:

| Rejected | Message |
|---|---|
| a `volatile` variable, `volatile uint8_t frame_done;` | `not supported yet: volatile variables (only pointers to volatile memory)` |
| a `volatile` pointer, `uint8_t *volatile cursor;` | `not supported yet: 'volatile' on a pointer itself` |
| a cast to a `volatile` value, `(volatile uint8_t)level` | `not supported yet: volatile values (only pointers to volatile memory)` |

**A cast from a pointer to an integer** gives the SNES address of what the pointer points to,
`bank << 16 | address`, which a DMA channel needs as its source address. A cast to a narrower
type truncates that number: for an array `static const uint8_t tiles[64]`,
`(uint16_t)(uint32_t)tiles` is its 16-bit address within its [bank](/glossary#bank). On the PC
the number is something else, and gcc warns `cast from pointer to integer of different size`.
The two builds agree only on distances within one object:
`(uint32_t)&tiles[64] - (uint32_t)tiles` is 64 on both.

dampf does not tell platform code from game code. Both constructs, and the cast from an integer
to a pointer such as `(uint8_t *)0x7E2000`, are accepted in any file.

## Interrupt handlers

A game has one interrupt of its own, the IRQ (interrupt request): its handler `game_irq` runs
at a row of the picture. The NMI (non-maskable interrupt) is the kit's: a game that publishes its
data gets it, and it runs the game's [vertical blank](/glossary#vertical-blank). Both are
described on [The console API](/reference/console-api); this section is about what dampf does
with a handler.

Use `DAMPF_INTERRUPT(irq)` or `DAMPF_INTERRUPT(nmi)` from `<dampf.h>` in front of a function
definition to make the function the handler of that interrupt. A handler must be
`void name(void)`, there is at most one per interrupt, and C code never calls it: the hardware
calls it through an entry stub of the [runtime](/glossary#runtime). The entry function cannot be
one.

```c [raster.c]
#include <stdint.h>
#include <dampf.h>

uint8_t scroll;
uint8_t wave_step;

static uint8_t clamp(uint8_t value) {
    return value > 100 ? 100 : value;
}

DAMPF_INTERRUPT(irq) void game_irq(void) {
    scroll = clamp(scroll + wave_step);
}

void game_tick(void) {
    wave_step = clamp(wave_step + 1);
}
```

An interrupt can come in the middle of any code. dampf gives every local a fixed address and
lets functions that never run at the same time share those places, so a handler that used them
would overwrite live locals. The handler and every function it calls therefore form a context of
their own, with their own frames, [direct page](/glossary#direct-page) and scratch registers;
the main code (the entry function and what it calls) is another context. Code of an interrupt
context does not use the CPU's hardware multiplier either, whose factors cannot be read back and
restored. It multiplies in software, about five times slower: a 16-bit product takes 22 to 532
CPU cycles instead of 108.

A function that two contexts call, such as `clamp` above, is compiled once for each. Each copy
has its own parameters and locals in its context's frames, and the functions of a context call
that context's copies. The original belongs to the first context that reaches it, in the order
main code, NMI, IRQ, and keeps its name. A copy is named after its context: `clamp@irq` in
messages, `__irq_clamp` in the [timing report](/glossary#timing-report) and in Mesen. Each copy
takes ROM, but no cycles of its own. A program whose contexts share no function compiles as if
there were no copies.

Globals, `static` locals and string literals are not copied: a `static` local of `clamp` is one
variable for all its copies. A global that several contexts write is the program's to guard,
since an interrupt between a read and a write of the main code loses the interrupt's write. dampf
keeps every store in the order of the program and removes none, and never reuses or hoists a
`volatile` load; the kit's published data, which a handler reads, relies on that.

| Rejected | Message |
|---|---|
| a handler that is not `void name(void)` | `an interrupt handler must be 'void game_irq(void)' (DAMPF_INTERRUPT)` |
| a second handler of the same interrupt | `'other_irq' is a second IRQ handler; the first is 'game_irq' at`, then the place of the first |
| a call of a handler | `'game_irq' is the IRQ handler (DAMPF_INTERRUPT): the hardware calls it, C code cannot` |
| the entry function as a handler | `the entry function 'game_tick' cannot be an interrupt handler (DAMPF_INTERRUPT)` |
| another interrupt, `DAMPF_INTERRUPT(vblank)` | `DAMPF_INTERRUPT needs 'nmi' or 'irq'` |
| `DAMPF_INTERRUPT` in front of anything but a function definition | `DAMPF_INTERRUPT belongs in front of a function definition` |
| a function written in assembly (`DAMPF_ASM`) that a handler reaches | `'mix8' is written in assembly (asm/mix8.s) and reached from the IRQ handler 'game_irq': an assembly function assumes the direct page 0 and the main code's frames and may use the CPU's multiplier, so interrupt code cannot call it yet (docs/hints.md, DAMPF_ASM)` |

The rules that need the whole program, from the second handler to the entry function, are
checked by `dampf build`, and by `dampf check` when its files define the entry function. The one
about assembly is checked only by `dampf build`.

On the PC, `DAMPF_INTERRUPT` expands to nothing, and the handler is an ordinary function that
nothing calls unless the program does. `DAMPF_HAS_INTERRUPTS`, also from `<dampf.h>`, is 1 with
dampf and 0 on the PC. Code that leaves a job to a handler on the SNES and does it itself on the
PC asks it, and dampf drops the branch not taken. The
[code generator from MIR](/glossary#code-generator-from-mir) then leaves out a function only that
branch called. Here the NMI handler calls `advance_clock`, and on the PC the tick does:

```c
void game_tick(void) {
    if (!DAMPF_HAS_INTERRUPTS) {
        advance_clock();
    }
}
```

The branch cannot call the handler itself: a call of a handler is rejected even in a branch that
is never taken, so it calls what the handler calls.

## Preprocessor

dampf preprocesses every file with a preprocessor of its own, so using dampf needs no C
compiler. It has a subset of C99's directives and macros: what the kit's library, its examples
and its tests use. Its include directory holds only `<stdint.h>`, `<dampf.h>` and `<string.h>`.
Host headers and host macros never reach the code: `__GNUC__` and `__x86_64__` are not defined,
and `#include <stdio.h>` fails. A failure of the preprocessor is an ordinary error at its place.
For a file `stdio.c` whose second line is `#include <stdio.h>`:

```
stdio.c:2:10: error: stdio.h: No such file or directory
```

The preprocessor has:

- **`#include "..."` and `#include <...>`.** A name in quotes is looked for in the directory of
  the including file first. Then both kinds are looked for in dampf's include directory and in
  the directories given with `-I`, in that order.
- **`#define` and `#undef`**, with object-like, function-like and variadic macros
  (`__VA_ARGS__`), `#` and `##`. Macros expand exactly as C99 says (section 6.10.3).
- **`#if`, `#elif`, `#ifdef`, `#ifndef`, `#else`, `#endif` and `defined`.** The arithmetic of
  `#if` is that of `intmax_t` and `uintmax_t`, 64 bit.
- **`#error`**, which stops with an error whose message is the directive and its text, such as
  `#error LEVELS must be defined`.
- **`#pragma`**, which is passed on, and `#pragma once`.
- **Predefined macros**: `__FILE__`, `__LINE__`, `__STDC__`, `__STDC_VERSION__` (`199901L`),
  `__STDC_HOSTED__` and `__DAMPF__`, and the macros given with `-D`.

Everything else is rejected with a message that names it: two features of C99 that no code of
the kit uses (`#line` and an include of a macro), later standards' additions and gcc's
extensions.

| Rejected | Message |
|---|---|
| `__VA_OPT__` | `__VA_OPT__ is not supported by dampf's preprocessor` |
| a named variadic parameter, `#define TRACE(args...)` | `named variadic macros are not supported by dampf's preprocessor` |
| an include of a macro, `#include HEADER` | `#include of a macro is not supported by dampf's preprocessor` |
| `#line`, `#include_next`, `#warning`, `#ident`, `#assert` and gcc's other directives | `#line is not supported by dampf's preprocessor` (with the directive written) |
| an unknown directive, `#region` | `invalid preprocessing directive #region` |
| `_Pragma`, `__has_include`, `__has_include_next`, `__COUNTER__`, `__DATE__`, `__TIME__`, `__TIMESTAMP__`, `__BASE_FILE__`, `__INCLUDE_LEVEL__` | `__COUNTER__ is not supported by dampf's preprocessor` (with the name written) |

Trigraphs, digraphs and universal character names are not handled. The PC build preprocesses
with the PC's compiler; on everything dampf's preprocessor accepts, both give the same tokens,
and the tests check this against clang on every C file dampf compiles in the kit.

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

Because dampf sees every call, the code generator from MIR, dampf's default, leaves out the
functions the program never reaches: those that the entry function, the interrupt handlers and
the functions written in assembly do not reach through calls. A library's functions that a game
does not call cost it no ROM, and a test or a debugger that calls a function by its label finds
it only if the program calls it. A global goes with the dropped functions only when they used it
and nothing kept does; a global that no function names stays, so a debugger can still read it by
name. A function that is called keeps its body even where every call is inlined, so it keeps its
line in the timing report.

The header `<dampf.h>` defines three [hints](/glossary#hint), which tell dampf what it cannot
infer: `DAMPF_RANGE(v, lo, hi)`, a statement of its own for a local variable or parameter,
`DAMPF_BUDGET(n)`, which stands only in front of a function definition, and
`DAMPF_ASM("file.s")`, which stands after the parameter list of a function definition. What they
do, and the messages for their misuse, are on [Hints](/reference/hints). `<dampf.h>` also has
`DAMPF_INTERRUPT` and `DAMPF_HAS_INTERRUPTS` (see [Interrupt handlers](#interrupt-handlers)).
There are no intrinsics: the hardware is reached through the console API
([The console API](/reference/console-api)).

## Open decisions

One question about the subset is not settled. Until it is, dampf does what was recommended:

- **Pointer width.** Some pointers must be 24 bit, because data in ROM spans more than one bank.
  dampf makes every pointer 4 bytes today (address, bank, zero byte). Narrower pointers are an
  optimization of the code generator from MIR, such as
  [pointer compression](/glossary#pointer-compression); they do not change `sizeof`.

The other questions are decided: plain `char` is text (see
[Plain char is text](#plain-char-is-text)), enum objects take only values of their enum (see
[Enum objects](#enum-objects)), a `sizeof` that differs from the PC is rejected (see
[Sizes](#sizes)), and `goto` stays rejected (see [Statements](#statements)).
