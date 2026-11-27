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

The kit is at version 0.10.1, and the milestones M0 to M10 are concluded (M10 as 0.10.0 on
2026-11-26). A game is written once against the kit's [console API](/glossary#console-api), and
the `superdevkit` command builds it for the SNES with dampf, as a program for Linux and Windows,
and since 0.8.0 as a page in the browser, with the same picture on each, sound effects, save
RAM, music on the desktop and the web, and a debug view. dampf compiles all of Blubber Blasen,
which builds its SNES ROM with the kit and plays its recordings with the
[PC build](/glossary#pc-build)'s checksum on every tick; dampf preprocesses, assembles and links
itself, so it needs nothing but Java. With 0.9.0 its optimizer chooses registers by cost and
keeps byte loops in [8-bit mode](/glossary#_8-bit-and-16-bit-mode). With 0.10.0 a game handles
interrupts in C: an IRQ handler of its own, which runs at a picture row the game sets, and the
kit's NMI handler, which runs the game's [vertical blank](/glossary#vertical-blank) for a game
that publishes the data of its tick. A game can also replace a function by hand-written assembly
beside the optimized code, and the console API is the hardware at once, with optional layers
above it. With 0.10.1, released on 2026-11-27, [Rachael](/glossary#rachael), the SNES the kit
rebuilds for the desktop and the web, draws the picture modes beyond 1 and 7 too, modes 0, 2,
3 and 4 with offsets per tile, 256-colour tiles (`superdevkit png tiles8`) and direct colour,
mosaic, mode 7's second layer (EXTBG), `VMAIN`'s address remapping, and
[VRAM](/glossary#vram), [CGRAM](/glossary#cgram) and [OAM](/glossary#oam) read back by
[DMA](/glossary#dma). The example `modes` shows each, the same as in [Mesen](/glossary#mesen)
pixel for pixel.

The compiler is not at its goal yet: during play the game took 0.79 frames per
[tick](/glossary#tick) on 2026-11-18 against a goal of 0.6. The speedups still open are part of
fine tuning (M12), while the game reduces its own work. Next is M11, timing and debugging, then
M12.

## Versions

The version is the dev kit's. It is set in one place, `build.gradle.kts`, and `dampf --version`
prints it. Milestone N is released as version 0.N.0. Between two milestones the last number
rises for every change worth naming, features included, so a feature of a later milestone that
is built early does not wait for its version. M6 was done ahead of M5 and is part of 0.4.0, and
M5, concluded when 0.5 was already in use, took the free 0.6.0.

Every version before 1.0 is a pre-release: the [subset](/glossary#subset), the command line and
the files dampf writes may change from one version to the next. Publishing is a step of its
own: the repository is public, and 0.9.1 is its first release with downloads (see
[Publishing](#publishing)). What 1.0 needs is not decided.

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
| M10 | Language, platform and usability: C in interrupts, hand-written assembly, the subset decisions on `char` and enums | 0.10.0 | Done 2026-11-26 |
| M11 | Timing and debugging: [master clocks](/glossary#master-clock), bounds for more loops, locals and types in Mesen | 0.11.0 | Next |
| M12 | Fine tuning: what was left of M4 and M9, until the frame budget is met | 0.12.0 | Planned |

M3 and M4 were concluded at version 0.4.0 although only part of their goals was reached: dampf
is faster than Calypsi, but the frame budget is not met yet. Too much had stayed open in them
while too little was planned after them, so what was left moved to M9 and M12. M9's own list of
speedups had no finish line, so it was closed on 2026-11-23 with what was built, for a first
public version: the speedups left moved to M12, and two subset decisions to M10. M10 dropped its
[intrinsics](/glossary#intrinsic): dampf uses the hardware multiplier for every product, a
division by a constant is a multiplication, and [DMA](/glossary#dma) and the wait for the
vertical blank are calls of the console API; the hardware divider went to M12. The third subset
decision, 16-bit pointers into RAM, moved to M12 as well, and `restrict` was measured at about
zero on the examples and rejected. The order after M5 follows the likely gain and what each
milestone needs from the one before; it was kept when M5 was concluded.

The examples build and run on Linux, Windows and in the browser from the kit. Blubber Blasen's
switch to the kit's desktop and web builds was handed over to the game, which does it itself.
M8 was narrowed to the web, because the game's own desktop frontend had to stay until the kit's
window had the game's development tools. Those moved to M10 and are in the kit's window since
0.9, so the frontend can go too once the game has switched.

### M11: timing and debugging

M11 makes dampf's timing more exact and its programs easier to follow in Mesen. The
[timing report](/glossary#timing-report), the assembly comments and the budgets are to count in
master clocks instead of CPU cycles. The report and budget errors are to name the costliest path
per C line, and the comment of a line with a branch its best and its worst case. dampf is to
find bounds for more loops: 37 of the game's functions have no worst case today, and a
[hint](/glossary#hint) for loops without a counter, `DAMPF_TRIPS`, is postponed. The emulator
tests are to check the measured cycles of every function with a budget.

Worst cases of pair loops are far too high: a path that is rare in every pair, such as a hit
that kills, is counted in every pair, so the `collide` function of the example Er-Tüp is 5.4
million cycles at worst and a few thousand in play. Its frame is guarded by a budget measured in
its check instead; a hint for how often a path runs per call, or a bound per call chain, would
make budgets usable there, and both are to be looked into.

A game's IRQ handler has about 25 CPU cycles after its wait for the horizontal blank
(`sdevkit_irq_wait_hblank`) for its register writes, less the [HDMA](/glossary#hdma) of the
line. Today only the check in Mesen sees a write that comes too late, as a torn row; in M11 the
timing report is to count the cycles from the wait to the handler's last register write, and
the build is to fail when they exceed that time. Frame-level timing adds DRAM refresh, HDMA and
interrupts. Already built, in 0.9, is measuring a game with nothing but a
[superdevkit home](/glossary#superdevkit-home): the work of every tick and the ROM's sound.

Mesen shows the C source and the names of global variables from dampf's debug file, and nothing
more; dampf's users would gain from variables shown with their C type, struct fields, and the
locals of a function in their [direct page](/glossary#direct-page) places. dampf first fills
what the debug file's format already has room for, then a small patch teaches Mesen to read it,
offered to MesenCE, where Mesen is maintained now. The patch waited until the dev kit was
public, as it now is, because a patch from a compiler with users is easier to argue for. With
it, the code generator from MIR is to write the processor state line in the assembly as the
[naive code generator](/glossary#naive-code-generator) does. A debugger of dampf's own is not
planned.

### After M11

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
arithmetic and ordered comparisons, which still go through memory slots of the frame. A register
write gets faster code, since the game's IRQ handler has only about 25 cycles for its writes:
`xba` in `sdevkit_reg_write_twice` instead of a second load saves 7 cycles, and no `cmp #$0000`
right after a load, which sets the flags already, 3 more. The CPU's hardware divider (16 by 8
bits) is to be chosen automatically for a division by a variable whose divisor dampf's value
ranges prove to fit a byte, once a measured program divides by variables on a hot path; it
replaces the intrinsics M10 had planned. From M4 come the block layout by a profile by default
(it is built, but only on request, because it needs a profile of the program), superoptimization
of short hot sequences, `<<8` as a byte store, `MVN` for copies, and unrolling by a hint.
Inlining is to be guided by the profile, and a tuning option is to try the parameters chosen
today on one game, such as the inlining limit and the block order, on the user's own code and
keep the fastest build, each candidate checked against the PC's results. The hints that
transform code come in M12, as do variables in [bank](/glossary#bank) `$7F`, and subrange types
declared in the source, but those only once a profile shows a hot value whose range dampf cannot
infer. The last open subset decision, 16-bit pointers into RAM, is in M12 too: a pointer stored
in a struct field, a global or an array takes 4 bytes today, and one that provably points only
into bank `$7E` could take 2, with cheaper loads. A program cannot see the size, so it is an
optimization, decided and built when a measured program shows the gain; once variables can live
in bank `$7F`, a 2-byte pointer also needs a proof of its bank. Splitting arrays of structs into
one array per field waits until a measured loop gains markedly from it, since measurements so
far went both ways; it would come as a hint, `DAMPF_SPLIT`, because the layout in memory then
differs from the PC's. The rest of the way to the goal has to come from the game doing less
work, as the checkpoint after M2 concluded.

### Not scheduled for the dev kit

Some steps of the dev kit have no milestone yet. The ROM has not run on a real SNES or a flash
cartridge, on NTSC and PAL, and the DMA margin of the [platform layer](/glossary#platform-layer)
is to be measured there: both wait until there is hardware. Music on the SNES is a stub (sound
effects and save RAM are built there). Also without a milestone: macOS, where raylib runs but
which nobody can test now; `superdevkit bundle`, one folder with every
[target](/glossary#target), ready to publish; the desktop running the ROM in an emulator; and
Mesen as the desktop target. Function pointers in constant tables, which the subset allows, were
built and not merged on 2026-11-24, since no example and no game uses them and a `switch` on an
enum does the same; they wait until a game asks for them.

Other work is open without a milestone. dampf's own speed and memory are tuned once it is more
or less feature complete; a compile of Blubber Blasen took 1.8 GB. A game on the kit's optional
layers keeps `game_vblank` in its loop, since the layers' state is the tick's; the layers could
publish their own state as a game publishes its data, so that their commit runs in the NMI
handler too. Rachael leaves a few picture features undrawn until a game needs them, and the
desktop check fails on each: modes 5 and 6, high resolution, overscan and interlace, which
change the picture's size in every frontend, and HDMA with indirect tables, which needs a call
that builds the table for each platform; direct HDMA does the same with larger tables.

Some ideas have no milestone either and become a step of one only when they are decided:
dropping the body of a function inlined at every call while keeping its entry in the timing
report, which would make the examples' code another 25 to 35 % smaller; dampf in Compiler
Explorer (godbolt), next to gcc; `dampf explore`, a local page with the cycles per source line,
the MIR after each [pass](/glossary#pass) and both code generators side by side; and
`superdevkit studio`, the kit's editors (the sound effects' first, later graphics, tilemaps,
sprites and a level editor) served by the command in a browser window and working on the files
of the game's folder; and a spec of a game that an AI builds the game from. Eight further kinds
of tests, from a survey of how the large compilers are tested, are not scheduled either, among
them stress runs that make the optimizer's legal choices at random and a reducer for failing
programs.

### Publishing

The dev kit went public as superdevkit on 2026-11-23, after version 0.9.0.
The first steps came earlier. On 2026-11-19 the snapshot of Blubber Blasen, its replays and its
hand-written reference function left the repository and its git history, and the dev kit's
examples took over their jobs: Er-Tüp is a test case of a whole game's size, the reference
functions are functions of the examples, and `dev/bench_examples.sh` measures the work per tick
of all examples against a baseline. The examples are a set of benchmarks in different styles
rather than one busy scene: many small calls (Schach), 16- and 32-bit arithmetic (Mandelbrot),
arrays and bytes (Steinschlag), fixed point, particles, a pair loop and state machines (Er-Tüp),
and the small ones. Until then every speed decision was measured on one game: the optimizations
of 2026-11-18 made play of Blubber Blasen 13 % faster, but the execution tests only 0.9 %, and
part of the gain comes from parameters chosen on this one game, such as the inlining limit and
the block order. The inlining limits have been checked on the whole set since 2026-11-21, the
block order not yet. The repository was
renamed to superdevkit, with dampf as its compiler, on 2026-11-19. With dampf's own preprocessor
and the converters for pictures and sounds in Java, building for the SNES needs nothing but Java
and the kit.

The steps of a release are built and apply from version 0.9.1. A release is a zip per system on
GitHub, for Linux and Windows on x64, with a Java runtime and raylib in it, so that a user
installs no Java. A workflow builds each zip on its own system, checks it as a user gets it and
makes a draft release from a version tag. The draft becomes public only when someone has tried
a zip and published it. When a release is published, a second workflow puts the book and the
examples on GitHub Pages, so the site always describes a released version. For the
desktop a C compiler stays needed. A check in the automatic builds that the book is up to date
is still open. The licence is decided already: superdevkit, dampf included, is under the Apache
License 2.0 with LLVM Exceptions, the same as LLVM, so code dampf and the library put into a ROM
carries no licence conditions and games built with the dev kit can use any licence.

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

## Open questions

- **What 1.0 needs.** The frame budget met, the last subset decision taken (16-bit pointers into
  RAM, in M12), or a set of users: which of them is open.
- **Locals in Mesen.** How Mesen shows a direct page place that several values share at
  different times (M11).
- **Paths that cannot happen together.** Whether a hint for how often a path runs per call, or a
  bound per call chain, makes the worst cases of pair loops tight enough for a budget (M11).
- **HDMA in the frame budget.** How the budget of a frame learns which HDMA channels a game has
  on, since a game turns them on while it runs, with `sdevkit_hdma_enable` (M11).
- **Reference functions.** Which functions become the hand-written yardsticks for dampf's code.
  So far they are Er-Tüp's `random16` and `sine`, measured against the optimized code since
  M10: `random16` takes 68 cycles by hand and by dampf, `sine` 21 by hand and 24 by dampf.
  Candidates are the pair loop, Mandelbrot's iteration and Steinschlag's cave.
