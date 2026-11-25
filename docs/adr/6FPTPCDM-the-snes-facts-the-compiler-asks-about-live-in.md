# ADR-6FPTPCDM: The SNES facts the compiler asks about live in dampf.target, which knows none of its types

**Context.** dampf has one target (ADR-6F0R61EZ), and its plan keeps the plain 65816 core apart
from the SNES ([architecture.md](../architecture.md#core-and-target)). As built until 2026-11-25,
the code generators knew the SNES themselves: the bank `$7E` in the pointers both of them
build, the frame start `$20` in four classes and the test "below `$100`" in five, the stack at
`$1E00` in two, the multiplier's registers in `ArithSelection`, the I/O register ranges in the
optimizer's `Schedule`, the variables' start `$2000` in the linker, the ld65 configuration and
two checks.
Moving the variables to bank `$7F` (M12) would have meant a search through all of them, and
nothing told which `$7E` meant the data bank, which the variables' bank and which the bank of a
pointer to a frame place.

**Decision.**

- The facts live in the package `dampf.target`: `SnesTarget` (the banks of RAM and how a fixed
  address is reached, where the frames, the direct page, the stack and the variables lie, the
  I/O registers the optimizer may move loads past, the multiplier's registers), `ContextAreas`
  (the interrupt contexts' pages below the stack, ADR-6FM9TXV4) and `Cycles65816`. The code
  generators, the optimizer, the linker and the checks ask there and hold no SNES numbers of
  their own.
- The target answers in numbers, booleans and its own enum `Reach` (data bank or long). It
  imports none of the compiler's types (MIR, LIR, operands), so every part of the compiler may
  ask it and it depends on nothing but `dampf.ast.Interrupt`.
- The three meanings of `$7E` have three names: `DATA_BANK` (what DBR holds), `VARIABLE_BANK`
  (the bank byte of a pointer to a variable) and `FRAME_BANK` (the bank byte of a pointer to a
  frame place in low RAM).
- It is built for the questions the code asks, as static members of one class, with no
  interface for a second target.
- The runtime (`dampf/snes/runtime.s`) is SNES code written by hand and keeps its own copies of
  the data bank, the stack's top and the multiplier's addresses; `SnesTargetTest` checks that
  they are the same.

**Alternatives.**

- The package `dampf.snes`, beside `RomBuilder`: that package builds the ROM from the assembly
  and depends on the assembler, so the code generators would have depended on the ROM builder's
  package. `dampf.target` already held the cycle table.
- An interface `Target` with an SNES implementation passed to the code generators: nothing is
  built for a second target, and every class on the way would have carried it.
- Passing each fact as a parameter, as `Peephole` took the frame start: the value was always
  the same, and the parameter only moved the question to the caller.

**Consequences.** A change to the memory map starts in `SnesTarget`, and the places that use one
of its names are the places it affects. The generated code did not change with the move (every
output of BackendOutputTest and every example's ROM byte for byte the same). Still outside the
target: the ROM layout (banks from `$80`, the header) in `RomBuilder` and `Linker`, the debug
ports (`$21FA`-`$21FF`) in the tools and the emulator, and the emulator itself, which is the
hardware and not the compiler.
