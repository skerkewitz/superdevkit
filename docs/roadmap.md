# Roadmap

What comes next for superdevkit: the milestones still open and what is left of them, the work
without a milestone, and ideas. What is done and released is in
[history.md](history.md) (ADR-6FK7WP0H).

## Where things stand

M0 to M9 are concluded ([history.md](history.md#where-dampf-stands)). During play the game took
0.79 frames per tick on 2026-11-18 against a goal of 0.6, so the compiler work goes on while the
game reduces its own work in parallel; the current numbers are in
[optimizations.md](optimizations.md#where-things-stand). Next: language, platform and usability
(M10), then timing and debugging (M11), then fine tuning (M12).

## Versions

The version is the dev kit's; it is in `build.gradle.kts`, and `dampf --version` prints it.
Milestone N is released as version 0.N.0; between milestones the last number rises for every
change worth naming, features included. Every version before 1.0 is a pre-release: the subset,
the command line and the files dampf writes may change from one to the next. What 1.0 needs is
an open question: the frame budget met, the subset decisions taken, C in interrupts, or a set
of users. The rule is ADR
[ADR-6F3CJT1P](adr/6F3CJT1P-milestone-n-is-version-0n0-and-the-first-public.md), amended by
[ADR-6F3CJX8T](adr/6F3CJX8T-superdevkit-is-the-project-and-dampf-is-its.md) and
[ADR-6F60ZDMF](adr/6F60ZDMF-m5-is-concluded-as-060-and-a-version-between.md). When a milestone
is concluded, it moves to [history.md](history.md).

## Milestones

| Milestone | Goal | Version | State |
| --- | --- | --- | --- |
| M10 | Language, platform and usability: C in interrupts, hand-written assembly, the subset decisions on `char` and enums | 0.10.0 | In progress |
| M11 | Timing and debugging: master cycles, bounds for more loops, locals and types in Mesen | 0.11.0 | Planned |
| M12 | Fine tuning: what was left of M4 and M9, until the frame budget is met | 0.12.0 | Planned |

The order follows the likely gain and what each milestone needs from the one before.

## M10: language, platform and usability (0.10.0)

Built of it so far, and released in 0.9: dampf's own preprocessor, the game's development tools
in the kit's window, and a build step before the build with `superdevkit gen`
([history.md](history.md#built-for-m10-and-m11-released-in-09)). Built since, for 0.10.0 (each
moves to the history with that release):

- **gcc's `-Woverflow` warning**: a constant that does not fit the type it initialises or is
  assigned to (`uint8_t w[] = { 420 }`) is reported where gcc reports it (2026-11-24). It hid a
  bug in CatchMe that only the desktop build showed.
- **The project's defines kept out of `lib/snes`**: `--lib` compiles a library's files without
  the `-D` defines, so `[snes]` reaches only the game's files, as on the desktop and the web
  ([ADR-6FKYXP8C](adr/6FKYXP8C-dampf-compiles-a-library-s-files-without-the.md), 2026-11-24).
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
- **Hand-written assembly with the code generator from MIR**: `DAMPF_ASM("file.s")` on a C
  function, one convention for both code generators, cycles and safety checks from dampf's own
  reading of the assembly, and the assembly checked against the C body in every build
  ([ADR-6FMATG2V](adr/6FMATG2V-hand-written-assembly-replaces-a-c-function.md), 2026-11-24).
  Er-Tüp's two reference functions are now measured against the optimized code.
- **Interrupt contexts, the groundwork of C in interrupt handlers**: `DAMPF_INTERRUPT(irq|nmi)`,
  frames and a direct page per context, an entry stub (about 97 cycles beyond the handler, 102
  for the NMI), no hardware multiply in interrupt code, the timing per handler, and NMI and
  timer IRQs in dampf's emulator
  ([ADR-6FM9TXV4](adr/6FM9TXV4-interrupt-handlers-run-in-contexts-of-their-own.md), 2026-11-24).
  Not for games yet: an assembly function in interrupt code is rejected.
- **A constant on an index into the operand**: `a[i + 1]` of a global is `sta a+1,x`, the sum
  never computed (2026-11-25); the examples' work 0.7 % lower, every one's code smaller
  ([log](optimizations-log.md#a-constant-on-an-index-into-the-operand-2026-11-25-on-the-examples)).
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

Left:

- **C in interrupt handlers.** Frames have fixed places (ADR
  [ADR-6F0R668G](adr/6F0R668G-no-recursion-no-free-function-pointers-and-no-heap.md)), so C called from
  an NMI or IRQ would overwrite the frames of the code it interrupted, and the platform layer
  waits for the vertical blank instead. Nearly every SNES game has an NMI handler, and raster
  effects need an IRQ handler. The design is decided
  ([brainstorm summary](brainstorm/interrupts-and-asm_2026-11-24.md), part 1): the kit owns the
  NMI, the game an IRQ in C; interrupt contexts with frames and a direct page of their own; no
  hardware multiply in interrupt code; interrupts read only data the tick published; Rachael
  calls the IRQ at its line; then the NMI runs the frame's work. The contexts are built (above).
  Left, in this order: the data the tick publishes, with a barrier the optimizer respects before
  the swap; Rachael calling the IRQ at its line, and an API to set the line, which make the IRQ
  usable by games; then the NMI running `game_vblank` and the frame's work, where `game_vblank`
  joins the NMI context and may call nothing the tick calls unless a function can be compiled
  once per context (idea 2 of the summary).
- **The SNES facts out of the code generators** (DBR `$7E`, the frame addresses, long I/O
  addresses) into the target ([architecture.md](architecture.md#core-and-target)).

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
  there. This is how impossible path combinations get expressed, a question
  [timing.md](timing.md#open-questions) left open until the bounds turned out too pessimistic;
- level 3: DRAM refresh, HDMA and interrupts at frame level. Open is how the budget learns
  which HDMA channels a game has on, since a game turns them on at run time
  (`sdevkit_hdma_enable`).

Better C debugging in Mesen: Mesen shows the C source and the names of global variables from
the `.dbg` file, and that is all. Of a C symbol (`csym`) it reads only the name and the assembly
symbol it belongs to, and the type that cc65 and dampf write is always `"00"`. dampf's users
would gain from variables shown with their C type (`uint8_t`, signed `int16_t`), struct fields,
and the locals of a function in their direct page places; open is how Mesen shows a place that
several values share at different times. The cc65 format already has fields
for part of this (a storage class `sc` and an offset on `csym`) that Mesen ignores. The plan:
dampf first fills what the format has, then a small patch teaches Mesen to read it, offered to
MesenCE, where Mesen is maintained now ([ADR-6F3CK1V6](adr/6F3CK1V6-mesence-is-the-emulator-for-the-console-checks.md)); NES developers who use cc65 would gain from it too. The patch waited
until the dev kit was public, since a patch from a compiler with users is easier to argue for. With it, the code from MIR
writes the processor state line in the assembly as the naive code generator does.

## M12: fine tuning (0.12.0)

What was left of M4 and M9, until the frame budget is met. The rows keep the numbers of the
plan made on 2026-11-17 (the rows not listed are done, [history.md](history.md#m3-and-m4)); the
gains are estimates from the profile's shares, not measurements, and several ideas that looked
good measured zero ([optimizations.md](optimizations.md#tried-and-rejected)).

| # | Idea | Likely gain | Effort | Notes |
| --- | --- | --- | --- | --- |
| 2 | Register allocation: values in A, X and Y over several instructions instead of a direct page place each | 10-20 % | high | Loads and stores of direct page values are the largest share of the cycles. With one accumulator, choosing what lives where is the hard part. Places get their addresses on the LIR and go to X or Y by cost since M9 ([history.md](history.md#m9)). Left: the choice of the index register is still selection's (`findXCounters`, `findYIndexes`; without the first the pair loops cost up to 0.9 % more, without the second up to 1.8 %), `FrameLayout`'s predictions for selection (kept until a model of the whole function replaces them, ADR-6FGF7X0N), and `Schedule`'s order for a computed value stored at an index into a global, which needs a cost model (value last makes `hud_lives` 12 % faster and Schach's `next` slower); `findForwards` and `Schedule` are still needed (2026-11-22: without them the examples are 1.6 and 0.5 % slower); registers across calls were measured at 0.35 % at most and are not built. |
| 3 | 8-bit stretches: the M flag followed (`REP`/`SEP`) across blocks, `uint8_t` code in 8-bit mode | 5-10 % | high | Fewer `AND #$00FF`, and 8-bit accesses are a cycle cheaper. Within a block and in loops over bytes this is done since M9 ([history.md](history.md#m9)). Left: the general placement across blocks. Measured then: the other switches are 0.5 to 4.5 % of the examples' work and nearly all around byte stores, where they are needed. |
| 4 | Arrays of structs as one array per field (catalogue D, `DAMPF_SPLIT`), with HIR to do it in | 5-10 % in the bubble loops | high | `separate`, `bubbles_update` and `bubbles_draw` are 30 %. Needs a subset decision or a hint, since the layout then differs from the PC. Measured against it: in the game's pair loop (its finding 9) one array per field was 16 % slower for the counted loop and 1 % slower for the sorted list, and in `exec/pair_loops.c` 1.9 % slower for the counted loop; a loop that moves two fields of every element gains about 12 % (the spelling families `struct-fields` and `field-arrays`). The game reaches its objects through pointers (1,516 `->`), so a split needs to know where each pointer points. Taken up when a measured loop gains clearly, as the hint `DAMPF_SPLIT`. |
| 5 | 8-bit index registers, arrays aligned to pages | 2-4 % | high | `LDA abs,X` a cycle cheaper; needs the X flag followed and the alignment in the memory map. |
| 7 | Calling convention (catalogue C): the first argument in A, `JMP` for tail calls | 1-3 % | medium | `ground_px` is called 12,000 times in 800 ticks. |
| 8 | The block layout by a profile by default | ~1 % | low | Opt-in today (`--block-counts`); needs a profile of the program (`dampf bench --profile`). |
| 9 | Value ranges from array bounds | 1-2 % | medium | More 8-bit narrowing, fewer masks. An index into an array needs no range for its access since M9 (ADR-6F8NBESR), but the value ranges do not learn from that access yet. |
| 10 | Superoptimizer for short hot sequences | 1-3 % | high | Gains spread thin. |
| 11 | `<<8` as a byte store, `MVN` for copies (and `memcpy`), unrolling by hint | <1 % each | low to medium | Large locally, small on the whole game. |

Also in M12:

- 32-bit arithmetic and ordered comparisons, which still go through frame slots (the rule
  `slow-32-bit` of [lib/C_STYLE.md](../lib/C_STYLE.md));
- profile-guided inlining, and code size spent for speed where the profile says it is hot;
- a tuning option (`--tune`) that tries the parameters chosen today on one game (the inlining
  limit, the block order) on the user's own workload and keeps the fastest build, each candidate
  checked against the PC's results ([optimizations.md](optimizations.md));
- `DAMPF_TABLE`, `DAMPF_PIN_X`, `DAMPF_LIKELY`/`DAMPF_UNLIKELY` and the other transforming
  hints ([hints.md](hints.md));
- the CPU's hardware divider (16 by 8 bits) for a division by a variable whose divisor dampf's
  value ranges prove to fit a byte, chosen automatically, built when a measured program divides
  by variables on a hot path (the examples do it once: Schach's opening book). It replaces the
  intrinsics planned in M10 (dropped on 2026-11-24): the multiplier is used for every product
  since M9, a division by a constant is a multiplication, DMA and the wait for the vertical
  blank are console API calls, and dampf places constant data in its banks itself;
- bank `$7F` for variables ([memory-map.md](memory-map.md));
- subset decision 3, 16-bit pointers into RAM ([subset.md](subset.md); moved from M10 on
  2026-11-24): a pointer stored in a struct field, a global or an array takes 4 bytes today, and
  a pointer that provably points only into bank `$7E` could take 2, with cheaper loads and
  dereferences. A program cannot see the size (decision 5 rejects a `sizeof` that differs), so it
  is an optimization, decided and built when a measured program shows the gain; few pointers are
  stored today (the game has 4 pointer fields). It goes with bank `$7F` for variables: once
  variables can live there, a 2-byte pointer also needs a proof of its bank;
- subrange types (`DAMPF_RANGED`, `DAMPF_AS`), only when a profile shows a hot value whose range
  dampf cannot infer ([subrange-types.md](subrange-types.md), ADR
  [ADR-6F0R6Q5P](adr/6F0R6Q5P-value-ranges-are-inferred-first-and-subrange-types.md)).

The split of struct arrays (4) waits until a measured loop asks for it. The rest has to come
from the game doing less work: the pair loop of `separate` (16 %) and the CPU partner (the
game's finding 1, forwarded to it), as the [checkpoint after M2](history.md#checkpoint-after-m2)
concluded.

## Open without a milestone

- **`checkBook` in CI.** It runs by hand in a release's preparation (ADR
  [ADR-6F60ZB4N](adr/6F60ZB4N-the-book-is-brought-in-step-before-a-release-not.md)).
- **The block order checked on the examples.** It was chosen on Blubber Blasen
  ([optimizations-log.md](optimizations-log.md#optimizations-on-the-branch-optimise-2026-11-18));
  the inlining limits have been measured on the examples since
  ([history.md](history.md#m9)), the block order not yet. The documents say which set a
  measurement comes from.
- **Blubber Blasen on the kit's desktop and web builds.** Both switches were handed over to the
  game with M7 and M8. Its own desktop frontend and its own web build go when it has switched;
  the tools its frontend needed are in the kit's window since 0.9.
- **More reference functions** ([reference/](../reference/README.md)), now that `DAMPF_ASM`
  measures them against the optimized code: candidates are the pair loop, Mandelbrot's
  iteration and Steinschlag's cave. Later, from the same brainstorm: `dampf eject`, which writes
  a function's optimized code as the start of a `.s` file, and arguments in registers together
  with M12's calling convention (row 7).
- **Less memory for a compile.** Blubber Blasen took 1.8 GB. Moved out of M10 on 2026-11-24:
  dampf's own speed and memory are tuned once it is more or less feature complete.

## Not scheduled for the dev kit

The ROM on a real SNES or a flash cartridge, on NTSC and PAL, and the DMA margin
(`SETUP_LINES`) measured there, until there is hardware (from M5;
[superdevkit-transition.md](archive/superdevkit-transition.md#57-on-a-console-needs-the-hardware)).
Music on the SNES (the calls are a stub there; sound effects and save RAM are built).
macOS (raylib runs there; nobody can test it now), `superdevkit bundle` (one folder with every
target, ready to publish), the desktop running the ROM in an emulator and Mesen as the desktop.
Function pointers in constant tables, which ADR-6F0R668G allows: they were built on the branch
`m10-function-tables` and not merged on 2026-11-24, since no example and no game uses them and a
`switch` on an enum does the same (PR #59 has the example and the reasons); the branch stays for
when a game asks for them.

## Future ideas

Ideas without a milestone; one becomes a step of a milestone when it is decided.

- **Inlined functions without a copy of their own.** A function inlined at every call keeps its
  body in the ROM, so the timing report has it (ADR-6FNWEJAA). Dropping that body but keeping its
  entry in the report would shrink the examples' code by another 25 to 35 % (measured on
  2026-11-25).

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

## History

The concluded milestones, the changes to the plan and the answered questions are in
[history.md](history.md).
