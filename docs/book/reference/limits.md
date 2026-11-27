<!-- GENERATED from doc-specs/reference/limits.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# Limits

superdevkit, and dampf with it, is at version 0.10.1: milestones M0 to M10 are concluded. dampf
is the compiler of superdevkit, a [development kit](/glossary#dev-kit) for SNES games. The
milestones named on this page are the open ones: M11 for timing and debugging, and M12 for fine
tuning.
Some items below belong to no milestone at all. They are on the [Roadmap](/inside/roadmap),
which also has their state and their order; nothing here goes beyond it. Every version before
1.0 is a pre-release, so [the subset](/glossary#subset), the command line and the files dampf
writes may change from one version to the next.

## Not built yet

**Music on the SNES.** Every example of the kit builds into a ROM you can play. The kit's
library on the SNES, `lib/snes`, is written in C and implements the console API of
`sdevkit/snes.h` ([Console API](/reference/console-api)) and the main loop. Sound effects and
save RAM are built on every [target](/glossary#target) ([Graphics and
sound](/guide/graphics-and-sound)); music is not built on the SNES. There `sdevkit_music_play`
and `sdevkit_music_stop` do nothing, and dampf removes the calls, while the desktop and the web
play the track from its file. Music on the SNES needs a driver of its own on the SPC700, which
plays a sequence of notes beside the effects; it is not scheduled.

**More of the sound effects.** The effects have no ADSR envelopes and no echo, and
`sdevkit_sfx_play` takes no volume or pan per call: an effect plays at the volume its sample has
in `assets/sfx/sfx.json`. Uploading the effects to the SPC700 when the game starts is slow,
about 350 bytes a frame, so Schach's 13.8 KB take 40 frames after the splash screen; a loader
that takes three bytes per exchange would be about three times faster, and is not built. None of
these has a milestone. During the upload the [PPU](/glossary#ppu) keeps showing its picture, so a
game that draws its title and switches the screen on before it calls `sdevkit_sfx_init` shows the
title instead of a black screen; the picture stands still and the [joypads](/glossary#joypad)
are not read until the upload ends.

**Only the console API, and only what both sides draw.** A game calls the console API and
nothing else. The desktop and web builds check that the game's objects call only the kit's
functions, because [Rachael](/glossary#rachael), the SNES rebuilt in C that those builds run on,
cannot see a write that goes around her. Of the hardware, a game can use only what both
`lib/snes` and Rachael draw. Rachael draws modes 0 to 4 and 7, with 256-colour tiles, direct
colour, offsets per tile, mosaic and mode 7's second layer (EXTBG); she follows `VMAIN`'s address
remapping and reads [VRAM](/glossary#vram), [CGRAM](/glossary#cgram) and [OAM](/glossary#oam)
back by [DMA](/glossary#dma). The example `modes` shows each of them. A register write she does
not draw is counted, and the desktop's check fails on it, since the SNES would show something
else there: modes 5 and 6, and `SETINI`'s high resolution, overscan and interlace, which would
change the size of the picture, and indirect [HDMA](/glossary#hdma). That is decided: a feature
that Rachael only reports is not usable, and a feature a game needs is built on both sides in
the same change. These wait for a game that needs them and have no milestone; meanwhile direct
HDMA reaches the same pictures as indirect HDMA, with larger tables that hold the data written
out per entry.

**Interrupt code.** A game can have an IRQ in C (an interrupt request, raised here by the CPU's
timer before a row of the picture): `game_irq` from `sdevkit/irq.h`. A game that publishes its
data with `sdevkit/publish.h`, a copy made after every tick that interrupt code reads, gets the
kit's NMI (the interrupt at the start of each [vertical blank](/glossary#vertical-blank)), which
runs the game's vertical blank code ([Console API](/reference/console-api), [The C
subset](/reference/c-subset)). Four things are not built:

- **A function written in assembly** (`DAMPF_ASM` or `--replace`) cannot be reached from an
  interrupt handler: dampf stops with an error that names the function and the handler. Its
  convention assumes the main code's [direct page](/glossary#direct-page) and the places of its
  locals, while an interrupt has its own, and nothing stops its code from using the CPU's
  multiplier, which interrupt code must not touch.
  No milestone changes this; meanwhile interrupt code calls only C.
- **An IRQ in the middle of a row.** `sdevkit_irq_line` takes a row and no horizontal position,
  because Rachael draws the picture row by row and has no time inside a row. A handler writes
  after `sdevkit_irq_wait_hblank`, and its writes show from the row it was set for. No milestone
  adds a horizontal position.
- **dampf's check of the IRQ's window.** After the wait a handler has about 25 CPU cycles for
  its register writes, about 19 with one HDMA channel of one byte on the line; a scroll register
  takes 17. A handler that takes longer writes late and may tear the row, and Rachael has no
  time to see it, so today only a run in [Mesen](/glossary#mesen) shows it. M11 is to find the
  cycles from the wait to the handler's last register write and fail the build when they exceed
  that budget, and M12 is to give more room with faster code for a register write (7 cycles
  less in `sdevkit_reg_write_twice`). Meanwhile, what does not fit after the wait goes into HDMA
  or into a second IRQ row.
- **The optional layers in the kit's NMI.** A game on the optional layers (`sdevkit_commit`, the
  queue, the shadow registers, the sprite list) keeps its `game_vblank` in the main loop, since
  their state is the tick's, so nothing writes its picture while a [tick](/glossary#tick)
  overruns. The layers could publish their own state as a game publishes its struct; this has no
  milestone.

**Hints.** A [hint](/glossary#hint) is a macro that tells dampf something it cannot infer, such
as `DAMPF_RANGE(i, 0, 15)` for "`i` is always between 0 and 15". In the [PC
build](/glossary#pc-build) a hint that states a fact becomes a checked assertion, so a wrong
hint is caught on the PC before it does damage on the SNES. Three hints from `<dampf.h>` are
built, `DAMPF_RANGE`, `DAMPF_BUDGET` (a cycle budget for a function, see [Exact
timing](/inside/timing)) and `DAMPF_ASM` (hand-written assembly in place of a function's body);
all three are described in [Hints](/reference/hints). The names of the
others are decided, but none of them is built, so you cannot use them yet. `DAMPF_TRIPS(16)`,
for a loop that runs at most 16 times, is postponed to M11: on the PC it would have to count the
rounds per entry into the loop, and a macro cannot see that entry without changing the loop.
Meanwhile a [`DAMPF_RANGE`](/reference/hints) on a counter in the loop gives the loop its bound,
and a loop without a counter can be given one. The hints that request a transformation
(`DAMPF_TABLE`, `DAMPF_PIN_X`, `DAMPF_LIKELY` and the others) belong to M12. dampf also infers
[value ranges](/glossary#value-range) itself from constants, operations, conditions and loops,
so a range hint is needed only where it cannot. Declared subrange types (`DAMPF_RANGED`,
`DAMPF_AS`) are listed in M12 as well, but they are built only when a profile shows a hot value
whose range dampf cannot infer.

**`restrict`.** dampf rejects `restrict`, so you cannot tell it that two pointers never
overlap, and no milestone adds it: with every pointer treated as `restrict`, the most such a hint
could promise, the work per tick of the kit's examples changed by 0.01 % at most, since
[pointer compression](/glossary#pointer-compression) and inlining already tell dampf where the
pointers point.

**Intrinsics and the divider.** An [intrinsic](/glossary#intrinsic) would give C code access to
the SNES hardware. dampf has none, and none are planned: it multiplies two variables on the CPU's
8 x 8 multiplier itself, without a call when both are bytes, a division by a constant becomes a
multiplication, DMA and the wait for the vertical blank are calls of the console API, and dampf
places constant data in its ROM banks itself. The kit's library on the SNES reaches the I/O
registers through pointers to `volatile` memory, and casts a pointer to an integer to get the
SNES address a DMA channel copies from ([The C subset](/reference/c-subset)). The CPU's hardware
divider is not used yet. M12 is to use it, chosen by dampf, for a division by a variable whose
divisor its value ranges prove to fit a byte, once a measured program divides by variables
on a hot path.

**Timing in master clocks and outside the CPU.** dampf computes a best and a worst case for
every function, writes them to the [timing report](/glossary#timing-report) and checks
`DAMPF_BUDGET` at build time ([Exact timing](/inside/timing)), but it counts in CPU cycles only.
[Master clocks](/glossary#master-clock), the unit that includes the speed of each memory region
([FastROM, SlowROM](/glossary#fastrom-and-slowrom), WRAM, I/O), are not counted yet. Nor does
dampf account for the time lost outside the CPU: DRAM refresh, HDMA and interrupts. The report
gives each interrupt a line of its own, its entry stub and handler, but a function's bounds leave
out the interrupts that come while it runs, and budgets for a whole frame, which would add the
cost of the handlers, are not built; how such a budget learns which HDMA channels a game has on
is open, since a game turns them on at run time. Nor is a check of the measured cycles of
budgeted functions in the emulator built. The comments of
the generated assembly carry the CPU cycles of each instruction, and each straight-line C line
their sum; a line with branches does not show its best and worst case yet, and neither the
report nor a budget error shows the costliest path line by line. A loop whose bound dampf cannot
find in the code makes a worst case "unbounded", which was so for 37 functions of [Blubber
Blasen](/glossary#blubber-blasen) when it was last measured (2026-11-18); such a loop needs a
hint, as described above. A loop over pairs of objects gets a worst case far above what play
takes, because a path that is rare in every pair, such as a hit that kills or drops an item, is
counted in every pair: the `collide` function of Er-Tüp, one of the kit's examples, is 5.4
million CPU cycles at worst and a few thousand in play, so a `DAMPF_BUDGET` is of no use there.
Er-Tüp guards that frame with a budget measured in its check instead. All of this is M11: master
clocks in the report, the comments and the budgets, the costliest path per C line, bounds for
more loops, a hint for how often a path runs or a bound per call chain for pair loops, the
measured cycles checked, the IRQ's window checked (see above), and DRAM refresh, HDMA and
interrupts at frame level. For measured
cycles per function, use `dampf profile` ([Command line](/reference/command-line)).

**The preprocessor's subset.** dampf preprocesses the C source with a preprocessor of its own,
so building for the SNES needs nothing besides a release zip, which brings its own Java; from
the repository it needs a JDK 25 or newer. Like the language, the preprocessor is a subset
of C99's: it has what the kit's code and Blubber Blasen use, with macro expansion as the standard
describes it, and rejects the rest with an error that names it. Rejected are `__VA_OPT__`,
`#line`, `#include_next`, an `#include` of a macro, `#warning`, `#ident` and gcc's other
directives, named variadic parameters (`args...`), `__COUNTER__`, `__DATE__`, `__TIME__`,
`_Pragma` and `__has_include`; trigraphs, digraphs and universal character names are not
handled. No milestone adds them. For a line `#warning "old"`, dampf reports, after the place in
the source:

```
error: #warning is not supported by dampf's preprocessor
```

The desktop and web builds preprocess with their own C compiler, which accepts these, so code
that uses one builds there and stops only in the SNES build. What the preprocessor has is listed
in [The C subset](/reference/c-subset). Assembling and linking need nothing else either: dampf
has its own assembler and linker, and [ca65 and ld65](/glossary#ca65-and-ld65) are only an
alternative path that the tests compare it with.

**Other systems, a bundle, and a real console.** superdevkit builds a game for the SNES, Linux,
Windows and the web ([The superdevkit command](/reference/superdevkit)). macOS is not scheduled:
raylib runs there, but nobody can test it now. Nor is `superdevkit bundle`, one folder with every
target, ready to publish. The development tools in the desktop's window are built
([The desktop](/guide/the-desktop)), with two limits. Shift+F6, a tick back in a replay, starts
the program again and replays up to the tick before, so it is not on the web. A recording made
with `--seed` or `--set` does not play in dampf's emulator, which refuses it because the SNES has
neither. The ROMs themselves have run in [Mesen](/glossary#mesen) and in dampf's emulator, never
on a real console: a run on a console or a flash cartridge, on NTSC and PAL, with the DMA margin
measured there, is not scheduled until there is hardware.

**Other items you might notice.** These are smaller, but each changes what you can write or
expect:

- **A small library.** dampf ships `<stdint.h>`, `<dampf.h>` and a `<string.h>` with only
  `memset` and `memcpy`, which take a byte value and a 16-bit size. Both copy byte by byte in
  C; copies with `MVN` come in M12.
- **`goto`** and labels are rejected, and that is decided (decision 4 of the subset). If real
  code needs `goto`, dampf may allow a form that only jumps forward and out of statements, such
  as out of two loops to a label after them. That form keeps every loop with a single entry, so
  the loop optimizations and the worst-case bounds of the timing analysis still apply.
  Unrestricted `goto`, which can jump into a loop, will not be built. Meanwhile leave nested
  loops with a flag, or with `return` from a helper function, which costs nothing once dampf
  inlines it.
- **Function pointers in constant tables** known at compile time are allowed by the design of
  the subset, but not built and not scheduled: no example and no game uses them, and a `switch`
  on an enum does the same. They were built once and not taken in, and are taken up when a game
  asks for them.
- **Designated initializers** are rejected and stay out for now, without a milestone; the
  subset notes they are easy to add.
- **Pointers** stored in a struct field, a global or an array take 4 bytes. Within a function
  and through parameters, dampf already turns a pointer into one object into a 16-bit offset
  (pointer compression). 16-bit pointers into RAM, decision 3 of the subset, belong to M12: a pointer that provably points only into
  bank `$7E` could take 2 bytes, with cheaper loads. A program cannot see the size, so this is
  an optimization, decided and built when a measured program shows the gain.
- **Hand-written assembly** in place of a function's body (`DAMPF_ASM`, see
  [Hints](/reference/hints)) works with both code generators and keeps the timing exact, but the
  function must be a leaf: it calls nothing, its parameters and result are integers and
  pointers, and `MVN` and `MVP` are rejected in it, since dampf does not follow them yet. dampf
  runs the assembly against the C body in every build, but that check does not see writes to
  I/O registers, and with the [naive code generator](/glossary#naive-code-generator) it does not
  run. None of this has a milestone.
- **Warnings.** dampf is to warn where gcc warns by default, and goes on with the build; it has
  one such warning so far: gcc's `-Woverflow` on a constant that does not fit the type it is
  converted to, such as `uint8_t w[] = { 420 }`. gcc's warning about an overflow while it folds
  a constant, and its other default warnings, are not built yet and have no milestone; the
  warnings of the desktop build show them.
- **Memory for a compile:** compiling Blubber Blasen took 1.8 GB. Less has no milestone: dampf's
  own speed and memory are tuned once it is more or less feature complete.
- **Locals in Mesen:** Mesen shows the C source and the names of globals, but not the locals
  that live in fixed direct page slots, nor the C type of a variable. Better C debugging in
  Mesen is planned for M11: dampf first fills what the debug format has, then a small patch is
  to teach Mesen to read it.
- **Speed.** The goal is that the game logic of a tick fits in 60 % of a frame. On the dev
  kit's examples, measured on 2026-11-20, the work per tick, FastROM, averages 150,410
  [master clocks](/glossary#master-clock) in Er-Tüp (a frame is 357,368), 286,177 in its
  busiest 60 ticks, and 416,847 in Schach. Until 2026-11-19 dampf was measured
  on Blubber Blasen, which has not reached the goal; its last figures are from 2026-11-18.
  During play (the ticks of the game's benchmark in which a round is played) it took 0.79
  frames per tick with dampf, FastROM, and on the console, during a minute of play of the
  playable ROM, 1.16 frames per tick. That figure is not comparable with the benchmark's: a
  tick that needs more than one frame waits for the next vertical blank, and the benchmark
  leaves out what `video.c` costs on the hardware. Your code may gain less from an optimization
  than the game did: the optimizations that made Blubber Blasen's play 13 % faster made the
  execution tests only 0.9 % faster, and the block order, chosen on the game, is still to be
  checked on the examples. The optimizer goes on in M12,
  the fine tuning. Register allocation is built in part (values go to the X or Y register by
  cost); left are the [`M` flag](/glossary#m-and-x-flags) followed across blocks in general,
  arrays of structs stored as one array per field once a measured loop gains from it, 8-bit
  index registers with arrays aligned to pages, a calling convention with the first argument
  in A, and 32-bit arithmetic and ordered comparisons, which still go through frame slots;
  [Writing fast C](/guide/writing-fast-c) measures that gap and says how to write around it.
  Also in M12 are the layout of blocks by a profile by default (it is built, but opt-in), a
  superoptimizer for short hot sequences, `MVN` for copies, profile-guided inlining, a tuning
  option that tries the inlining limit and the block order on your own workload, and bank `$7F`
  for variables.

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

No difference from the [PC build](/glossary#pc-build) is known in what dampf computes, and no
miscompilation is known. The last two were found and fixed during M5, each with an execution
test that keeps it fixed: a loop counter kept in the X register was stepped before a later store
whose address was formed from its old value, and a branch whose two sides each start a loop
wrote both start values before branching while the frame layout let the two counters share a
place. The second showed first when dampf inlined functions of up to 80 instructions instead of
its general limit of 60, and later in a default build of Blubber Blasen; dampf has no option
to change the limit.

Apart from that, where dampf lays out data differently from gcc on x86-64, a program cannot see
it: dampf's pointers are 4 bytes instead of 8, its enum objects take the smallest width that
holds their enumerators, 1 or 2 bytes for most enums instead of 4, and it does not pad struct
fields, so a `sizeof` whose value would differ from the PC is rejected. Only the element count
`sizeof a / sizeof a[0]` is allowed for such types, because its quotient is the same on both
sides. A struct that gcc pads is therefore copied by a struct assignment, since no constant
size passed to `memcpy` is right on both sides. For `sizeof(struct pair)` with
`struct pair { uint8_t a; int16_t b; }`, dampf reports, after the place in the source:

```
error: sizeof struct pair is 3 in dampf but 4 on the PC (dampf's pointers are 4 bytes, 8 on x86-64, its enum objects 1 or 2 bytes for most enums, 4 on x86-64, and dampf does not pad struct fields): the program would compute other values than its PC build. Only the element count sizeof a / sizeof a[0] is allowed for such types
```

The result of every program must match gcc on x86-64 bit for bit. The tests check that for
dampf's own test programs and for the dev kit's examples: the execution tests and random programs
are each built with dampf and compared with the PC build, made by clang, whose results on x86-64
are gcc's wherever the subset leaves C no choice, and every example plays its check on the SNES,
on the desktop and in the browser, with a checksum of the game's state after every tick.
