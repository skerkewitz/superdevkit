# Architecture

How dampf is built: the pipeline from C source to ROM, the intermediate representations, the
split between the 65816 core and the SNES target, and what the generated assembly looks like.
What comes next is in [roadmap.md](roadmap.md), what is done in [history.md](history.md).

## Where the code stands

Of the three IR levels below MIR and LIR are built. MIR (`dampf.mir`): the lowering from the AST
(SSA built directly, Braun et al.), a text format (`dampf mir file.c`) that parses back
(`MirParser`), a verifier and an interpreter. LIR (`dampf.lir`): 65816 instructions in basic
blocks with the accumulator width each runs in, whose text is the ca65 source dampf writes
(`LPrinter`, `LParser`), a verifier (`LVerifier`) and passes. HIR will sit between the AST and MIR
once struct splitting needs it. The code generator from MIR (`dampf.backend.MirCodeGen`, the
default of `dampf build`) selects instructions once per function (`Selection` and the classes per
kind of instruction, with `FrameLayout` proposing the places); some places go to X or Y where
that saves cycles (`RegisterChoice`), and the others get their addresses on the code
(`PlaceColouring`, checked by `PlaceCheck`, ADR-6F8NBG4P); the LIR passes then remove loads of what a
register holds, turn loads of what another register holds into transfers or move them out of a loop (`RedundantLoads`, on `RegContents`), apply peephole rules
(`Peephole`), run short 16-bit stretches between byte stores in 8 bits (`ByteStretches`) and
loops over bytes in 8 bits from entry to exit (`ByteLoops`), drop a
`CLC` or `SEC` where the carry is known (`KnownCarry`), remove stores nobody
reads and move a store only some ways read onto those ways (`DeadStores`), choose the branch forms (`BranchRelax`) and number the local labels
(`LocalLabels`); `Timing` gives the best and worst case on the LIR. The way there is in
[archive/transition-to-lir.md](archive/transition-to-lir.md). The naive code generator works on the AST and stays the reference for optimized
builds (`--backend naive`; see [testing.md](testing.md)).

A function can be written by hand in assembly (`DAMPF_ASM`, or `--replace` on the command line;
[hints.md](hints.md)). `dampf.asm.AsmFunction` reads its file with dampf's assembler, follows the
machine code from its label (control flow, register widths, the stack) and gives its cycles and
the bytes of its locals; `dampf.AsmFunctions` finds and analyses the program's assembly functions.
In MIR such a function is a stand-in (`MFunction.isAsm`) that the passes do not look into, and
`MirCodeGen` reserves its frame from `dampf_frame` and exports its parameters' places instead of
writing code; the naive generator puts the frame at the same place. The C body is lowered as well,
under another label, and `dampf.emu.AsmCheck` runs it against the assembly in a second ROM that
`RomBuilder.linkInMemory` links without writing files.

The MIR passes (`dampf.mir.Optimizer`): simplification (`Simplify`), tests against neighbouring
constants as one range check (`RangeChecks`), a bit chosen by a bit
(`BitSelect`), the absolute difference of signed values as one instruction, `abds` (`AbsDiff`), 8.8 values and values written in halves read as bytes (`ByteLoads`), width narrowing (`Narrow`, and
`RangeNarrow` with the value ranges of `Ranges`), loop-invariant code (`Licm`), induction
variables (`Induction`), pointer compression (`PointerOffsets`), multiplication and division by
constants (`StrengthReduce`), common subexpressions and redundant loads (`Gvn`), dead code
removal (`DeadCode`, `DeadLoops`) and two loops over the rows of a table as one (`Collapse`),
repeated until nothing changes; then parameter ranges from the calls
(`ParamRanges`), inlining (`Inline`), pointer parameters as offsets (`PointerParams`) and scaled
index parameters as their product (`IndexParams`), loads from constant objects at constant
places as their values (`ConstLoads`, from here on in every round), and the passes once more; then 32-bit values
only combined bit by bit as two 16-bit values (`SplitWords`) and a 32-bit comparison with a sum
of 16-bit values as a 16-bit one (`CompareSum`), each followed by the passes on what it changed;
last, comparisons next to the branches that use them (`SinkCompares`) and the order of the
instructions for the code generator (`Schedule`). Before all of them, the functions that neither
the entry, the interrupt handlers nor the assembly functions reach are dropped, with the globals
only they used (`Reachable`, ADR-6FNWEJAA): a library's functions a program does not call take no
ROM and their variables no RAM. A function that is called stays in the MIR even where it is
inlined, so the timing report keeps it; after the optimizer, `Reachable.needCode` finds the ones
no call and no assembly reaches any more, and the code generator compiles and times them but
leaves their code out of the ROM (ADR-6FVMCY48). The
code generator itself first gives loop exits their own blocks (`ExitEdges`), copies return blocks
into their predecessors (`Returns`), computes pointer sums again where their value is needed
(`PointerRemat`) and takes out the checks of the hints (`Hints`). How values
get places, registers and frames is in [optimizations.md](optimizations.md).

