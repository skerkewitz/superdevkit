# Architecture

How dampf is built: the pipeline from C source to ROM, the intermediate representations, the
split between the 65816 core and the SNES target, and what the generated assembly looks like.
Milestone state is in [roadmap.md](roadmap.md).

## Where the code stands

Of the three IR levels below MIR and LIR are built. MIR (`dampf.mir`): the lowering from the AST
(SSA built directly, Braun et al.), a text format (`dampf mir file.c`) that parses back
(`MirParser`), a verifier and an interpreter. LIR (`dampf.lir`): 65816 instructions in basic
blocks with the accumulator width each runs in, whose text is the ca65 source dampf writes
(`LPrinter`, `LParser`), a verifier (`LVerifier`) and passes. HIR will sit between the AST and MIR
once struct splitting needs it. The code generator from MIR (`dampf.backend.MirCodeGen`, the
default of `dampf build`) selects instructions once per function (`Selection` and the classes per
kind of instruction, with `FrameLayout` proposing the places); the places get their addresses on
the code (`PlaceColouring`, checked by `PlaceCheck`, ADR-6F8NBG4P), and some go to X or Y
instead where that saves cycles (`RegisterChoice`); the LIR passes then remove loads of what a
register holds or move them out of a loop (`RedundantLoads`, `IndexInY`, on `RegContents`), apply peephole rules
(`Peephole`), run short 16-bit stretches between byte stores in 8 bits (`ByteStretches`) and
loops over bytes in 8 bits from entry to exit (`ByteLoops`), drop a
`CLC` or `SEC` where the carry is known (`KnownCarry`) and remove stores nobody
reads (`DeadStores`), choose the branch forms (`BranchRelax`) and number the local labels
(`LocalLabels`); `Timing` gives the best and worst case on the LIR. The way there is in
[archive/transition-to-lir.md](archive/transition-to-lir.md). The naive code generator works on the AST and stays the reference for optimized
builds (`--backend naive`; `--replace` works only with it; see [testing.md](testing.md)).

The MIR passes (`dampf.mir.Optimizer`): simplification (`Simplify`), tests against neighbouring
constants as one range check (`RangeChecks`), a bit chosen by a bit
(`BitSelect`), 8.8 values read as bytes (`ByteLoads`), width narrowing (`Narrow`, and
`RangeNarrow` with the value ranges of `Ranges`), loop-invariant code (`Licm`), induction
variables (`Induction`), pointer compression (`PointerOffsets`), multiplication and division by
constants (`StrengthReduce`), common subexpressions and redundant loads (`Gvn`), dead code
removal (`DeadCode`, `DeadLoops`) and two loops over the rows of a table as one (`Collapse`),
repeated until nothing changes; then parameter ranges from the calls
(`ParamRanges`), inlining (`Inline`) and pointer parameters as offsets (`PointerParams`), and the
passes once more; last, the order of the instructions for the code generator (`Schedule`). The
code generator itself first gives loop exits their own blocks (`ExitEdges`), copies return blocks
into their predecessors (`Returns`), computes pointer sums again where their value is needed
(`PointerRemat`) and takes out the checks of the hints (`Hints`). How values
get places, registers and frames is in [optimizations.md](optimizations.md).

## Pipeline

```
C source
  → gcc -E            preprocessing; keeps line markers (# 142 "bubble.c")
  → ANTLR 4 parser    based on the C grammar from grammars-v4; parses broadly
  → AST (checked)     subset enforced, types resolved, error messages
  → HIR               C structure and meaning: loops, structs, arrays, hints
  → MIR               SSA form, explicit widths, no structs, still machine-independent
  → LIR               65816 instructions, processor state, direct page slots, cycles
  → assembly          ca65 source, commented, with source line directives
  → assembler, linker ROM + .dbg debug file (dampf's own; ca65 and ld65 give the same ROM)
  → Mesen             runs and debugs at C source level
```

