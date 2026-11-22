# ADR-6F60Z819: The examples replace Blubber Blasen's snapshot as test case and benchmark

**Context.** dampf was developed against a copy of Blubber Blasen's code in
`snapshots/blubberblasen` (ADR-6F0R6P51 and ADR-6F3CJX8T): its parse test, its benchmark of 3,610 ticks against
a PC build, its recorded games played on both sides, the static timing checked on every call of
the benchmark, its playable ROM, and the one hand-written reference function. Every speed decision
was measured on that one game, and the kit cannot be published with another project's code in
it (roadmap, Publishing). By 2026-11-19 the examples are larger than the game (about 9,000 lines
of C to its 8,000: Er-Tüp, Schach, Steinschlag, Mandelbrot and three small ones), compare a
checksum every tick on the SNES and the desktop (ADR-6F60Z67N), and cover the styles of code the
roadmap asks for, the last one with Mandelbrot (16- and 32-bit arithmetic).

**Decision.** The snapshot leaves the repository, with the tools and tests that needed it
(`dev/bench_game.sh`, `bench_compilers.sh`, `build_game_rom.sh`, `check_game_rom.sh`,
`profile_game.sh`, `game_mir.sh`, `update_game_snapshot.sh`, the replay driver,
`reference/level_solid_px.s`; `BlubberBlasenParseTest`, `ReplayTest`, `GameTimingTest`,
`RenameScriptTest`). The examples do their jobs: `ExamplesTest` plays every example's
`check.txt` on both sides with a checksum per tick, `ExamplesTimingTest` checks every call of every
example against its static bounds, `BackendOutputTest` and `MirTextTest` take the examples, the
reference functions are functions of the examples (`reference/<example>_<function>.s`,
`ReferenceFunctionsTest`), and `dev/bench_examples.sh` measures the work per tick of all of them,
against a baseline, as the benchmark. The game builds with the kit from its own repository;
what dampf found in its code stays in `docs/game-findings.md` for it. `dampf bench`, `dampf mir
--bench` and `dampf profile` stay: they work on any ROM that speaks the benchmark's ports, the
game's among them.

**Alternatives.**

- Keep the snapshot until publishing: one more program to test on, but every measurement would
  go on being taken on it first, and the step would only be postponed.
- Keep it outside git (fetched by a script): the tests would depend on another project's state.

**Consequences.** Speed is judged on a set of programs in different styles, not on one game;
the parameters tuned on the game (the inlining limit, the block order) are to be checked on the
set. The git history still holds the snapshot: publishing cleans the history or starts a new
repository (roadmap). ADR-6F0R6P51 and ADR-6F3CJX8T are amended: the game and dampf still work in parallel,
but dampf no longer carries the game's code, and the repository has no `snapshots/`.
