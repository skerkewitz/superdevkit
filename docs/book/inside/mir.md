<!-- GENERATED from doc-specs/inside/mir.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# MIR

[MIR](/glossary#mir) is the form in which dampf optimizes a program. Every
[pass](/glossary#pass) reads it and writes it, the
[code generator from MIR](/glossary#code-generator-from-mir) turns it into 65816 code, and
`dampf mir` prints it as text.

## What MIR is

dampf's design has three intermediate representations between the checked syntax tree and the
assembly: HIR keeps the structure of C (loops, structs, array indexing), MIR describes the data
flow, and LIR the 65816 with its registers and processor state. MIR is the middle level,
comparable to LLVM IR; despite the name it is neither Rust's MIR nor LLVM's machine IR. Of the
three, only MIR is built: the program goes straight from the [AST](/glossary#ast) to MIR, and
the code generator from MIR writes assembly from it. HIR and LIR are on the
[roadmap](/inside/roadmap).

A function in MIR is a graph of basic blocks: straight runs of instructions that end in exactly
one jump, branch, switch or return. The instructions are in [SSA](/glossary#ssa) form (static
single assignment): every value is assigned exactly once, and an instruction that produces a
value *is* that value, so other instructions name it directly. Where control flow joins and a C
variable can have come from more than one place, a φ (phi) at the top of the block picks the
value of the edge that was taken. A pass that wants to know where a value comes from looks at
one instruction instead of searching for every assignment.

MIR keeps what decides the result bit for bit, because that result must match gcc on the PC.
Every value has an explicit width: `i8`, `i16` and `i32` for C's 8-, 16- and 32-bit integers,
`i1` for the result of a comparison, and one `ptr` type for pointers. Signedness is not part of
the type but of the operation: a signed C type picks `sdiv`, `srem`, `ashr`, `slt` and `sext`,
an unsigned one `udiv`, `urem`, `lshr`, `ult` and `zext`. C's conversions are already explicit
in the AST and become `zext`, `sext` and `trunc` instructions. Every instruction also carries
the file and line of the C source it came from.

MIR drops what only C's syntax needs:

- **Structured statements.** `if`, the loops, `switch`, `break`, `continue`, `&&`, `||` and
  `?:` become blocks and jumps. Code no path reaches, such as statements after a `return`, is
  removed.
- **Variables.** A scalar local or parameter whose address is never taken becomes SSA
  values. An array, a struct or a local whose address is taken gets a slot in the
  [frame](/glossary#frame) of its function, in memory, instead.
- **Structs and arrays as types.** Memory is bytes: a field or element is a `ptradd` of its
  byte offset to the address of the object, followed by a `load` or `store` of the value's
  width. Assigning a struct is a `copy` of its size in bytes.

A variable read before any assignment, and the result of a function whose end is reached
without a `return`, are undefined values, as in C.

## Reading the text format

Take this function, saved as `sum.c`:

```c [sum.c]
#include <stdint.h>

uint8_t scores[8];
uint16_t total;

void sum_scores(void)
{
    uint16_t sum = 0;
    for (uint8_t i = 0; i < 8; i++) {
        sum += scores[i];
    }
    total = sum;
}
```

Run `dampf mir` to print its MIR as the lowering from the AST produces it, before any pass
(`dampf` is the launcher `build/install/dampf/bin/dampf`;
[Command line](/reference/command-line) has the options):

```sh
dampf mir sum.c
```

```
global @_scores : 8
global @_total : 2

func @_sum_scores() -> void {
b0_entry:
    jump b1_for                                 ; sum.c:9
b1_for:                    ; from b0_entry, b3_step
    %0 = phi i8 [b0_entry, 0], [b3_step, %13]   ; sum.c:9
    %1 = phi i16 [b0_entry, 0], [b3_step, %10]  ; sum.c:8
    %2 = zext i8 %0 to i32                      ; sum.c:9
    %3 = cmp slt i32 %2, 8                      ; sum.c:9
    br %3, b2_body, b4_endfor                   ; sum.c:9
b2_body:                    ; from b1_for
    %4 = zext i16 %1 to i32                     ; sum.c:10
    %5 = zext i8 %0 to i32                      ; sum.c:10
    %6 = ptradd @_scores, i32 %5                ; sum.c:10
    %7 = load i8 %6                             ; sum.c:10
    %8 = zext i8 %7 to i32                      ; sum.c:10
    %9 = add i32 %4, %8                         ; sum.c:10
    %10 = trunc i32 %9 to i16                   ; sum.c:10
    jump b3_step                                ; sum.c:9
b3_step:                    ; from b2_body
    %11 = zext i8 %0 to i32                     ; sum.c:9
    %12 = add i32 %11, 1                        ; sum.c:9
    %13 = trunc i32 %12 to i8                   ; sum.c:9
    jump b1_for                                 ; sum.c:9
b4_endfor:                    ; from b1_for
    store i16 @_total, %1                       ; sum.c:12
    ret                                         ; sum.c:13
}
```

The text starts with the global objects: a `global` line names the symbol and its size in bytes.
Constant data in ROM has `const` after the size, and an initialized object lists its initial
values by byte offset, such as `= [0: i16 1234, 2: i16 -1]`; a pointer in an initializer is
written as `ptr @symbol+offset`. Symbols are the assembler labels of the C names, hence the
underscore in `@_scores`.

Each function follows as `func @name(parameters) -> result`. Parameters are written with their
type, `i16 %a`. Frame slots come next, one `slot $name : size` line each; this function needs
none, because `sum` and `i` are scalars whose address is never taken. A slot is named after the
function and the variable, so an array `pair` in `main` is `$_main__pair`, and an operand
`$_main__pair` is the address of that slot, as `@_scores` is the address of the global.

Each block starts with its label, a number and the kind of statement it came from (`b1_for` is
the head of the `for`, `b3_step` its `i++`), and a comment lists the blocks that jump to it.
Instructions that produce a value are numbered `%0`, `%1` and so on; the others, `store`,
`jump`, `br` and `ret`, have no name. Constants appear as bare numbers, typed by the instruction
they are in. The comment at the end of a line is the C source position.

The two φs in `b1_for` are the C variables `i` and `sum`: on entry they are 0, after a round of
the loop they are `%13` and `%10`, which `b3_step` and `b2_body` compute. `%13` is used before
the line that defines it; that is allowed for a φ operand, because it is read at the end of
`b3_step`, the edge it comes from.

Most of the instructions spell out C's integer promotions. `i < 8` compares `int` values in C,
so `i` is zero-extended to `i32` and compared signed (`slt`), although `i` is a `uint8_t`.
`sum += scores[i]` adds two `int`s and truncates the sum back to `i16`. The index becomes a
byte offset for `ptradd`; for an array of 16-bit elements, a `mul` by 2 would come before it.

With `-O`, `dampf mir` prints the MIR after the passes:

```sh
dampf mir -O sum.c
```

```
global @_scores : 8
global @_total : 2

func @_sum_scores() -> void {
b0_entry:
    jump b1_for                                 ; sum.c:9
b1_for:                    ; from b0_entry, b2_body
    %0 = phi i8 [b0_entry, 0], [b2_body, %8]    ; sum.c:9
    %1 = phi i16 [b0_entry, 0], [b2_body, %7]   ; sum.c:8
    %2 = cmp ult i8 %0, 8                       ; sum.c:9
    br %2, b2_body, b4_endfor                   ; sum.c:9
b2_body:                    ; from b1_for
    %3 = zext i8 %0 to i16                      ; sum.c:10
    %4 = ptradd @_scores, i16 %3                ; sum.c:10
    %5 = load i8 %4                             ; sum.c:10
    %6 = zext i8 %5 to i16                      ; sum.c:10
    %7 = add i16 %1, %6                         ; sum.c:10
    %8 = add i8 %0, 1                           ; sum.c:9
    jump b1_for                                 ; sum.c:9
b4_endfor:                    ; from b1_for
    store i16 @_total, %1                       ; sum.c:12
    ret                                         ; sum.c:13
}
```

No value is wider than it needs to be any more: the comparison is an 8-bit `ult`, the sum is
added in 16 bits, and the offset into `scores` is an `i16`. The result is the same for every
input, which is what allows the change. The step block is gone and `i++` has moved to the end
of `b2_body`; blocks keep their names, so the numbers have a gap where `b3_step` was. The values
are numbered afresh each time the text is printed. Which pass does what is on
[Optimizations](/inside/optimizations).

## Instructions

`Instr.java` defines thirteen kinds of instruction. In the text, each starts with the name in
the first column:

| Group | Instruction | What it does |
| --- | --- | --- |
| Arithmetic | `add`, `sub`, `mul`, `udiv`, `sdiv`, `urem`, `srem` | both operands and the result have one type |
| Bits | `and`, `or`, `xor`, `shl`, `lshr`, `ashr` | as above; `lshr` shifts in zeros, `ashr` the sign |
| Comparison | `cmp` with `eq`, `ne`, `ult`, `ule`, `ugt`, `uge`, `slt`, `sle`, `sgt`, `sge` | compares two integers or two pointers; the result is `i1` |
| Conversion | `zext`, `sext`, `trunc` | widens with zeros or the sign, or cuts to a narrower type |
| | `ptrtoint`, `inttoptr` | converts between `ptr` and `i32` |
| Address | `ptradd` | a pointer plus a signed byte offset, `i32` or `i16` |
| Memory | `load`, `store` | reads or writes a value at an address; `volatile` for I/O registers |
| | `copy` | copies a number of bytes from one address to another |
| Call | `call` | calls a function with the arguments as operands |
| Join | `phi` | picks a value by the block control came from |
| Terminator | `jump` | goes to one block |
| | `br` | goes to one of two blocks, by an `i1` |
| | `switch` | goes to the block of the matching case, else to a default block |
| | `ret` | returns a value, or nothing |

There is no negation and no `not`: the lowering writes `-x` as `sub 0, x` and `~x` as
`xor x, -1`. A comparison used as a value in C, `a < b` as an `int`, is a `cmp` followed by a
`zext` to `i32`. Pointer subtraction converts both pointers with `ptrtoint`, subtracts and
divides by the element size with `sdiv`.

A `ptradd` result is only used while it points into the same object or just past its end. Since
no object is larger than 32 KB or crosses a [bank](/glossary#bank), the code generator adds
only the low 16 bits of the offset, which is why an `i16` offset is enough.

## Verifier and interpreter

The verifier (`dampf.mir.Verifier`) checks the rules of MIR. The lowering ends with it, and the
tests run it after every pass as well, so a broken pass is caught where it breaks something,
not three steps later as a wrong result. It checks:

- **Blocks.** φs come first in their block, and every block ends with exactly one
  terminator; every block is reachable from the entry, and the list of predecessors of each
  block agrees with the terminators that jump to it.
- **φs.** A φ has one operand for each predecessor.
- **Types.** The operands of each instruction have the types it needs (both operands of an `add`
  the result's type, a `br` an `i1`, a `ptradd` a `ptr` and an `i32` or `i16`); a `ret` matches
  the function's result type, and a `call` matches its callee in result, number and types of
  arguments.
- **SSA.** Every value is defined before it is used, in a block that dominates the use. For a φ
  operand the definition must dominate the end of the predecessor it comes from.

When a rule is broken, the verifier throws an error that starts with `MIR is broken:` and lists
up to 20 violations, each naming the function, the block and the instruction.

The interpreter (`dampf.mir.Interpreter`) runs a whole program in MIR, with C's meaning of every
instruction. It is a definition of what the program does that does not depend on gcc or on any
65816 code: the tests run every execution test and every random program in it and compare the
globals with the [PC build](/glossary#pc-build), once straight after the lowering and once after
the passes. A wrong lowering shows up there before any 65816 code exists for it. Its memory is a
flat array: the globals lie one after another from `$10000`, and every call gets fresh frame
slots from `$400000`. Stores to addresses below `$10000` go to a listener, as I/O does on the
SNES; the [runtime](/glossary#runtime) and the game's benchmark mark events there, such as the
write to `$21FB` when the entry function returns. Loads from there read 0. A division by zero
stops the interpreter with an error, and so does a run longer than its step limit, so a program
that loops forever fails instead of hanging the tests.

The interpreter also checks the range analysis: every value it computes must lie in the
[value range](/glossary#value-range) dampf inferred for it. The execution tests, the random
programs and the replay run with that check. The game's benchmark runs in the interpreter too,
and gives the checksums of the PC build on all 3,610 [ticks](/glossary#tick).

The text format parses back. `MirTextTest` prints the MIR of every execution test and of the
whole of [Blubber Blasen](/glossary#blubber-blasen), parses the text, verifies the result and
prints it again; the second text must equal the first. Pass tests can therefore start from MIR
text, as `NarrowTest`, `GvnTest` and `InlineTest` do. The test also runs hand-written MIR, a
loop that sums 1 to 100, in the interpreter, and checks that the verifier rejects a use before
its definition.

[Testing](/inside/testing) shows where these checks sit among dampf's other tests.

## Why it works this way

**MIR between two other levels.** High-level optimizations such as inlining or splitting arrays
of structs need the structure of the program; low-level ones need registers, processor state
and cycle costs. One representation does both badly, so dampf's design has three levels, as
Swift and Rust go through several before machine code. It stops at three: each level costs a
lowering step, a verifier, a text format and tests, and a fourth is only added if an
optimization fits nowhere cleanly. (`docs/architecture.md`, *Why more than one* and
*Deliberately not more than three*)

**Text, verifier and interpreter for every level.** The alternative is to test only the
finished ROM, where a broken pass shows up as a wrong number with no hint which step caused it.
A text that parses back lets a pass test be IR text in, pass, expected IR text out, as in LLVM;
the verifier after every pass stops a broken pass at once; and an interpreter per level narrows
a bug down to the lowering between two levels. The cost is that every level needs all three
before it is useful. (`docs/testing.md`, *What the IR levels bring for testing*)
