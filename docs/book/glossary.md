# Glossary

Terms the book uses, in alphabetical order. A page explains a term in a few words where it
first needs it and links here for the rest.

## 8-bit and 16-bit mode

The 65816 runs its accumulator and its index registers either 8 or 16 bits wide. The width is
processor state, set with `REP` and `SEP`, not part of the instruction: the same `LDA` loads one
byte or two depending on the [`M` flag](#m-and-x-flags). A compiler for the 65816 has to know
the mode at every instruction, and switching it costs cycles.

## Args file

A file of command-line arguments that dampf reads when the command line names it with `@`:
`dampf build @game.args`. Its paths are relative to the file, so a game's build names its
sources, include folders and the superdevkit home once. See
[Command line](/reference/command-line).

## AST

Abstract syntax tree: the program as a tree of declarations, statements and expressions, built
from the parse. dampf's AST is typed and covers the whole program; the naive code generator
works directly on it, the optimizer lowers it to [MIR](#mir) first.

## Backdrop

The colour the SNES shows where no background layer and no sprite draws a pixel: colour 0 of
[CGRAM](#cgram).

## Bank

A 64 KB block of the 65816's 16 MB address space, numbered by the top byte of a 24-bit address:
`$7E` is the first bank of the SNES work RAM. An object never crosses a bank boundary in a ROM
built by dampf.

## Blubber Blasen

The game dampf was developed for, and its first user. Its game logic is C that also builds and is
tested on the PC. Until 2026-11-19 a copy of its code was dampf's main test case and benchmark;
since then the dev kit's examples are.

## ca65 and ld65

The assembler and linker of the cc65 suite. dampf writes its assembly in ca65's syntax and
assembles and links it with its own assembler and linker; with `--assembler ca65` it uses ca65
and ld65 instead, which give the same ROM. The debug file Mesen reads is in ld65's format.

## Calypsi

Another C compiler for the 65816. dampf's speed on Blubber Blasen is measured against it.

## CGRAM

The SNES's colour memory: 256 colours of 15 bits (5 bits each of blue, green and red, written
BGR 5:5:5), 512 bytes. Background layers and sprites take their colours from it in palettes of
16 or 4 colours. Like [VRAM](#vram) it is written only in the [vertical blank](#vertical-blank)
or with the screen off.

## Code generator from MIR

dampf's main code generator (`--backend mir`, the default): it translates optimized
[MIR](#mir) into 65816 assembly. Compare the [naive code generator](#naive-code-generator).

## CPU partner

The computer-controlled second player of Blubber Blasen. It plans by simulating moves ahead
every tick, which made *play with the CPU partner* the hardest part of the game's benchmark.

## DBR

Data bank register: the [bank](#bank) the 65816 uses for 16-bit data addresses. dampf keeps it
at `$7E`, where all RAM objects live.

## Dev kit

superdevkit, a development kit for SNES games: dampf as its compiler, a library with the console
API for the SNES and, through [Rachael](#rachael), for the desktop, and tools. It lives in
dampf's repository until it is published. Its library for the SNES is the [platform layer](#platform-layer) that today sits in
`lib/snes`.

## Direct page

A 256-byte window of bank 0 that the 65816 reaches with one-byte addresses, faster and shorter
than full addresses. Its position is set by the `D` register. dampf places the frames of
functions there.

## DMA

Direct memory access: the SNES copies bytes from memory into [VRAM](#vram), [CGRAM](#cgram) or
[OAM](#oam) without the CPU, at 8 [master clocks](#master-clock) a byte. A vertical blank has
room for about 5,700 bytes of it. The kit's `sdevkit_dma_copy_*` functions use it.

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

## Front end

The part of dampf that reads C: it preprocesses a file with `gcc -E`, parses it, checks it
against the [subset](#subset) and reports what is outside it. `dampf check` runs only the front
end. A [hint](#hint) becomes a built-in there, a name only the front end knows.

## HDMA

DMA during the picture: at the start of each line, a channel writes a few bytes from a table
into a [PPU](#ppu) register, so a scroll value or a colour can change from line to line (a wave,
a gradient). The kit's `sdevkit_hdma_set` and `sdevkit_hdma_enable` set it up.

## Hint

A macro from dampf's header `<dampf.h>` that tells dampf something it cannot infer, such as a
value range (`DAMPF_RANGE`) or a cycle budget (`DAMPF_BUDGET`). In the PC build a hint is a
checked assertion or nothing. Two hints are built; see [Hints](/reference/hints).

## Intrinsic

A function that gives C code access to SNES hardware, such as the hardware multiplier or DMA. In
the PC build it is a plain function. Not built yet.

## Joypad

The SNES's controller: a cross, A, B, X, Y, L, R, Start and Select. A game gets the state of each
joypad once a [tick](#tick) as 16 bits, one per button (`SDEVKIT_KEY_*`); on the desktop the
keyboard and a gamepad stand in for joypad 1.

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

MesenCE (the continuation of Mesen 2), the SNES emulator and debugger dampf's ROMs are run and
debugged in. It reads the debug
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

## OAM

The SNES's sprite table: position, tile, palette, priority and size of 128 sprites, 544 bytes. A
game writes the kit's copy (`sdevkit_oam_memory`, through the `sdevkit_oam_*` functions), and the
main loop copies it into OAM in every [vertical blank](#vertical-blank).

## Pass

One step of the optimizer that reads [MIR](#mir) and writes better MIR, such as narrowing values
to 8 or 16 bits or removing a repeated computation.

## PC build

The same C source compiled with gcc on an x86-64 PC. It is the reference: what dampf's code
computes on the SNES must match it bit for bit.

## Platform layer

The C code that implements a game's console API on the real SNES: setting up the hardware, the
main loop and waiting for the [vertical blank](#vertical-blank). dampf compiles it together with
the game. The dev kit's is `lib/snes`, for the console API `sdevkit/snes.h` (PVSnesLib's calls,
renamed, and the main loop); a game that calls another library needs a platform layer for that
library.

## Pointer compression

Replacing a pointer by a 16-bit offset into the one object it always points into, where dampf
can see that every value of the pointer points into the same object. Accesses through it then
index the object with the X register instead of going through a full address.

## PPU

The SNES's picture processing unit: it draws each picture line by line from [VRAM](#vram),
[CGRAM](#cgram), [OAM](#oam) and its registers (`$2100` to `$213F`), which a game sets through the
console API.

## PVSnesLib

An SDK for SNES programming with the 816-tcc C compiler. Blubber Blasen uses its platform calls;
the benchmark stubs them out.

## Rachael

The SNES rebuilt in portable C, in the dev kit's `lib/rachael`: video memory, registers, DMA and
HDMA, and a renderer that draws the picture line by line as the PPU does. It runs no 65816 code:
the game is compiled for the PC and calls the same console API, and gives the same picture as on
the SNES, pixel for pixel. Rachael is the same on every PC target; what a target adds (the
window, the input, the frame rhythm) is its frontend, `lib/desktop` now and `lib/web` later. The
desktop build runs on it, the web build will. It is to the SNES what Winelib is to Windows: the
game's source is compiled against an implementation of its API on the PC, and nothing is
emulated, so it runs at the PC's speed, not the console's. Named after the replicant of Blade
Runner who can hardly be told from the real thing.

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

## Superdevkit home

The folder superdevkit is installed in: `bin/dampf`, the kit's library (`lib/`), the tools, the
examples, the desktop build and this book (`docs/`). A game lives in a folder of its own and names
the home in its [args file](#args-file). See [Getting started](/guide/getting-started).

## Tick

One step of a game's logic: the main loop calls `game_tick` once a tick, normally once a
[frame](#frame). A game's speed is measured in frames per tick, or in the work of a tick
(`dev/bench_examples.sh`); a tick that takes more than a frame makes the game slower. For
Blubber Blasen the goal was that the game logic of a tick fits in 60 % of a frame.

## Timing report

The file `<rom>.timing.txt` that `dampf build` writes next to the ROM: for every function, the
best and the worst case from its entry to its return, in CPU cycles, calls included; a function
whose worst case dampf cannot bound says `unbounded` and names the loop or call. A
`DAMPF_BUDGET` is checked against it. See [Exact timing](/inside/timing).

## UBSan

gcc's undefined behaviour sanitizer. The tests build the PC side with it, so that a test program
whose result depends on undefined behaviour fails instead of passing by chance.

## Value range

The smallest and largest value a variable or an expression can have, which dampf infers. A value
that fits 8 or 16 bits lets dampf use the cheaper mode and instructions.

## Vertical blank

The time between two pictures when the SNES does not draw, about 60 times a second. Graphics
memory ([VRAM](#vram), [CGRAM](#cgram), [OAM](#oam)) may only be written then, or while the
screen is off. The kit's main loop waits for it after every tick and calls `game_vblank` in it.

## VRAM

The SNES's video memory: 64 KB, addressed as 32,768 words of 16 bits, which hold the tiles of the
background layers and sprites and the maps of the layers. The [PPU](#ppu) reads it while it draws,
so a game writes it only in the [vertical blank](#vertical-blank) or with the screen off, with
[DMA](#dma).

## Whole program

dampf always compiles all `.c` files of a program together, never one file at a time. Every pass
sees every call and every use of every variable.
