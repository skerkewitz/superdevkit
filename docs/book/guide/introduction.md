<!-- GENERATED from doc-specs/guide/introduction.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# What is dampf?

dampf is a compiler for a [subset](/glossary#subset) of C that produces fast code for the WDC
65816, and it has exactly one target: the Super Nintendo. The same source also compiles with gcc
on the PC, and what dampf's code computes on the SNES matches that [PC build](/glossary#pc-build)
bit for bit. dampf is the compiler of superdevkit, a development kit for SNES games that is not
published yet.

The name comes from German: *Dampf machen* ("make steam") means to get things moving fast. It
also fits the steam factory of [Blubber Blasen](/glossary#blubber-blasen), dampf's first user, a
game whose logic is C that also builds and is tested on the PC.

## Why another C compiler

Blubber Blasen's SNES port needs a C compiler for the 65816, and the existing ones were not good
enough for it. Their speed is given in [frames](/glossary#frame) per [tick](/glossary#tick): a
tick is one step of the game logic, a frame one picture on the screen, 60 a second. The game
logic has to fit in 60 % of a frame, about 0.6 frames per tick.

| Option | Result |
| --- | --- |
| [PVSnesLib](/glossary#pvsneslib) with its C compiler 816-tcc | Very slow code; the data layout needed workarounds because the compiler rejects ordinary code. |
| [Calypsi](/glossary#calypsi) | Works as intended, but the code is still too slow: 2.72 frames per tick during play, on an older version of the game. |
| llvm-mos | Good 6502 code, but no native 16-bit 65816 code generation. |
| Hand-written assembly for the whole game | Tried years ago, and it failed. |

LLVM fits the 65816 poorly: it assumes many uniform registers and values of a fixed width, while
on the 65816 the width of an operand is processor state, switched between
[8-bit and 16-bit mode](/glossary#_8-bit-and-16-bit-mode) with `REP` and `SEP`. Hand-written
assembly stays possible later for a few hot functions, but it is not the plan. Calypsi's 2.72
frames per tick is far from the 0.6 the game needs.

So dampf does not try to be a general C compiler. It solves a much smaller problem: making the
game code of SNES games fast.

## What dampf assumes

A compiler with that one job can assume things no general compiler can:

- **One CPU, one target.** dampf needs no portability layers between the C code and the
  machine.
- **A subset it defines and checks.** dampf defines the part of C it accepts, and a checker
  enforces it, so every assumption the optimizer makes is guaranteed to hold.
- **The whole program at once.** dampf compiles all `.c` files together, never one at a time,
  so every optimization sees the complete call graph (which function calls which) and every use
  of every variable ([whole program](/glossary#whole-program)).
- **Hints in the source.** Macros in the C source give dampf knowledge it cannot infer
  ([hint](/glossary#hint)), so it can optimize on facts it could not prove itself or check a
  promise at build time. Two of them are built so far: `DAMPF_RANGE` adds a value range at one
  point, and `DAMPF_BUDGET` sets a cycle budget for a function, both from `<dampf.h>`.
- **C as the contract with the PC build.** C is what keeps the same source compiling on the PC
  with gcc or clang, and that is what makes the code testable.

For your own game this means two things. Its logic must also build with gcc on the PC, because
that build is what dampf's result is compared with. And the calls it makes into PVSnesLib or
another library need a [platform layer](/glossary#platform-layer) written in C, which dampf
compiles together with the game, as Blubber Blasen's is. dampf brings no such layer for your
game: the one in the repository implements Blubber Blasen's calls only.

## The PC build is the reference

Game logic is much easier to test on the PC than on the SNES, for example against recorded
input. That only helps if the SNES runs the same logic. dampf therefore
treats gcc on x86-64 as the reference: whatever your code computes on the PC, dampf's code must
compute on the SNES, bit for bit, including the effects of C's integer promotions (small types
are widened to `int` before arithmetic) and the wrap-around of fixed-width types.

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
6. **Nothing to install but Java, in the end.** dampf assembles and links with its own
   assembler and linker, so you need no [ca65 and ld65](/glossary#ca65-and-ld65); gcc is still
   needed for preprocessing until dampf has a preprocessor of its own.

Its non-goals:

- Full C, or conformance to the C standard beyond the subset.
- Other CPUs or targets.
- A debugger of its own: dampf uses Mesen.
- Floating point.
- Fast compilation. A game the size of Blubber Blasen already compiles in seconds.

## Where it stands

dampf compiles all of Blubber Blasen, and its version is 0.5.0, in work. The milestones M0
(define the subset), M1 (end to end, with stepping through C in Mesen), M2 (all of the game
compiled by the [naive code generator](/glossary#naive-code-generator), with the PC's
checksums), M3 (the optimizer: dampf lowers the program to [MIR](/glossary#mir), its
intermediate representation, optimizes it and generates 65816 code from it), M4 (fine tuning:
block layout, peephole rules, loop and branch optimizations) and M6 (an assembler and linker of
its own) are concluded. M5, in which Blubber Blasen builds its ROM with the dev kit from its own
repository, is in work as version 0.5.0. Publishing the dev kit is a step of its own and not
scheduled yet. Every version before 1.0 is a pre-release, so the subset, the command line and
the files dampf writes may change from one version to the next. After M5, the dev kit comes to
Windows and Linux in M7 and to the web in M8; the optimizer work that is left goes on in M9,
language, platform and usability (among them a preprocessor of dampf's own) in M10, timing and
debugging in M11, and fine tuning until the frame budget is met in M12.

The game's benchmark runs the game code with a fixed input and every PVSnesLib call stubbed out.
During play (the benchmark's ticks of the game itself, not title, story or round intro), Blubber
Blasen's game logic takes 0.79 frames per tick with dampf, against 4.37 with the naive code
generator.
Calypsi's 2.72 came from an older version of the game. On the same version, in the play ticks
where both compilers play the same scene, both with [FastROM](/glossary#fastrom-and-slowrom),
Calypsi takes 1.42 frames per tick and dampf 0.66: dampf is 2.14 times as fast, so M3's goal of
beating Calypsi is reached. These figures were measured on 2026-11-18. The goal of 0.6 frames per
tick is not reached yet in play, and the compiler alone is not expected to reach it: the game is
being made to do less work at the same time.

dampf builds a playable ROM of the game. The dev kit's library, written in C, implements the
console API `lib/include/sdevkit/snes.h`, taken over from the game and renamed so that every name
a game can use starts with `sdevkit_` or `SDEVKIT_` (`setMode` is `sdevkit_set_mode`): the
PVSnesLib-shaped calls the game makes, and the main loop of `sdevkit/loop.h`. Until the game
switches to the new names, a deprecated compatibility header, `lib/include/snes/`, maps the old
ones for the snapshot of Blubber Blasen; `tools/sdevkit_rename.py` converts a game. On the console the game ran at 1.16
frames per tick during a minute of play (3,600 frames, 3,098 ticks), measured on 2026-11-18 after
the latest optimizations. That figure includes waiting for the
[vertical blank](/glossary#vertical-blank) and the platform layer, so it is not comparable with
the benchmark: a tick that needs more than one frame waits for the next vertical blank, and the
benchmark leaves out what the game's video code costs on the hardware. Sound and save RAM are
not built. Most of the hints are not built either. dampf computes a best and a worst case in CPU
cycles for every function, and a function marked with `DAMPF_BUDGET` that goes over its cycle
budget fails the build. The [Roadmap](/inside/roadmap) has the plan, and
[Limits](/reference/limits) lists what dampf does not do yet.

## Where to go next

- [Getting started](/guide/getting-started): install dampf and compile a first program.
- [Command line](/reference/command-line): the start of the reference, for every detail.
- [How a compile works](/inside/how-a-compile-works): the inside guide, for how dampf works under
  the hood.
