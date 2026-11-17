<!-- GENERATED from doc-specs/guide/introduction.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# What is dampf?

dampf is a compiler for a [subset](/glossary#subset) of C that produces fast code for the WDC
65816, and it has exactly one target: the Super Nintendo. The same source also compiles with gcc
on the PC, and what dampf's code computes on the SNES matches that [PC build](/glossary#pc-build)
bit for bit.

The name comes from German: *Dampf machen* ("make steam") means to get things moving fast. It
also fits the steam factory of [Blubber Blasen](/glossary#blubber-blasen), dampf's first user, a
game whose logic is C that also builds and is tested on the PC.

## Why another C compiler

Blubber Blasen's SNES port needs a C compiler for the 65816, and the existing ones were not good
enough for it:

| Option | Result |
| --- | --- |
| [PVSnesLib](/glossary#pvsneslib) with 816-tcc | Very slow code; the data layout needed workarounds because the compiler rejects ordinary code. |
| [Calypsi](/glossary#calypsi) | Works as intended, but the code is still too slow: 2.72 frames per tick during play, on an older version of the game. |
| llvm-mos | Good 6502 code, but no native 16-bit 65816 code generation. |
| Hand-written assembly for the whole game | Tried years ago, and it failed. |

LLVM fits the 65816 poorly: it assumes many uniform registers and values of a fixed width, while
on the 65816 the width of an operand is processor state, switched between
[8-bit and 16-bit mode](/glossary#_8-bit-and-16-bit-mode) with `REP` and `SEP`. Hand-written
assembly stays possible later for a few hot functions, but it is not the plan.

The speed is measured in [frames](/glossary#frame) per [tick](/glossary#tick): a tick is one step
of the game logic, a frame one picture on the screen, 60 a second. The game logic has to fit in
60 % of a frame, and Calypsi's 2.72 frames per tick is far from that.

So dampf does not try to be a general C compiler. It solves a much smaller problem: making the
game code of SNES games fast.

## What dampf assumes

A compiler with that one job can assume things no general compiler can:

- **One CPU, one target.** dampf needs no portability layers between the C code and the
  machine.
- **A subset it defines and checks.** dampf defines the part of C it accepts, and a checker
  enforces it, so every assumption the optimizer makes is guaranteed to hold.
- **The whole program at once.** dampf compiles all `.c` files together, never one at a time,
  so every optimization sees the complete call graph and every use of every variable
  ([whole program](/glossary#whole-program)).
- **Hints in the source.** Macros in the C source are meant to give dampf knowledge it cannot
  infer, such as a value range ([hint](/glossary#hint)). Two of them are built so far:
  `DAMPF_RANGE` and `DAMPF_BUDGET` from `<dampf.h>`.
- **C as the contract with the PC build.** C is what keeps the same source compiling on the PC
  with gcc or clang, and that is what makes the code testable.

## The PC build is the reference

Game logic is much easier to test on the PC than on the SNES, for example against recorded
input. That only helps if the SNES runs the same logic. dampf therefore
treats gcc on x86-64 as the reference: whatever your code computes on the PC, dampf's code must
compute on the SNES, bit for bit, including the effects of C's integer promotions and the
wrap-around of fixed-width types.

The tests hold dampf to that. Every test program is built with dampf and with gcc on the PC and
the results are compared, and Blubber Blasen's own benchmark gives the PC's checksums on every
one of its 3,610 ticks. When dampf's code looks different from what you expected, the reason is
often this rule. [Writing C for dampf](/guide/writing-c) shows how to write code that stays
within it.

## Goals and non-goals

dampf's goals:

1. **Fast 65816 code for SNES game logic.** The game logic of Blubber Blasen's busiest scene has
   to fit in at most 60 % of a frame.
2. **The same behaviour on both sides.** The same source compiles on the PC with gcc or clang
   and behaves bit-identically there and on the SNES.
3. **Source-level debugging in Mesen.** You step through the C code in
   [Mesen](/glossary#mesen), the SNES emulator and debugger.
4. **Readable output.** The generated assembly is commented, so you can follow what each part is
   meant to do.
5. **Correctness first.** Every optimization is checked automatically against a reference.

Its non-goals:

- Full C, or conformance to the C standard beyond the subset.
- Other CPUs or targets.
- An assembler, linker or debugger of its own: dampf uses [ca65, ld65](/glossary#ca65-and-ld65)
  and Mesen.
- Floating point.
- Fast compilation. A game the size of Blubber Blasen compiles in seconds either way.

## Where it stands

dampf compiles all of Blubber Blasen. The milestones M0 (define the subset), M1 (end to end, with
stepping through C in Mesen) and M2 (all of the game compiled by the
[naive code generator](/glossary#naive-code-generator), with the PC's checksums) are done. M3, the
optimizer, is in progress: dampf lowers the program to [MIR](/glossary#mir), its intermediate
representation, optimizes it and generates 65816 code from it. The fine tuning of M4 has not
started.

During play, Blubber Blasen's game logic takes 1.10 frames per tick with dampf (1.31 without
FastROM), against 5.17 with the naive code generator. Calypsi's 2.72 came from an older version of the game. On the same
version, in the play ticks where both compilers play the same scene, Calypsi takes 1.67 frames
per tick and dampf 1.14: dampf is 1.47 times as fast, so M3's goal of beating Calypsi is
reached. The goal of 0.6 frames is not reached yet, and the compiler alone is not expected to
reach it: the game is being made to do less work at the same time.

dampf builds a playable ROM of the game. The SNES platform layer, written in C, implements the
game's console API: the PVSnesLib-shaped calls the game makes, and the main loop. On the console
the game runs at 2.28 frames per tick for now: a tick that needs more than one frame waits for
the next vertical blank, and the benchmark leaves out what the game's video code costs on the
hardware. Sound and save RAM are not built. Most of the hints are not built either. dampf
computes a best and a worst case in CPU cycles for every function, and a function marked with
`DAMPF_BUDGET` that goes over its cycle budget fails the build. The [Roadmap](/inside/roadmap)
has the plan, and [Limits](/reference/limits) lists what dampf does not do yet.

## Where to go next

- [Getting started](/guide/getting-started): install dampf and compile a first program.
- [Command line](/reference/command-line): the start of the reference, for every detail.
- [How a compile works](/inside/how-a-compile-works): the inside guide, for how dampf works under
  the hood.
