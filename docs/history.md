# History

The concluded milestones of superdevkit, what was built ahead of a milestone and released, and
how the plan changed on the way (ADR-6FK7WP0H). What comes next is in [roadmap.md](roadmap.md);
what each release brought, from 0.9.2 on, is in [releases](releases). Dates are 2026-11-17
unless a line says otherwise. The measurements are in [optimizations-log.md](optimizations-log.md).

## Where dampf stands

M0 to M10 are concluded. M0 to M4 and M6 with version 0.4.0 (2026-11-18): all of Blubber Blasen
compiles, the benchmark and the replays give the PC's checksums, on the SNES and in a playable
ROM, dampf's code is faster than Calypsi's, and dampf assembles and links itself. M5 as version
0.6.0 (2026-11-19): Blubber Blasen builds its SNES ROM with the dev kit from its own repository,
and the ROM plays its recordings with the PC's checksum on every tick. M7 as version 0.7.0
(2026-11-19): the desktop (Linux, Windows) from the kit, sound effects, save RAM, music on the
desktop, the debug view, and the `superdevkit` command. M8 as version 0.8.0 (2026-11-22): the web
(`superdevkit build web` and `run web`), every example checked in the browser against the SNES.
M9 as version 0.9.0 (2026-11-23): the optimizer's tests and canonical form, register choice by
cost, 8-bit loops and array indexes that need no range. The repository went public after 0.9.0,
and 0.9.1 was its first release with downloads. M10 as version 0.10.0 (2026-11-26): C in
interrupt handlers (the game's IRQ, and the kit's NMI for a game that publishes), hand-written
assembly beside the optimized code, the subset decisions on `char` and enums, and a console API
that is the hardware at once, with optional layers above it. 0.10.1 (2026-11-27) added picture
modes 0, 2, 3 and 4, mosaic and mode 7's second layer to the desktop and the web
([below](#built-after-m10-released-in-0101)).

dampf is the compiler of a development kit for SNES games, superdevkit; this repository is
superdevkit's (ADR
[ADR-6F3CJX8T](adr/6F3CJX8T-superdevkit-is-the-project-and-dampf-is-its.md)).

## The concluded milestones

| Milestone | Goal | Version | Concluded |
| --- | --- | --- | --- |
| M0 | Define the subset: Blubber Blasen's `src/game` scanned, [subset.md](subset.md) written | 0.4.0 | 2026-11-17 |
| M1 | End to end: a small function compiled, assembled, linked, run in a test, stepped through in Mesen | 0.4.0 | 2026-11-17 |
| M2 | Naive but complete: all of Blubber Blasen compiles, the benchmark and replays give the PC's checksums, cycles measured and compared with Calypsi | 0.4.0 | 2026-11-17 |
| M3 | The big levers: MIR, LIR and the code generator from MIR, static timing and budgets, value ranges and pointer compression; faster than Calypsi | 0.4.0 | 2026-11-18 |
| M4 | Fine tuning, started early: block layout by a profile, peephole rules, loop and branch optimizations | 0.4.0 | 2026-11-18 |
| M6 | Standing alone: dampf's own assembler and linker | 0.4.0 | 2026-11-18 |
| M5 | Blubber Blasen builds its ROM with the dev kit, from its own repository | 0.6.0 | 2026-11-19 |
| M7 | Windows and Linux: Rachael and the desktop frontend in the kit, the `superdevkit` command with `init`, `build`, `run`; the examples and Blubber Blasen run on the desktop from the kit | 0.7.0 | 2026-11-19 (the game's switch handed over to it) |
| M8 | The web: the same games in the browser; Blubber Blasen's web build from the kit | 0.8.0 | 2026-11-22 (the game's switch handed over to it) |
| M9 | The optimizer: what was left of M3; tests and a canonical form first, then register allocation | 0.9.0 | 2026-11-23 (what was left moved to M10 and M12) |
| M10 | Language, platform and usability: C in interrupts, hand-written assembly, the subset decisions on `char` and enums | 0.10.0 | 2026-11-26 |

M6 was done ahead of M5 and is part of 0.4.0, and M5, concluded after 0.5.0 was in use, took the
free 0.6.0 ([ADR-6F60ZDMF](adr/6F60ZDMF-m5-is-concluded-as-060-and-a-version-between.md)).

## Changes to the plan

- **After M1.** The design discussion had added three items to M1: the Java CPU emulator with
  cycle counting, cycle counts in the assembly comments, and the three IR levels with text format
  and verifier (and HIR/MIR interpreters to M2). M1 was completed by its original definition
  without them. They moved to the start of M3, because the IR levels and interpreters protect
  lowering and optimization passes, which do not exist before M3, and the M2 measurements work
  with Mesen. M2 was split into steps so the first measurement of the whole game came early, the
  checkpoint was added, and the playable ROM was separated from M2.
- **During M3.** The playable ROM was built before the game's own SNES port needs it, so that
  every iteration of dampf can be played and the toolchain is tested on the real console.
- **During M3.** dampf was measured against Calypsi on the same game version and is faster (M3's
  goal). What is left was estimated and ordered; FastROM was added as a platform step, and 816-tcc
  dropped from the comparisons (about 3.6 times slower than dampf).
- **During M3.** The design for value ranges ([subrange-types.md](subrange-types.md)) was added.
  Inferred ranges and pointer compression (planned for M4 in that design) went into M3, because
  they need no language change and the profile points at them. Declared subrange types wait until
  a profile shows a range dampf cannot infer.

- **At version 0.4.0** (2026-11-18). M3 and M4 were concluded: their goals as first set were
  partly reached (faster than Calypsi; the frame budget not yet), and too much stayed open in
  them while too little was planned after them. What was left moved to new milestones: the
  optimizations of M3 to M7, the fine tuning of M4 to M10, and the work found in the review of
  that day ([archive/status-2026-11-18.md](archive/status-2026-11-18.md)) to M5, M8 and M9. M5
  changed from publishing to cleanup and publishing, an alpha for use from Blubber Blasen's
  repository; dampf's own preprocessor moved from M5 to M8. Versions came in: milestone N is
  0.N.0 ([ADR-6F3CJT1P](adr/6F3CJT1P-milestone-n-is-version-0n0-and-the-first-public.md)).

- **The dev kit** (2026-11-18). dampf became the compiler of superdevkit, a development kit for
  SNES games held in this repository (ADR
  [ADR-6F3CJX8T](adr/6F3CJX8T-superdevkit-is-the-project-and-dampf-is-its.md)). M5 changed to the game
  building with the dev kit; publishing left M5 and is not scheduled.

- **The targets** (2026-11-18). After a brainstorming session
  ([summary](brainstorm/superdevkit-targets_2026-11-18.md)) the dev kit got its desktop and web
  targets, split by target after M5: M7 Windows and Linux, M8 the web (ADR-6F3CJZBY and ADR-6F3CK03Z;
  the plan is [superdevkit-transition.md](archive/superdevkit-transition.md)). The milestones planned
  before moved from M7–M10 to M9–M12; this history names them by their old numbers until then.

- **M8 narrowed to the web** (2026-11-22). M8 was to end with Blubber Blasen dropping its whole
  PC side. Its web build can go with M8, but its desktop frontend stays until the kit's window
  has the game's development tools (the bot, start options, text in the view, stepping back in a
  replay), which no step of M8 planned. They moved to M10 (usability), so that 0.8.0 is the web.

