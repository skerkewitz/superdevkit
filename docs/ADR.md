# Architecture decision records

Decisions taken for dampf and the kit, oldest first. Each record is a file of its own in
`docs/adr/`, named by its ID and the start of its title, and states the context, the decision,
the alternatives weighed where there were any, and what follows from it. To change a decision,
add a new record that supersedes or amends the old one instead of editing it, and give it a row
in this table. The status of a record is kept here only, so that an old record need not be
edited when a later one changes it. `AdrTest` checks that the table and the files agree.

A record's ID is 8 characters of Crockford's Base32, the digits and the capital letters without
I, L, O and U: 6 for the moment it was made, in seconds since 2020-01-01 00:00 UTC, and 2 for a
hash of its title. Records are cited by ID ("ADR-6F0R61EZ"), with a link to the file. The IDs
sort by time, and so does this table; the date of a row is the day of its ID in UTC.
`dev/new_adr.sh "Title"` makes the ID, the file and the row. Records written on different
branches at the same time do not collide (ADR-6FCBBNSH).

The first 25 records were written on 2026-11-18 from the working documents, for decisions taken
from the kickoff on; the date is the day each decision was taken. An open question becomes a
record when it is decided, not before: until then it stays in [roadmap.md](roadmap.md) or the
document of its subject.

