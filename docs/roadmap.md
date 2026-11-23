# Roadmap

Milestones, versions, risks and open questions. The plan was set at the kickoff on 2026-11-17
and revised several times since; on 2026-11-18, at version 0.4.0, M3 and M4 were concluded and
what was left of them moved to the milestones after M5 (see [History](#history)).

## Where dampf stands

M0 to M8 are concluded. M0 to M4 and M6 with version 0.4.0 (2026-11-18): all of Blubber Blasen
compiles, the benchmark and the replays give the PC's checksums, on the SNES and in a playable
ROM, dampf's code is faster than Calypsi's, and dampf assembles and links itself. M5 as version
0.6.0 (2026-11-19): Blubber Blasen builds its SNES ROM with the dev kit from its own repository,
and the ROM plays its recordings with the PC's checksum on every tick. M7 as version 0.7.0
(2026-11-19): the desktop (Linux, Windows) from the kit, sound effects, save RAM, music on the
desktop, the debug view, and the `superdevkit` command. M8 as version 0.8.0 (2026-11-22): the web
(`superdevkit build web` and `run web`), every example checked in the browser against the SNES.
During play the game took
0.79 frames per tick on 2026-11-18 against a goal of 0.6, so the compiler work goes on while the
game reduces its own work in parallel; the current numbers are in
[optimizations.md](optimizations.md#where-things-stand).

dampf is the compiler of a development kit for SNES games, superdevkit; this repository is
superdevkit's (ADR
[ADR-6F3CJX8T](adr/6F3CJX8T-superdevkit-is-the-project-and-dampf-is-its.md)). Next: the optimizer (M9),
then language, platform and usability (M10).

## Versions

The version is the dev kit's; it is in `build.gradle.kts`, and `dampf --version` prints it.
Milestone N is released as version 0.N.0; between milestones the last number rises for every
change worth naming, features included. M6 was done ahead of M5 and is part of 0.4.0, and M5,
concluded after 0.5.0 was in use, took the free 0.6.0. Every version before 1.0 is a pre-release: the subset,
the command line and the files dampf writes may change from one to the next. Publishing is a
step of its own, not scheduled yet (see [Publishing](#publishing-not-scheduled)). What 1.0
needs is an open question. The rule is ADR
[ADR-6F3CJT1P](adr/6F3CJT1P-milestone-n-is-version-0n0-and-the-first-public.md), amended by
[ADR-6F3CJX8T](adr/6F3CJX8T-superdevkit-is-the-project-and-dampf-is-its.md) and
[ADR-6F60ZDMF](adr/6F60ZDMF-m5-is-concluded-as-060-and-a-version-between.md).

## Milestones

| Milestone | Goal | Version | State |
| --- | --- | --- | --- |
| M0 | Define the subset: Blubber Blasen's `src/game` scanned, [subset.md](subset.md) written | 0.4.0 | Done 2026-11-17 |
| M1 | End to end: a small function compiled, assembled, linked, run in a test, stepped through in Mesen | 0.4.0 | Done 2026-11-17 |
| M2 | Naive but complete: all of Blubber Blasen compiles, the benchmark and replays give the PC's checksums, cycles measured and compared with Calypsi | 0.4.0 | Done 2026-11-17 |
| M3 | The big levers: MIR, LIR and the code generator from MIR, static timing and budgets, value ranges and pointer compression; faster than Calypsi | 0.4.0 | Done 2026-11-18 |
| M4 | Fine tuning, started early: block layout by a profile, peephole rules, loop and branch optimizations | 0.4.0 | Done 2026-11-18 |
| M6 | Standing alone: dampf's own assembler and linker | 0.4.0 | Done 2026-11-18 |
| M5 | Blubber Blasen builds its ROM with the dev kit, from its own repository | 0.6.0 | Done 2026-11-19 |
| M7 | Windows and Linux: Rachael and the desktop frontend in the kit, the `superdevkit` command with `init`, `build`, `run`; the examples and Blubber Blasen run on the desktop from the kit | 0.7.0 | Done 2026-11-19 (the game's switch handed over to it) |
| M8 | The web: the same games in the browser; Blubber Blasen's web build from the kit | 0.8.0 | Done 2026-11-22 (the game's switch handed over to it) |
| M9 | The optimizer: what was left of M3; tests and a canonical form first, then register allocation | 0.9.0 | In progress |
| M10 | Language, platform and usability: C in interrupts, intrinsics, the open subset decisions, the game's development tools in the kit's window (the preprocessor and the tools are built) | 0.10.0 | In progress |
| M11 | Timing and debugging: master cycles, bounds for more loops, locals and types in Mesen | 0.11.0 | Planned |
| M12 | Fine tuning: what was left of M4, until the frame budget is met | 0.12.0 | Planned |

What each concluded milestone reached is in [History](#history). The order after M5 follows the
likely gain and what each milestone needs from the one before; it was kept when M5 was concluded.

## M8: the web (0.8.0)

The target `web` with Emscripten, save RAM in the browser, `superdevkit run web`; the examples
and Blubber Blasen in the browser, after which the game drops its own web build. Its own desktop
frontend can go too, now that the kit's window has the game's development tools (M10). The steps are
in [superdevkit-transition.md](archive/superdevkit-transition.md#m8-the-web-080).

Concluded on 2026-11-22 as version 0.8.0 ([History](#m8)).

## M9: the optimizer (0.9.0)

What was left of M3. The rows are from the plan made on 2026-11-17 and keep its numbers; the
gains are estimates from the profile's shares, not measurements, and several ideas that looked
good measured zero ([optimizations.md](optimizations.md#tried-and-rejected)). The done rows (1
FastROM, 12 hardware registers at constant addresses) are in [History](#history).

| # | Idea | Likely gain | Effort | Notes |
| --- | --- | --- | --- | --- |
| 2 | Register allocation: values in A, X and Y over several instructions instead of a direct page place each | 10-20 % | high | Loads and stores of direct page values are the largest share of the cycles. With one accumulator, choosing what lives where is the hard part. Today one pass per pattern (loop counters in X, indexes in Y, values handed on in A, `PointerRemat`, `ByteStretches`); a common model of what lives where comes first. Built by 2026-11-21 (the compiler flaws, step B): places get their addresses on the LIR (`PlaceColouring`, ADR-6F8NBG4P) and go to X or Y by cost (`RegisterChoice`), also for one loop only. Left: the choice of the index register is still selection's (`findXCounters`, `findYIndexes`; without the first the pair loops cost up to 0.9 % more, without the second up to 1.8 %), `FrameLayout`'s predictions for selection (kept until a model of the whole function replaces them, ADR-6FGF7X0N), and `Schedule`'s order for a computed value stored at an index into a global, which needs a cost model (value last makes `hud_lives` 12 % faster and Schach's `next` slower); `findForwards` and `Schedule` are still needed (2026-11-22: without them the examples are 1.6 and 0.5 % slower); registers across calls were measured at 0.35 % at most and are not built. |
| 3 | 8-bit stretches: the M flag followed (`REP`/`SEP`) across blocks, `uint8_t` code in 8-bit mode | 5-10 % | high | Fewer `AND #$00FF`, and 8-bit accesses are a cycle cheaper. Built: 8-bit mode across C lines within a block (`ByteStretches`), loops over bytes in 8 bits from entry to exit (`ByteLoops`, 2026-11-21). Measured then: the other switches are 0.5 to 4.5 % of the examples' work and nearly all around byte stores, where they are needed; the general placement is not built. |
| 4 | Arrays of structs as one array per field (catalogue D, `DAMPF_SPLIT`), with HIR to do it in | 5-10 % in the bubble loops | high | `separate`, `bubbles_update` and `bubbles_draw` are 30 %. Needs a subset decision or a hint, since the layout then differs from the PC. Measured against it: in the game's pair loop (its finding 9) one array per field was 16 % slower for the counted loop and 1 % slower for the sorted list, and in `exec/pair_loops.c` 1.9 % slower for the counted loop; a loop that moves two fields of every element gains about 12 % (the spelling families `struct-fields` and `field-arrays`). The game reaches its objects through pointers (1,516 `->`), so a split needs to know where each pointer points. Taken up when a measured loop gains clearly, as the hint `DAMPF_SPLIT`. |
| 5 | 8-bit index registers, arrays aligned to pages | 2-4 % | high | `LDA abs,X` a cycle cheaper; needs the X flag followed and the alignment in the memory map. |
| 6 | Comparisons on the flags: `BIT` for bit tests | 2-4 % for the whole row; `BIT` alone at most 0.07 % (measured) | dropped | Done for `CMP #0`. `BIT` is measured and not built (2026-11-23): `BIT #mask` costs what `AND #mask` costs, so at most 0.07 % of the examples' work on average and 0.4 % on Mode 7 would go ([optimizations.md](optimizations.md#tried-and-rejected)). Most remaining `CMP #imm` compare with other constants and feed `BCC`/`BCS`. |
| 7 | Calling convention (catalogue C): the first argument in A, `JMP` for tail calls | 1-3 % | medium | `ground_px` is called 12,000 times in 800 ticks. |
| 9 | Value ranges from array bounds | 1-2 % | medium | More 8-bit narrowing, fewer masks. Built 2026-11-20 as far as an index into an array goes (ADR-6F8NBESR). |

Also in M9:

- **Random programs and execution tests that reach the optimizer's patterns** (from M5, see
  [Risks](#risks)). Done: `RandomLoopProgram` ([testing.md](testing.md)) generates the loops and
  pointer walks the optimizer's passes work on.
- **16-bit pointers into RAM** ([subset.md](subset.md), decision 3), and enum objects in one byte
  (decision 2), each once its subset decision is taken.
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

The order (decided 2026-11-20) was the plan in [archive/compiler-flaws.md](archive/compiler-flaws.md),
an analysis of why the same loop costs between 25 and 138 cycles a round depending on its
spelling: tests and a canonical form before register allocation; rows 2, 3 and 9 and the gaps of
the style guide above were its steps B3, B5, R3 and C/L. It was done on 2026-11-21; what it left
is in rows 2 to 4. The split of struct arrays (4) once a measured loop asks for it, and 5 and 7,
come after it. The rest has to come from the
game doing less work: the pair loop of `separate` (16 %) and the CPU partner (the game's finding
1, forwarded to it), as the checkpoint after M2 concluded.

## M10: language, platform and usability (0.10.0)

- **C in interrupt handlers.** Frames have fixed places (ADR
  [ADR-6F0R668G](adr/6F0R668G-no-recursion-no-free-function-pointers-and-no-heap.md)), so C called from
  an NMI or IRQ would overwrite the frames of the code it interrupted, and the platform layer
  waits for the vertical blank instead. Nearly every SNES game has an NMI handler, and raster
  effects need an IRQ handler. The functions an interrupt reaches can get a frame area of their
  own; open is how a handler is declared and what it saves
  ([optimizations.md](optimizations.md#knowing-the-machine) has the idea).
- **Intrinsics** for the hardware multipliers, the divider and DMA ([subset.md](subset.md),
  *Intrinsics*).
- **The open subset decision** 1 (plain `char` for text only) in [subset.md](subset.md); 2 and
  3 come with M9. Decision 4 is taken: `goto` stays rejected (ADR
  [ADR-6F3CJVHC](adr/6F3CJVHC-goto-and-labels-stay-rejected-and-a-forward-and.md)).
- **Function pointers in constant tables**, allowed by design and not built.
- **Hand-written assembly with the code generator from MIR:** `--replace` works only with the
  naive one, and a linked-in function has no way to declare its calling convention, direct page
  use and cycles for the timing analysis.
- **dampf's own preprocessor** (built 2026-11-22, ADR-6FD4GDN5): using dampf needs nothing but
  Java. A subset of C99's: what the kit's code and the game use, macro expansion exactly after
  the standard (Prosser's algorithm); `__VA_OPT__`, `#line`, `#include_next` and gcc's
  extensions are rejected. `clang -E` is the oracle in the tests, on every C file dampf compiles
  in the repository and on random macro programs.
- **Warnings where gcc warns by default**, first a constant that does not fit the type it
  initialises or is assigned to (`uint8_t w[] = { 420 }`): dampf truncates it as gcc does, but
  silently. It hid a bug in CatchMe (a cat's wait of 420 became 164) that only the desktop
  build's `-Woverflow` showed.
- **Less memory for a compile:** Blubber Blasen took 1.8 GB.
- **The SNES facts out of the code generators** (DBR `$7E`, the frame addresses, long I/O
  addresses) into the target ([architecture.md](architecture.md#core-and-target)).
- **`restrict`** or a no-alias hint of dampf's own (see [Open questions](#open-questions)).
- **The game's development tools in the kit's window** (built 2026-11-22), so that Blubber Blasen
  can drop its own desktop frontend (`src/platform`, kept beside the kit's by the game's ADR-0010):
  `--seed` and `--set name=value` for how a game starts, a bot as a toggle the game registers,
  `--mute`, read-only lines in the debug view (`sdevkit_dbg_text`, `_value`), `--jump` and a tick
  back in a replay (Shift+F6), defines per target in `superdevkit.toml` (ADR-6FEWEE95), and filled
  boxes and arrows in the view (`sdevkit_dbg_fill`, `_arrow`).
- **A build step before the build, and `superdevkit gen`** (built 2026-11-22). `[build] before` in
  `superdevkit.toml` runs a game's own converters first (ADR-6FEVZD02), and `superdevkit gen` runs
  only the conversions, for a build system of the game's own (ADR-6FEW1T2S).

## M11: timing and debugging (0.11.0)

Timing ([timing.md](timing.md#not-built-yet)):

- master cycles instead of CPU cycles, in the report, the comments and the budgets;
- the costliest path per C line, in the report and in budget errors, and best and worst case in
  the comments of lines with a branch;
- bounds for more loops: 37 of the game's functions have none; `DAMPF_TRIPS` for loops without
  a counter (postponed, [hints.md](hints.md));
- the measured cycles of budgeted functions checked in the emulator tests;
- worst cases of pair loops: a path that is rare in every pair (a hit that kills, drops an item,
  is collected) is counted in every pair, so Er-Tüp's `collide` is 5.4 million cycles at worst
  and a few thousand in play; its frame is guarded by a measured budget in `check.txt` instead
  (`budget T1 T2`, ExamplesTest). To be looked into: a hint for how often a path runs per call
  (`DAMPF_AT_MOST`, [hints.md](hints.md)), or a bound per call chain, would make budgets usable
  there;
- level 3: DRAM refresh, HDMA and interrupts at frame level.

Measuring a game with only a superdevkit home (built 2026-11-22): `dampf.emu.ExampleBench` writes
the work of every tick with a fourth argument, and `ExampleCheck` and `dev/replay.sh` write the
ROM's sound in the format of the desktop's `sound.txt`.

Better C debugging in Mesen: Mesen shows the C source and the names of global variables from
the `.dbg` file, and that is all. Of a C symbol (`csym`) it reads only the name and the assembly
symbol it belongs to, and the type that cc65 and dampf write is always `"00"`. dampf's users
would gain from variables shown with their C type (`uint8_t`, signed `int16_t`), struct fields,
and the locals of a function in their direct page places. The cc65 format already has fields
for part of this (a storage class `sc` and an offset on `csym`) that Mesen ignores. The plan:
dampf first fills what the format has, then a small patch teaches Mesen to read it, offered to
MesenCE, where Mesen is maintained now ([ADR-6F3CK1V6](adr/6F3CK1V6-mesence-is-the-emulator-for-the-console-checks.md)); NES developers who use cc65 would gain from it too. A patch from a compiler with
users is easier to argue for, so the patch waits until the dev kit is public. With it, the code from MIR
writes the processor state line in the assembly as the naive code generator does.

## M12: fine tuning (0.12.0)

What was left of M4, until the frame budget is met:

| # | Idea | Likely gain | Effort | Notes |
| --- | --- | --- | --- | --- |
| 8 | The block layout by a profile by default | ~1 % | low | Built (`--block-counts`), opt-in; needs a profile of the program (`dampf bench --profile`). |
| 10 | Superoptimizer for short hot sequences | 1-3 % | high | Gains spread thin. |
| 11 | `<<8` as a byte store, `switch` as a jump table, `MVN` for copies (and `memcpy`), unrolling by hint | <1 % each | low to medium | Large locally, small on the whole game. |

Also in M12:

- profile-guided inlining, and code size spent for speed where the profile says it is hot;
- a tuning option (`--tune`) that tries the parameters chosen today on one game (the inlining
  limit, the block order) on the user's own workload and keeps the fastest build, each candidate
  checked against the PC's results ([optimizations.md](optimizations.md));
- `DAMPF_TABLE`, `DAMPF_PIN_X`, `DAMPF_LIKELY`/`DAMPF_UNLIKELY` and the other transforming
  hints ([hints.md](hints.md));
- bank `$7F` for variables ([memory-map.md](memory-map.md));
- subrange types (`DAMPF_RANGED`, `DAMPF_AS`), only when a profile shows a hot value whose range
  dampf cannot infer ([subrange-types.md](subrange-types.md), ADR
  [ADR-6F0R6Q5P](adr/6F0R6Q5P-value-ranges-are-inferred-first-and-subrange-types.md)).

## Not scheduled for the dev kit

The ROM on a real SNES or a flash cartridge, on NTSC and PAL, and the DMA margin
(`SETUP_LINES`) measured there, until there is hardware (from M5;
[superdevkit-transition.md](archive/superdevkit-transition.md#57-on-a-console-needs-the-hardware)).
Music on the SNES (the calls are a stub there; sound effects and save RAM are built).
macOS (raylib runs there; nobody can test it now), `superdevkit bundle` (one folder with every
target, ready to publish), the desktop running the ROM in an emulator and Mesen as the desktop.

## Publishing (not scheduled)

The dev kit goes public as superdevkit when it is useful to someone other than Blubber Blasen.
What it takes:

- **Own examples instead of Blubber Blasen.** Done on 2026-11-19 (ADR-6F60Z819): the snapshot,
  its replays and its hand-written function left the repository, and on 2026-11-19 the git
  history too (`git filter-repo`, force pushed), with the game's tools and tests. The examples
  took over their jobs: Er-Tüp is a test case of a whole game's size,
  `reference/` holds functions of the examples, and `dev/bench_examples.sh` measures the work per
  tick of all of them against a baseline: many small calls (Schach), 16- and 32-bit arithmetic
  (Mandelbrot), arrays and bytes (Steinschlag), fixed point, particles, a pair loop and state
  machines (Er-Tüp), and the small ones. Still open: until then every speed decision was measured
  on one game (the optimizations of 2026-11-18 made Blubber Blasen's play 13 % faster but the
  execution tests only 0.9 %, part of it from parameters chosen on that game, the inlining limit
  and the block order:
  [optimizations-log.md](optimizations-log.md#optimizations-on-the-branch-optimise-2026-11-18));
  those parameters are to be checked on the set, and the docs say which set a measurement comes
  from.
- **The game's own findings** went to the game on 2026-11-19 and left this repository;
  `docs/game-guide.md` is written from them there.
- **The documents** say nothing that only holds for the private repository.
- **The repository renamed** to superdevkit, with dampf as its compiler: done on 2026-11-19 (on
  GitHub, still private), the README and the documents follow it.
- **The book's getting started on `hello`**, with the book before the
  release ([ADR-6F60ZB4N](adr/6F60ZB4N-the-book-is-brought-in-step-before-a-release-not.md); from M5).
- **Before the first release** ([ADR-6F60Z9QJ](adr/6F60Z9QJ-the-release-is-a-zip-per-system-with-a-java.md)):
  building for the SNES needs nothing but the download. Done: the converters are Java in the CLI
  (M7), and dampf has its own preprocessor (M10).
- **Hosting:** a release on GitHub, a zip per system with a Java runtime (jlink) and raylib (ADR
  ADR-6F60Z9QJ), built by GitHub Actions (which already builds every pull request, with cc65 for
  the tests that compare with ca65 and ld65 and without Mesen; `checkBook` after `build`,
  ADR-6F60ZB4N), the book on GitHub Pages.

## Future ideas

Ideas without a milestone; one becomes a step of a milestone when it is decided.

- **dampf in Compiler Explorer.** A local Compiler Explorer (godbolt) knows extra compilers from
  `etc/config/c.local.properties`; a wrapper script takes its gcc-style arguments (`-S -o out.s
  input.c`), calls dampf and turns `.dbg line, "file", N` into the `.file`/`.loc` directives
  Compiler Explorer maps the source lines with. That gives dampf next to gcc, diffs and links
  for an afternoon's work. On godbolt.org itself after publishing: it needs a stable release
  that installs unattended and a pull request to Compiler Explorer's `infra` repository.
- **`dampf explore`.** A local page served by dampf itself, compiling in the JVM, for what
  Compiler Explorer cannot show: the cycles per source line and per function (best and worst
  case, from the timing analysis), the MIR after each pass, the naive and the MIR backend side
  by side, and running the code in `dampf.emu` with its result and cycles against gcc's result
  on the PC. A few days for a first version; worth it once the cycle column is missed in
  Compiler Explorer.
- **`superdevkit studio`.** The CLI serves the labs (BRR Lab first, later graphics, tilemaps,
  sprites) from a small local server (the JDK's own, no dependencies) and opens them in a
  browser window in app mode (`--app=` of Chrome and Edge), which looks like an app without
  Electron or Tauri. A REST API reads and writes the files of the game's folder, so uploading and
  downloading go away, and it runs the kit's own conversions: the lab plays the BRR bytes of
  `superdevkit sfx` instead of its own JavaScript encoder, so what is heard is what the SNES
  plays. A lab only edits the files the CLI reads, so a human in the lab and an agent in the
  files work on the same source; changes on disk reach the open page by server-sent events.
  Needed from the start: listen on `127.0.0.1` only, a random token in the opened URL, every
  path confined to the project folder. The labs stay usable as plain pages. Under WSL the
  browser is started through `wslview` or `cmd.exe /c start`. Tauri is the step after, if a
  native app is wanted, since it wraps the same pages; `dampf explore` could share the server.
  A decision about the kit's shape, so an ADR record when it is taken.
- **A level editor that compiles pictures to tiles,** the first big lab of the studio: levels
  built from smart objects (stretched like a 9-patch, with random variants and colour styles)
  placed to the pixel, and the build makes the tiles, palettes and maps or says why the SNES
  cannot show it ([studio-editor-proposal.md](studio-editor-proposal.md)).
- **A spec of the game, and the AI builds the game from it.** The kit gives no format for the
  spec, only the content tools and a way to check behaviour
  ([game-spec-proposal.md](game-spec-proposal.md)).

### Tests dampf does not have yet

Eight kinds of tests from the survey of how the large compilers are tested, in the order proposed
on 2026-11-23. None is scheduled. Each step's place in the code, its change and how to tell that it
works are in [archive/testing-ideas.md](archive/testing-ideas.md), written on 2026-11-20; where
the two differ, this list is current.

1. **[V1, stress runs](archive/testing-ideas.md#v1-stress-runs).** A seed makes the optimizer's
   legal choices at random: passes skipped or reordered, other inlining limits, arbitrary block
   counts. Not built; the pass limit it builds on exists. The back end was rewritten without it.
   Its choices for selection's patterns and `FrameLayout` stay in the step, since those stay in
   the compiler (`findXCounters`, `findYIndexes`, ADR-6FGF7X0N).
2. **[V5, programs changed where they never ran](archive/testing-ideas.md#v5-programs-changed-where-they-never-ran).**
   Code a run never reaches is changed, and the result must stay the same. Not built. It should
   start from the execution tests and the random programs, which the sanitizer has checked,
   instead of the examples as the step says. It needs block counts from dampf's emulator.
3. **[V2, random joypad input](archive/testing-ideas.md#v2-random-joypad-input-on-the-examples).**
   The naive and the optimized build of an example play the same random input and must agree on
   every tick. Not built. First measure how much of each example its recorded input reaches, and
   build the fuzzer only if that is little: a difference can also be the game's own undefined
   behaviour, which someone then has to tell from a compiler bug.
4. **[V4, every rewrite on every input](archive/testing-ideas.md#v4-every-rewrite-checked-on-every-input).**
   Partly there: `SimplifyTest` checks `Simplify`'s rules on edge values. Open: all inputs for
   `i8`, and the LIR rewrites run on a bare CPU.
5. **[V8, a second opinion for the reference](archive/testing-ideas.md#v8-a-second-opinion-for-the-reference).**
   Partly there: "clang is the tests' reference on the PC"
   ([ADR-6FD2DGP0](adr/6FD2DGP0-clang-is-the-tests-reference-on-the-pc.md)) ended gcc's false
   bounds reports. Open: another compiler on a failure, to tell a wrong reference from a dampf
   bug, and the address sanitizer in the checked build.
6. **[V6, a reducer](archive/testing-ideas.md#v6-a-reducer-for-failing-programs).** A failing
   program shrinks to the lines that matter. Not built; `dev/bisect_passes.sh` already names the
   pass.
7. **[V3, random MIR](archive/testing-ideas.md#v3-random-mir).** Random programs in the optimizer's
   own form, run in the MIR interpreter before and after the passes. Not built.
8. **[V7, a mutation score](archive/testing-ideas.md#v7-a-mutation-score-for-each-pass).** PIT
   breaks one condition of a pass at a time and lists what no test notices. Not built, and whether
   PIT runs on Java 25 class files and JDK 27 has not been checked. Mutation checks are done by
   hand until then.

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

Open: the DMA margin waits for a console ([Not scheduled](#not-scheduled-for-the-dev-kit)).
Constant shifts longer than 8 were written out in M9 (2026-11-22).

## Risks

- **Miscompilation.** Mitigated by the test layers, the naive reference and the examples' checks
  as oracle, a checksum every tick against the PC ([testing.md](testing.md)). When one shows,
  `dampf.emu.RomDiff` names the global it breaks first.
- **The optimizer is tested by whole programs only in a few.** The execution tests and 50 random
  programs per build barely reached the optimizer's patterns; the optimizer was tested by whole
  programs: Blubber Blasen's benchmark and one replay until 2026-11-19, since then the examples'
  checks, a checksum every tick. Mitigated since by `RandomLoopProgram`, which generates the loops
  and pointer walks the optimizer's passes work on; more examples would still help.
- **Tuned on one game.** The inlining limit, the block order and the idioms the optimizer looks
  for came from Blubber Blasen. Mitigated by the examples' benchmark (`dev/bench_examples.sh`),
  on which they are still to be checked.
- **Never run on a console.** Everything is checked in dampf's emulator and in Mesen; FastROM,
  the DMA margin and timing at the edges have not run on hardware (not scheduled until there is
  hardware).
- **Different behaviour on PC and SNES.** Mitigated by fixed-width types only, exact promotion
  rules and the differential tests against the PC (clang).
- **The frame budget depends on the game.** The checkpoint after M2 concluded that the compiler
  alone cannot reach it; with the CPU partner the worst tick takes 3 frames.
- **Little human knowledge of the code.** About 23,000 lines were written in two days, mostly by
  AI agents. A bug like the miscompilation of 2026-11-19 needs someone who knows how the code generator
  works; today that knowledge is mostly in the documents and the ADR.
- **Scope creep.** The compiler could become the project and stall the game; the book and M6
  came before M5. Mitigated by running it in parallel and by milestones that are measured against
  Blubber Blasen.
- **Gains smaller than hoped.** Each optimization is measured; the catalogue was re-prioritised
  after M2.
- **A wrong hint silently produces wrong code.** Mitigated by checking every fact hint as an
  assertion on the PC, with the examples' checks running against them ([hints.md](hints.md)).

## Open questions

- **A feature in Rachael as a dummy** (2026-11-19, from the hardware guide). Is a feature that
  Rachael does not draw but reports when it is used enough to let a game use it? It keeps the
  desktop honest, but the desktop then cannot check that game's picture. Until it is decided, a
  feature is usable only when both `lib/snes` and Rachael draw it
  ([lib/SNES_HARDWARE.md](../lib/SNES_HARDWARE.md)).

- What 1.0 needs: the frame budget met, the subset decisions taken, C in interrupts, or a set of
  users.
- How Mesen shows locals that live in fixed direct page places, several values sharing a place
  (M11).
- How to express impossible path combinations, should the worst-case bounds turn out too
  pessimistic in practice ([timing.md](timing.md)).
- How `restrict` is checked on the PC in practice, and whether a dampf-specific no-alias hint is
  clearer than `restrict` ([hints.md](hints.md)).
- Which functions become reference benchmarks. Er-Tüp's `random16` and `sine` so far
  (`reference/`); candidates are the pair loop, Mandelbrot's iteration and Steinschlag's cave.
- How much HDMA the game uses, since it changes the frame-level budget ([timing.md](timing.md)).

## History

Dates are 2026-11-17 unless a line says otherwise. The measurements are in [optimizations-log.md](optimizations-log.md).

### Changes to the plan

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
  before moved from M7–M10 to M9–M12; the History above names them by their old numbers.

- **M8 narrowed to the web** (2026-11-22). M8 was to end with Blubber Blasen dropping its whole
  PC side. Its web build can go with M8, but its desktop frontend stays until the kit's window
  has the game's development tools (the bot, start options, text in the view, stepping back in a
  replay), which no step of M8 planned. They moved to M10 (usability), so that 0.8.0 is the web.

### M0 to M2

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

### Checkpoint after M2

The question was whether the optimizations reach the budget or the game also has to do less
work. **Decided: compiler and game in parallel**, with 30 Hz game logic on the heaviest ticks as
the fallback; the numbers and the options weighed are in ADR
[ADR-6F0R6P51](adr/6F0R6P51-the-compiler-and-the-game-work-in-parallel-towards.md). The request to the
game is the game's finding 1 (forwarded to it).

### M3 and M4

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

### M5

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
`tools/png2snes.py` and the examples came with it, and the game's snapshot left the repository
([ADR-6F60Z819](adr/6F60Z819-the-examples-replace-blubber-blasen-s-snapshot-as.md)).

Its steps beyond the goal moved: random programs and execution tests that reach the optimizer to
M9, the book's getting started on `hello` to the release work, the ROM on a console to the work
that waits for hardware.

### M7

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

### M8

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

Concluded on 2026-11-22 as version 0.8.0, narrowed to the web (see [Changes to the plan](#changes-to-the-plan)):
Blubber Blasen builds and plays its checks in the browser from the kit unchanged, and its switch
to the kit's web build was handed over to it (finding 3 of
[game-findings.md](game-findings.md)); its own desktop frontend goes with M10.

### M6

dampf assembles and links itself, so ca65 and ld65 are no longer needed and using dampf needs
nothing but Java. dampf only uses a small part of ca65 (its own output) and of ld65 (its own
memory map), so both stay as the oracle in the tests: the ROM dampf builds itself must be
byte-identical to the one ca65 and ld65 build from the same assembly, and the debug information
must still let Mesen show the C source. It was planned as the last step, since it changes nothing in
the code dampf generates. It was decided on 2026-11-18 that it comes: dampf should need nothing but Java. It was done the same day,
ahead of M5 ([archive/transition-to-lir.md](archive/transition-to-lir.md), phase C): `dampf build` uses its own
assembler and linker, `--assembler ca65` keeps the old path, and the tests build every program
both ways.

### Answered questions

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
