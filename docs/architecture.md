# Architecture

How dampf is built: the pipeline from C source to ROM, the intermediate representations, the
split between the 65816 core and the SNES target, and what the generated assembly looks like.
Decided at the kickoff (2026-11-17) and extended by the design discussion the same day.

## Where the code stands

The target pipeline below is not built in full yet. Today dampf has the front end (preprocessor,
parser, typed AST with C's conversions made explicit), the call graph check, and the **naive code
generator**, which works directly on the AST and produces ca65 assembly. That generator stays as
the reference for all optimized builds (see [testing.md](testing.md)).

**MIR** exists (`dampf.mir`): the lowering from the AST (SSA built directly, Braun et al.), a text
format (`dampf mir file.c`) that parses back (`MirParser`), a verifier and an interpreter. All of Blubber Blasen lowers to MIR,
and the game's benchmark run in the MIR interpreter gives the PC's checksums on all 3,610 ticks.
A simple code generator turns MIR into 65816 code (`dampf.backend.MirCodeGen`, `dampf build
--backend mir`). Every value has a fixed place in RAM, the frames form a compiled stack (a
function's frame starts after the frames of all its callers), and φs are copied through an
"incoming" place. It is correct on all tests and the whole game, and slow; the MIR passes will
make it fast. The MIR passes (`dampf.mir.Optimizer`) so far: width narrowing (`Narrow`) and
dead code removal (`DeadCode`). HIR and LIR are still to be built;
[roadmap.md](roadmap.md) says when. MIR currently goes straight from the AST; HIR will sit
between them once inlining and struct splitting need it.

## Pipeline

```
C source
  → gcc -E            preprocessing; keeps line markers (# 142 "bubble.c")
  → ANTLR 4 parser    based on the C grammar from grammars-v4; parses broadly
  → AST (checked)     subset enforced, types resolved, error messages
  → HIR               C structure and meaning: loops, structs, arrays, hints
  → MIR               SSA form, explicit widths, no structs, still machine-independent
  → LIR               65816 instructions, processor state, direct page slots, cycles
  → ca65 assembly     commented, with source line directives
  → ca65 + ld65       ROM + .dbg debug file
  → Mesen             runs and debugs at C source level
```

The parser accepts more C than the subset on purpose: a construct outside the subset is better
reported by the subset checker ("recursion is not supported: …") than by a parse error.

Preprocessing runs `gcc -E -nostdinc -undef -D__DAMPF__=1` with dampf's own `<stdint.h>`, so host
headers and host macros never reach the code. The same preprocessor serves the PC build and the
dampf build, so both see exactly the same code (this matters for the hints, see
[hints.md](hints.md)).

## Intermediate representations

### Why more than one

High-level optimizations need the structure of the program: loops, structs, arrays, calls.
Low-level optimizations need the details of the 65816: registers, processor state, memory
regions, cycle costs. One representation can do both only badly. Modern compilers therefore use
several levels: Swift goes from SIL to LLVM IR, Rust from MIR to LLVM IR to LLVM's machine IR.
dampf uses three levels, plus the checked syntax tree in front.

A note on names: dampf's MIR is the mid-level IR, comparable to LLVM IR. It is not Rust's MIR and
not LLVM's machine IR; dampf's LIR plays the role of LLVM's machine IR.

### The three levels

| Level | Comparable to | What it represents | What happens there |
| --- | --- | --- | --- |
| **HIR** (high-level) | Rust MIR, Swift SIL | A control flow graph that still has struct fields, array indexing, calls and intrinsics. C's integer promotions are made explicit here, so the meaning of every operation is unambiguous. | Call graph and recursion check; inlining; recognising loops; `DAMPF_SPLIT` (arrays of structs into one array per field); `DAMPF_TABLE` (evaluating functions at compile time) |
| **MIR** (mid-level) | LLVM IR | SSA form (every value is assigned exactly once). Types `i8`, `i16`, `i24`; pointers as `ptr16` (within the current data bank) or `ptr24` (any bank). Memory as symbol plus offset. *As built: `i1`, `i8`, `i16`, `i32` (C's int) and one `ptr` type; signedness in the operations (sdiv/udiv, ashr/lshr, slt/ult, sext/zext); memory through `load`/`store`/`copy` on addresses from `ptradd`; locals whose address is taken, and arrays and structs, in frame slots.* | Constant propagation, dead code removal, common subexpressions, hoisting invariant code out of loops, **narrowing widths** using value ranges (16 → 8 bit where provably identical), recognising 8.8 fixed-point patterns as byte accesses |
| **LIR** (low-level) | LLVM machine IR | Real 65816 instructions on virtual locations, with the processor state (M, X, D, DB) as part of the representation | Instruction selection; placing `REP`/`SEP` optimally; direct page slot allocation; assigning A, X and Y; peephole optimization; branch distances; **counting cycles and computing bounds** ([timing.md](timing.md)) |

### What runs through all levels

- **Hints as assumptions.** `DAMPF_RANGE(i, 0, 15)` becomes an `assume` operation (similar to
  LLVM's `llvm.assume`) that the range analysis in MIR uses. In the PC build the same condition
  is a checked assertion ([hints.md](hints.md)).
- **A source position on every operation.** Every operation carries its file and line, and every
  pass must preserve them. When two operations are merged, the result keeps one of the
  positions. Only this makes the assembly comments and the C source view in Mesen possible.

Every level also has a text format that can be printed and parsed back, a verifier, and (for HIR
and MIR) an interpreter; what that brings for testing is in [testing.md](testing.md).

### Deliberately not more than three

Every level costs a lowering step, a verifier, a text format and tests. Three levels cover the
three kinds of knowledge: the structure of C, data flow, and the machine. A further level is only
added if an optimization demonstrably fits nowhere cleanly.

## Core and target

Almost everything is plain 65816: the IR, width analysis, direct page frames, instruction
selection. SNES specifics live in one target module:

- the memory map: direct page and stack in the first 8 KB of bank 0, WRAM, LoROM/HiROM (the
  current layout is in [memory-map.md](memory-map.md));
- the cost of each memory access in cycles (FastROM, WRAM and I/O registers differ); the
  optimizer and the timing analysis need these ([timing.md](timing.md));
- the multiply and divide registers;
- intrinsics for DMA, vertical blank and ROM banks.

Nothing is built for a second target, but the split keeps one possible.

## Assembly output and debugging

dampf emits ca65 assembly and links with ld65. An own assembler or linker would gain nothing: with
whole-program compilation the compiler already knows every instruction size (so it decides branch
distances itself) and controls placement through segments. ld65 also writes `.dbg` debug files,
which Mesen reads. With source line directives (`.dbg line`) in the assembly, Mesen shows and
steps through the C source. This was the biggest pain in the earlier attempt, so it was verified
first, in M1 (it works since 2026-11-17).

Every C statement is emitted with its source line, the processor state and a comment per
instruction. Optimizations add why the code looks the way it does, for example
`; >>8 read as high byte`. Once the cycle counting exists, every instruction also gets its cost
and every C line the sum (for code with branches: best and worst case). The target format (the
numbers only illustrate it):

```
; bubble.c:142   b->x += b->vx;                       [ 13 cyc ]
        ; M=16 X=8  D=&bubbles[i]
        lda z:BUB_VX        ; A = vx (8.8)              4
        clc                 ;                           2
        adc z:BUB_X         ; A = x + vx                4
        sta z:BUB_X         ; x = A                     3
```

The naive code generator writes all of this: the source line, the processor state, a comment per
instruction, and the CPU cycles of every instruction (from `dampf.target.Cycles65816`; a
conditional branch shows "not taken/taken"). A C line of straight-line code gets the sum in its
header; a line with branches gets none until the static timing analysis gives its best and worst
case. The cycles are CPU cycles; master cycles, which depend on the memory each access touches,
come with that analysis.

## Implementation

- Java, compiled for the current LTS release (25): records, sealed interfaces and pattern
  matching suit an IR and compiler passes well, and strong types catch many errors early.
- ANTLR 4 for the parser, with the C grammar from grammars-v4 (vendored, see
  `src/main/antlr/dampf/parser/README.md`).
- Gradle with the wrapper as the build tool (decided 2026-11-17).
- Compilation speed does not matter: a game this size compiles in seconds either way.
