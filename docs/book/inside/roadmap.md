<!-- GENERATED from doc-specs/inside/roadmap.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->

# Roadmap

superdevkit and its compiler dampf are built in milestones, first for
[Blubber Blasen](/glossary#blubber-blasen), the game dampf was developed for. Until 2026-11-19
each was measured against a copy of the game's code, since then against the
[dev kit](/glossary#dev-kit)'s examples. The goal of the compiler is that the game logic of the
game's busiest scene fits in at most 60 % of a [frame](/glossary#frame). This page says where
the kit stands, what comes next and what is still open. Unlike the other pages of the book, it
also describes plans. What is not built yet, listed for users, is on
[Limits](/reference/limits).

## Where dampf stands

The kit is at version 0.9.0 (2026-11-23), and the milestones M0 to M9 are concluded. A game is
written once against the kit's console API, and the `superdevkit` command builds it for the SNES
with dampf, as a program for Linux and Windows, and since 0.8.0 as a page in the browser, with
the same picture on each, sound effects, save RAM, music on the desktop and the web, and a debug
view. dampf compiles all of Blubber Blasen, which builds its SNES ROM with the kit and plays its
recordings with the [PC build](/glossary#pc-build)'s checksum on every tick; dampf preprocesses,
assembles and links itself, so it needs nothing but Java. With 0.9.0 its optimizer chooses
registers by cost and keeps byte loops in [8-bit mode](/glossary#_8-bit-and-16-bit-mode).

The compiler is not at its goal yet: during play the game took 0.79 frames per
[tick](/glossary#tick) on 2026-11-18 against a goal of 0.6. The speedups still open are part of
fine tuning (M12), while the game reduces its own work. Next are M10, language, platform and
usability, which is in progress, and M11, timing and debugging.

## Versions

The version is the dev kit's. It is set in one place, `build.gradle.kts`, and `dampf --version`
and `superdevkit --version` print it. Milestone N is released as version 0.N.0. Between two
milestones the last number rises for every change worth naming, features included, so a feature
of a later milestone that is built early does not wait for its version. M6 was done ahead of M5
and is part of 0.4.0, and M5, concluded when 0.5 was already in use, took the free 0.6.0.

Every version before 1.0 is a pre-release: the [subset](/glossary#subset), the command line and
the files dampf writes may change from one version to the next. Publishing is a step of its
own and not scheduled yet (see [Publishing](#publishing-not-scheduled)). What 1.0 needs is not
decided.

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
| M2 | Naive but complete: all of Blubber Blasen compiles, the benchmark and the replays give the PC's checksums, cycles measured and compared with [Calypsi](/glossary#calypsi) | 0.4.0 | Done 2026-11-17 |
| M3 | The big levers: [MIR](/glossary#mir), [LIR](/glossary#lir) and the [code generator from MIR](/glossary#code-generator-from-mir), static timing and budgets, [value ranges](/glossary#value-range) and [pointer compression](/glossary#pointer-compression); faster than Calypsi | 0.4.0 | Done 2026-11-18 |
| M4 | Fine tuning, started early: the block layout by a profile, peephole rules, loop and branch optimizations | 0.4.0 | Done 2026-11-18 |
| M6 | Standing alone: dampf's own assembler and linker | 0.4.0 | Done 2026-11-18 |
| M5 | Blubber Blasen builds its ROM with the dev kit, from its own repository | 0.6.0 | Done 2026-11-19 |
| M7 | Windows and Linux: [Rachael](/glossary#rachael) and the desktop frontend in the dev kit, the `superdevkit` command with `init`, `build` and `run`; the examples and Blubber Blasen run on the desktop from the dev kit | 0.7.0 | Done 2026-11-19 |
| M8 | The web: the same games in the browser; Blubber Blasen's web build from the dev kit | 0.8.0 | Done 2026-11-22 |
| M9 | The optimizer: what was left of M3; tests and a canonical form first, then register allocation | 0.9.0 | Done 2026-11-23 |
| M10 | Language, platform and usability: C in interrupts, [intrinsics](/glossary#intrinsic), the open subset decisions, the game's development tools in the kit's window and dampf's own preprocessor (both built) | 0.10.0 | In progress |
| M11 | Timing and debugging: [master clocks](/glossary#master-clock), bounds for more loops, locals and types in Mesen | 0.11.0 | Planned |
| M12 | Fine tuning: what was left of M4 and M9, until the frame budget is met | 0.12.0 | Planned |

M3 and M4 were concluded at version 0.4.0 although only part of their goals was reached: dampf
is faster than Calypsi, but the frame budget is not met yet. Too much had stayed open in them
while too little was planned after them, so what was left moved to M9 and M12. M9's own list of
speedups had no finish line, so it was closed on 2026-11-23 with what was built, for a first
public version: the speedups left moved to M12, and two subset decisions to M10. The order after
M5 follows the likely gain and what each milestone needs from the one before; it was kept when
M5 was concluded.

The examples build and run on Linux, Windows and in the browser from the kit. Blubber Blasen's
switch to the kit's desktop and web builds was handed over to the game, which does it itself.
M8 was narrowed to the web, because the game's own desktop frontend had to stay until the kit's
window had the game's development tools. Those moved to M10 and are built, so the frontend can
go too.

### M10: language, platform and usability

Built: dampf's own C preprocessor (2026-11-22), so that using dampf needs nothing but Java;
until then it needed gcc, on Windows MinGW or MSYS only for `gcc -E`. It is a subset of C99's
preprocessor, as the language is a subset of C: what the kit's code and the game use, with macro
expansion exactly as the standard says. It rejects with an error what nothing uses, such as
`__VA_OPT__`, `#line`, `#include_next` and gcc's extensions.

Built as well (2026-11-22) are the development tools Blubber Blasen asked for in the kit's
window, so that the game can drop its own desktop frontend: start options (a seed, and setting
a registered toggle or range by name before the first tick), a bot as a toggle the game
registers, mute in the window, text and values in the debug view, jumping to a tick and stepping
back in a replay (not on the web), defines per [target](/glossary#target) in the
[project file](/glossary#project-file), and filled boxes and arrows in the view. A project can
also name commands that run before its build, such as its own converters, and `superdevkit gen`
runs only the conversions, for a game with a build system of its own.

The rest of M10 starts with C in interrupt handlers. dampf keeps the frames of functions at
fixed places, so C called from an NMI or IRQ would overwrite the frames of the code it
interrupted, and the [platform layer](/glossary#platform-layer) waits for the
[vertical blank](/glossary#vertical-blank) instead. Nearly every SNES game has an NMI handler,
and raster effects need an IRQ handler, so the functions an interrupt reaches are to get a frame
area of their own; how a handler is declared and what it saves is open. M10 also brings
intrinsics for the hardware multipliers, the divider and [DMA](/glossary#dma), the open
decisions on the subset (plain `char` for text only, enum objects in one byte, 16-bit pointers
into RAM), each built once it is taken, function pointers in constant tables, hand-written
assembly with the code generator from MIR (`--replace` works only with the
[naive code generator](/glossary#naive-code-generator) today), `restrict` or a no-alias
[hint](/glossary#hint) of dampf's own, and less memory for a compile, which took 1.8 GB for
Blubber Blasen. dampf is to warn where gcc warns by default, first on a constant that does not
fit the type it initializes or is assigned to (`uint8_t w[] = { 420 }`): dampf truncates it as
gcc does, but silently, and that hid a bug in CatchMe, a cat's wait of 420 that became 164,
which only the desktop build's `-Woverflow` showed.

### After M10

**M11, timing and debugging**, counts the [timing report](/glossary#timing-report), the
assembly comments and the budgets in master clocks instead of CPU cycles, names the costliest
path per C line in the report and in budget errors, and finds bounds for more loops: 37 of the
game's functions have no worst case today. Worst cases of pair loops are far too high: a path
that is rare in every pair, such as a hit that kills, is counted in every pair, so the `collide`
function of the example Er-Tüp is 5.4 million cycles at worst and a few thousand in play. Its
frame is guarded by a budget measured in its check instead; a hint for how often a path runs,
or a bound per call chain, would make budgets usable there. Frame-level timing adds DRAM
refresh, [HDMA](/glossary#hdma) and interrupts. Already built is measuring a game with nothing
but a [superdevkit home](/glossary#superdevkit-home): the work of every tick and the ROM's sound.
Mesen shows the C source and the names of global variables from dampf's debug file, and nothing
more; dampf's users would gain from variables shown with their C type, struct fields, and the
locals of a function in their [direct page](/glossary#direct-page) places. dampf first fills
what the debug file's format already has room for, then a small patch teaches Mesen to read it,
offered to MesenCE, where Mesen is maintained now. The patch waits until the dev kit is public,
because a patch from a compiler with users is easier to argue for. A debugger of dampf's own is
not planned.

**M12, fine tuning**, takes up what was left of M4 and M9 until the frame budget is met. The
gains named here are estimates from the shares of the cycles in a profile, made in the plan of
2026-11-17, not measurements, and several ideas that looked good measured nothing. The largest
lever and the hardest is register allocation, keeping values in A, X and Y over several
instructions instead of a direct page place each, estimated at 10 to 20 %: loads and stores of
direct page values are the largest share of the cycles, and with one accumulator the hard part
is choosing what lives where. Part of it is built: dampf chooses by cost whether a value lives
in X, Y or a direct page place, also for a single loop. Also to come are 8-bit mode followed
across blocks (the [`M` flag](/glossary#m-and-x-flags)), 8-bit index registers with arrays
aligned to pages, a calling convention that passes the first argument in A and uses `JMP` for
tail calls, value ranges from array bounds beyond an index into the array, and faster 32-bit
arithmetic and ordered comparisons, which still go through memory slots of the frame. From M4
come the block layout by a profile by default (it is built, but only on request, because it
needs a profile of the program), superoptimization of short hot sequences, `<<8` as a byte
store, `switch` as a jump table, `MVN` for copies, and unrolling by a hint. Inlining is to be
guided by the profile, and a tuning option is to try the parameters chosen today on one game,
such as the inlining limit and the block order, on the user's own code and keep the fastest
build, each candidate checked against the PC's results. The hints that transform code come in
M12, as do variables in bank `$7F`, and subrange types declared in the source, but those only
once a profile shows a hot value whose range dampf cannot infer. Splitting arrays of structs
into one array per field waits until a measured loop gains markedly from it, since measurements
so far went both ways; it would come as a hint, `DAMPF_SPLIT`, because the layout in memory then
differs from the PC's. The rest of the way to the goal has to come from the game doing less
work, as the checkpoint after M2 concluded.

### Not scheduled for the dev kit

Some steps of the dev kit have no milestone yet. The ROM has not run on a real SNES or a flash
cartridge, on NTSC and PAL, and the DMA margin of the platform layer is to be measured there:
both wait until there is hardware. Music on the SNES is a stub (sound effects and save RAM are
built there). Also without a milestone: macOS, where raylib runs but which nobody can test
now; `superdevkit bundle`, one folder with every target, ready to publish; the desktop running
the ROM in an emulator; and Mesen as the desktop target.

Some ideas have no milestone either and become a step of one only when they are decided: dampf
in Compiler Explorer (godbolt), next to gcc; `dampf explore`, a local page with the cycles per
source line, the MIR after each pass and both code generators side by side; and
`superdevkit studio`, the kit's editors (the sound effects' first, later graphics, tilemaps,
sprites and a level editor) served by the command in a browser window and working on the files
of the game's folder; and a spec of a game that an AI builds the game from. Eight further kinds
of tests, from a survey of how the large compilers are tested, are not scheduled either, among
them stress runs that make the optimizer's legal choices at random and a reducer for failing
programs.

### Publishing (not scheduled)

The dev kit goes public as superdevkit when it is useful to someone other than Blubber Blasen.
The first steps are done. On 2026-11-19 the snapshot of Blubber Blasen, its replays and its
hand-written reference function left the repository and its git history, and the dev kit's
examples took over their jobs: Er-Tüp is a test case of a whole game's size, the reference
functions are functions of the examples, and `dev/bench_examples.sh` measures the work per tick
of all examples against a baseline. The examples are a set of benchmarks in different styles
rather than one busy scene: many small calls (Schach), 16- and 32-bit arithmetic (Mandelbrot),
arrays and bytes (Steinschlag), fixed point, particles, a pair loop and state machines (Er-Tüp),
and the small ones. Until then every speed decision was measured on one game: the optimizations
of 2026-11-18 made play of Blubber Blasen 13 % faster, but the execution tests only 0.9 %, and
part of the gain comes from parameters chosen on this one game, such as the inlining limit and
the block order. Those parameters are still to be checked on the whole set. The repository was
renamed to superdevkit, with dampf as its compiler, on 2026-11-19; it is on GitHub, still
private. With dampf's own preprocessor and the converters for pictures and sounds in Java,
building for the SNES needs nothing but Java and the kit.

Still to come is the release itself: a zip per system on GitHub, with a Java runtime and raylib
in it, so that a user installs no Java, and the book on GitHub Pages. For the desktop a C
compiler stays needed. The licence is decided already: superdevkit, dampf included, is under the
Apache License 2.0 with LLVM Exceptions, the same as LLVM, so code dampf and the library put
into a ROM carries no licence conditions and games built with the dev kit can use any licence.

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
  catch wrong code.
- **The optimizer is tested by whole programs.** The execution tests and the 50 random programs
  of each build barely reached the optimizer's patterns, so in practice the optimizer was tested
  by whole programs: Blubber Blasen's benchmark and one replay until 2026-11-19, since then the
  examples' checks. Random programs with the loops and pointer walks the optimizer works on came
  with M9; more examples would still help.
- **Tuned on one game.** The inlining limit, the block order and the idioms the optimizer looks
  for came from Blubber Blasen. The examples' benchmark, a set of programs in different styles,
  is meant to correct that; the parameters are still to be checked on it.
- **Never run on a console.** Everything is checked in dampf's emulator and in Mesen;
  [FastROM](/glossary#fastrom-and-slowrom), the DMA margin and timing at the edges have not run
  on hardware yet, and that is not scheduled until there is hardware.
- **Different behaviour on PC and SNES.** The subset allows fixed-width types only, dampf
  follows C's promotion rules exactly, and the tests compare every program with the PC build.
- **The frame budget depends on the game.** The checkpoint after M2 concluded that the compiler
  alone cannot reach it; with the CPU partner the worst tick takes 3 frames.
- **Little human knowledge of the code.** About 23,000 lines were written in two days, mostly
  by AI agents. A bug like the miscompilation found on 2026-11-19 needs someone who knows how
  the code generator works, and today that knowledge is mostly in the documents and the
  decision records.
- **Scope creep.** The compiler could become the project and stall the game; the book and M6
  came before M5. The two run in parallel, and every milestone is measured against the game.
- **Gains smaller than hoped.** Each optimization is measured, and the order of the remaining
  ones follows the profile.
- **A wrong hint silently produces wrong code.** Every hint that states a fact is checked as an
  assertion in the PC build, with the examples' checks running against it.

Open questions a user may run into:

- **A feature Rachael only reports.** Rachael counts every register write she does not draw,
  and the desktop check fails on it. Whether a feature she does not draw but reports may be
  used anyway is open: it keeps the desktop honest, but the desktop then cannot check that
  game's picture. Until it is decided, a feature is usable only when both the SNES library and
  Rachael draw it.
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