As built, HIR is skipped: `dampf.mir.Lower` lowers the AST to MIR, `dampf.backend.MirCodeGen`
selects LIR from it, and the LIR passes and printer write the ca65 assembly; `dampf.backend.NaiveCodeGen` goes from the
AST to ca65 assembly. `dampf.snes.RomBuilder` builds the ROM with dampf's assembler and linker
(`dampf.asm`), or with ca65 and ld65 (`--assembler ca65`), and `dampf.Compiler` ties the steps
together.

The parser accepts more C than the subset on purpose: a construct outside the subset is better
reported by the subset checker ("recursion is not supported: …") than by a parse error.

Preprocessing runs `gcc -E -nostdinc -undef -D__DAMPF__=1` with dampf's own `<stdint.h>`, so host
headers and host macros never reach the code. The same preprocessor serves the PC build, so both
builds see the same code (this matters for the hints, see [hints.md](hints.md)).

## Intermediate representations

### Why more than one

High-level optimizations need the structure of the program: loops, structs, arrays, calls.
Low-level optimizations need the details of the 65816: registers, processor state, memory
regions, cycle costs. One representation does both badly, so compilers use several levels: Swift goes from SIL to LLVM IR, Rust from MIR to LLVM IR to LLVM's machine IR.
dampf uses three levels, plus the checked syntax tree in front.

A note on names: dampf's MIR is the mid-level IR, comparable to LLVM IR. It is not Rust's MIR and
not LLVM's machine IR; dampf's LIR plays the role of LLVM's machine IR.

### The three levels