- **M9 concluded** (2026-11-23). M9's list of speedups had no finish line, so it was closed
  with what was built, for a first public version. The speedups left (rows 2 to 5, 7 and 9, and
  32-bit arithmetic) moved to M12, and the subset decisions 2 and 3 to M10.

- **The roadmap split** (2026-11-24). What is done moved from the roadmap to this file
  (ADR-6FK7WP0H).

- **During M10** (2026-11-24 to 2026-11-26). The `switch` as a jump table moved up from M12.
  The intrinsics planned for M10 were dropped: the multiplier is used for every product since
  M9, a division by a constant is a multiplication, and DMA and the wait for the vertical blank
  are console API calls; the hardware divider for a divisor that fits a byte went to M12.
  `restrict` was measured as about zero on the examples and rejected
  ([optimizations-log.md](optimizations-log.md)). Subset decision 3 (16-bit pointers into RAM)
  moved to M12, as an optimization a program cannot see. Less memory for a compile left M10:
  dampf's own speed and memory are tuned once it is more or less feature complete. Function
  pointers in constant tables were built and not merged, since no game uses them (PR #59). The
  console API got a new design after the shadow state of ADR-6FMHDB6H had been built and
  measured (ADR-6FNPPCSX). C in interrupt handlers needed two steps the brainstorm had postponed
  or not foreseen: copies of a function per context (ADR-6FQ4NSHE), and two published copies,
  since a short tick on the SNES published before the IRQ's row (ADR-6FRDS030).

## M0 to M2

- **M0.** The decisions in subset.md are implemented as recommended until decided. The hint macros
  keep their working names, from `<dampf.h>`.
- **M1.** Stepping through C in Mesen was checked by hand.
- **M2, step 1: ROMs larger than 32 KB.** Code and constant data spread over several banks, calls
  across banks, no object across a bank boundary; `exec/banks.c` spans four banks.
- **Step 2: the game's benchmark.** `bench.c` and all of `src/game` compile; all 3,610 ticks match
  the PC (`dev/bench_game.sh`). The game's finding 5 (`(unsigned)` casts in `video.c`) is in
  the snapshot, so all 16 files pass the front end.
- **Step 3: the replays.** A converter turns a replay into an input table in ROM;
  `plus_bot_seed42.bbr` (3,000 ticks, 50 checksums) matches on the PC and on the SNES.
- **Step 4: cycles per function.** `dev/profile_game.sh bench|replay` and `dampf profile`.
- **Step 5: first reference function.** `level_solid_px` by hand (`reference/`,
  `dampf build --replace`); dampf's naive code was 4.5 times slower.

## Checkpoint after M2

The question was whether the optimizations reach the budget or the game also has to do less
work. **Decided: compiler and game in parallel**, with 30 Hz game logic on the heaviest ticks as
the fallback; the numbers and the options weighed are in ADR
[ADR-6F0R6P51](adr/6F0R6P51-the-compiler-and-the-game-work-in-parallel-towards.md). The request to the
game is the game's finding 1 (forwarded to it).

## M3 and M4

- **MIR.** Lowering with SSA, a text format that parses back (`dampf.mir.MirParser`), verifier and
  interpreter; the whole game runs on MIR with the PC's checksums. The optimizing pipeline is
  built next to the naive code generator, which keeps working on the AST: an independent reference
  catches more bugs than one that shares code with what it checks.
- **The code generator from MIR** (now the default), correct on all test layers.
- **The CPU emulator** with master cycles and exact memory speeds; passes the SingleStepTests
  native-mode suite and matches Mesen on the benchmark (checksums and master cycles).
- **Cycle counts in the assembly comments**, in CPU cycles per instruction and per straight-line
  C line.
- **MIR passes:** width narrowing (B), constant multiplication and division (I), the standard
  optimizations (H), compare and branch in one.
- **Inferred value ranges** (`Ranges`, `RangeNarrow`): constants, operations, conditions on the
  path, loops, loop bounds for the timing analysis, parameter ranges from the call sites
  (`ParamRanges`), conditions between two values, narrowing after widening.
- **Values in registers and the direct page:** reloads of what A holds left out, φs written
  directly, frames in low RAM with the leaves in the direct page and shared places, inlining,
  indexed addressing, byte loads, short branches, `INC`/`DEC`, `STZ`. Register allocation across
  instructions in the form the 65816 allows: scheduling for hand-overs in A, comparisons and
  offsets taken from A, loop counters in X. Later steps of row 2: values every use takes from A
  are not stored, narrow switches, copied returns, indexes through pointers in Y, loop counters in
  X next to an index in Y, loop tests on the offset, sums as pointers for an index in Y.
- **Induction variables** (`Induction`).
- **Pointer compression** for pointers into a single object, within functions and through
  parameters (`PointerOffsets`, `PointerParams`).
- **Static timing:** best and worst case per function in CPU cycles, checked against the emulator
  on every call. `DAMPF_RANGE` bounds loops by hand, checked on the PC and in the MIR interpreter,
  at no cost on the SNES. `DAMPF_BUDGET` is checked at build time (an error over the budget or
  without a worst case, a warning above 90 %).
- **FastROM** (row 1): banks $80+ at 3.58 MHz, the default; `--slowrom` for a SlowROM cartridge.
  Calypsi gains as much with FastROM.
- **`CMP #0` left out** where the instruction before set the flags (row 6).
- **Hardware registers at constant addresses** (row 12): `STA f:$00210B` instead of a pointer
  through `dampf_ptr`. It matters for the playable ROM's vertical blank, which the benchmark does
  not measure.
- **Block layout by a profile** (`dampf profile --blocks`, `dampf build --block-counts`), opt-in.

## The playable ROM

Built 2026-11-17, ahead of the game's own SNES port, so that every iteration of dampf can be played
and the toolchain is tested on what only the console shows.

- **The platform layer** is `lib/snes`, in C: the console API of `sdevkit/snes.h` (PVSnesLib's
  calls, renamed, and the extensions; `snes.c`), and the main loop (`main.c`). It reaches the hardware through
  pointers to `volatile` memory and needs one language feature of its own, the cast from a pointer
  to an integer for DMA source addresses ([subset.md](subset.md), *Hardware access*).
- **No interrupt handler.** The loop waits for the vertical blank, then copies the OAM shadow and
  calls `game_vblank`. C code called from an NMI in the middle of a tick would overwrite the frames
  of the code it interrupted, since dampf keeps them at fixed places.
- Sound effects and save RAM are built ([sound.md](sound.md), `sdevkit_sram`), and the game plays
  the effects.
- **Building and checking:** while the game's code was here, a script built its playable ROM and
  another played it in Mesen for a minute with scripted input (screenshots; no byte may reach
  VRAM, CGRAM or OAM outside a vertical blank), and the replay test played a recording through
  the platform layer. The examples' checks do the same for every example now (ADR
  [ADR-6F60Z819](adr/6F60Z819-the-examples-replace-blubber-blasen-s-snapshot-as.md)).

