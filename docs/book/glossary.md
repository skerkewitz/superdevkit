# Glossary

Terms the book uses, in alphabetical order. A page explains a term in a few words where it
first needs it and links here for the rest.

## 8-bit and 16-bit mode

The 65816 runs its accumulator and its index registers either 8 or 16 bits wide. The width is
processor state, set with `REP` and `SEP`, not part of the instruction: the same `LDA` loads one
byte or two depending on the [`M` flag](#m-and-x-flags). A compiler for the 65816 has to know
the mode at every instruction, and switching it costs cycles.

## AST

Abstract syntax tree: the program as a tree of declarations, statements and expressions, built
from the parse. dampf's AST is typed and covers the whole program; the naive code generator
works directly on it, the optimizer lowers it to [MIR](#mir) first.

## Bank

A 64 KB block of the 65816's 16 MB address space, numbered by the top byte of a 24-bit address:
`$7E` is the first bank of the SNES work RAM. An object never crosses a bank boundary in a ROM
built by dampf.

## Blubber Blasen

The game dampf is developed for and tested on. Its game logic is C that also builds and is
tested on the PC. A snapshot of its code is in `examples/blubberblasen`.

## ca65 and ld65

The assembler and linker of the cc65 suite. dampf writes its assembly in ca65's syntax and
assembles and links it with its own assembler and linker; with `--assembler ca65` it uses ca65
and ld65 instead, which give the same ROM. The debug file Mesen reads is in ld65's format.

## Calypsi

Another C compiler for the 65816. dampf's speed on Blubber Blasen is measured against it.

## Code generator from MIR

dampf's main code generator (`--backend mir`, the default): it translates optimized
[MIR](#mir) into 65816 assembly. Compare the [naive code generator](#naive-code-generator).

## CPU partner

The computer-controlled second player of Blubber Blasen. It plans by simulating moves ahead
every tick, which makes *play with the CPU partner* the hardest part of the game's benchmark.

## DBR

Data bank register: the [bank](#bank) the 65816 uses for 16-bit data addresses. dampf keeps it
at `$7E`, where all RAM objects live.

## Direct page

A 256-byte window of bank 0 that the 65816 reaches with one-byte addresses, faster and shorter
than full addresses. Its position is set by the `D` register. dampf places the frames of
functions there.

## Entry function

The C function the [runtime](#runtime) calls after start-up, `main` unless `--entry` names
another. It is `void name(void)`, or `int main(void)`, whose result is ignored.

## FastROM and SlowROM

The two speeds at which the SNES reads a cartridge's ROM. FastROM reads banks `$80` and up at
3.58 MHz, SlowROM at 2.68 MHz. dampf builds FastROM by default; `--slowrom` builds for a SlowROM
cartridge.

## Frame

One picture on the screen; the SNES shows 60 a second (NTSC). The speed of game code is given in
frames per [tick](#tick). Also, on the code side, the memory a function's locals and parameters
occupy; the book says *frame of a function* for that.

## Hint

A macro from dampf's header `<dampf.h>` that tells dampf something it cannot infer, such as a
value range (`DAMPF_RANGE`) or a cycle budget (`DAMPF_BUDGET`). In the PC build a hint is a
checked assertion or nothing. Two hints are built; see [Hints](/reference/hints).

## Intrinsic

A function that gives C code access to SNES hardware, such as the hardware multiplier or DMA. In
the PC build it is a plain function. Not built yet.

## LIR

dampf's low-level intermediate representation: each function as basic blocks of real 65816
instructions, each knowing the accumulator width it runs in. Its text is the assembly file dampf
writes. Its [passes](#pass) remove loads and stores that are not needed and choose the short
form of branches.

## M and X flags

The two bits of the 65816's processor status that set [8-bit or 16-bit mode](#_8-bit-and-16-bit-mode):
`M` for the accumulator and memory accesses, `X` for the index registers X and Y.

## Master clock

The SNES's base clock. A CPU cycle takes several master clocks, more for slow memory, so the
exact cost of code is counted in master clocks: one NTSC [frame](#frame) is 357,366 of them.
dampf's cycle report counts CPU cycles; its benchmark measures master clocks.

## Mesen

Mesen 2, the SNES emulator and debugger dampf's ROMs are run and debugged in. It reads the debug
file (in ld65's format) and shows the C source while stepping. dampf also has an emulator of its
own for the tests.

## MIR

dampf's middle-level intermediate representation: each function as a graph of basic blocks of
simple instructions in [SSA](#ssa) form. The optimizer's [passes](#pass) work on MIR; `dampf mir`
prints it.

## Naive code generator

The first code generator of dampf (`--backend naive`): it translates the [AST](#ast) directly,
doing almost everything in 32-bit `int` as C's promotions say. It stays as an independent
reference that the tests compare the [code generator from MIR](#code-generator-from-mir) with.

## Pass

One step of the optimizer that reads [MIR](#mir) and writes better MIR, such as narrowing values
to 8 or 16 bits or removing a repeated computation.

## PC build

The same C source compiled with gcc on an x86-64 PC. It is the reference: what dampf's code
computes on the SNES must match it bit for bit.

## Platform layer

The C code that implements a game's console API on the real SNES: setting up the hardware, the
main loop and waiting for the [vertical blank](#vertical-blank). dampf compiles it together with
the game. Blubber Blasen's platform layer implements its PVSnesLib-shaped calls; a game that
calls another library needs a platform layer for that library.

## Pointer compression

Replacing a pointer by a 16-bit offset into the one object it always points into, where dampf
can see that every value of the pointer points into the same object. Accesses through it then
index the object with the X register instead of going through a full address.

## PVSnesLib

An SDK for SNES programming with the 816-tcc C compiler. Blubber Blasen uses its platform calls;
the benchmark stubs them out.

## Runtime

The start-up code dampf links into every ROM: it sets up the processor and memory, calls the
[entry function](#entry-function), and writes to `$21FB` when it returns.

## SSA

Static single assignment: each value is assigned exactly once. Where control flow joins and a
variable may come from more than one place, a *φ* (phi) picks the value of the path taken.
SSA makes it simple for a pass to see where a value comes from.

## Subset

The part of C that dampf accepts. Anything outside it is rejected with a message; see
[The C subset](/reference/c-subset).

## Tick

One step of Blubber Blasen's game logic. Its speed is measured in [frames](#frame) per tick:
the goal is that the game logic of a tick fits in 60 % of a frame.

## UBSan

gcc's undefined behaviour sanitizer. The tests build the PC side with it, so that a test program
whose result depends on undefined behaviour fails instead of passing by chance.

## Value range

The smallest and largest value a variable or an expression can have, which dampf infers. A value
that fits 8 or 16 bits lets dampf use the cheaper mode and instructions.

## Vertical blank

The time between two pictures when the SNES does not draw, about 60 times a second. Graphics
memory may only be written then, so a game waits for it at the end of every frame.

## Whole program

dampf always compiles all `.c` files of a program together, never one file at a time. Every pass
sees every call and every use of every variable.
