<!-- GENERATED from doc-specs/reference/limits.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Limits

This page lists what dampf does not do: first what is planned but not built, then what dampf
will never do, and last where its results could differ from the [PC build](/glossary#pc-build).
The milestones named here (M3 for the optimizer, M4 for fine tuning) are on the
[Roadmap](/inside/roadmap), which also has their state; this page makes no promise beyond it.

## Not built yet

**Sound and save RAM in the SNES platform layer.** dampf builds a playable ROM of Blubber
Blasen with `tools/build_game_rom.sh`. The platform layer in `platform/snes`, written in C,
implements the 19 [PVSnesLib](/glossary#pvsneslib)-shaped calls that the game's `video.c` makes,
the rest of the game's console API and the main loop. Two parts are missing: sound, which the
game does not have yet, and save RAM, so the high scores start empty at every power-on. The
playable ROM belongs to no milestone: it was split off from M2 and built ahead of the game's own
SNES port. The layer also has no interrupt handler. Its loop waits for the vertical blank and
does the work there, because C code called from an interrupt in the middle of a tick would
overwrite the [frames](/glossary#frame) of the functions it interrupted: dampf keeps them at
fixed places.

**Hints.** A [hint](/glossary#hint) is a macro that tells dampf something it cannot infer, such
as `DAMPF_RANGE(i, 0, 15)` for "`i` is always between 0 and 15", or `DAMPF_TRIPS(16)` for a loop
that runs at most 16 times. In the PC build a hint that states a fact becomes a checked
assertion, so a wrong hint is caught on the PC before it does damage on the SNES. Two hints
from `<dampf.h>` are built, `DAMPF_RANGE` and `DAMPF_BUDGET` (see the timing report below); the
names of the others are decided, but none of them is built. `DAMPF_TRIPS` is postponed: on the
PC it would have to count the rounds per entry into the loop, and a macro cannot see that entry
without changing the loop. Meanwhile a `DAMPF_RANGE` on a counter in the loop gives the loop its
bound, and a loop without a counter can be given one. The hints that request a transformation
(`DAMPF_TABLE`, `DAMPF_PIN_X` and the others) belong to M4. dampf also infers
[value ranges](/glossary#value-range) itself from constants, operations, conditions and loops,
so a range hint is needed only where it cannot. Declared subrange types
(`DAMPF_RANGED`, `DAMPF_AS`) are not scheduled at all: they are built only when a profile shows
a hot value whose range dampf cannot infer.

**Intrinsics.** An [intrinsic](/glossary#intrinsic) gives C code access to the SNES hardware:
the hardware multiplier and divider, DMA, waiting for vertical blank, placing constant data in
ROM banks. In the PC build each is a plain function. None is built, and the roadmap places
them in no milestone. The platform layer of the playable ROM needed none: it reaches the I/O
registers through pointers to `volatile` memory, and casts a pointer to an integer to get the
SNES address a DMA channel copies from ([The C subset](/reference/c-subset)). Meanwhile,
platform code reaches the hardware the same way.

**The timing report and cycle budgets.** dampf already computes a best and a worst case in CPU
cycles for every function, from its entry to its return with calls included, and `dampf build`
writes them to `<rom>.timing.txt`, most expensive first. A loop whose bound dampf cannot find in
the code makes the worst case "unbounded"; in Blubber Blasen that is so for 38 functions, and
the report names, after each of them, the first loop without a bound or else the call that makes
it so. The loops left run over memory (`while (s[n]) n++;`), have a counter that is not stepped
by a constant, or could really run forever in C; they need a hint, as described above. A
function marked with `DAMPF_BUDGET(n)` fails the build when it can take more than `n` CPU cycles
or has no worst case, and `dampf build` warns when its worst case is above 90 % of the budget.
The rest of the timing work of M3 is not built: the costliest path line by line in the report
and in the budget errors, and cycle counts in master cycles that include the speed of each
memory region. Budgets for a whole frame, which add the cost of interrupt handlers, are not
built either, nor a check of the measured cycles of budgeted functions in the emulator. The
comments of the generated assembly carry the CPU cycles of each instruction, and each
straight-line C line their sum; a line with branches does not show its best and worst case yet.
For measured cycles per function, use `dampf profile` ([Command line](/reference/command-line)).

**Other items you might notice.** These are smaller, but each changes what you can write or
expect:

- **`goto`** is rejected. Whether a restricted form is allowed later is an open question of the
  roadmap; the recommendation is to forbid it until a use appears.
- **Function pointers in constant tables** known at compile time are allowed by the design of
  [the subset](/glossary#subset), but not built until something needs them.
- **Designated initializers** stay out for now; the subset notes they are easy to add.
- **Plain `char` limited to text:** dampf treats `char` as a signed 8-bit type, as on x86-64,
  but does not yet reject arithmetic on it beyond comparing and copying.
- **Locals in [Mesen](/glossary#mesen):** how Mesen shows locals that live in fixed
  [direct page](/glossary#direct-page) slots is an open question of the roadmap.
- **Speed.** The fine tuning of M4 (more optimizations, page-aligned arrays with 8-bit index
  registers, profile-guided optimization from the replays) has only started with the layout of
  blocks by a profile, so the generated code is not yet as fast as the goal of 60 % of a
  [frame](/glossary#frame) per [tick](/glossary#tick): during play, Blubber Blasen takes 0.92
  frames per tick with dampf (with FastROM; 1.31 without). On the console, during a minute of play of the playable ROM, it takes 2.28 frames per tick,
  because a tick that needs more than one frame waits for the next vertical blank and the
  benchmark leaves out what `video.c` costs on the hardware.

## Never planned

These are non-goals of dampf, not gaps that a later milestone closes.

- **Full C.** dampf compiles [the C subset](/reference/c-subset) it defines, and does not aim at
  conformance to the C standard beyond it. Some rules of the subset will stay: there is no
  recursion, no heap, and no free function pointers, so that the call graph stays complete and
  all memory use is known at compile time.
- **Other CPUs or targets.** dampf compiles for the 65816 in the SNES and nothing else. Its code
  keeps the 65816 core and the SNES target apart, but nothing is built for a second target.
- **An own assembler, linker or debugger.** dampf writes assembly for
  [ca65](/glossary#ca65-and-ld65), links with ld65 and leaves debugging to Mesen.
- **Floating point.** `float` and `double` are rejected and will stay so.

Fast compilation is not a goal either; a game the size of Blubber Blasen compiles in seconds.

## Known differences from gcc

None are known. Where dampf lays out data differently from gcc on x86-64, a program cannot see
it: dampf's pointers are 4 bytes instead of 8 and it does not pad struct fields, so a `sizeof`
whose value would differ from the PC is rejected. Only the element count
`sizeof a / sizeof a[0]` is allowed for such types, because its quotient is the same on both
sides. For `sizeof(struct pair)` with `struct pair { uint8_t a; int16_t b; }`, dampf reports,
after the place in the source:

```
error: sizeof struct pair is 3 in dampf but 4 on the PC (dampf's pointers are 4 bytes, 8 on x86-64, and dampf does not pad struct fields): the program would compute other values than its PC build. Only the element count sizeof a / sizeof a[0] is allowed for such types
```

The result of every program must match gcc on x86-64 bit for bit, and the tests
check that for every program: the execution tests, random programs, the replays of Blubber
Blasen and its benchmark are each built with dampf and compared with gcc on the PC. The
execution tests build the PC side with [UBSan](/glossary#ubsan), so a test whose result depends
on undefined behaviour fails instead of passing by chance.
