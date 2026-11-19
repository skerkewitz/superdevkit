# Architecture decision records

Decisions taken for dampf, oldest first. Each record is a file of its own in `docs/adr/`, named
by its number and the start of its title, and states the context, the decision, the
alternatives weighed where there were any, and what follows from it. To change a decision, add a
new record that supersedes or amends the old one instead of editing it, and give it a row in
this table. The status of a record is kept here only, so that an old record need not be edited
when a later one changes it. Records are cited by number ("ADR record 6"), with a link to the
file. `AdrTest` checks that the table and the files agree.

Records 1 to 25 were written on 2026-11-18 from the working documents, for decisions taken from
the kickoff on; the date is the day each decision was taken. An open question becomes a record
when it is decided, not before: until then it stays in [roadmap.md](roadmap.md) or the document
of its subject.

| No. | Decision | Status | Date |
|---|---|---|---|
| 1 | [dampf is a compiler of its own for one target, the SNES](adr/0001-dampf-is-a-compiler-of-its-own-for-one-target-the.md) | Accepted | 2026-11-17 |
| 2 | [Java 25, ANTLR with the grammars-v4 C grammar, and Gradle](adr/0002-java-25-antlr-with-the-grammars-v4-c-grammar-and.md) | Accepted | 2026-11-17 |
| 3 | [dampf compiles a subset of C, enforced by a checker after a broad parser](adr/0003-dampf-compiles-a-subset-of-c-enforced-by-a-checker.md) | Accepted | 2026-11-17 |
| 4 | [Every result matches gcc on x86-64 bit for bit](adr/0004-every-result-matches-gcc-on-x86-64-bit-for-bit.md) | Accepted | 2026-11-17 |
| 5 | [The whole program is compiled at once](adr/0005-the-whole-program-is-compiled-at-once.md) | Accepted | 2026-11-17 |
| 6 | [No recursion, no free function pointers and no heap, so frames have fixed places](adr/0006-no-recursion-no-free-function-pointers-and-no-heap.md) | Accepted | 2026-11-17 |
| 7 | [`gcc -E` preprocesses, without host headers or host macros](adr/0007-gcc-e-preprocesses-without-host-headers-or-host.md) | Accepted | 2026-11-17 |
| 8 | [Hints are macros, and a hint that states a fact is checked on the PC](adr/0008-hints-are-macros-and-a-hint-that-states-a-fact-is.md) | Accepted | 2026-11-17 |
| 9 | [All variables live in bank $7E, and DBR stays $7E](adr/0009-all-variables-live-in-bank-7e-and-dbr-stays-7e.md) | Accepted | 2026-11-17 |
| 10 | [Pointers are 4 bytes, and no object crosses a bank or exceeds 32 KB](adr/0010-pointers-are-4-bytes-and-no-object-crosses-a-bank.md) | Accepted | 2026-11-17 |
| 11 | [Structs have no padding, and a `sizeof` that differs from the PC is an error](adr/0011-structs-have-no-padding-and-a-sizeof-that-differs.md) | Accepted | 2026-11-17 |
| 12 | [LoROM with FastROM, code from bank $80, one segment per function, `JSL` for every call](adr/0012-lorom-with-fastrom-code-from-bank-80-one-segment.md) | Accepted | 2026-11-17 |
| 13 | [Three IR levels, and HIR waits until something needs it](adr/0013-three-ir-levels-and-hir-waits-until-something.md) | Accepted | 2026-11-17 |
| 14 | [LIR is basic blocks with explicit successors, and its text is ca65 source](adr/0014-lir-is-basic-blocks-with-explicit-successors-and.md) | Accepted | 2026-11-18 |
| 15 | [The naive code generator stays as an independent reference](adr/0015-the-naive-code-generator-stays-as-an-independent.md) | Accepted | 2026-11-17 |
| 16 | [gcc with UBSan is the oracle, and the tests compare every global](adr/0016-gcc-with-ubsan-is-the-oracle-and-the-tests-compare.md) | Accepted | 2026-11-17 |
| 17 | [dampf has its own emulator, validated against SingleStepTests and Mesen](adr/0017-dampf-has-its-own-emulator-validated-against.md) | Accepted | 2026-11-17 |
| 18 | [Every function gets a best and a worst case, and `DAMPF_BUDGET` fails the build](adr/0018-every-function-gets-a-best-and-a-worst-case-and.md) | Accepted | 2026-11-17 |
| 19 | [dampf assembles and links itself, with ca65 and ld65 as the oracle](adr/0019-dampf-assembles-and-links-itself-with-ca65-and.md) | Accepted | 2026-11-18 |
| 20 | [Mesen is the debugger, and the `.dbg` file means what ld65's means](adr/0020-mesen-is-the-debugger-and-the-dbg-file-means-what.md) | Accepted; amended by 33 | 2026-11-18 |
| 21 | [The platform layer is C with `volatile` pointers, and the main loop waits for the vertical blank](adr/0021-the-platform-layer-is-c-with-volatile-pointers-and.md) | Accepted | 2026-11-17 |
| 22 | [The compiler and the game work in parallel towards the frame budget](adr/0022-the-compiler-and-the-game-work-in-parallel-towards.md) | Accepted; amended by 40 | 2026-11-17 |
| 23 | [Value ranges are inferred first, and subrange types wait for a profile](adr/0023-value-ranges-are-inferred-first-and-subrange-types.md) | Accepted | 2026-11-17 |
| 24 | [A refactoring step may cost about 1 % speed if the back end gets much cleaner](adr/0024-a-refactoring-step-may-cost-about-1-speed-if-the.md) | Accepted | 2026-11-18 |
| 25 | [dampf is under the Apache License 2.0 with LLVM Exceptions](adr/0025-dampf-is-under-the-apache-license-20-with-llvm.md) | Accepted | 2026-11-17 |
| 26 | [Milestone N is version 0.N.0, and the first public version is an alpha](adr/0026-milestone-n-is-version-0n0-and-the-first-public.md) | Accepted; amended by 29 | 2026-11-18 |
| 27 | [`goto` and labels stay rejected, and a forward and outward form is the most dampf will allow](adr/0027-goto-and-labels-stay-rejected-and-a-forward-and.md) | Accepted | 2026-11-18 |
| 28 | [Options can be kept in an argument file, given by `--config` or `@file`](adr/0028-options-can-be-kept-in-an-argument-file-given-by.md) | Accepted | 2026-11-18 |
| 29 | [superdevkit is the project, and dampf is its compiler](adr/0029-superdevkit-is-the-project-and-dampf-is-its.md) | Accepted; amended by 32, 34, 40, 41 | 2026-11-18 |
| 30 | [One book for superdevkit, with dampf as a part of its own](adr/0030-one-book-for-superdevkit-with-dampf-as-a-part-of.md) | Accepted; amended by 43 | 2026-11-18 |
| 31 | [The superdevkit command builds a project from its TOML file and calls the compilers itself](adr/0031-the-superdevkit-command-builds-a-project-from-its.md) | Accepted; amended by 39, 41 | 2026-11-18 |
| 32 | [Desktop and web are for prototyping, with sound, save RAM and a debug view the game registers into](adr/0032-desktop-and-web-are-for-prototyping-with-sound.md) | Accepted; amended by 34, 44 | 2026-11-18 |
| 33 | [MesenCE is the emulator for the console checks and the debugger](adr/0033-mesence-is-the-emulator-for-the-console-checks.md) | Accepted | 2026-11-18 |
| 34 | [Every name of the console API starts with `sdevkit_`, and a game reaches nothing else](adr/0034-every-name-of-the-console-api-starts-with.md) | Accepted | 2026-11-18 |
| 35 | [The pseudo SNES is called Rachael](adr/0035-the-pseudo-snes-is-called-rachael.md) | Accepted | 2026-11-19 |
| 36 | [One ROM for NTSC and PAL, with NTSC's limits, and PAL a sixth slower for now](adr/0036-one-rom-for-ntsc-and-pal-with-ntsc-s-limits-and.md) | Accepted | 2026-11-19 |
| 37 | [Every game starts with the kit's splash screen, and a button shortens it](adr/0037-every-game-starts-with-the-kit-s-splash-screen.md) | Accepted; amended by 42 | 2026-11-19 |
| 38 | [The examples compare a checksum per tick on the SNES and the desktop, and the game names its state](adr/0038-the-examples-compare-a-checksum-per-tick-on-the.md) | Accepted | 2026-11-19 |
| 39 | [win64 is built with MSVC, for now from WSL](adr/0039-win64-is-built-with-msvc-for-now-from-wsl.md) | Accepted | 2026-11-19 |
| 40 | [The examples replace Blubber Blasen's snapshot as test case and benchmark](adr/0040-the-examples-replace-blubber-blasen-s-snapshot-as.md) | Accepted | 2026-11-19 |
| 41 | [The release is a zip per system with a Java runtime and raylib, and the repository looks like it](adr/0041-the-release-is-a-zip-per-system-with-a-java.md) | Accepted | 2026-11-19 |
| 42 | [The splash screen is the new design in parts on BG1, and its stars are sprites](adr/0042-the-splash-screen-is-the-new-design-in-parts.md) | Accepted | 2026-11-19 |
| 43 | [The book is brought in step before a release, not with every change](adr/0043-the-book-is-brought-in-step-before-a-release-not.md) | Accepted | 2026-11-19 |
| 44 | [Sound effects come from sfx.json, and Rachael plays the SNES's BRR](adr/0044-sound-effects-come-from-sfx-json-and-rachael-plays.md) | Accepted | 2026-11-19 |