The console showed things the benchmark could not. A tick that needs more than one frame waits for
the next vertical blank, and the benchmark leaves out what `video.c` costs on the hardware. With the
naive code generator the vertical blank code is so slow that the room tiles never fit
(the game's finding 2, forwarded to it). The console figure (frames per tick during a
minute of play, measured again on 2026-11-18) is in
[optimizations.md](optimizations.md#where-things-stand).

## M5

Concluded on 2026-11-19 as version 0.6.0 (ADR
[ADR-6F60ZDMF](adr/6F60ZDMF-m5-is-concluded-as-060-and-a-version-between.md)): Blubber Blasen builds its
SNES ROM from its own repository with a local install of the kit (`build_snes.sh`: dampf,
`lib/snes`, `superdevkit sfx`), and the ROM plays four recordings of 36,000 ticks each (Arcade,
with the CPU partner, Plus, Plus with the partner) with the PC's checksum on every tick, which
covers the sprite table and both tilemaps; its DSP writes equal Rachael's. The examples build for
the SNES and play in Mesen without losing a byte.

On the way: `tools/` became `dev/` (nothing in it was for other games), `platform/snes` became
`lib/snes`, and the console API (`lib/include/sdevkit/snes.h`) and the main loop with its frame
rhythm (`lib/include/sdevkit/loop.h`) belong to the library ([lib/README.md](../lib/README.md)).
Every name a game can use starts with `sdevkit_` or `SDEVKIT_`, the desktop build checks that a
game calls nothing else, and `tools/sdevkit_rename.py` converts code from the old names; the game
switched, and the deprecated header that kept them went (ADR
[ADR-6F3CK2AR](adr/6F3CK2AR-every-name-of-the-console-api-starts-with.md)). dampf can be called by a
game: argument files (`@file`, `--config`), every error of a run reported, `<string.h>` with
`memset` and `memcpy`. Two miscompilations were found and fixed: a loop counter kept in X stepped
before a later store whose address was formed from its old value
(`exec/regress_x_step_address.c`), and, found by the game with the default inlining limit, a
branch whose two sides start a loop each wrote both start values before branching while the
frame layout let them share a place (`exec/regress_branch_phis.c`). The converter
`tools/png2snes.py` (since M7 a part of the `superdevkit` command) and the examples came with it,
and the game's snapshot left the repository
([ADR-6F60Z819](adr/6F60Z819-the-examples-replace-blubber-blasen-s-snapshot-as.md)).

Its steps beyond the goal moved: random programs and execution tests that reach the optimizer to
M9, the book's getting started on `hello` to the release work, the ROM on a console to the work
that waits for hardware.

## M7

Started early, during M5 (2026-11-18), because prototyping and checking a game is so much
quicker on the desktop: the pseudo SNES is in the kit as [Rachael](../lib/README.md#rachael) (`lib/rachael`, with 16 x 16 background
tiles, and row y of the picture as scanline y + 1 like the SNES), with a desktop frontend for
Linux (`lib/desktop`, raylib 5.5) and a headless check that plays an example's `check.txt` in a second
(`ExamplesTest`, which also plays every example's check in dampf's emulator and compares a
checksum per tick with the desktop). The examples give the same picture on the SNES and on the desktop,
pixel for pixel (`dev/compare_example.sh`); that comparison found a bug in `lib/snes`, whose
`oamSetVisible` (now `sdevkit_oam_set_visible`) moved a sprite at a negative x to the right
edge. Both are built on WSL: Linux with gcc, Windows with the MSVC of the Windows side
([ADR-6F60Z709](adr/6F60Z709-win64-is-built-with-msvc-for-now-from-wsl.md)), which plays with the gamepads WSL does not
see and gives the same checksums and pictures as Linux.

Built in M7 on 2026-11-19: sound effects on both platforms ([docs/sound.md](sound.md), ADR
[ADR-6F60ZC7C](adr/6F60ZC7C-sound-effects-come-from-sfx-json-and-rachael-plays.md)); Schach and
Steinschlag use them; save RAM too (`sdevkit_sram`, `dampf --sram`; CatchMe keeps its high score
there); and the debug view with the registrations of the console API (F1 on the desktop, empty on
the SNES, where dampf removes the calls); and the `superdevkit` command with `init`, `build` and
`run` for `snes`, `linux` and `win64` ([ADR-6F3CJZBY](adr/6F3CJZBY-the-superdevkit-command-builds-a-project-from-its.md)),
on a project file, `superdevkit.toml`, which every example has instead of its build script; the
converters are Java in it, so building needs nothing but Java and a C compiler; `init` writes a
`CLAUDE.md` into the new project that points to the kit's guides, `lib/C_STYLE.md` and
`lib/SNES_HARDWARE.md`; and music on the desktop from the files the project lists, a stub on the
SNES ([ADR-6F3CK03Z](adr/6F3CK03Z-desktop-and-web-are-for-prototyping-with-sound.md), with the names
of [ADR-6F3CK2AR](adr/6F3CK2AR-every-name-of-the-console-api-starts-with.md); [sound.md](sound.md#music)).
The steps are in [superdevkit-transition.md](archive/superdevkit-transition.md#m7-windows-and-linux-070).

Concluded on 2026-11-19 as version 0.7.0, with one part of its goal left to the game: the examples
build and run on Linux and Windows from the kit, and Blubber Blasen's switch to the kit's desktop
build was handed over to it (the game's finding 1, sent the same day); the game's frontend and
build script go when it has switched.

## M8

Built on 2026-11-22. `superdevkit build web` compiles a game with Emscripten's `emcc` the way the
desktop compiles it with gcc, and links it with Rachael, the desktop's frontend, `lib/web` and
raylib's web package; the browser runs the desktop's `main.c`, whose loops wait for the browser's
frames with Asyncify and a 60 Hz clock
([ADR-6FCVAA30](adr/6FCVAA30-the-web-runs-the-desktop-s-frontend-which-waits.md)). The page starts
with a click, keeps the save RAM in IndexedDB and offers F11's recording and F12's screenshot as
downloads; `superdevkit run web` serves it on this machine. The symbol check reads WebAssembly
objects. `ExamplesTest` plays every example's check in headless Chromium and compares the
checksum of every tick, the sound and the recording with the SNES's
([ADR-6FD1Y6W2](adr/6FD1Y6W2-the-web-s-main-takes-check-and-the-examples-test.md)), and the debug
view is only in a build with `--debug`, so a page put online gives its players none
([ADR-6FD7KEJV](adr/6FD7KEJV-on-the-web-the-debug-view-is-only-in-a-build-with.md)).
The steps are in [superdevkit-transition.md](archive/superdevkit-transition.md#m8-the-web-080).

Concluded on 2026-11-22 as version 0.8.0, narrowed to the web (see [Changes to the plan](#changes-to-the-plan)):
Blubber Blasen builds and plays its checks in the browser from the kit unchanged, and its switch
to the kit's web build was handed over to it (finding 3 of
[game-findings.md](game-findings.md)).

## M9

Built from 2026-11-20 to 2026-11-23, in the order of the plan in
[archive/compiler-flaws.md](archive/compiler-flaws.md), an analysis of why the same loop costs
between 25 and 138 cycles a round depending on its spelling: tests and a canonical form before
register allocation. Of the rows of the speedup plan (now in M12 of the roadmap):

- **Register allocation (row 2).** By 2026-11-21 (the compiler flaws, step B), places get their
  addresses on the LIR (`PlaceColouring`, ADR-6F8NBG4P) and go to X or Y by cost
  (`RegisterChoice`), also for one loop only.
- **8-bit stretches (row 3).** 8-bit mode across C lines within a block (`ByteStretches`), and
  loops over bytes in 8 bits from entry to exit (`ByteLoops`, 2026-11-21).
- **Value ranges from array bounds (row 9).** An index into an array needs no range for its
  access (2026-11-20, ADR-6F8NBESR).

Also built:

- **Random programs and execution tests that reach the optimizer's patterns** (from M5).
  `RandomLoopProgram` ([testing.md](testing.md)) generates the loops and pointer walks the
  optimizer's passes work on.
- **Constant shifts written out** (built 2026-11-22). Every shift by a constant below the width
  is code without a loop: a 32-bit shift by 16 or more moves a word, one by 3 to 15 builds each
  word from both, a 16-bit one above 8 swaps the bytes first; before, all of these looped, a
  32-bit shift by 16 about 600 cycles.
- **The gaps the C style guide measures** ([lib/C_STYLE.md](../lib/C_STYLE.md), each a rule that
  `StyleGuideTest` checks; the change that closes a gap changes or retires its rule):
  - a loop counter's width changed the cost of the loop: gone on 2026-11-20 with
    `slow-u8-counter` (unsigned byte ranges) and `slow-u16-counter-words` (the loop test moved
    to the derived offset, which also retired `slow-word-array`);
  - an array with a 16-bit index went through a 24-bit pointer unless a `DAMPF_RANGE` bounded
    the index: gone on 2026-11-20 with `fast-range-index` (ADR-6F8NBESR);
  - a pointer walk cost more than indexing (`o->x; o++`): gone on 2026-11-20 (`same-pointer-walk`
    now);
  - a multiplication of two bytes called `dampf_mul16`, the SNES's 8 x 8 multiplier was not
    used: gone on 2026-11-22, the runtime multiplies on it and two bytes need no call
    (`same-multiply-width` is now `fast-multiply-bytes`);
  - a signed division by a power of two kept its rounding correction when a `DAMPF_RANGE` proved
    the value not negative: gone on 2026-11-22; `slow-signed-divide` stays for values dampf knows
    nothing about;
  - 32-bit operands went through frame slots (`hash ^= key` copied all four words): gone for bit
    operations and tests for equality on 2026-11-23 (`same-32-bit-bits`); `slow-32-bit` stays for
    arithmetic and ordered comparisons;
  - a constant shift above 8 was a loop (`shl 9` as `ldx #9; asl; dex; bne`; found in Schach's
    Zobrist table): gone on 2026-11-22 with `slow-shift-above-8`.
- **The inlining limits checked on the examples** (2026-11-21): no pair of limits was best for
  every example, and a callee called once in a loop is now inlined up to 600 instructions
  ([optimizations-log.md](optimizations-log.md#a-large-function-called-once-in-a-loop-is-inlined-2026-11-21-on-the-examples)).

Row 6, comparisons on the flags: Done for `CMP #0`. `BIT` is measured and not built (2026-11-23): `BIT #mask` costs what `AND #mask` costs, so at most 0.07 % of the examples' work on average and 0.4 % on Mode 7 would go ([optimizations.md](optimizations.md#tried-and-rejected)). Most remaining `CMP #imm` compare with other constants and feed `BCC`/`BCS`.

Concluded on 2026-11-23 as version 0.9.0, with what was left moved to M10 and M12 (see
[Changes to the plan](#changes-to-the-plan)).

## M6

dampf assembles and links itself, so ca65 and ld65 are no longer needed and using dampf needs
nothing but Java. dampf only uses a small part of ca65 (its own output) and of ld65 (its own
memory map), so both stay as the oracle in the tests: the ROM dampf builds itself must be
byte-identical to the one ca65 and ld65 build from the same assembly, and the debug information
must still let Mesen show the C source. It was planned as the last step, since it changes nothing in
the code dampf generates. It was decided on 2026-11-18 that it comes: dampf should need nothing but Java. It was done the same day,
ahead of M5 ([archive/transition-to-lir.md](archive/transition-to-lir.md), phase C): `dampf build` uses its own
assembler and linker, `--assembler ca65` keeps the old path, and the tests build every program
both ways.

## Built for M10 and M11, released in 0.9

Built on 2026-11-22, before M10 and M11 were concluded; the rest of M10 is [below](#m10), what is
left of M11 in the roadmap.

- **dampf's own preprocessor** (ADR-6FD4GDN5): using dampf needs nothing but Java. A subset of
  C99's: what the kit's code and the game use, macro expansion exactly after the standard
  (Prosser's algorithm); `__VA_OPT__`, `#line`, `#include_next` and gcc's extensions are
  rejected. `clang -E` is the oracle in the tests, on every C file dampf compiles in the
  repository and on random macro programs.
- **The game's development tools in the kit's window**, so that Blubber Blasen can drop its own
  desktop frontend (`src/platform`, kept beside the kit's by the game's ADR-0010): `--seed` and
  `--set name=value` for how a game starts, a bot as a toggle the game registers, `--mute`,
  read-only lines in the debug view (`sdevkit_dbg_text`, `_value`), `--jump` and a tick back in
  a replay (Shift+F6), defines per target in `superdevkit.toml` (ADR-6FEWEE95), and filled boxes
  and arrows in the view (`sdevkit_dbg_fill`, `_arrow`).
- **A build step before the build, and `superdevkit gen`.** `[build] before` in
  `superdevkit.toml` runs a game's own converters first (ADR-6FEVZD02), and `superdevkit gen`
  runs only the conversions, for a build system of the game's own (ADR-6FEW1T2S).
- **Measuring a game with only a superdevkit home** (M11): `dampf.emu.ExampleBench` writes the
  work of every tick with a fourth argument, and `ExampleCheck` and `dev/replay.sh` write the
  ROM's sound in the format of the desktop's `sound.txt`.

## M10

Language, platform and usability. Built from 2026-11-24 to 2026-11-26 and concluded on
2026-11-26 as version 0.10.0; three parts were built earlier and released in 0.9
([above](#built-for-m10-and-m11-released-in-09)). What it promised: C in interrupt handlers,
hand-written assembly with the code generator from MIR, and the subset decisions on `char` and
enums. On the way it also got a new console API, which every game had to move to.

The C language and its code:

- **gcc's `-Woverflow` warning**: a constant that does not fit the type it initialises or is
  assigned to (`uint8_t w[] = { 420 }`) is reported where gcc reports it (2026-11-24). It hid a
  bug in CatchMe that only the desktop build showed.
- **Subset decision 1, plain `char` for text**: a `char` value is only copied, tested and
  compared with `==` and `!=`
  ([ADR-6FM063YZ](adr/6FM063YZ-plain-char-is-text-a-char-value-is-only-copied.md), 2026-11-24).
- **Subset decision 2, enum objects in one byte**: an enum object takes only values of its enum,
  in the smallest width that holds them
  ([ADR-6FKZFDJ3](adr/6FKZFDJ3-enum-objects-take-only-values-of-their-enum-and.md), 2026-11-24).
- **`switch` as a jump table** (moved up from M12): a `switch` becomes a table where that costs
  fewer cycles than its compares, from 4 to 7 cases depending on the range check
  ([ADR-6FM7MWGX](adr/6FM7MWGX-a-switch-becomes-a-jump-table-where-that-costs.md), 2026-11-24).
  On the examples the work per tick is the same; the worst cases went down. Open: weighing the
  cases by a profile (`--block-counts`), since a table treats every case as equally likely and
  Steinschlag, whose first case is the common one, got 0.4 % slower.
- **A constant on an index into the operand**: `a[i + 1]` of a global is `sta a+1,x`, the sum
  never computed (2026-11-25); the examples' work 0.7 % lower, every one's code smaller
  ([log](optimizations-log.md#a-constant-on-an-index-into-the-operand-2026-11-25-on-the-examples)).

Hand-written assembly:

- **Hand-written assembly with the code generator from MIR**: `DAMPF_ASM("file.s")` on a C
  function, one convention for both code generators, cycles and safety checks from dampf's own
  reading of the assembly, and the assembly checked against the C body in every build
  ([ADR-6FMATG2V](adr/6FMATG2V-hand-written-assembly-replaces-a-c-function.md), 2026-11-24).
  Er-Tüp's two reference functions are now measured against the optimized code.

C in interrupt handlers, in five steps; the design is the
[brainstorm summary](brainstorm/interrupts-and-asm_2026-11-24.md), part 1:

- **Interrupt contexts, the groundwork of C in interrupt handlers**: `DAMPF_INTERRUPT(irq|nmi)`,
  frames and a direct page per context, an entry stub (about 97 cycles beyond the handler, 102
  for the NMI), no hardware multiply in interrupt code, the timing per handler, and NMI and
  timer IRQs in dampf's emulator
  ([ADR-6FM9TXV4](adr/6FM9TXV4-interrupt-handlers-run-in-contexts-of-their-own.md), 2026-11-24).
  An assembly function in interrupt code is rejected.
- **A function reached from several contexts compiled once per context**: the main code, the
  IRQ and the NMI may call the same functions, the kit's and the game's; each context gets a
  copy with frames of its own, `static` locals and constants stay shared, and a program whose
  contexts share nothing is not changed
  ([ADR-6FQ4NSHE](adr/6FQ4NSHE-a-function-reached-from-several-contexts-is.md), 2026-11-25).
  The game's IRQ needs it to call the console API.
- **The data the tick publishes for interrupt code**: `SDEVKIT_PUBLISH` in `sdevkit/publish.h`
  copies one struct after each tick into a copy interrupt code does not read, and `game_vblank`
  makes it the current one, so interrupt code reads, complete, the data of the tick the picture
  was made from, on the SNES with any tick length as on the desktop and the web; about 10.5
  cycles per byte per tick
  ([ADR-6FPV1EQV](adr/6FPV1EQV-interrupt-code-reads-a-copy-of-the-tick-s-data.md), 2026-11-25,
  two copies since
  [ADR-6FRDS030](adr/6FRDS030-interrupt-code-reads-the-copy-of-the-tick-whose.md), 2026-11-26).
  Read by the game's IRQ and the kit's NMI.
- **The game's IRQ**: `game_irq` in `sdevkit/irq.h`, before the picture row `sdevkit_irq_line`
  sets, on the SNES and on Rachael; it reads its published data, waits for the horizontal blank
  (`sdevkit_irq_wait_hblank`) and writes, about 25 CPU cycles of writes measured in Mesen, and
  Rachael counts a register written before the wait as written while the picture is drawn.
  `dev/irq_check` splits the screen twice and matches Mesen pixel for pixel, with short ticks and
  long ones ([ADR-6FRX1GYC](adr/6FRX1GYC-the-game-s-irq-runs-at-a-picture-row-and-writes.md),
  2026-11-26).
- **The kit's NMI running the vertical blank of a game that publishes**: `SDEVKIT_PUBLISH` also
  defines the kit's NMI handler, which switches to the newest published copy and runs the game's
  `vblank` in every vertical blank, also while a tick overruns; `game_vblank` waits for the
  switch, so the NMI never switches to a copy a tick still fills; about 120 to 140 cycles beyond
  `vblank` a frame and a page of RAM; the desktop's check runs `vblank` twice and counts what the
  second run changes. A game that does not publish, the examples and the optional layers keep
  `game_vblank` in the loop, byte for byte the same
  ([ADR-6FS0T4WB](adr/6FS0T4WB-the-kit-s-nmi-runs-the-vertical-blank-of-a-game.md), 2026-11-26).

The kit and its platform:

- **The console API's core and its optional layers**: the core is the hardware at once (every
  PPU register by `sdevkit_reg_write`, DMA, VRAM, CGRAM and OAM copies, HDMA), and the kit writes
  nothing in the vertical blank itself; above it optional layers in portable C (the registers in
  memory with marks, a sprite list, an upload queue with a budget the same on both platforms, the
  buttons pressed), one commit for all of them in `game_vblank`; the desktop counts a register
  written while the picture is drawn
  ([ADR-6FNPPCSX](adr/6FNPPCSX-the-console-api-is-the-hardware-at-once-with.md), 2026-11-25,
  after the [brainstorm](brainstorm/console-api-shadow-state_2026-11-24.md)'s shadow state was
  built and turned out to cost a game that knew the vertical blank's rules). The examples draw the
  same pictures, pixel for pixel, the core's faster than before (with DMA's time counted).
- **Unreachable functions left out**: dampf drops the functions a program cannot reach and the
  globals only they used, so an optional layer a game does not call costs it nothing
  ([ADR-6FNWEJAA](adr/6FNWEJAA-dampf-leaves-out-the-functions-a-program-cannot.md), 2026-11-25);
  the examples' code 4 to 48 % smaller.
- **The project's defines kept out of `lib/snes`**: `--lib` compiles a library's files without
  the `-D` defines, so `[snes]` reaches only the game's files, as on the desktop and the web
  ([ADR-6FKYXP8C](adr/6FKYXP8C-dampf-compiles-a-library-s-files-without-the.md), 2026-11-24).
- **The SNES facts out of the code generators**: the banks of RAM, where frames, the direct page,
  the stack and the variables lie, how an I/O register is reached and the multiplier's
  registers are answered by `dampf.target.SnesTarget`, which both code generators ask; the
  generated code is byte for byte the same
  ([ADR-6FPTPCDM](adr/6FPTPCDM-the-snes-facts-the-compiler-asks-about-live-in.md), 2026-11-25).

## Built after M10, released in 0.10.1

Built on 2026-11-26, after M10 and ahead of M11; released as 0.10.1 on 2026-11-27.

- **Rachael draws the picture modes beyond 1 and 7**: modes 0, 2, 3 and 4 with offsets per tile,
  256-colour tiles (`superdevkit png tiles8`) and direct colour, mosaic, mode 7's second layer
  (EXTBG), VMAIN's address remapping and VRAM, CGRAM and OAM read back by DMA; `examples/modes`
  shows each, the same as Mesen pixel for pixel
  ([ADR-6FSHCP06](adr/6FSHCP06-rachael-draws-modes-0-to-4-mosaic-and-extbg-the.md), 2026-11-26).
  Left undrawn until a game needs them: modes 5 and 6, high resolution, overscan and interlace,
  and HDMA with indirect tables.

## Built after 0.10.1

Built on 2026-11-27, ahead of M11; not released yet.

- **Inlined functions without code of their own**: a function inlined at every call keeps its
  line in the timing report and its budget, but no code in the ROM
  ([ADR-6FVMCY48](adr/6FVMCY48-a-function-inlined-at-every-call-keeps-its-timing.md),
  2026-11-27); the examples' code 25 to 35 % smaller, their work per tick unchanged.
- **Bank `$7F` for variables**: when a program's variables do not fit into bank `$7E`, dampf
  moves the largest of them to bank `$7F` until the rest fits, reaches them there with long
  addresses and gives pointers to them bank `$7F`; the start-up clears and copies them, the
  check of assembly functions uses only the part of `$7F` they leave free, and variables that
  fit into neither bank are a compile error. A program that fits into `$7E` builds the same ROM
  as before, byte for byte (the examples did); `exec/bank_7f.c` and `exec/bank_7f_pointers.c`
  run with variables in both banks
  ([ADR-6FVM9QWS](adr/6FVM9QWS-variables-that-do-not-fit-into-bank-7e-go-to-bank.md), 2026-11-27).

## Publishing

The dev kit went public as superdevkit on 2026-11-23, after version 0.9.0. What it took:

- **Own examples instead of Blubber Blasen.** Done on 2026-11-19 (ADR-6F60Z819): the snapshot,
  its replays and its hand-written function left the repository, and on 2026-11-19 the git
  history too (`git filter-repo`, force pushed), with the game's tools and tests. The examples
  took over their jobs: Er-Tüp is a test case of a whole game's size,
  `reference/` holds functions of the examples, and `dev/bench_examples.sh` measures the work per
  tick of all of them against a baseline: many small calls (Schach), 16- and 32-bit arithmetic
  (Mandelbrot), arrays and bytes (Steinschlag), fixed point, particles, a pair loop and state
  machines (Er-Tüp), and the small ones. Until then every speed decision was measured on one game
  (the optimizations of 2026-11-18 made Blubber Blasen's play 13 % faster but the execution tests
  only 0.9 %, part of it from parameters chosen on that game, the inlining limit and the block
  order: [optimizations-log.md](optimizations-log.md#optimizations-on-the-branch-optimise-2026-11-18)).
- **The game's own findings** went to the game on 2026-11-19 and left this repository;
  `docs/game-guide.md` is written from them there.
- **The documents** say nothing that only holds for the private repository.
- **The repository renamed** to superdevkit, with dampf as its compiler: done on 2026-11-19 (on
  GitHub, then still private), the README and the documents follow it.
- **The book's getting started on `hello`**, with the book before the
  release ([ADR-6F60ZB4N](adr/6F60ZB4N-the-book-is-brought-in-step-before-a-release-not.md); from M5).
- **Before the first release** ([ADR-6F60Z9QJ](adr/6F60Z9QJ-the-release-is-a-zip-per-system-with-a-java.md)):
  building for the SNES needs nothing but the download: the converters are Java in the CLI
  (M7), and dampf has its own preprocessor (M10).
- **Hosting:** a release on GitHub, a zip per system with a Java runtime (jlink) and raylib (ADR
  ADR-6F60Z9QJ): `./gradlew releaseZip` and the workflow `release.yml`, which builds and
  checks the Linux and the Windows zip on their own systems and makes a draft release from a
  version tag. The workflow `pages.yml` puts the book and the seven examples on GitHub Pages when
  a release is published (ADR-6FHKAC1X). 0.9.1 was the first release with downloads.

## Answered questions

- An own assembler and linker: yes, in M6 (ADR
  [ADR-6F3CJKHB](adr/6F3CJKHB-dampf-assembles-and-links-itself-with-ca65-and.md)). An own debugger
  stays a non-goal ([ADR-6F3CJMJ5](adr/6F3CJMJ5-mesen-is-the-debugger-and-the-dbg-file-means-what.md)).
- Build tool: Gradle ([ADR-6F0R624X](adr/6F0R624X-java-25-antlr-with-the-grammars-v4-c-grammar-and.md)).
- Stage 2 platform API: no intrinsics needed; a platform layer in C with volatile register access
  ([ADR-6F0R6NS5](adr/6F0R6NS5-the-platform-layer-is-c-with-volatile-pointers-and.md)).
- Whole ROMs headless in tests: Mesen 2's test runner (`Mesen.exe --testrunner rom.sfc
  script.lua`), as the game's `tools/snesbench` does.
- Mesen shows the C source with dampf's debug output: yes.
- Licence: Apache License 2.0 with LLVM Exceptions
  ([ADR-6F0R6SCV](adr/6F0R6SCV-dampf-is-under-the-apache-license-20-with-llvm.md)).
- A feature in Rachael as a dummy, which she does not draw but reports when it is used: not
  enough to let a game use it. A feature of the picture is usable only when `lib/snes` and
  Rachael both draw it (2026-11-24,
  [ADR-6FKTVDE8](adr/6FKTVDE8-a-hardware-feature-is-usable-only-when-the-snes.md)).
- What the Calypsi investigation finds: dampf is faster on the same scene; the order of the
  optimizations follows the plan above. Why Calypsi's results drifted from the PC's from tick 596
  on was not found; it is not pursued, since the game left Calypsi on 2026-11-19 and the kit no
  longer compares with it.

## Relation to Blubber Blasen

Blubber Blasen is dampf's first user and was its main test case: its deterministic replays were
the oracle for correctness, and its busiest scene the benchmark for speed, on a copy of its code
in this repository. Since 2026-11-19 the dev kit's examples are both, and the game's code is no
longer here ([ADR-6F60Z819](adr/6F60Z819-the-examples-replace-blubber-blasen-s-snapshot-as.md)); the
game builds with the kit from its own repository. What dampf finds worth changing in the game goes
to a findings file the user forwards (`docs/game-findings.md`, created when there is one); when dampf is finished, `docs/game-guide.md` explains how
to write game code that dampf compiles well.
