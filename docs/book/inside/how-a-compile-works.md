<!-- GENERATED from doc-specs/inside/how-a-compile-works.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->

# How a compile works

This page follows one `dampf build` from the C files to the ROM, phase by phase, and names the
class that does each step, so that you know where to look when something goes wrong. You do
not need any of it to use dampf.

## The big picture

The class `Compiler` holds the whole pipeline: C files go in, a SNES ROM with debug
information comes out. Two code generators share the front end: the code generator from
[MIR](/glossary#mir), which `dampf build` uses unless you choose another, and the naive code
generator, which works straight from the [AST](/glossary#ast).

```mermaid
flowchart TD
    C[C files] --> PP[preprocess: gcc -E]
    PP --> P[parse: ANTLR grammar]
    P --> AST[typed AST of the whole program]
    AST --> CHK[checks: call graph, no recursion]
    CHK --> LOW[lowering to MIR]
    LOW --> PASS[MIR passes]
    PASS --> MCG[code generator from MIR]
    CHK --> NCG[naive code generator]
    MCG --> AS[ca65 and ld65]
    NCG --> AS
    AS --> ROM[ROM and debug file]
```

- **Preprocess**: gcc expands macros and includes, one C file at a time.
- **Parse**: an ANTLR parser turns the preprocessed text into a parse tree.
- **AST**: the parse trees of all files become one typed AST; subset violations are found here.
- **Checks**: the call graph of the whole program is built, and recursion is rejected.
- **Lowering to MIR**: each function becomes a graph of basic blocks in SSA form.
- **Passes**: the optimizer rewrites the MIR until nothing changes.
- **Code generation**: MIR, or the AST in the naive path, becomes ca65 assembly.
- **ca65 and ld65**: the assembly is assembled, packed into ROM banks and linked.

## Preprocess and parse

The class `Preprocessor` runs `gcc -E -std=c99 -nostdinc -undef -D__DAMPF__=1` on each source
file. The option `-nostdinc` keeps the host's headers out, and dampf's own include directory
provides `<stdint.h>`; `-undef` drops the host's predefined macros such as `__x86_64__`, and
`__DAMPF__` lets a header tell the dampf build apart. The `-I` directories and `-D` macros of
the command line are passed on. If gcc fails, on a missing header for example, dampf stops with
gcc's first error message at gcc's position, like any other error of the compile.

The parser comes from ANTLR 4, a tool that generates a parser in Java from a grammar. dampf
uses the C grammar of the grammars-v4 collection unchanged; only its support classes were
adapted, and the lexer no longer runs a preprocessor of its own, since dampf has already run
`gcc -E`. The grammar parses C23 with GNU extensions, far more than
[the subset](/glossary#subset).

Source positions survive the preprocessor: gcc keeps line markers such as
`# 142 "bubble.c"` in its output, and `Parser` maps every position in the preprocessed text
back to the original file and line before it reports a syntax error. The same positions travel
on through the AST and MIR into the `.dbg line` directives of the assembly, which is how
[Mesen](/glossary#mesen) shows the C source while you step.

## The typed AST

The class `AstBuilder` takes the parse tree of each file in turn and builds one AST for the
[whole program](/glossary#whole-program). In the AST every expression has its type, and C's
conversions, the integer promotions among them, are made explicit, so the meaning of every
operation is fixed before any code is generated.

A `sizeof` becomes a constant here, and it has to have the value gcc gives on x86-64, because
the result must match the PC build bit for bit. dampf's pointers are 4 bytes against 8 on the
PC, and dampf does not pad struct fields, so for such types dampf rejects `sizeof` with a
message. The one use it allows is the element count `sizeof a / sizeof a[0]`, whose quotient is
the same on both.

The class `AstBuilder` also reads the hints of `<dampf.h>`, macros that tell dampf what it
cannot infer. A `DAMPF_RANGE` statement gives the lowest and highest value of a local variable
or parameter of an integer type, with constant bounds, and becomes a statement of its own in the
AST. `DAMPF_BUDGET` in front of a function definition gives the most CPU cycles the function
may take.

The subset is enforced here, not in the grammar. A construct outside the subset parses, and
then gets a message that says what is not supported, with the position of the C source. Once
the AST is complete, `Compiler` looks up the [entry function](/glossary#entry-function) and
stops if it is missing. `examples/m1/calls.c` names its entry function `test_main`, so
without `--entry` it fails:

```sh
dampf build -o build/calls.sfc examples/m1/calls.c
```

```
examples/m1/calls.c:1:1: error: entry function 'main' is not defined
```

## Checks on the whole program

Because dampf sees every file at once, it builds the call graph of the whole program
(`CallGraph`) and rejects recursion, direct or through other functions. Every function then
has a fixed set of callers, which the code generators rely on when they place the frames of
functions in memory.

The command `dampf check` runs the same check on the files that pass the front end, after it
has checked each file on its own; the command is on [Command line](/reference/command-line).

## MIR and its passes

dampf's design has three intermediate levels below the AST: HIR for the structure of C, MIR
for data flow and LIR for the 65816; today only MIR exists, and HIR and LIR are planned (see
the [Roadmap](/inside/roadmap)).

The class `Lower` translates the AST into MIR directly: each function becomes a graph of basic
blocks in [SSA](/glossary#ssa) form, with explicit widths and the signedness in the operations.
A `DAMPF_RANGE` becomes comparisons that branch to a block ending in `unreachable` when the hint
is wrong: the range analysis learns the [value range](/glossary#value-range) from them, and the
code generator takes them out once it has computed the ranges.

Then `Optimizer` runs the [passes](/glossary#pass). On each function it repeats, until nothing
changes:

1. simplification (`Simplify`),
2. a bit chosen by a bit without a branch (`BitSelect`),
3. byte loads (`ByteLoads`),
4. width narrowing (`Narrow`), and narrowing with value ranges (`RangeNarrow`),
5. loop-invariant code moved before the loop (`Licm`),
6. induction variables (`Induction`),
7. pointer compression (`PointerOffsets`),
8. multiplication and division by constants (`StrengthReduce`),
9. common subexpressions and redundant loads (`Gvn`),
10. dead code removal (`DeadCode`).

After that round, `ParamRanges` narrows the ranges of parameters across the whole program, and
if it did, the round runs again. Then small functions are inlined (`Inline`), pointer
parameters are compressed (`PointerParams`), and if either changed something, the passes run
again, followed by `ParamRanges` once more. Last, `Schedule` sets the order of the
instructions for the code generator, and the verifier checks the result.
What MIR looks like is on [MIR](/inside/mir); what each pass does is on
[Optimizations](/inside/optimizations).

## Code generation

The [code generator from MIR](/glossary#code-generator-from-mir), `MirCodeGen`, is dampf's
main code generator. Besides the assembly, it computes the best and worst case of every
function in CPU cycles, which `dampf build` writes to a `.timing.txt` file next to the ROM.
`Compiler` then checks every function with a `DAMPF_BUDGET` against its worst case: a function
above its budget, or one without a worst case, stops the build with an error, and one above
90 % of its budget gets a warning that does not.

The [naive code generator](/glossary#naive-code-generator), `NaiveCodeGen`, translates the AST
directly, almost everything in 32-bit `int` as C's promotions say. It stays as an independent
reference: the tests compare the code generator from MIR with it. It is also the only one that
accepts `--replace`, which links a hand-written assembly version of a function in place of the
compiled one; with the code generator from MIR, dampf stops with
`--replace works with the naive code generator only`. The options are on
[Command line](/reference/command-line).

Both write ca65 assembly with the source line of every C statement as a `.dbg line`
directive. Each function and each constant object goes into a segment of its own, named
`C_` or `R_` and the name, such as `C_add3`.

## Assembling and linking

The class `RomBuilder` turns the assembly into a LoROM image with
[ca65 and ld65](/glossary#ca65-and-ld65). It copies the [runtime](/glossary#runtime) as
`dampf_runtime.s` next to the program, assembles both with `ca65 -g`, and then links twice.
ld65 cannot split a segment, so dampf has to know every size before it can place the segments
in [banks](/glossary#bank): the first link puts everything into one large area only to read the
segment sizes from the map file. dampf then packs the function and constant segments into the
32 KB banks from `$80` up, largest first, each into the first bank with room, and links again
with that configuration. The image is padded to a power of two, and the header gets the ROM
size and the checksum.

The segments of the final configuration:

| Segment | Where |
|---|---|
| `ZEROPAGE` | the [direct page](/glossary#direct-page), `$0000`–`$00FF` |
| `BSS` | RAM in bank `$7E` from `$2000` |
| `DATA` | initial values: stored in bank `$80`, copied to RAM |
| `STARTUP`, `DAMPF_CODE` | the runtime, in bank `$80` |
| `C_…`, `R_…` | functions and constant objects, packed into banks `$80` and up |
| `HEADER`, `VECTORS` | bank `$80` at `$FFC0` and `$FFE0` |

The runtime and the initial values must fit into bank `$80` together, and no function or
constant object may be larger than 32 KB; dampf stops with a message if either is not so.

All files go into the directory of the ROM, which dampf creates if it is missing. For
`-o build/calls.sfc` they are:

| File | What it is |
|---|---|
| `calls.sfc` | the ROM |
| `calls.dbg` | ld65's debug file, which Mesen reads |
| `calls.lbl`, `calls.map` | label file and map file of the final link |
| `calls.s`, `calls.o` | the generated assembly and its object file |
| `dampf_runtime.s`, `dampf_runtime.o` | the runtime and its object file |
| `calls.cfg` | the linker configuration of the final link |
| `calls_measure.cfg`, `calls_measure.map` | configuration and map file of the first link |
| `calls.timing.txt` | best and worst case per function (code generator from MIR only) |

The tools run in that directory with plain file names, and the C file names in the debug file
are relative to it, so Mesen finds the sources wherever the ROM is opened from.

## Why it works this way

**A parser that accepts more than the subset.** The grammar parses C23 and GNU extensions
although dampf supports far less. A parse error would only say that something is wrong where
the subset checker can say what is not supported, such as recursion, with a clear message
(`docs/architecture.md`, *Pipeline*).

**ca65 and ld65 instead of an own assembler.** Because dampf compiles the whole program, it
already knows every instruction size and decides branch distances itself, and it controls
placement through segments; an own assembler or linker would gain nothing. ld65 also writes
the `.dbg` file Mesen reads, which makes debugging at the level of the C source possible. The
price is the second link to learn the segment sizes (`docs/architecture.md`, *Assembly output
and debugging*).

**Not more than three intermediate levels.** Every level costs a lowering step, a verifier, a
text format and tests, so dampf plans three, one each for the structure of C, the data flow and
the machine, and adds another only if an optimization fits nowhere cleanly
(`docs/architecture.md`, *Deliberately not more than three*).
