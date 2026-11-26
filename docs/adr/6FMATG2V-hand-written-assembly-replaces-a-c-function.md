# ADR-6FMATG2V: Hand-written assembly replaces a C function through DAMPF_ASM and is checked against its body

**Context.** Until now `--replace name=file.s` was the only way to link a function written in
65816 assembly, and it worked with the naive code generator only, whose static frames gave every
parameter a fixed label. A game built with the code generator from MIR (the default) could not
have one hand-written routine, and the reference functions in `reference/` were measured against
the naive code. The brainstorm of 2026-11-24 ([summary](../brainstorm/interrupts-and-asm_2026-11-24.md),
part 2) settled the frame: game authors and dampf's own benchmarks are the users; an assembly
function is a leaf that calls no C; the C function stays, as what the PC compiles and as the
specification the assembly must match bit for bit; dampf computes its cycles; the declaration is
in C. It recommended ideas 5, 1 and 4 and the check against the C body, and the user took that.
The MIR code generator gives a function's frame its addresses only after selection
(ADR-6F8NBG4P), so a hand-written callee needs a fixed convention, and the bytes of its frame
have to be reserved below its callers' frames.

**Decision.**

- **Declaration.** `DAMPF_ASM("file.s")` from `<dampf.h>`, after the parameter list of a
  function's definition (`uint16_t random16(void) DAMPF_ASM("asm/random16.s") { ... }`). The
  path is relative to the C file. dampf expands it to `__attribute__((dampf_asm("file.s")))`,
  the PC to nothing, so the PC compiles the body. dampf links the file instead of compiling the
  body. `--replace name=file.s` stays as a command-line override with the same convention, now
  for both code generators; it wins over the declaration. On a declaration that is not a
  definition, and on the entry function, `DAMPF_ASM` is an error. Parameters and results are
  integers and pointers.
- **Convention**, the same for both code generators. The function's label is the C function's
  (`_name`), exported by the file, in a segment named `C_...` (`C_name` by convention). Its frame
  starts at `dampf_frame` ($0020): an assembly function is a leaf, so in the code from MIR its
  frame lies below every caller's, as a C leaf's does, and the naive generator puts it there too.
  The parameters lie there in their order, 2 bytes for 8 and 16 bits and 4 for 32 bits and
  pointers (address, bank, 0); dampf exports their places as `_name__param` and the first byte
  after them as `_name__locals`, and the file imports them (`.importzp`). An 8-bit parameter is
  the low byte of its place; the high byte is undefined. The result is in A, an 8-bit result in
  A's low byte (the caller extends it as its type says), a 32-bit result or a pointer with the
  high word in X. Entry and exit with A, X and Y 16 bit, DBR $7E, D 0, `JSL`/`RTL`. A, X, Y,
  the flags and the scratch registers (`dampf_regs`, `dampf_ptr`) count as clobbered; the
  stack must be as it was.
- **Locals.** dampf computes their size from the code: the highest byte the file touches at
  `_name__locals+n` (with the width of the access). A size the author declares was the
  alternative; the code shows it anyway, and a declared size can be wrong.
- **Analysis.** dampf assembles the file with its own assembler (the part of ca65 that dampf
  writes: instructions, labels including cheap local and unnamed ones, `.proc`, `.segment`,
  `.import`/`.importzp`, `.export`, `.global`, `.byte`, `.word`, `.res`, equates,
  `.a8`/`.a16`/`.i8`/`.i16`; no macros, no `.include`, no `.if`). An
  unsupported directive is an error at its line that lists what is read. From the function's
  label dampf follows the machine code: control flow from the branches and jumps, the widths
  through `SEP`, `REP`, `PHP` and `PLP`, the bytes on the stack, DBR and D through `PHB`/`PLB`
  and `PHD`/`PLD`, the interrupt and decimal flags. It rejects a call (`JSR`, `JSL`), a jump out
  of the function's segment, an indirect jump, `RTS`/`RTI`, `XCE`, `BRK`, `COP`, `STP`, `WAI`,
  `TXS`/`TCS`, `MVN`/`MVP` (not followed yet); an `RTL` with another state than at the entry;
  ways that meet with different widths or stacks; an immediate assembled for another width than
  the register has there; a place of the frames other than its own parameters and locals (a
  fixed address in $0020-$1FFF, `dampf_frame`, another function's place, an indexed access to
  its own places, an access beyond a parameter's bytes). Globals, I/O registers and the scratch
  registers stay allowed, as for C.
- **Bounds.** Every backward branch or jump carries a bound in a comment on its line:
  `; dampf: at most N`, meaning that the loop starting at the branch's target runs at most N
  rounds each time it is entered (the meaning of a C loop's bound in the timing). Loops must
  nest and are entered at their head. dampf times the code with the cycle table of the C code
  (`Cycles65816`), loops collapsed innermost first as for C; with 8-bit index registers the
  worst case counts the page crossing of indexed reads. The function then appears in the timing
  report, and `DAMPF_BUDGET` and the caller's bounds treat it like any other.
- **Check against C.** "This assembly computes what this C computes" is a fact hint, and fact
  hints are checked. Every build with the code generator from MIR compiles the C body too, under
  another label (`_dampf_check_name`, not in the shipped ROM), links a second ROM in memory, and
  in dampf's emulator runs both on 256 inputs per function (fewer for a function without
  parameters): edge values of each parameter, then random ones from a fixed seed, within a
  `DAMPF_RANGE` at the start of the body; the globals the body reads with their start-up values,
  all zero, all ones and random bytes; pointer parameters into bank $7F, filled with random
  bytes; the high byte of an 8-bit parameter not 0. The result's bytes and all of RAM from
  $7E:2000 to $7F:FFFF must be equal, and the assembly's cycles must lie within its computed
  bounds, so a bound comment set too low fails the build too. A difference is a compile error at
  the function with the inputs and both results. It costs about 0.1 s for a small program and
  0.4 s for Er-Tüp, whose build takes 13 s, so it runs in every build. The naive code generator
  links the same file and does not check it.

**Alternatives.**

- Arguments in registers (idea 2): the cheapest call, but a second convention beside C's until
  M12 decides C's own; later, together with M12.
- Reading the assembly into LIR (idea 3): no declaration at all, but dampf would become
  responsible for arbitrary assembly. The analysis takes only the half that timing and the
  checks need.
- A frame size declared by the author: simpler, but nothing would stop the code from writing one
  byte too far into the caller's frame.
- The check only in `dampf check` or in tests: it is cheap enough to run every time, and a hint
  that is checked only sometimes can go wrong unnoticed.
- `dampf eject` (idea 6), which writes dampf's own code as the starting point: later.

**Consequences.** A game can replace a hot function with assembly in the default build and keeps
its timing, budgets and the guarantee of bit-for-bit results. The MIR passes treat an assembly
function as opaque (no inlining, no parameter rewriting, it may write any memory). Programs
without assembly functions compile to the same code as before. `--replace` no longer switches
to the naive code generator. The reference functions (`reference/`) are linked into the build
from MIR and measured against dampf's optimized code; Er-Tüp's `random16` takes 68 cycles by hand
and by dampf, `sine` 21 by hand and 24 by dampf. An assembly function reached from an interrupt
handler is not handled; interrupt contexts are being built separately and will have to reject or
place it. The check does not compare writes to I/O registers, and a pointer parameter points
only into bank $7F.
