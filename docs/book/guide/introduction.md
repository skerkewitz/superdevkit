<!-- GENERATED from doc-specs/guide/introduction.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# What is dampf?

dampf is a compiler for a [subset](/glossary#subset) of C that produces fast code for the WDC
65816, and it has exactly one target: the Super Nintendo. The same source also compiles with gcc
on the PC, and what dampf's code computes on the SNES matches that [PC build](/glossary#pc-build)
bit for bit. dampf is the compiler of superdevkit, a [development kit](/glossary#dev-kit) for
SNES games that also holds a library with the console API, tools, examples and this book.

A game is written once against the kit's console API, and
[the superdevkit command](/reference/superdevkit) builds it for each
[target](/glossary#target): for the SNES with dampf, for Linux and Windows with the PC's C
compiler, and for [the web](/guide/the-web) with Emscripten. The last three run the game on
[Rachael](/glossary#rachael), the SNES rebuilt in portable C, with the same picture pixel for
pixel.

The name comes from German: *Dampf machen* ("make steam") means to get things moving fast. It
also fits the steam factory of [Blubber Blasen](/glossary#blubber-blasen), dampf's first user, a
game whose logic is C that also builds and is tested on the PC.

## Why another C compiler

Blubber Blasen's SNES port needs a C compiler for the 65816 that makes fast code. Speed is given
in [frames](/glossary#frame) per [tick](/glossary#tick): a tick is one step of the game logic, a
frame one picture on the screen, 60 a second. The game logic has to fit in 60 % of a frame,
about 0.6 frames per tick.

Of the existing compilers, [Calypsi](/glossary#calypsi) makes faster code than 816-tcc, the
compiler of [PVSnesLib](/glossary#pvsneslib), and dampf's code is faster than Calypsi's. The
other ways were no better:

| Way | Result |
| --- | --- |
| llvm-mos | Good 6502 code, but no native 16-bit 65816 code generation. |
| Hand-written assembly for the whole game | Tried years ago, and it failed. |

LLVM fits the 65816 poorly: it assumes many uniform registers and values of a fixed width, while
on the 65816 the width of an operand is processor state, switched between
[8-bit and 16-bit mode](/glossary#_8-bit-and-16-bit-mode) with `REP` and `SEP`. Hand-written
assembly stays possible later for a few hot functions, but it is not the plan.

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
  promise at build time. Three of them are built so far, all from `<dampf.h>`: `DAMPF_RANGE`
  adds a value range at one point, `DAMPF_BUDGET` sets a cycle budget for a function, and
  `DAMPF_ASM` puts hand-written assembly in place of a function, checked against its C body.
- **C as the contract with the PC build.** C is what keeps the same source compiling on the PC
  with gcc or clang, and that is what makes the code testable.

For your own game this means two things. Its logic must also build with gcc on the PC, because
that build is what dampf's result is compared with. And it reaches the console only through the
kit's [console API](/reference/console-api), `sdevkit/snes.h`, where every name starts with
`sdevkit_` or `SDEVKIT_`. The API is the hardware at once (every register of the picture,
copies by [DMA](/glossary#dma), [HDMA](/glossary#hdma)), with optional layers above it that a
game uses only if it calls them, such as a sprite list and an upload queue. On the SNES the kit's
[platform layer](/glossary#platform-layer) in `lib/snes`, written in C, implements it, and dampf
compiles it together with the game; on the desktop and the web Rachael implements it. A game
written against PVSnesLib or another library moves its calls to the console API by hand.

## The PC build is the reference

Game logic is much easier to test on the PC than on the SNES, for example against recorded
input. That only helps if the SNES runs the same logic. dampf therefore
treats gcc on x86-64 as the reference: whatever your code computes on the PC, dampf's code must
compute on the SNES, bit for bit, including the effects of C's integer promotions (small types
are widened to `int` before arithmetic) and the wrap-around of fixed-width types.

The tests hold dampf to that. Every test program is built with dampf and with clang on the PC,
and the results are compared; gcc and clang differ only where C leaves the result open, which
the subset avoids. Blubber Blasen's own benchmark gave the PC's checksums on every one of its
3,610 ticks. The kit's example games are played with a fixed input in dampf's own
emulator and as a PC build, and a checksum of their state is compared after every tick, in
every build. When dampf's code looks different from what you expected, the reason is
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
6. **Nothing to install for a SNES build.** The release zip brings its own Java, and dampf
   preprocesses, assembles and links with its own preprocessor, assembler and linker, so a SNES
   build needs neither gcc nor [ca65 and ld65](/glossary#ca65-and-ld65). The desktop and the web
   builds need the PC's C compiler or Emscripten.

Its non-goals:

- Full C, or conformance to the C standard beyond the subset.
- Other CPUs or targets for dampf itself: the kit's desktop and web builds use the PC's C
  compiler.
- A debugger of its own: dampf uses Mesen.
- Floating point.
- Fast compilation. A game the size of Blubber Blasen already compiles in seconds.

## Where it stands

dampf compiles all of Blubber Blasen, and the kit's version is 0.10.0. The milestones M0
(define the subset), M1 (end to end, with stepping through C in Mesen), M2 (all of the game
compiled by the [naive code generator](/glossary#naive-code-generator), with the PC's
checksums), M3 (the optimizer: dampf lowers the program to [MIR](/glossary#mir), its
intermediate representation, optimizes it and generates 65816 code from it), M4 (fine tuning:
block layout, peephole rules, loop and branch optimizations), M6 (an assembler and linker of
its own), M5 (Blubber Blasen builds its ROM with the dev kit from its own repository), M7 (the
desktop, Linux and Windows, and the `superdevkit` command), M8 (the web), M9 (more of the
optimizer: registers chosen by cost, byte loops kept in 8-bit mode) and M10 (C in interrupt
handlers, hand-written assembly beside the optimized code, the subset's rules for `char` and
enums, and a console API that is the hardware at once) are concluded. Timing and debugging
follow in M11, and fine tuning until the frame budget is met in M12. Version 0.9.1 is the first
release with downloads: a zip each for Linux and Windows on x64, with its own Java and raylib.
This is still an alpha release: every version before 1.0 is a pre-release, so the subset, the
command line and the files dampf writes may change from one version to the next.

Until 2026-11-19 dampf was measured on Blubber Blasen's benchmark, which ran the game code with
a fixed input and every PVSnesLib call stubbed out. During play (the benchmark's ticks of the
game itself, not title, story or round intro), the game logic took 0.79 frames per tick with
dampf, against 4.37 with the naive code generator, measured on 2026-11-18. The goal of 0.6
frames per tick is not reached yet in play, and the compiler alone is not expected to reach it:
the game is being made to do less work at the same time.

dampf built a playable ROM of the game with the dev kit's library for the SNES, which
implements the console API `lib/include/sdevkit/snes.h` and the main loop of `sdevkit/loop.h`.
In Mesen the ROM ran at 1.16 frames per tick during a minute of play (3,600 frames, 3,098
ticks), measured on 2026-11-18 after the latest optimizations. That figure includes waiting for
the [vertical blank](/glossary#vertical-blank) and the platform layer, so it is not comparable
with the benchmark: a tick that needs more than one frame waits for the next vertical blank,
and the benchmark leaves out what the game's video code costs on the hardware. No ROM has run
on a real SNES yet. Since 2026-11-19 the dev kit's own examples are dampf's test cases and its
benchmark, and the game's code is no longer in the repository.

Since the game left, the library has gained sound effects, on the SNES through the kit's driver
for its sound processor, the SPC700, and save RAM, the cartridge's battery RAM. Music plays on
the desktop and the web, not yet on the SNES. Most of the hints are not built. dampf computes a
best and a worst case in CPU cycles for every function, and a function marked with
`DAMPF_BUDGET` that goes over its cycle budget fails the build. The [Roadmap](/inside/roadmap)
has the plan, and [Limits](/reference/limits) lists what dampf does not do yet.

## Where to go next

- [Getting started](/guide/getting-started): install superdevkit and build a first game.
- [Command line](/reference/command-line): the start of the reference, for every detail.
- [How a compile works](/inside/how-a-compile-works): the inside guide, for how dampf works under
  the hood.
