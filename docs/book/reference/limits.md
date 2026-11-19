<!-- GENERATED from doc-specs/reference/limits.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Limits

dampf is at version 0.5.0, in work: milestones M0 to M4 and M6 are concluded. dampf is the
compiler of superdevkit, a development kit for SNES games that is held in dampf's repository
until it is published. The milestones named on this page are the open ones: M5, in which Blubber
Blasen builds its ROM with the dev kit from its own repository (0.5.0, in work), M7 for Windows
and Linux, M8 for the web, M9 for the optimizer, M10 for language, platform and usability, M11
for timing and debugging, and M12 for fine tuning. Publishing is a step of its own and not
scheduled yet. They are on the [Roadmap](/inside/roadmap), which also has their state and their
order; nothing here goes beyond it. Every version before 1.0 is a pre-release, so the subset,
the command line and the files dampf writes may change from one version to the next.

## Not built yet

**Sound and save RAM in the SNES platform layer.** dampf built a playable ROM of Blubber Blasen,
and every example of the dev kit builds into a ROM you can play. The [platform
layer](/glossary#platform-layer) they are built with, `lib/snes`, is written in C and implements
the console API of `sdevkit/snes.h` ([PVSnesLib](/glossary#pvsneslib)'s calls, renamed, and the
extensions) and the main loop. Two parts are missing: sound, which Blubber Blasen does not have
yet, and save RAM, so its high scores start empty at every power-on. In M7 the console API gets
sound, by number with `sdevkit_sfx_play`, `sdevkit_music_play` and `sdevkit_music_stop`, and
save RAM, as a block of memory from `sdevkit_sram()`; both are built on the desktop and stubs on
the SNES until the SNES side has them. The playable ROM belongs to no milestone: it was split
off from M2 and built ahead of the game's own SNES port. It has been played in Mesen, not yet on
a real console; that test is part of M5. The layer also has no interrupt handler. Its loop waits
for the [vertical blank](/glossary#vertical-blank) and does the work there, because C code
called from an interrupt in the middle of a tick would overwrite the [frames](/glossary#frame)
of the functions it interrupted: dampf keeps them at fixed places. C in interrupt handlers is
planned for M10, where the functions an interrupt reaches can get a frame area of their own; how
a handler is declared and what it saves is still open.

**A platform layer for your own game.** The layer in `lib/snes` implements Blubber Blasen's
console API and nothing else. It is the dev kit's library now, and the console API is the dev
kit's own `lib/include/sdevkit/snes.h`, taken over from the game and renamed: every name a game
can use starts with `sdevkit_` or `SDEVKIT_`, so PVSnesLib's `setMode` is `sdevkit_set_mode` and
`KEY_A` is `SDEVKIT_KEY_A`. Blubber Blasen still uses the old names; a deprecated
header in `lib/include/snes/` maps them to the new ones until the game switches, and
`tools/sdevkit_rename.py` converts code from the old names.
To build a ROM of your own game, you write a platform layer in C for the library your game
calls, and dampf compiles it together with the game. Write it the way `lib/snes` is
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
to M11: on the PC it would have to count the rounds per entry into the loop, and a macro cannot
see that entry without changing the loop. Meanwhile a [`DAMPF_RANGE`](/reference/hints) on a
counter in the loop gives the loop its bound, and a loop without a counter can be given one. The
hints that request a transformation (`DAMPF_TABLE`, `DAMPF_PIN_X`, `DAMPF_LIKELY` and the
others) belong to M12. dampf also infers [value ranges](/glossary#value-range) itself from
constants, operations, conditions and loops, so a range hint is needed only where it cannot.
Declared subrange types (`DAMPF_RANGED`, `DAMPF_AS`) are listed in M12 as well, but they are
built only when a profile shows a hot value whose range dampf cannot infer.

**`restrict`.** dampf rejects `restrict` ("not supported yet"), so you cannot tell it that two
pointers never overlap. How `restrict` would be checked on the PC, and whether a hint of
dampf's own is clearer, is an open question of the roadmap; the work belongs to M10.

**Intrinsics.** An [intrinsic](/glossary#intrinsic) gives C code access to the SNES hardware:
the hardware multiplier and divider, DMA, waiting for vertical blank, placing constant data in
ROM banks. In the PC build each is a plain function. None is built; the roadmap places the
intrinsics for the hardware multipliers, the divider and DMA in M10. The platform layer of the
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
worst case "unbounded", which was so for 37 functions of Blubber Blasen when it was last
measured (2026-11-18); such a loop needs a hint, as described above. A loop over pairs of
objects gets a worst case far above what play takes, because a path that is rare in every pair,
such as a hit that kills or drops an item, is counted in every pair: the `collide` function of
Er-Tüp, one of the kit's examples, is 5.4 million CPU cycles at worst and a few thousand in
play, so a `DAMPF_BUDGET` is of no use there. Er-Tüp guards that frame with a budget measured in
its check instead. All of this is M11: master clocks in the report, the comments and the
budgets, the costliest path per C line, bounds for more loops, a hint for how often a path runs
or a bound per call chain for pair loops, the measured cycles checked, and DRAM refresh, HDMA
and interrupts at frame level. For measured cycles per function, use `dampf profile`
([Command line](/reference/command-line)).

**An own preprocessor.** dampf preprocesses the C source with `gcc -E`, so you need gcc
installed next to Java; on Windows that means installing MinGW or MSYS for this alone. In M10,
dampf gets its own C preprocessor in Java, and gcc is then needed only to develop dampf, not to
use it; `gcc -E` stays in the tests, which compare the output of dampf's preprocessor with
gcc's. Assembling and linking already need nothing else: dampf has its own assembler and linker
(M6, done ahead of M5), and [ca65 and ld65](/glossary#ca65-and-ld65) are only an alternative
path that the tests compare it with.

**Other items you might notice.** These are smaller, but each changes what you can write or
expect:

- **A small library.** dampf ships `<stdint.h>`, `<dampf.h>` and a `<string.h>` with only
  `memset` and `memcpy`, which take a byte value and a 16-bit size. Both copy byte by byte in
  C; copies with `MVN` or DMA come in M12.
- **`goto`** and labels are rejected, and that is decided (decision 4 of the subset). If real
  code needs `goto`, most likely after M5 when users bring code from elsewhere, dampf may allow
  a form that only jumps forward and out of statements, such as out of two loops to a label
  after them. That form keeps every loop with a single entry, so the loop optimizations and the
  worst-case bounds of the timing analysis still apply. Unrestricted `goto`, which can jump into
  a loop, will not be built. Meanwhile leave nested loops with a flag, or with `return` from a
  helper function, which costs nothing once dampf inlines it.
- **Function pointers in constant tables** known at compile time are allowed by the design of
  [the subset](/glossary#subset), but not built until something needs them; they are listed in
  M10.
- **Designated initializers** are rejected and stay out for now, without a milestone; the
  subset notes they are easy to add.
- **Plain `char` for text only:** dampf treats `char` as a signed 8-bit type, as on x86-64, and
  accepts arithmetic on it. The plan is to reject arithmetic on `char` beyond comparing and
  copying, so that its signedness can never change a result; this is decision 1 of the subset,
  open and to be taken in M10.
- **Hand-written assembly** linked in with `dampf build --replace` works only with the
  [naive code generator](/glossary#naive-code-generator), and a function linked in this way
  cannot declare its calling convention, [direct page](/glossary#direct-page) use and cycles
  for the timing analysis. Making it work with the
  [code generator from MIR](/glossary#code-generator-from-mir) is part of M10.
- **Warnings.** dampf truncates a constant that does not fit the type it initializes or is
  assigned to, such as `uint8_t w[] = { 420 }`, as gcc does, but silently, where gcc warns by
  default. Warnings where gcc warns by default are planned for M10, this one first. Meanwhile
  the warnings of gcc on the PC build show it.
- **Memory for a compile:** compiling Blubber Blasen took 1.8 GB. Less is a goal of M10.
- **Locals in [Mesen](/glossary#mesen):** Mesen shows the C source and the names of globals,
  but not the locals that live in fixed direct page slots, nor the C type of a variable.
  Better C debugging in Mesen is planned for M11: dampf first fills what the debug format has,
  then a small patch is to teach Mesen to read it, which waits until the dev kit is public.
- **Speed.** The goal is that the game logic of a [tick](/glossary#tick) fits in 60 % of a
  frame. On the dev kit's examples, measured since 2026-11-19, the work per tick, FastROM,
  averages 152,423 [master clocks](/glossary#master-clock) in Er-Tüp (a frame is 357,366),
  291,129 in its busiest 60 ticks, and 415,317 in Schach. Until then dampf was measured on
  Blubber Blasen, which has not reached the goal; its last figures are from 2026-11-18. During
  play (the ticks of the game's benchmark in which a round is played) it took 0.79 frames per
  tick with dampf, FastROM, and on the console, during a minute of play of the playable ROM,
  1.16 frames per tick. That figure is not comparable with the benchmark's: a tick that needs
  more than one frame waits for the next vertical blank, and the benchmark leaves out what
  `video.c` costs on the hardware. Your code may gain less from an optimization than the game
  did: the optimizations that made Blubber Blasen's play 13 % faster made the execution tests
  only 0.9 % faster, and the parameters chosen on the game, the inlining limit and the block
  order, are still to be checked on the examples. The optimizer goes on in M9: register
  allocation first, then the [`M` flag](/glossary#m-and-x-flags) followed across blocks, arrays
  of structs stored as one array per field, 8-bit index registers with arrays aligned to pages,
  16-bit pointers into RAM, and constant shifts by a multiple of 8 as byte moves (a 32-bit shift
  by 16 is a loop of 16 steps today, about 600 cycles). M9 also closes the gaps that [Writing
  fast C](/guide/writing-fast-c) measures, each of which is a rule there until its change
  retires or changes it: the width of a loop counter changes the cost of the loop, and which
  width is cheaper depends on the array it indexes (over bytes an 8-bit counter costs more, over
  words a 16-bit one), an array with a 16-bit index goes through a 24-bit pointer unless a
  `DAMPF_RANGE` bounds the index, a pointer walk costs more than indexing, a multiplication of
  two bytes calls `dampf_mul16` instead of the SNES's 8 x 8 multiplier at `$4202`, a signed
  division by a power of two keeps its rounding correction even where a `DAMPF_RANGE` proves the
  value is not negative, 32-bit operands are copied through frame slots, and a constant shift by
  more than 8 is a loop. Until then, that page says how to write around most of them. The fine
  tuning of M12 follows: the layout of blocks by a profile by default (it is built, but opt-in),
  a superoptimizer for short hot sequences, `switch` as a jump table, `MVN` for copies, and bank
  `$7F` for variables.

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
yet; finding and fixing it is part of M5. A default build of Blubber Blasen did not meet it, and
the default builds of the examples do not: their checks give the PC's checksums in every build.
dampf inlines functions of up to 60 instructions, and with that limit the game's benchmark gave
the PC's checksums. With a limit of 80, the benchmark differed from the PC in 56 of 3,610 ticks;
dampf has no option to change the limit. The optimized MIR is right, and the fault stays when
the newer parts of the code generator are switched off one by one, so it lies in an older part
(instruction selection, the frame layout, the masks), on a function that the larger inlining
creates. That other code meets it with the default limit cannot be ruled out; the comparison
with the PC build described below is how it shows.

Apart from that, where dampf lays out data differently from gcc on x86-64, a program cannot see
it: dampf's pointers are 4 bytes instead of 8 and it does not pad struct fields, so a `sizeof`
whose value would differ from the PC is rejected. Only the element count
`sizeof a / sizeof a[0]` is allowed for such types, because its quotient is the same on both
sides. A `memcpy` of a struct that gcc pads is therefore written with its size as a constant,
or as a struct assignment. For `sizeof(struct pair)` with `struct pair { uint8_t a; int16_t b; }`,
dampf reports, after the place in the source:

```
error: sizeof struct pair is 3 in dampf but 4 on the PC (dampf's pointers are 4 bytes, 8 on x86-64, and dampf does not pad struct fields): the program would compute other values than its PC build. Only the element count sizeof a / sizeof a[0] is allowed for such types
```

The result of every program must match gcc on x86-64 bit for bit. The tests check that for
dampf's own test programs and for the dev kit's examples: the execution tests and random programs
are each built with dampf and compared with gcc on the PC, and every example plays its check on
the SNES and on the desktop, which is built with gcc, with a checksum of the game's state after
every tick.
