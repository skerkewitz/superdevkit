<!-- GENERATED from doc-specs/reference/limits.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Limits

This page lists what dampf does not do: first what is planned but not built, then what dampf
will never do, and last where its results could differ from the [PC build](/glossary#pc-build).
The milestones named here (M3 for the optimizer, M4 for fine tuning) are on the
[Roadmap](/inside/roadmap), which also has their state; this page makes no promise beyond it.

## Not built yet

**The SNES platform layer for a playable ROM.** A ROM that plays a game on real hardware needs
start-up code for the game, joypad input, a vertical blank handler with DMA for sprites, tile
maps and palettes, and the 19 [PVSnesLib](/glossary#pvsneslib)-shaped calls that Blubber
Blasen's `video.c` makes. None of it exists. It belongs to no milestone: it was split off from
M2 and is planned for when the game's SNES port needs it. How the game's platform API maps onto
dampf's intrinsics is still an open question. Meanwhile dampf builds ROMs whose
[runtime](/glossary#runtime) calls one [entry function](/glossary#entry-function) and writes to
`$21FB` when it returns; the game's benchmark runs that way because it stubs out every
PVSnesLib call.

**Hints.** A [hint](/glossary#hint) is a macro that tells dampf something it cannot infer, such
as `DAMPF_RANGE(i, 0, 15)` for "`i` is always between 0 and 15", or `DAMPF_TRIPS(16)` for a loop
that runs at most 16 times. In the PC build a hint that states a fact becomes a checked
assertion, so a wrong hint is caught on the PC before it does damage on the SNES. The design is
agreed, but no hint is built, and the names and the set of macros are not decided yet; this
decision was left open in M0 and is needed by M3. The hints that request a transformation
(`DAMPF_TABLE`, `DAMPF_PIN_X` and the others) belong to M4. Meanwhile dampf infers
[value ranges](/glossary#value-range) itself from constants, operations, conditions and loops,
so much of what `DAMPF_RANGE` would say it already knows. Declared subrange types
(`DAMPF_RANGED`, `DAMPF_AS`) are not scheduled at all: they are built only when a profile shows
a hot value whose range dampf cannot infer.

**Intrinsics.** An [intrinsic](/glossary#intrinsic) gives C code access to the SNES hardware:
the hardware multiplier and divider, DMA, waiting for vertical blank, placing constant data in
ROM banks. In the PC build each is a plain function. None is built, and the roadmap ties them
to the playable ROM rather than to a milestone.

**The timing report and cycle budgets.** In M3, dampf is to compute a best and a worst case per
function, report the costliest path line by line, and fail the build when a function marked
with `DAMPF_BUDGET(n)` can take more than `n` cycles. None of that is built. What exists already
is the cycle count in the comments of the generated assembly: each instruction carries its CPU
cycles, and each straight-line C line their sum. For measured cycles per function, use
`dampf profile` ([Command line](/reference/command-line)).

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
  registers, profile-guided optimization from the replays) has not started, so the generated
  code is not yet as fast as the goal of 60 % of a [frame](/glossary#frame) per
  [tick](/glossary#tick).

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

None are known. The result of every program must match gcc on x86-64 bit for bit, and the tests
check that for every program: the execution tests, random programs, the replays of Blubber
Blasen and its benchmark are each built with dampf and compared with gcc on the PC. The
execution tests build the PC side with [UBSan](/glossary#ubsan), so a test whose result depends
on undefined behaviour fails instead of passing by chance.
