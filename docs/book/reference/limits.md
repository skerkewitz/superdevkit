<!-- GENERATED from doc-specs/reference/limits.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Limits

dampf is at version 0.5.0, in work: milestones M0 to M4 and M6 are concluded. dampf is the
compiler of superdevkit, a development kit for SNES games that is held in dampf's repository
until it is published. The milestones named on this page are the open ones: M5, in which Blubber
Blasen builds its ROM with the dev kit from its own repository (0.5.0, in work), M7 for the
optimizer, M8 for language, platform and usability, M9 for timing and debugging, and M10 for
fine tuning. Publishing is a step of its own and not scheduled yet. They are on the
[Roadmap](/inside/roadmap), which also has their state and their order; nothing here goes beyond
it. Every version before 1.0 is a pre-release, so the subset, the command line and the files
dampf writes may change from one version to the next.

## Not built yet

**Sound and save RAM in the SNES platform layer.** dampf builds a playable ROM of Blubber
Blasen with `tools/build_game_rom.sh`. Its [platform layer](/glossary#platform-layer) in
`platform/snes`, written in C, implements the 19 [PVSnesLib](/glossary#pvsneslib)-shaped calls
that the game's `video.c` makes, the rest of the game's console API and the main loop. Two parts
are missing: sound, which the game does not have yet, and save RAM, so the high scores start
empty at every power-on. The playable ROM belongs to no milestone: it was split off from M2 and
built ahead of the game's own SNES port. It has been played in Mesen, not yet on a real console
or flash cartridge; that test is part of M5. The layer also has no interrupt
handler. Its loop waits for the [vertical blank](/glossary#vertical-blank) and does the work
there, because C code called from an interrupt in the middle of a tick would overwrite the
[frames](/glossary#frame) of the functions it interrupted: dampf keeps them at fixed places. C
in interrupt handlers is planned for M8, where the functions an interrupt reaches can get a
frame area of their own; how a handler is declared and what it saves is still open.

**A platform layer for your own game.** The layer in `platform/snes` implements Blubber Blasen's
console API (`snes.h`) and nothing else. In M5 it becomes the dev kit's library, `lib/snes`,
and the console API becomes the dev kit's own `lib/include/snes.h`, taken over from the game.
To build a ROM of your own game, you write a platform layer in C for the library your game
calls, and dampf compiles it together with the game. Write it the way `platform/snes` is
written: it reaches the I/O registers through pointers to `volatile` memory, and casts a
pointer to an integer to get the SNES address a DMA channel copies from
([The C subset](/reference/c-subset)).

**Hints.** A [hint](/glossary#hint) is a macro that tells dampf something it cannot infer, such
as `DAMPF_RANGE(i, 0, 15)` for "`i` is always between 0 and 15". In the PC build a hint that
states a fact becomes a checked assertion, so a wrong hint is caught on the PC before it does
damage on the SNES. Two hints from `<dampf.h>` are built, `DAMPF_RANGE` and `DAMPF_BUDGET` (a
cycle budget for a function, see [Exact timing](/inside/timing)); both are described in
[Hints](/reference/hints). The names of the others are decided, but none of them is built, so
you cannot use them yet. `DAMPF_TRIPS(16)`, for a loop that runs at most 16 times, is postponed
to M9: on the PC it would have to count the rounds per entry into the loop, and a macro cannot
see that entry without changing the loop. Meanwhile a [`DAMPF_RANGE`](/reference/hints) on a
counter in the loop gives the loop its bound, and a loop without a counter can be given one. The
hints that request a transformation (`DAMPF_TABLE`, `DAMPF_PIN_X`, `DAMPF_LIKELY` and the
others) belong to M10. dampf also infers [value ranges](/glossary#value-range) itself from
constants, operations, conditions and loops, so a range hint is needed only where it cannot.
Declared subrange types (`DAMPF_RANGED`, `DAMPF_AS`) are listed in M10 as well, but they are
built only when a profile shows a hot value whose range dampf cannot infer.

**`restrict`.** dampf rejects `restrict` ("not supported yet"), so you cannot tell it that two
pointers never overlap. How `restrict` would be checked on the PC, and whether a hint of
dampf's own is clearer, is an open question of the roadmap; the work belongs to M8.

**Intrinsics.** An [intrinsic](/glossary#intrinsic) gives C code access to the SNES hardware:
the hardware multiplier and divider, DMA, waiting for vertical blank, placing constant data in
ROM banks. In the PC build each is a plain function. None is built; the roadmap places the
intrinsics for the hardware multipliers, the divider and DMA in M8. The platform layer of the
playable ROM needed none: the `volatile` pointers and the pointer-to-integer cast described
above serve instead.

**Timing in master clocks and outside the CPU.** dampf computes a best and a worst case for
every function, writes them to the timing report and checks `DAMPF_BUDGET` at build time
([Exact timing](/inside/timing)), but it counts in CPU cycles only.
[Master clocks](/glossary#master-clock), the unit that includes the speed of each memory region
([FastROM, SlowROM](/glossary#fastrom-and-slowrom), WRAM, I/O), are not counted yet. Nor does
dampf account for the time lost outside the CPU: DRAM refresh, HDMA and interrupts. Budgets for
a whole frame, which would add the cost of interrupt handlers, are not built, nor a check of the
measured cycles of budgeted functions in the emulator. The comments of the generated assembly
carry the CPU cycles of each instruction, and each straight-line C line their sum; a line with
branches does not show its best and worst case yet, and neither the report nor a budget error
shows the costliest path line by line. A loop whose bound dampf cannot find in the code makes a
worst case "unbounded", which is so for 37 functions of Blubber Blasen; such a loop needs a
hint, as described above. All of this is M9: master clocks in the report, the comments and the
budgets, the costliest path per C line, bounds for more loops, the measured cycles checked, and
DRAM refresh, HDMA and interrupts at frame level. For measured cycles per function, use
`dampf profile` ([Command line](/reference/command-line)).

**An own preprocessor.** dampf preprocesses the C source with `gcc -E`, so you need gcc
installed next to Java; on Windows that means installing MinGW or MSYS for this alone. In M8,
dampf gets its own C preprocessor in Java, and gcc is then needed only to develop dampf, not to
use it; `gcc -E` stays in the tests, which compare the output of dampf's preprocessor with
gcc's. Assembling and linking already need nothing else: dampf has its own assembler and linker
(M6, done ahead of M5), and [ca65 and ld65](/glossary#ca65-and-ld65) are only an alternative
path that the tests compare it with.

**Other items you might notice.** These are smaller, but each changes what you can write or
expect:

- **One error per run.** dampf stops at the first error it finds, so a file with several
  problems takes several runs. Reporting all errors of a run is part of M5.
- **No argument file.** Every option and source file of a build goes on the command line, so a
  build as large as Blubber Blasen's needs a shell script. M5 adds options in an argument file,
  given by `--config` or `@file`, in which a folder stands for the `.c` files directly in it.
- **No `<string.h>`.** dampf ships only `<stdint.h>` and `<dampf.h>`, so `#include <string.h>`
  fails. M5 adds a `<string.h>` with `memset` and `memcpy`, written in C; copies with `MVN` or
  DMA come in M10.
- **`goto`** and labels are rejected, and that is decided (decision 4 of the subset). If real
  code needs `goto`, most likely after M5 when users bring code from elsewhere, dampf may allow
  a form that only jumps forward and out of statements, such as out of two loops to a label
  after them. That form keeps every loop with a single entry, so the loop optimizations and the
  worst-case bounds of the timing analysis still apply. Unrestricted `goto`, which can jump into
  a loop, will not be built. Meanwhile leave nested loops with a flag, or with `return` from a
  helper function, which costs nothing once dampf inlines it.
- **Function pointers in constant tables** known at compile time are allowed by the design of
  [the subset](/glossary#subset), but not built until something needs them; they are listed in
  M8.
- **Designated initializers** are rejected and stay out for now, without a milestone; the
  subset notes they are easy to add.
- **Plain `char` for text only:** dampf treats `char` as a signed 8-bit type, as on x86-64, and
  accepts arithmetic on it. The plan is to reject arithmetic on `char` beyond comparing and
  copying, so that its signedness can never change a result; this is decision 1 of the subset,
  open and to be taken in M8.
- **Hand-written assembly** linked in with `dampf build --replace` works only with the
  [naive code generator](/glossary#naive-code-generator), and a function linked in this way
  cannot declare its calling convention, [direct page](/glossary#direct-page) use and cycles
  for the timing analysis. Making it work with the
  [code generator from MIR](/glossary#code-generator-from-mir) is part of M8.
- **Memory for a compile:** compiling Blubber Blasen takes 1.8 GB. Less is a goal of M8.
- **Locals in [Mesen](/glossary#mesen):** Mesen shows the C source and the names of globals,
  but not the locals that live in fixed direct page slots, nor the C type of a variable.
  Better C debugging in Mesen is planned for M9: dampf first fills what the debug format has,
  then a small patch is to teach Mesen to read it, which waits until the dev kit is public.
- **Speed.** The generated code is not yet as fast as the goal of 60 % of a frame per
  [tick](/glossary#tick). During play (the ticks of the game's benchmark in which a round is
  played), Blubber Blasen takes 0.79 frames per tick with dampf, FastROM. On the console, during
  a minute of play of the playable ROM, it took 1.57 frames per tick (measured on 2026-11-18,
  before the latest optimizations). That figure is not comparable with the benchmark's: a tick
  that needs more than one frame waits for the next vertical blank, and the benchmark leaves out
  what `video.c` costs on the hardware. These figures come from one game, and your code may gain
  less: the optimizations that made Blubber Blasen's play 13 % faster made the execution tests
  only 0.9 % faster. A set of benchmarks in different styles, on which each optimization is
  measured and tuned parameters such as the inlining limit are chosen, comes with publishing,
  which is not scheduled yet. The optimizer goes on in M7: register allocation first, then the
  [`M` flag](/glossary#m-and-x-flags) followed across blocks, arrays of structs stored as one
  array per field, 8-bit index registers with arrays aligned to pages, 16-bit pointers into RAM,
  and constant shifts by a multiple of 8 as byte moves (a 32-bit shift by 16 is a loop of 16
  steps today, about 600 cycles). The fine tuning of M10 follows: the layout of blocks by a
  profile by default (it is built, but opt-in), a superoptimizer for short hot sequences,
  `switch` as a jump table, `MVN` for copies, and bank `$7F` for variables.

## Never planned

These are non-goals of dampf, not gaps that a later milestone closes.

- **Full C.** dampf compiles [the C subset](/reference/c-subset) it defines, and does not aim at
  conformance to the C standard beyond it. Some rules of the subset will stay: there is no
  recursion, no heap, and no free function pointers, so that the call graph stays complete and
  all memory use is known at compile time.
- **Other CPUs or targets.** dampf compiles for the 65816 in the SNES and nothing else. Its code
  keeps the 65816 core and the SNES target apart, but nothing is built for a second target.
- **An own debugger.** dampf leaves debugging to Mesen, which shows the C source from the
  debug file dampf writes.
- **Floating point.** `float` and `double` are rejected and will stay so.

Fast compilation is not a goal either; a game the size of Blubber Blasen compiles in seconds.

## Known differences from gcc

No difference from the [PC build](/glossary#pc-build) is known in what dampf computes, but one
bug is known: a miscompilation in an older part of the code generator, whose place is not found
yet; finding and fixing it is part of M5. A default build of Blubber Blasen does not meet it.
dampf inlines functions of up to 60 instructions, and with that limit the game's benchmark gives
the PC's checksums. With a limit of 80, the benchmark differs from the PC in 56 of 3,610 ticks;
dampf has no option to change the limit. The optimized MIR is right, and the fault stays when
the newer parts of the code generator are switched off one by one, so it lies in an older part
(instruction selection, the frame layout, the masks), on a function that the larger inlining
creates. That other code meets it with the default limit cannot be ruled out; the comparison
with the PC build described below is how it shows.

Apart from that, where dampf lays out data differently from gcc on x86-64, a program cannot see
it: dampf's pointers are 4 bytes instead of 8 and it does not pad struct fields, so a `sizeof`
whose value would differ from the PC is rejected. Only the element count
`sizeof a / sizeof a[0]` is allowed for such types, because its quotient is the same on both
sides. For `sizeof(struct pair)` with `struct pair { uint8_t a; int16_t b; }`, dampf reports,
after the place in the source:

```
error: sizeof struct pair is 3 in dampf but 4 on the PC (dampf's pointers are 4 bytes, 8 on x86-64, and dampf does not pad struct fields): the program would compute other values than its PC build. Only the element count sizeof a / sizeof a[0] is allowed for such types
```

The result of every program must match gcc on x86-64 bit for bit. The tests check that for
dampf's own test programs and for the game: the execution tests, random programs, the replays of
Blubber Blasen and its benchmark are each built with dampf and compared with gcc on the PC.