Interrupt handlers in C (`DAMPF_INTERRUPT`, ADR-6FM9TXV4) are built in the compiler; games have
the IRQ through the kit (`sdevkit/irq.h`, ADR-6FRX1GYC), and a game that publishes its data the
kit's NMI, which runs its vertical blank (`sdevkit/publish.h`, ADR-6FS0T4WB). Each handler is the root of a context, and a function reached from several contexts is
copied in the AST once per context before code is generated (`dampf.check.ContextCopies`,
ADR-6FQ4NSHE; the main code keeps the originals, so a program whose contexts share nothing is not
changed). Both code generators give a context its own direct page below the stack
(`dampf.target.ContextAreas`), with its copy of the runtime's scratch registers; the code
generator from MIR lays out the context's frames from that page with the main code's rule and writes their absolute
places with the context's symbol (`dampf_frame_irq`), and both multiply in software there. The
runtime gets an entry stub per handler, assembled only into a program that has one
([memory-map.md](memory-map.md#interrupt-contexts), [timing.md](timing.md)). Handlers read the
game's data through the copy the tick publishes, which the kit's header `sdevkit/publish.h` makes
after every tick into the copy no handler reads, and switches to in the kit's NMI with a volatile
byte (ADR-6FPV1EQV, ADR-6FRDS030, ADR-6FS0T4WB); no compiler pass knows about it. The one thing
dampf adds for it is `DAMPF_HAS_INTERRUPTS` in `<dampf.h>`, a constant that tells the header's
`game_vblank` whether the NMI does the job.

## Pipeline

```
C source
  → preprocessor      dampf's own (dampf.frontend.pp); line markers (# 142 "bubble.c")
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

dampf preprocesses with a preprocessor of its own (`dampf.frontend.pp`, ADR-6FD4GDN5), a subset of
C99's: no host headers and no host macros reach the code, dampf's own include directory has
`<stdint.h>`, `<dampf.h>` and `<string.h>`, and `__DAMPF__` is defined. It writes the text with
line markers, as `gcc -E` does, for the source positions. The PC build preprocesses with its own
compiler; both builds see the same code (this matters for the hints, see [hints.md](hints.md))
because dampf's preprocessor gives the same tokens as clang's and gcc's on the subset, which
`OracleTest` and `RandomMacrosTest` check.

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
| **MIR** (mid-level) | LLVM IR | SSA form (every value is assigned exactly once). Types `i8`, `i16`, `i24`; pointers as `ptr16` (within the current data bank) or `ptr24` (any bank). Memory as symbol plus offset. *As built: `i1`, `i8`, `i16`, `i32` (C's int) and one `ptr` type; signedness in the operations (sdiv/udiv, ashr/lshr, slt/ult, sext/zext, and abds, the absolute difference of signed values); memory through `load`/`store`/`copy` on addresses from `ptradd`; locals whose address is taken, and arrays and structs, in frame slots.* | Constant propagation, dead code removal, common subexpressions, hoisting invariant code out of loops, **narrowing widths** using value ranges (16 → 8 bit where provably identical), recognising 8.8 fixed-point patterns as byte accesses. *As built: all of these (constants in `Simplify`), plus the other passes listed above.* |
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
- the multiply and divide registers.

Nothing is built for a second target, but the split keeps one possible.

As built, the target is the package `dampf.target` (ADR-6FPTPCDM): `SnesTarget` holds the banks of
RAM (the data bank, the variables' bank, the bank of a pointer to a frame place) and how a fixed
address is reached (variables through the data bank; ROM data and I/O registers long), where the
frames, the direct page, the stack and the variables lie, the I/O registers that are no memory,
and the multiplier's registers; `ContextAreas` places the interrupt contexts' pages below the
stack; `Cycles65816` is the cycle table. Both code generators, the optimizer, the linker and the
checks of assembly functions ask it for these instead of knowing them. It answers in numbers
and booleans and knows none of the compiler's types. Outside it, on the SNES side, are
`dampf.snes.RomBuilder` and `dampf.asm.Linker` (the ROM's layout: banks, header, vectors) and the
runtime `compiler/src/main/resources/dampf/snes/runtime.s` (start-up, arithmetic routines,
header, vectors, and the entry stubs of interrupt handlers in C), whose data bank, stack and
multiplier registers `SnesTargetTest` checks against `SnesTarget`. Of the list above, the cost
model counts master cycles by the memory each access reaches (`dampf.target.MasterCycles`,
ADR-6FVMDV79); the multiplier is used for a product of two bytes and in the
runtime's multiplication, division is a software routine (the hardware divider is planned in
M12 of the [roadmap](roadmap.md)), and there are no intrinsics.

## Assembly output and debugging

dampf writes ca65 assembly and assembles and links it itself (`dampf.asm`, M6). For the code,
its own assembler and linker gain nothing over ca65 and ld65: with whole-program compilation the
compiler already knows every instruction size (so it decides branch distances itself) and
controls placement through segments. They exist so that using dampf needs no cc65
([history.md](history.md#m6)); ca65 and ld65 stay the oracle in the tests, which build every program
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
instruction, and the master cycles of every instruction (from `dampf.target.MasterCycles`, with
the CPU cycles of `dampf.target.Cycles65816`; a conditional branch shows "not taken/taken", an
access through a pointer "best-worst"). The code generator from MIR writes the source line,
the cycles, and a comment per MIR instruction (`; %0 = add i16 %a, %b`) above the instructions it
became; it writes no processor state line. Both share `dampf.backend.AsmWriter`. A C line of
straight-line code gets the sum in its header; a line with branches gets none. The static timing
analysis gives the best and worst case per function, not per line (`<rom>.timing.txt`, code from
MIR only). The cycles are master cycles, by the memory each access touches
([timing.md](timing.md#master-cycles)).

## Implementation

Java 25, ANTLR 4 with the vendored C grammar from grammars-v4
(`compiler/src/main/antlr/dampf/parser/README.md`), and Gradle with the wrapper; the reasons are in ADR
[ADR-6F0R624X](adr/6F0R624X-java-25-antlr-with-the-grammars-v4-c-grammar-and.md).