| ID | Decision | Status | Date |
|---|---|---|---|
| ADR-6F0R61EZ | [dampf is a compiler of its own for one target, the SNES](adr/6F0R61EZ-dampf-is-a-compiler-of-its-own-for-one-target-the.md) | Accepted | 2026-11-17 |
| ADR-6F0R624X | [Java 25, ANTLR with the grammars-v4 C grammar, and Gradle](adr/6F0R624X-java-25-antlr-with-the-grammars-v4-c-grammar-and.md) | Accepted | 2026-11-17 |
| ADR-6F0R633Z | [dampf compiles a subset of C, enforced by a checker after a broad parser](adr/6F0R633Z-dampf-compiles-a-subset-of-c-enforced-by-a-checker.md) | Accepted | 2026-11-17 |
| ADR-6F0R64GD | [Every result matches gcc on x86-64 bit for bit](adr/6F0R64GD-every-result-matches-gcc-on-x86-64-bit-for-bit.md) | Accepted | 2026-11-17 |
| ADR-6F0R65FW | [The whole program is compiled at once](adr/6F0R65FW-the-whole-program-is-compiled-at-once.md) | Accepted | 2026-11-17 |
| ADR-6F0R668G | [No recursion, no free function pointers and no heap, so frames have fixed places](adr/6F0R668G-no-recursion-no-free-function-pointers-and-no-heap.md) | Accepted | 2026-11-17 |
| ADR-6F0R6752 | [`gcc -E` preprocesses, without host headers or host macros](adr/6F0R6752-gcc-e-preprocesses-without-host-headers-or-host.md) | Superseded; superseded by ADR-6FD4GDN5 | 2026-11-17 |
| ADR-6F0R68AN | [Hints are macros, and a hint that states a fact is checked on the PC](adr/6F0R68AN-hints-are-macros-and-a-hint-that-states-a-fact-is.md) | Accepted | 2026-11-17 |
| ADR-6F0R69B6 | [All variables live in bank $7E, and DBR stays $7E](adr/6F0R69B6-all-variables-live-in-bank-7e-and-dbr-stays-7e.md) | Accepted | 2026-11-17 |
| ADR-6F0R6A8V | [Pointers are 4 bytes, and no object crosses a bank or exceeds 32 KB](adr/6F0R6A8V-pointers-are-4-bytes-and-no-object-crosses-a-bank.md) | Accepted | 2026-11-17 |
| ADR-6F0R6BSK | [Structs have no padding, and a `sizeof` that differs from the PC is an error](adr/6F0R6BSK-structs-have-no-padding-and-a-sizeof-that-differs.md) | Accepted | 2026-11-17 |
| ADR-6F0R6C4B | [LoROM with FastROM, code from bank $80, one segment per function, `JSL` for every call](adr/6F0R6C4B-lorom-with-fastrom-code-from-bank-80-one-segment.md) | Accepted | 2026-11-17 |
| ADR-6F0R6DZD | [Three IR levels, and HIR waits until something needs it](adr/6F0R6DZD-three-ir-levels-and-hir-waits-until-something.md) | Accepted | 2026-11-17 |
| ADR-6F0R6FT6 | [The naive code generator stays as an independent reference](adr/6F0R6FT6-the-naive-code-generator-stays-as-an-independent.md) | Accepted | 2026-11-17 |
| ADR-6F0R6G1M | [gcc with UBSan is the oracle, and the tests compare every global](adr/6F0R6G1M-gcc-with-ubsan-is-the-oracle-and-the-tests-compare.md) | Accepted; amended by ADR-6FD2DGP0 | 2026-11-17 |
| ADR-6F0R6H68 | [dampf has its own emulator, validated against SingleStepTests and Mesen](adr/6F0R6H68-dampf-has-its-own-emulator-validated-against.md) | Accepted | 2026-11-17 |
| ADR-6F0R6JD6 | [Every function gets a best and a worst case, and `DAMPF_BUDGET` fails the build](adr/6F0R6JD6-every-function-gets-a-best-and-a-worst-case-and.md) | Accepted | 2026-11-17 |
| ADR-6F0R6NS5 | [The platform layer is C with `volatile` pointers, and the main loop waits for the vertical blank](adr/6F0R6NS5-the-platform-layer-is-c-with-volatile-pointers-and.md) | Accepted | 2026-11-17 |
| ADR-6F0R6P51 | [The compiler and the game work in parallel towards the frame budget](adr/6F0R6P51-the-compiler-and-the-game-work-in-parallel-towards.md) | Accepted; amended by ADR-6F60Z819 | 2026-11-17 |
| ADR-6F0R6Q5P | [Value ranges are inferred first, and subrange types wait for a profile](adr/6F0R6Q5P-value-ranges-are-inferred-first-and-subrange-types.md) | Accepted | 2026-11-17 |
| ADR-6F0R6SCV | [dampf is under the Apache License 2.0 with LLVM Exceptions](adr/6F0R6SCV-dampf-is-under-the-apache-license-20-with-llvm.md) | Accepted | 2026-11-17 |
| ADR-6F3CJEQA | [LIR is basic blocks with explicit successors, and its text is ca65 source](adr/6F3CJEQA-lir-is-basic-blocks-with-explicit-successors-and.md) | Accepted; amended by ADR-6F8NBFAQ | 2026-11-18 |
| ADR-6F3CJKHB | [dampf assembles and links itself, with ca65 and ld65 as the oracle](adr/6F3CJKHB-dampf-assembles-and-links-itself-with-ca65-and.md) | Accepted | 2026-11-18 |
| ADR-6F3CJMJ5 | [Mesen is the debugger, and the `.dbg` file means what ld65's means](adr/6F3CJMJ5-mesen-is-the-debugger-and-the-dbg-file-means-what.md) | Accepted; amended by ADR-6F3CK1V6 | 2026-11-18 |
| ADR-6F3CJR10 | [A refactoring step may cost about 1 % speed if the back end gets much cleaner](adr/6F3CJR10-a-refactoring-step-may-cost-about-1-speed-if-the.md) | Accepted | 2026-11-18 |
| ADR-6F3CJT1P | [Milestone N is version 0.N.0, and the first public version is an alpha](adr/6F3CJT1P-milestone-n-is-version-0n0-and-the-first-public.md) | Accepted; amended by ADR-6F3CJX8T, ADR-6F60ZDMF | 2026-11-18 |
| ADR-6F3CJVHC | [`goto` and labels stay rejected, and a forward and outward form is the most dampf will allow](adr/6F3CJVHC-goto-and-labels-stay-rejected-and-a-forward-and.md) | Accepted | 2026-11-18 |
| ADR-6F3CJW9N | [Options can be kept in an argument file, given by `--config` or `@file`](adr/6F3CJW9N-options-can-be-kept-in-an-argument-file-given-by.md) | Accepted | 2026-11-18 |
| ADR-6F3CJX8T | [superdevkit is the project, and dampf is its compiler](adr/6F3CJX8T-superdevkit-is-the-project-and-dampf-is-its.md) | Accepted; amended by ADR-6F3CK03Z, ADR-6F3CK2AR, ADR-6F60Z819, ADR-6F60Z9QJ | 2026-11-18 |
| ADR-6F3CJYGP | [One book for superdevkit, with dampf as a part of its own](adr/6F3CJYGP-one-book-for-superdevkit-with-dampf-as-a-part-of.md) | Accepted; amended by ADR-6F60ZB4N | 2026-11-18 |
| ADR-6F3CJZBY | [The superdevkit command builds a project from its TOML file and calls the compilers itself](adr/6F3CJZBY-the-superdevkit-command-builds-a-project-from-its.md) | Accepted; amended by ADR-6F60Z709, ADR-6F60Z9QJ | 2026-11-18 |
| ADR-6F3CK03Z | [Desktop and web are for prototyping, with sound, save RAM and a debug view the game registers into](adr/6F3CK03Z-desktop-and-web-are-for-prototyping-with-sound.md) | Accepted; amended by ADR-6F3CK2AR, ADR-6F60ZC7C | 2026-11-18 |
| ADR-6F3CK1V6 | [MesenCE is the emulator for the console checks and the debugger](adr/6F3CK1V6-mesence-is-the-emulator-for-the-console-checks.md) | Accepted | 2026-11-18 |
| ADR-6F3CK2AR | [Every name of the console API starts with `sdevkit_`, and a game reaches nothing else](adr/6F3CK2AR-every-name-of-the-console-api-starts-with.md) | Accepted | 2026-11-18 |
| ADR-6F60Z3ZB | [The pseudo SNES is called Rachael](adr/6F60Z3ZB-the-pseudo-snes-is-called-rachael.md) | Accepted | 2026-11-19 |
| ADR-6F60Z4AZ | [One ROM for NTSC and PAL, with NTSC's limits, and PAL a sixth slower for now](adr/6F60Z4AZ-one-rom-for-ntsc-and-pal-with-ntsc-s-limits-and.md) | Accepted | 2026-11-19 |
| ADR-6F60Z5JJ | [Every game starts with the kit's splash screen, and a button shortens it](adr/6F60Z5JJ-every-game-starts-with-the-kit-s-splash-screen.md) | Accepted; amended by ADR-6F60ZA6T | 2026-11-19 |
| ADR-6F60Z67N | [The examples compare a checksum per tick on the SNES and the desktop, and the game names its state](adr/6F60Z67N-the-examples-compare-a-checksum-per-tick-on-the.md) | Accepted | 2026-11-19 |
| ADR-6F60Z709 | [win64 is built with MSVC, for now from WSL](adr/6F60Z709-win64-is-built-with-msvc-for-now-from-wsl.md) | Accepted | 2026-11-19 |
| ADR-6F60Z819 | [The examples replace Blubber Blasen's snapshot as test case and benchmark](adr/6F60Z819-the-examples-replace-blubber-blasen-s-snapshot-as.md) | Accepted | 2026-11-19 |
| ADR-6F60Z9QJ | [The release is a zip per system with a Java runtime and raylib, and the repository looks like it](adr/6F60Z9QJ-the-release-is-a-zip-per-system-with-a-java.md) | Accepted | 2026-11-19 |
| ADR-6F60ZA6T | [The splash screen is the new design in parts on BG1, and its stars are sprites](adr/6F60ZA6T-the-splash-screen-is-the-new-design-in-parts.md) | Accepted | 2026-11-19 |
| ADR-6F60ZB4N | [The book is brought in step before a release, not with every change](adr/6F60ZB4N-the-book-is-brought-in-step-before-a-release-not.md) | Accepted | 2026-11-19 |
| ADR-6F60ZC7C | [Sound effects come from sfx.json, and Rachael plays the SNES's BRR](adr/6F60ZC7C-sound-effects-come-from-sfx-json-and-rachael-plays.md) | Accepted | 2026-11-19 |
| ADR-6F60ZDMF | [M5 is concluded as 0.6.0, and a version between milestones may carry features](adr/6F60ZDMF-m5-is-concluded-as-060-and-a-version-between.md) | Accepted | 2026-11-19 |
| ADR-6F8NBESR | [An access to an array tells dampf its index is inside it](adr/6F8NBESR-an-access-to-an-array-tells-dampf-its-index-is.md) | Accepted | 2026-11-20 |
| ADR-6F8NBFAQ | [LIR operands are typed, and LIR names places before it has addresses](adr/6F8NBFAQ-lir-operands-are-typed-and-lir-names-places-before.md) | Accepted; amends ADR-6F3CJEQA | 2026-11-20 |
| ADR-6F8NBG4P | [Frame places are coloured on the LIR, and FrameLayout only proposes](adr/6F8NBG4P-frame-places-are-coloured-on-the-lir-and.md) | Accepted; amended by ADR-6F8NBHZ6, ADR-6FGF7X0N | 2026-11-20 |
| ADR-6F8NBHZ6 | [φs go through their incoming places, and the colouring coalesces the copies](adr/6F8NBHZ6-phis-go-through-their-incoming-places-and-the.md) | Accepted; amends ADR-6F8NBG4P | 2026-11-20 |
| ADR-6FB9QJ3R | [Every change goes through a pull request into develop, and a release through one into main](adr/6FB9QJ3R-every-change-goes-through-a-pull-request-into.md) | Accepted; amended by ADR-6FB9QKSV, ADR-6FD54GME | 2026-11-21 |
| ADR-6FB9QKSV | [Claude works on GitHub as an app of its own, and the user approves](adr/6FB9QKSV-claude-works-on-github-as-an-app-of-its-own-and.md) | Accepted; amends ADR-6FB9QJ3R | 2026-11-21 |
| ADR-6FCBBNSH | [Records get an ID made from the time and the title instead of a number](adr/6FCBBNSH-records-get-an-id-made-from-the-time-and-the-title.md) | Accepted | 2026-11-21 |
| ADR-6FCVAA30 | [The web runs the desktop's frontend, which waits for the browser's frames with Asyncify](adr/6FCVAA30-the-web-runs-the-desktop-s-frontend-which-waits.md) | Accepted; amended by ADR-6FD1Y6W2, ADR-6FD7KEJV | 2026-11-22 |
| ADR-6FD1Y6W2 | [The web's main takes --check, and the examples' test plays it in headless Chromium](adr/6FD1Y6W2-the-web-s-main-takes-check-and-the-examples-test.md) | Accepted | 2026-11-22 |
| ADR-6FD2DGP0 | [clang is the tests' reference on the PC](adr/6FD2DGP0-clang-is-the-tests-reference-on-the-pc.md) | Accepted | 2026-11-22 |
| ADR-6FD4GDN5 | [dampf has its own preprocessor, a subset of C99's](adr/6FD4GDN5-dampf-has-its-own-preprocessor-a-subset-of-c99-s.md) | Accepted; supersedes ADR-6F0R6752 | 2026-11-22 |
| ADR-6FD54GME | [The pull-request workflow and the ADR skill come from the sker-project package, installed with apmw](adr/6FD54GME-the-pull-request-workflow-and-the-adr-skill-come.md) | Accepted; amends ADR-6FB9QJ3R | 2026-11-22 |
| ADR-6FD7KEJV | [On the web the debug view is only in a build with --debug](adr/6FD7KEJV-on-the-web-the-debug-view-is-only-in-a-build-with.md) | Accepted | 2026-11-22 |
| ADR-6FEVZD02 | [A project can name commands that run before its build](adr/6FEVZD02-a-project-can-name-commands-that-run-before-its.md) | Accepted | 2026-11-22 |
| ADR-6FEW1T2S | [superdevkit gen runs only a project's conversions](adr/6FEW1T2S-superdevkit-gen-runs-only-a-project-s-conversions.md) | Accepted | 2026-11-22 |
| ADR-6FEWEE95 | [A project can define macros per target for its own files](adr/6FEWEE95-a-project-can-define-macros-per-target-for-its-own.md) | Accepted | 2026-11-22 |
| ADR-6FGF7X0N | [FrameLayout keeps its predictions until a model of the whole function replaces them](adr/6FGF7X0N-framelayout-keeps-its-predictions-until-a-model-of.md) | Accepted; amends ADR-6F8NBG4P | 2026-11-23 |
| ADR-6FHEEVST | [Release zips are checked on their own system and published from a draft](adr/6FHEEVST-release-zips-are-checked-on-their-own-system-and.md) | Accepted; amended by ADR-6FHSWZ2F | 2026-11-23 |
| ADR-6FHKAC1X | [The website of a release is on GitHub Pages, with the book and the example games](adr/6FHKAC1X-the-website-of-a-release-is-on-github-pages-with.md) | Accepted | 2026-11-23 |
| ADR-6FHSWZ2F | [The notes of a release are a file in the repository, and its draft is not edited](adr/6FHSWZ2F-the-notes-of-a-release-are-a-file-in-the.md) | Accepted | 2026-11-23 |
| ADR-6FK7WP0H | [The roadmap holds what is to come, and a concluded milestone moves to the history](adr/6FK7WP0H-the-roadmap-holds-what-is-to-come-and-a-concluded.md) | Accepted | 2026-11-24 |
| ADR-6FKTVDE8 | [A hardware feature is usable only when the SNES and Rachael both draw it](adr/6FKTVDE8-a-hardware-feature-is-usable-only-when-the-snes.md) | Accepted | 2026-11-24 |
| ADR-6FKYXP8C | [dampf compiles a library's files without the program's defines](adr/6FKYXP8C-dampf-compiles-a-library-s-files-without-the.md) | Accepted | 2026-11-24 |
| ADR-6FKZFDJ3 | [Enum objects take only values of their enum and the smallest width that holds them](adr/6FKZFDJ3-enum-objects-take-only-values-of-their-enum-and.md) | Accepted | 2026-11-24 |
| ADR-6FM7MWGX | [A switch becomes a jump table where that costs fewer cycles than its compares](adr/6FM7MWGX-a-switch-becomes-a-jump-table-where-that-costs.md) | Accepted | 2026-11-24 |

## Former numbers

Until ADR-6FCBBNSH the records were numbered in the order they were written. Commit messages, pull
requests and other projects may still cite them by number ("ADR record 6"):

| No. | ID | No. | ID | No. | ID |
|---|---|---|---|---|---|
| 1 | [ADR-6F0R61EZ](adr/6F0R61EZ-dampf-is-a-compiler-of-its-own-for-one-target-the.md) | 18 | [ADR-6F0R6JD6](adr/6F0R6JD6-every-function-gets-a-best-and-a-worst-case-and.md) | 35 | [ADR-6F60Z3ZB](adr/6F60Z3ZB-the-pseudo-snes-is-called-rachael.md) |
| 2 | [ADR-6F0R624X](adr/6F0R624X-java-25-antlr-with-the-grammars-v4-c-grammar-and.md) | 19 | [ADR-6F3CJKHB](adr/6F3CJKHB-dampf-assembles-and-links-itself-with-ca65-and.md) | 36 | [ADR-6F60Z4AZ](adr/6F60Z4AZ-one-rom-for-ntsc-and-pal-with-ntsc-s-limits-and.md) |
| 3 | [ADR-6F0R633Z](adr/6F0R633Z-dampf-compiles-a-subset-of-c-enforced-by-a-checker.md) | 20 | [ADR-6F3CJMJ5](adr/6F3CJMJ5-mesen-is-the-debugger-and-the-dbg-file-means-what.md) | 37 | [ADR-6F60Z5JJ](adr/6F60Z5JJ-every-game-starts-with-the-kit-s-splash-screen.md) |
| 4 | [ADR-6F0R64GD](adr/6F0R64GD-every-result-matches-gcc-on-x86-64-bit-for-bit.md) | 21 | [ADR-6F0R6NS5](adr/6F0R6NS5-the-platform-layer-is-c-with-volatile-pointers-and.md) | 38 | [ADR-6F60Z67N](adr/6F60Z67N-the-examples-compare-a-checksum-per-tick-on-the.md) |
| 5 | [ADR-6F0R65FW](adr/6F0R65FW-the-whole-program-is-compiled-at-once.md) | 22 | [ADR-6F0R6P51](adr/6F0R6P51-the-compiler-and-the-game-work-in-parallel-towards.md) | 39 | [ADR-6F60Z709](adr/6F60Z709-win64-is-built-with-msvc-for-now-from-wsl.md) |
| 6 | [ADR-6F0R668G](adr/6F0R668G-no-recursion-no-free-function-pointers-and-no-heap.md) | 23 | [ADR-6F0R6Q5P](adr/6F0R6Q5P-value-ranges-are-inferred-first-and-subrange-types.md) | 40 | [ADR-6F60Z819](adr/6F60Z819-the-examples-replace-blubber-blasen-s-snapshot-as.md) |
| 7 | [ADR-6F0R6752](adr/6F0R6752-gcc-e-preprocesses-without-host-headers-or-host.md) | 24 | [ADR-6F3CJR10](adr/6F3CJR10-a-refactoring-step-may-cost-about-1-speed-if-the.md) | 41 | [ADR-6F60Z9QJ](adr/6F60Z9QJ-the-release-is-a-zip-per-system-with-a-java.md) |
| 8 | [ADR-6F0R68AN](adr/6F0R68AN-hints-are-macros-and-a-hint-that-states-a-fact-is.md) | 25 | [ADR-6F0R6SCV](adr/6F0R6SCV-dampf-is-under-the-apache-license-20-with-llvm.md) | 42 | [ADR-6F60ZA6T](adr/6F60ZA6T-the-splash-screen-is-the-new-design-in-parts.md) |
| 9 | [ADR-6F0R69B6](adr/6F0R69B6-all-variables-live-in-bank-7e-and-dbr-stays-7e.md) | 26 | [ADR-6F3CJT1P](adr/6F3CJT1P-milestone-n-is-version-0n0-and-the-first-public.md) | 43 | [ADR-6F60ZB4N](adr/6F60ZB4N-the-book-is-brought-in-step-before-a-release-not.md) |
| 10 | [ADR-6F0R6A8V](adr/6F0R6A8V-pointers-are-4-bytes-and-no-object-crosses-a-bank.md) | 27 | [ADR-6F3CJVHC](adr/6F3CJVHC-goto-and-labels-stay-rejected-and-a-forward-and.md) | 44 | [ADR-6F60ZC7C](adr/6F60ZC7C-sound-effects-come-from-sfx-json-and-rachael-plays.md) |
| 11 | [ADR-6F0R6BSK](adr/6F0R6BSK-structs-have-no-padding-and-a-sizeof-that-differs.md) | 28 | [ADR-6F3CJW9N](adr/6F3CJW9N-options-can-be-kept-in-an-argument-file-given-by.md) | 45 | [ADR-6F60ZDMF](adr/6F60ZDMF-m5-is-concluded-as-060-and-a-version-between.md) |
| 12 | [ADR-6F0R6C4B](adr/6F0R6C4B-lorom-with-fastrom-code-from-bank-80-one-segment.md) | 29 | [ADR-6F3CJX8T](adr/6F3CJX8T-superdevkit-is-the-project-and-dampf-is-its.md) | 46 | [ADR-6F8NBESR](adr/6F8NBESR-an-access-to-an-array-tells-dampf-its-index-is.md) |
| 13 | [ADR-6F0R6DZD](adr/6F0R6DZD-three-ir-levels-and-hir-waits-until-something.md) | 30 | [ADR-6F3CJYGP](adr/6F3CJYGP-one-book-for-superdevkit-with-dampf-as-a-part-of.md) | 47 | [ADR-6F8NBFAQ](adr/6F8NBFAQ-lir-operands-are-typed-and-lir-names-places-before.md) |
| 14 | [ADR-6F3CJEQA](adr/6F3CJEQA-lir-is-basic-blocks-with-explicit-successors-and.md) | 31 | [ADR-6F3CJZBY](adr/6F3CJZBY-the-superdevkit-command-builds-a-project-from-its.md) | 48 | [ADR-6F8NBG4P](adr/6F8NBG4P-frame-places-are-coloured-on-the-lir-and.md) |
| 15 | [ADR-6F0R6FT6](adr/6F0R6FT6-the-naive-code-generator-stays-as-an-independent.md) | 32 | [ADR-6F3CK03Z](adr/6F3CK03Z-desktop-and-web-are-for-prototyping-with-sound.md) | 49 | [ADR-6F8NBHZ6](adr/6F8NBHZ6-phis-go-through-their-incoming-places-and-the.md) |
| 16 | [ADR-6F0R6G1M](adr/6F0R6G1M-gcc-with-ubsan-is-the-oracle-and-the-tests-compare.md) | 33 | [ADR-6F3CK1V6](adr/6F3CK1V6-mesence-is-the-emulator-for-the-console-checks.md) | 50 | [ADR-6FB9QJ3R](adr/6FB9QJ3R-every-change-goes-through-a-pull-request-into.md) |
| 17 | [ADR-6F0R6H68](adr/6F0R6H68-dampf-has-its-own-emulator-validated-against.md) | 34 | [ADR-6F3CK2AR](adr/6F3CK2AR-every-name-of-the-console-api-starts-with.md) | 51 | [ADR-6FB9QKSV](adr/6FB9QKSV-claude-works-on-github-as-an-app-of-its-own-and.md) |