| Level | Comparable to | What it represents | What happens there |
| --- | --- | --- | --- |
| **HIR** (high-level) | Rust MIR, Swift SIL | A control flow graph that still has struct fields, array indexing, calls and intrinsics. C's integer promotions are made explicit here, so the meaning of every operation is unambiguous. *Not built: the typed AST makes the conversions explicit.* | Call graph and recursion check; inlining; recognising loops; `DAMPF_SPLIT` (arrays of structs into one array per field); `DAMPF_TABLE` (evaluating functions at compile time). *As built: the recursion check runs on the AST (`dampf.check.CallGraph`), inlining and loops on MIR (`Inline`, `Loops`); `DAMPF_SPLIT` and `DAMPF_TABLE` are not built.* |
| **MIR** (mid-level) | LLVM IR | SSA form (every value is assigned exactly once). Types `i8`, `i16`, `i24`; pointers as `ptr16` (within the current data bank) or `ptr24` (any bank). Memory as symbol plus offset. *As built: `i1`, `i8`, `i16`, `i32` (C's int) and one `ptr` type; signedness in the operations (sdiv/udiv, ashr/lshr, slt/ult, sext/zext); memory through `load`/`store`/`copy` on addresses from `ptradd`; locals whose address is taken, and arrays and structs, in frame slots.* | Constant propagation, dead code removal, common subexpressions, hoisting invariant code out of loops, **narrowing widths** using value ranges (16 → 8 bit where provably identical), recognising 8.8 fixed-point patterns as byte accesses. *As built: all of these (constants in `Simplify`), plus the other passes listed above.* |
| **LIR** (low-level) | LLVM machine IR | Real 65816 instructions on virtual locations, with the processor state (M, X, D, DB) as part of the representation | Instruction selection; placing `REP`/`SEP` optimally; direct page slot allocation; assigning A, X and Y; peephole optimization; branch distances; **counting cycles and computing bounds** ([timing.md](timing.md)). *Built (`dampf.lir`): instruction selection (`MirCodeGen`), branch distances, redundant loads and dead stores, peephole rules, 8-bit stretches, cycles and bounds; `REP`/`SEP` across blocks comes next.* |

### What runs through all levels

- **Hints as assumptions.** `DAMPF_RANGE(i, 0, 15)` becomes comparisons in MIR that branch to a
  block ending in `unreachable` when the hint is wrong (the role of LLVM's `llvm.assume`): the
  range analysis learns the range from them, the MIR interpreter fails if it does not hold, and
  the code generator takes them out after computing the ranges. In the PC build the same
  condition is a checked assertion ([hints.md](hints.md)).
- **A source position on every operation.** Every operation carries its file and line, and every
  pass must preserve them. When two operations are merged, the result keeps one of the
  positions. Only this makes the assembly comments and the C source view in Mesen possible.

Every level also has a text format that can be printed and parsed back, a verifier, and (for HIR
and MIR) an interpreter; what that brings for testing is in [testing.md](testing.md). MIR has all
three (`Printer`/`MirParser`, `Verifier`, `Interpreter`).

### Deliberately not more than three

Why three levels and not more, and why HIR waits: ADR
[ADR-6F0R6DZD](adr/6F0R6DZD-three-ir-levels-and-hir-waits-until-something.md).

## Core and target

Almost everything is plain 65816: the IR, width analysis, direct page frames, instruction
selection. SNES specifics live in one target module:

- the memory map: direct page and stack in the first 8 KB of bank 0, WRAM, LoROM/HiROM (the
  current layout is in [memory-map.md](memory-map.md); dampf builds LoROM only);
- the cost of each memory access in cycles (FastROM, WRAM and I/O registers differ); the
  optimizer and the timing analysis need these ([timing.md](timing.md));
- the multiply and divide registers;
- intrinsics for DMA, vertical blank and ROM banks.

Nothing is built for a second target, but the split keeps one possible.

As built, the split is only partly there. The SNES side is `dampf.snes.RomBuilder` (memory map,
banks, header) and the runtime `compiler/src/main/resources/dampf/snes/runtime.s` (start-up, arithmetic
routines, header, vectors); the 65816 cycle table is `dampf.target.Cycles65816`. The code
generators still contain SNES facts themselves (DBR `$7E`, the RAM and frame addresses, long
addresses for I/O registers). Of the list above only the memory map is built: the cost model
counts CPU cycles, multiplication and division are software routines in the runtime, and there
are no intrinsics.

## Assembly output and debugging

dampf writes ca65 assembly and assembles and links it itself (`dampf.asm`, M6). For the code,
its own assembler and linker gain nothing over ca65 and ld65: with whole-program compilation the
compiler already knows every instruction size (so it decides branch distances itself) and
controls placement through segments. They exist so that using dampf needs no cc65
([roadmap.md](roadmap.md)); ca65 and ld65 stay the oracle in the tests, which build every program
both ways and compare the ROMs and the debug files. dampf writes the `.dbg` debug file in ld65's
format, which Mesen reads. With source line directives (`.dbg line`) in the assembly, Mesen shows and
steps through the C source. This was the biggest pain in the earlier attempt, so it was verified
first, in M1.

Every C statement is emitted with its source line, the processor state and a comment per
instruction. Optimizations add why the code looks the way it does, for example
`; >>8 read as high byte`. Every instruction also gets its cost and every C line the sum (planned
for code with branches: best and worst case). The target format, as an illustration of the
design and not dampf's current output (dampf runs X and Y at 16 bit with D = 0):

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
conditional branch shows "not taken/taken"). The code generator from MIR writes the source line,
the cycles, and a comment per MIR instruction (`; %0 = add i16 %a, %b`) above the instructions it
became; it writes no processor state line. Both share `dampf.backend.AsmWriter`. A C line of
straight-line code gets the sum in its header; a line with branches gets none. The static timing
analysis gives the best and worst case per function, not per line (`<rom>.timing.txt`, code from
MIR only). The cycles are CPU cycles; master cycles, which depend on the memory each access
touches, are not computed yet ([timing.md](timing.md)).

## Implementation

Java 25, ANTLR 4 with the vendored C grammar from grammars-v4
(`compiler/src/main/antlr/dampf/parser/README.md`), and Gradle with the wrapper; the reasons are in ADR
[ADR-6F0R624X](adr/6F0R624X-java-25-antlr-with-the-grammars-v4-c-grammar-and.md).
