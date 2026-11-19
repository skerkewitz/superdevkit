<!-- GENERATED from doc-specs/inside/roadmap.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->

# Roadmap

dampf is built in milestones for [Blubber Blasen](/glossary#blubber-blasen), the game it is
developed for. Until 2026-11-19 each was measured against a copy of the game's code, since then
against the dev kit's examples. The goal of the whole project is that the game logic of the
game's busiest scene fits in at most 60 % of a [frame](/glossary#frame). This page says where
dampf stands, what comes next and what is still open. Unlike the other pages of the book, it
also describes plans. What is not built yet, listed for users, is on
[Limits](/reference/limits).

## Where dampf stands

dampf is at version 0.5.0, in work; M0 to M4 and M6 were concluded with 0.4.0 on 2026-11-18.
dampf compiles all of Blubber Blasen and assembles and links the ROM itself. At 0.4.0 it gave
the results of the [PC build](/glossary#pc-build) in the game's benchmark and replays, on the
SNES and in a playable ROM. While the game was played it took 0.79 frames per
[tick](/glossary#tick) against a goal of 0.6, and dampf's code was faster than
[Calypsi](/glossary#calypsi)'s. Since 2026-11-19 the dev kit's own examples are
dampf's test cases and its benchmark, and the game's code is no longer in this repository.

dampf is the compiler of superdevkit, a [development kit](/glossary#dev-kit) for SNES games, and
this repository is superdevkit's. M5 is in work, as version 0.5.0: Blubber Blasen builds its ROM
with the dev kit, from its own repository.

## Versions

The version is the dev kit's. It is set in one place, `build.gradle.kts`, and `dampf --version`
prints it. Milestone N is released as version 0.N.0, and fixes between two milestones raise the
last number. M6 was done ahead of M5 and is part of 0.4.0, so there is no 0.6.0.

Every version before 1.0 is a pre-release: the [subset](/glossary#subset), the command line and
the files dampf writes may change from one version to the next. Publishing is a step of its
own and not scheduled yet (see [Publishing](#publishing-not-scheduled)), so 0.5.0, the end of
M5, is not a public version. What 1.0 needs is not decided.

Versions follow the milestones rather than what changed, as semantic versioning would have
them, because before 1.0 nearly every milestone breaks something, and such numbers would say
little more than the milestone does. A milestone's version is therefore known before it starts.
If a milestone is split or merged, the numbers of the ones after it change; numbers already
released stay.

## Milestones

| Milestone | Goal | Version | State |
| --- | --- | --- | --- |
| M0 | Define the subset of C from what the game uses | 0.4.0 | Done 2026-11-17 |
| M1 | End to end: a small function compiled, assembled, linked, run in a test, stepped through in [Mesen](/glossary#mesen) | 0.4.0 | Done 2026-11-17 |
| M2 | Naive but complete: all of Blubber Blasen compiles, the benchmark and the replays give the PC's checksums, cycles measured and compared with Calypsi | 0.4.0 | Done 2026-11-17 |
| M3 | The big levers: [MIR](/glossary#mir), [LIR](/glossary#lir) and the [code generator from MIR](/glossary#code-generator-from-mir), static timing and budgets, [value ranges](/glossary#value-range) and [pointer compression](/glossary#pointer-compression); faster than Calypsi | 0.4.0 | Done 2026-11-18 |
| M4 | Fine tuning, started early: the block layout by a profile, peephole rules, loop and branch optimizations | 0.4.0 | Done 2026-11-18 |
| M6 | Standing alone: dampf's own assembler and linker | 0.4.0 | Done 2026-11-18 |
| M5 | Blubber Blasen builds its ROM with the dev kit, from its own repository | 0.5.0 | In work |
| M7 | Windows and Linux: Rachael and the desktop frontend in the dev kit, the `superdevkit` command with `init`, `build` and `run`; the examples and Blubber Blasen run on the desktop from the dev kit | 0.7.0 | In work (started during M5) |
| M8 | The web: the same games in the browser; Blubber Blasen drops its own PC side | 0.8.0 | Planned |
| M9 | The optimizer: what was left of M3, register allocation first | 0.9.0 | Planned |
| M10 | Language, platform and usability: C in interrupts, [intrinsics](/glossary#intrinsic), the open subset decisions, dampf's own preprocessor | 0.10.0 | Planned |
| M11 | Timing and debugging: [master clocks](/glossary#master-clock), bounds for more loops, locals and types in Mesen | 0.11.0 | Planned |
| M12 | Fine tuning: what was left of M4, until the frame budget is met | 0.12.0 | Planned |

M3 and M4 were concluded at version 0.4.0 although only part of their goals was reached: dampf
is faster than Calypsi, but the frame budget is not met yet. Too much had stayed open in them
while too little was planned after them, so what was left moved to M9 and M12. The order after
M5 follows the likely gain and what each milestone needs from the one before; it is revised
when M5 is done.

### M5: Blubber Blasen builds with the dev kit

M5 is done when Blubber Blasen builds its SNES ROM from its own repository with a local install
of the dev kit, and that ROM plays the game's recordings with the PC's checksums, and when the
examples `hello`, CatchMe and Steinschlag build for the SNES and play in Mesen. No release is
needed for that while the repository is private. Its work falls into five parts:

- **Correctness first**, since the game will rely on the code. The miscompilation described
  under [Risks and open questions](#risks-and-open-questions) is found and fixed, with an
  execution test. Random programs and execution tests are added that reach the patterns the
  optimizer looks for; there are 31 execution tests today. The playable ROM runs on a real
  console, and the DMA margin of the [platform layer](/glossary#platform-layer) is measured
  there.
- **A compiler a game can call.** Done: dampf reports every error of a run, at most one per
  top-level declaration. Its options can be kept in an argument file, given by `--config` or
  `@file`, in which a folder stands for the `.c` files directly in it, so that a game checks in
  one file per build. dampf ships a `<string.h>` with `memset` and `memcpy`. The test of this
  step found a miscompilation, which is fixed: a loop counter kept in the X register was stepped
  before a later store whose address was formed from its old value. It was not the open one
  described below. Done: a converter for PNG images, `tools/png2snes.py`, and three examples,
  hello, CatchMe and Steinschlag, which play in Mesen without losing a byte and are built in
  every build.
- **The dev kit in this repository.** Done: the platform layer has become the dev kit's library
  in `lib/snes`, and the console API the dev kit owns is `lib/include/sdevkit/snes.h`. A table
  shows per function and platform whether it is built or a stub, and a test checks that every
  function of the API exists in `lib/snes` and in the table. The table also gives what a call
  costs on the SNES, best to worst case in CPU cycles, taken from the
  [timing report](/glossary#timing-report) by a test that fails when the numbers are not
  current. Two guides sit next to the library: `lib/C_STYLE.md`, how to write a game's C for
  dampf, whose speed rules are measurements a test checks, and `lib/SNES_HARDWARE.md`, what the
  SNES allows and punishes and which of it the dev kit has. The main loop (`lib/snes/main.c`)
  is the library's, and the frame rhythm it calls (`game_init`, `game_tick`, `game_vblank`, the
  pad bits) is declared in `lib/include/sdevkit/loop.h`. Done: every game starts with the dev
  kit's splash screen (`lib/splash`), which the main loop shows before `game_init`, on the SNES
  and on the desktop: the logo fades in, stays three seconds and fades out, and a button ends it
  once it has faded in. Done: every name a game can use starts
  with `sdevkit_` or `SDEVKIT_` (`oamSetXY` became `sdevkit_oam_set_xy`, `KEY_A` became
  `SDEVKIT_KEY_A`), so that it cannot clash with raylib, libc or the library's own helpers, and
  the desktop build checks that a game calls nothing else. `tools/sdevkit_rename.py` converts
  code from the old names, and a deprecated header, `lib/include/snes/`, keeps them for Blubber
  Blasen until the game switches. Done: the scripts are now in `dev/`, for developing the dev
  kit, since nothing in them was for other games. Done: the snapshot of Blubber Blasen moved to
  `snapshots/blubberblasen`, and on 2026-11-19 it left the repository (see
  [Publishing](#publishing-not-scheduled)). Examples become projects of their own, each with a
  README, an argument file, its sources and assets and its own build folder.
- **The book.** One book serves the whole dev kit. First the existing pages move into folders
  for dampf's part, `reference/compiler/` and `inside/compiler/`; then the user guide becomes
  the dev kit's, with getting started from an example project, and the reference gets the
  console API with its table of built and stub per platform, in `reference/api/`.
- **The game's side**, done in the game's own repository: the handover, after which the game
  builds with the dev kit and an argument file of its own.

### After M5

**M7, Windows and Linux**, brings the dev kit to the desktop, for prototyping a game quickly. It
started early, during M5, because prototyping and checking a game is much quicker on the
desktop. [Rachael](/glossary#rachael), the SNES rebuilt in portable C from Blubber Blasen's
implementation of the console API on the PC, is in the dev kit as `lib/rachael`, with a desktop
frontend built on raylib in `lib/desktop`, and the examples give the same picture on
the SNES and on the desktop, pixel for pixel. The frontend is built for Linux with gcc and for
Windows with MSVC, both from WSL; the Windows build plays with the gamepads WSL does not see,
and gives the same checksums and pictures as the Linux build. Rachael counts every byte the SNES
would lose and every register write she does not draw, such as a mode other than 1 or mosaic,
where the SNES would show something else; the desktop check fails on either. Every build also
plays each example's check in dampf's emulator and on the desktop and compares a checksum of the
game's state after every tick, so a difference shows with the first tick it appears in, without
Mesen. The checksum covers the sprite table and the bytes a game names with
`sdevkit_dbg_hash`, which on the SNES only hands the address and size to the checker, about 80
cycles a call.
Still to come are Rachael's tests from the game
and the `superdevkit` command. The console API gains sound, save RAM
and a debug view. A game plays sound by number
with `sdevkit_sfx_play(id)`, `sdevkit_music_play(track)` and `sdevkit_music_stop()`, the numbers
taken from the assets its project file lists. Save RAM is a block of memory, `sdevkit_sram()`,
as on a cartridge, and the dev kit saves it when it changed and on exit. The debug view has
standard views such as pause, step and the SNES's VRAM, and a game registers what it wants to
change with `sdevkit_dbg_toggle`, `sdevkit_dbg_range`, `sdevkit_dbg_color` and
`sdevkit_dbg_action` and draws boxes per frame with `sdevkit_dbg_box`. All of this is built on
the desktop and a stub on the SNES, where the debug functions are empty, so the game code needs
no `#ifdef`. The `superdevkit` command, written in Java and shipped with dampf, builds a project
described by its `superdevkit.toml`: `init` copies an example (and points the new project's
`CLAUDE.md` or `AGENTS.md` to the dev kit's guides for C and for the hardware), `build` builds
for the target it is given (`snes`, `win64`, `linux` or `web`), and `run` builds and starts the
game. It calls the compilers itself, one call per file and one to link, without CMake, Make or
shell scripts, and
`--verbose`, `--dry-run` and `--plan` print every call, check that everything needed is there,
or both. For the desktop you provide the C compiler yourself; the dev kit brings only dampf.
Until M7, a game builds for the SNES with dampf and an argument file. M7 is done when the
examples and Blubber Blasen run on Windows and Linux from the dev kit.

**M8, the web**, adds the target `web`, built with Emscripten, with save RAM kept in the
browser and `superdevkit run web` serving the game from a local server. The examples and
Blubber Blasen then run in the browser, where a link to a build is enough to share a game for
feedback, and Blubber Blasen drops its own PC side.

**M9, the optimizer**, takes up what was left of M3, in the order of the plan of 2026-11-17,
which estimated the likely gain on play from the shares of the cycles in a profile. The gains
are estimates, not measurements, and several ideas that looked good measured nothing. First
comes register allocation, keeping values in A, X and Y over several instructions instead of a
[direct page](/glossary#direct-page) place each, estimated at 10 to 20 %. It is the largest
lever and the hardest: loads and stores of direct page values are the largest share of the
cycles, and with one accumulator the hard part is choosing what lives where. Today one pass per
pattern does part of it, and a common model of what lives where comes first. Splitting arrays of
structs into one array per field (5 to 10 % in the loops over the bubbles) needs a decision on
the subset or a [hint](/glossary#hint), since the layout in memory then differs from the PC's,
and HIR, a level above MIR that is built only when something needs it. Then come
[8-bit stretches](/glossary#_8-bit-and-16-bit-mode) with the [`M` flag](/glossary#m-and-x-flags)
followed across blocks, 8-bit index registers with arrays aligned to pages, and a calling
convention that passes the first argument in A. 16-bit pointers into RAM and enum objects in one
byte follow once their subset decisions are taken, and constant shifts by a multiple of 8
become byte moves: a 32-bit shift by 16 is a loop of 16 steps today, about 600 cycles. M9 also
closes the gaps that the dev kit's C style guide measures, where one way of writing C is slower
than another: which width of loop counter is cheaper depends on the array it indexes, an 8-bit
counter costing more over bytes and a 16-bit one over words; an array with a 16-bit index goes
through a 24-bit pointer unless a `DAMPF_RANGE` hint bounds the index; a pointer walk costs more
than indexing; a multiplication of two bytes calls a routine instead of using the SNES's 8 x 8
multiplier; a signed division by a power of two keeps its rounding correction even when the
value is known not to be negative; 32-bit operands go through frame slots; and a constant shift
by more than 8 is a loop. Each gap is a rule of the guide that a test checks, and the change
that closes a gap changes or retires its rule. The rest
of the way to the goal has to come from the game doing less work, as the checkpoint after M2
concluded.

**M10, language, platform and usability**, starts with C in interrupt handlers. dampf keeps the
frames of functions at fixed places, so C called from an NMI or IRQ would overwrite the frames
of the code it interrupted, and the platform layer waits for the vertical blank instead. Nearly
every SNES game has an NMI handler, and raster effects need an IRQ handler, so the functions an
interrupt reaches are to get a frame area of their own; how a handler is declared and what it
saves is open. M10 also brings intrinsics for the hardware multipliers, the divider and DMA, the
open decision on the subset whether plain `char` is for text only, function pointers in
constant tables, hand-written assembly with the code generator from MIR (`--replace` works only
with the [naive code generator](/glossary#naive-code-generator) today), and less memory for a
compile, which took 1.8 GB for Blubber Blasen. dampf is to warn where gcc warns by default, first on
a constant that does not fit the type it initializes or is assigned to
(`uint8_t w[] = { 420 }`): dampf truncates it as gcc does, but silently, and that hid a bug in
CatchMe, a cat's wait of 420 that became 164, which only the desktop build's `-Woverflow`
showed. dampf gets its own C preprocessor, so that using it needs nothing but Java; today users
on Windows install MinGW or MSYS only for `gcc -E`, which stays in the tests as the oracle.

**M11, timing and debugging**, counts the timing report, the assembly comments and the budgets
in master clocks instead of CPU cycles, names the costliest path per C line in the report and in
budget errors, and finds bounds for more loops: 37 of the game's functions have no worst case
today. Worst cases of pair loops are far too high: a path that is rare in every pair, such as
a hit that kills, is counted in every pair, so the `collide` function of the example Er-Tüp is
5.4 million cycles at worst and a few thousand in play. Its frame is guarded by a budget
measured in its check instead; a hint for how often a path runs, or a bound per call chain,
would make budgets usable there. Frame-level timing adds DRAM refresh, HDMA and interrupts.
Mesen shows the C source and the names of global variables from dampf's debug file, and
nothing more; dampf's users would
gain from variables shown with their C type, struct fields, and the locals of a function in
their direct page places. dampf first fills what the debug file's format already has room for,
then a small patch teaches Mesen to read it, offered to Mesen's author. The patch waits until
the dev kit is public, because a patch from a compiler with users is easier to argue for. A
debugger of dampf's own is not planned.

**M12, fine tuning**, takes up what was left of M4 until the frame budget is met: the block
layout by a profile by default (it is built, but only on request, because it needs a profile in
the game's build), superoptimization of short hot sequences, `<<8` as a byte store, `switch` as
a jump table, `MVN` for copies, and unrolling by a hint. Inlining is to be guided by the
profile, and a tuning option is to try the parameters chosen today on one game, such as the
inlining limit and the block order, on the user's own code and keep the fastest build, each
candidate checked against the PC's results. The hints that transform code come in M12, as do
variables in bank `$7F`, and subrange types declared in the source, but those only once a
profile shows a hot value whose range dampf cannot infer.

### Not scheduled for the dev kit

Some steps of the dev kit have no milestone yet: macOS, where raylib runs but which nobody can
test now; `superdevkit bundle`, one folder with every target, ready to publish; the desktop
running the ROM in an emulator; Mesen as the desktop target; and the compiler moved into a
folder `compiler/` once a second build world in the repository makes it worth it.

Two ideas for dampf itself have no milestone either. A local Compiler Explorer (godbolt) could
show dampf next to gcc through a wrapper script that turns dampf's line information into the
directives Compiler Explorer maps source lines with; on godbolt.org itself only after
publishing, since that needs a stable release. `dampf explore` would be a local page served by
dampf for what Compiler Explorer cannot show: the cycles per source line and per function, the
MIR after each pass, both code generators side by side, and the code run in dampf's emulator
against gcc's result on the PC.

### Publishing (not scheduled)

The dev kit goes public as superdevkit when it is useful to someone other than Blubber Blasen.
The first step is done: on 2026-11-19 the snapshot of Blubber Blasen, its replays and the
hand-written reference function left the repository, and the dev kit's examples took over their
jobs. They are still in the git history, so either the history is cleaned or the public
repository starts without it. Er-Tüp is a test case of a whole game's size, the reference
functions are functions of the examples, and `dev/bench_examples.sh` measures the work per tick
of all examples against a baseline. The examples are a set of benchmarks in different styles
rather than one busy scene: many small calls (Schach), 16- and 32-bit arithmetic (Mandelbrot),
arrays and bytes (Steinschlag), fixed point, particles, a pair loop and state machines (Er-Tüp),
and the small ones. Until then every speed decision was measured on one game: the optimizations
of 2026-11-18 made play of Blubber Blasen 13 % faster, but the execution tests only 0.9 %, and
part of the gain comes from parameters chosen on this one game, such as the inlining limit and
the block order. Those parameters are still to be checked on the whole set. The repository was
renamed to superdevkit, with dampf as its compiler, on 2026-11-19; it is on GitHub, still
private. Still to come there are a release, a build on GitHub Actions and the book on GitHub
Pages.
The licence is decided already: superdevkit, dampf included, is under the Apache License 2.0
with LLVM Exceptions, the same as LLVM, so code dampf and the library put into a ROM carries no
licence conditions and games built with the dev kit can use any licence.

## The checkpoint after M2

Before M3 started, the measurements of M2 had to answer one question: can a better compiler
alone make the game fast enough, or does the game also have to do less work?

The goal of 0.6 frames per tick needed dampf's naive code to get 9 to 11 times faster on average
and 14 to 20 times on the worst tick. The optimizations were expected to bring a factor of 3 to
6 over the naive code, and the first function written by hand in assembly, `level_solid_px`,
agreed: the naive code was 4.5 times slower than it. Even with hand-written quality everywhere,
play would land at about 1.2 to 1.7 frames on average, and the worst tick with the
[CPU partner](/glossary#cpu-partner) at 3 to 4 frames. The compiler alone cannot close the gap.

Three options were on the table: compiler first and decide after M3, compiler and game in
parallel, or relax the requirement, for example game logic at 30 Hz on the heaviest ticks. The
decision was **compiler and game in parallel**. dampf goes on with the compiler, while the game
works on the hot spots no compiler can fix: the CPU partner and the pairwise separation of
bubbles in `separate`, the game's function that pushes bubbles apart (16 % of the cycles of
play). Relaxing the requirement stays as a fallback, decided on measured numbers if rare worst
ticks still do not fit.

The game changes in its own repository. Until 2026-11-19 dampf followed by refreshing a copy of
the game's code and measuring again with the benchmark, the replays and the profile; since then
the game builds with the dev kit from its own repository, and dampf is measured on its examples.
Whether the budget is met therefore depends on work outside dampf.

## Risks and open questions

The main risks and how they are met:

- **Miscompilation.** The layers of tests, the naive code generator as an independent
  reference, and the examples' checks as the oracle, a checksum every tick against the PC,
  catch wrong code. One miscompilation is known
  and not found yet; it is described below.
- **The optimizer is tested by whole programs.** Each build runs 31 execution tests and 50
  random programs, and the random programs barely reach the optimizer's patterns, so in practice
  the optimizer was tested by Blubber Blasen's benchmark and one replay recording, and since
  2026-11-19 by the examples' checks. More examples, and random programs and execution tests
  for the patterns, would help.
- **Tuned on one game.** The inlining limit, the block order and the idioms the optimizer looks
  for came from Blubber Blasen. The examples' benchmark, a set of programs in different styles,
  is meant to correct that; the parameters are still to be checked on it.
- **Never run on a console.** Everything is checked in dampf's emulator and in Mesen;
  [FastROM](/glossary#fastrom-and-slowrom), the DMA margin and timing at the edges have not run
  on hardware yet, which M5 changes.
- **Different behaviour on PC and SNES.** The subset allows fixed-width types only, dampf
  follows C's promotion rules exactly, and the tests compare every program with gcc.
- **The frame budget depends on the game.** The checkpoint after M2 concluded that the compiler
  alone cannot reach it; with the CPU partner the worst tick takes 3 frames.
- **Little human knowledge of the code.** About 23,000 lines were written in two days, mostly
  by AI agents. A bug like the open miscompilation needs someone who knows how the code
  generator works, and today that knowledge is mostly in the documents and the decision
  records.
- **Scope creep.** The compiler could become the project and stall the game; the book and M6
  came before M5. The two run in parallel, and every milestone is measured against the game.
- **Gains smaller than hoped.** Each optimization is measured, and the order of the remaining
  ones follows the profile.
- **A wrong hint silently produces wrong code.** Every hint that states a fact is checked as an
  assertion in the PC build, with the examples' checks running against it.

Open questions a user may run into:

- **A miscompilation not yet found.** dampf inlines functions of up to 60 instructions, and
  with that limit the game's benchmark gives the PC's checksums. With a limit of 80 it differs
  from the PC in 56 of 3,610 ticks, from tick 32 on. The fault is in an older part of the code
  generator, on a function that the larger inlining makes: the optimized MIR is right, since its
  interpreter gives the PC's checksums; dampf's assembler and [ca65](/glossary#ca65-and-ld65)
  build the same ROM; and the difference stays with every LIR pass switched off. The next step
  is to find that function and reduce it to an execution test; fixing it is part of M5. The
  game's code has left the repository, so the search needs it from the game's own repository,
  or an example that the limit of 80 breaks too.
- **The language of the dev kit's tools.** The first tool, the PNG converter for the
  examples, is written in Python for now. Java would keep the dev kit to nothing but Java to
  install and can read PNG files itself; Python is quicker to write but is one more thing to
  install. This is to be looked at before more tools come.
- **What 1.0 needs.** The frame budget met, the subset decisions taken, C in interrupts, or a
  set of users: which of them is open.
- **Locals in Mesen.** How Mesen shows locals that live in fixed direct page places, several
  values sharing a place (M11).
- **`restrict`.** Whether a no-alias hint of dampf's own is clearer than `restrict`, and how
  `restrict` is checked on the PC.
- **Timing.** How to express path combinations that cannot happen, should the worst-case
  bounds turn out too pessimistic, and how much HDMA the game uses (DMA transfers the SNES
  makes line by line while it draws), which changes the budget of a frame.
- **Reference functions.** Which functions become the hand-written yardsticks for dampf's code.
  So far they are Er-Tüp's `random16` and `sine`; candidates are the pair loop, Mandelbrot's
  iteration and Steinschlag's cave.
