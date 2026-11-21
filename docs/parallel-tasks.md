# Parallel tasks for superdevkit (2026-11-22)

Five independent tasks from the game's findings (`docs/superkit-findings.md`) and the transition plan.
Start one Claude Code session per task in the main checkout and paste the prompt as it is. Each session
works on its own branch and worktree, opens a pull request and stops.

Expected merge conflicts between them: a line each in `dev/README.md` or `docs/testing.md`; task C also
changes the recorded hashes in `compiler/src/test/resources/backend-output.txt` (whoever merges second
records them again on the merged state, as PR #8 did).

---

## Task A: ExampleCheck writes the ROM's sound (finding 8)

```
Task for superdevkit, via the /pr skill: start a branch "example-check-sound", do the work, open the pull
request, then stop. Don't merge, even if I approve later in this session.

Background: Blubber Blasen (the game that uses the kit) reported this as its finding 8. The file with the
game's findings is docs/superkit-findings.md in the main checkout (untracked, so it isn't in your worktree:
read it there, section "8."). Don't quote the game's
code or paths in anything that goes to GitHub.

The finding: dampf.emu.ExampleCheck records the ROM's DSP writes (Result.sound(), in the format of the
desktop's sound.txt, lib/desktop), but its main only writes the checksums
("usage: ExampleCheck rom.sfc recording.txt [hashes.txt]"). The game had to write a class of its own to get
the sound. Add an optional output, e.g. "ExampleCheck rom.sfc recording.txt [hashes.txt [sound.txt]]", so a
game can compare the ROM's sound with the desktop's using only the kit's tools.

Done when: the option exists and is tested (compare with what the desktop's --check writes for an example
that has sound effects, if a test can do that cheaply); dev/replay.sh, if it wraps ExampleCheck, can pass it
through; the usage line, dev/README.md and docs/testing.md say so. ./gradlew build passes.

Rules: follow CLAUDE.md and the pr skill. Don't write docs/game-findings.md (another session collects the
answers for the game); say in the pull request what the game can do now. If you hit a decision with
several reasonable options, ask me with the sker-explain-and-solve-problem skill instead of choosing.
```

---

## Task B: the work of every tick, without the wait (finding 12.1)

```
Task for superdevkit, via the /pr skill: start a branch "tick-work", do the work, open the pull request,
then stop. Don't merge, even if I approve later in this session.

Background: Blubber Blasen (the game that uses the kit) reported this as its finding 12, point 1. Read
docs/superkit-findings.md in the main checkout (untracked, not in your worktree), sections "6."
and "12.". Don't quote the game's code or paths in anything that goes to GitHub.

The finding: a game wants the work of every tick in master cycles, without the cycles the main loop spends
waiting for the vertical blank (sdevkit__wait_vblank in lib/snes). dampf.emu.ExampleBench computes exactly
that but prints only averages and the busiest 60 ticks; the game copied it to print every tick. Finding 6 is
already fixed (ExampleBench finds lib/snes/snes.c by the end of its path in the .dbg file); the game
suggests finding the wait loop by its symbol instead of source lines, which would need no paths at all.

Build: a way to get one line per tick with its work (a new tool such as dampf.emu.ExampleWork rom check.txt,
or an option of ExampleBench, your choice, but say why in the pull request), usable by a game outside the
repository with only a superdevkit home (./gradlew installDist). Consider whether finding the wait by its
symbol (from the .lbl file) is more robust than by source lines, and switch ExampleBench too if so.

Done when: tested on an example (the per-tick values sum to what ExampleBench reports), documented in
dev/README.md and docs/testing.md, ./gradlew build passes.

Rules: follow CLAUDE.md and the pr skill. Don't write docs/game-findings.md (another session collects the
answers for the game); say in the pull request what the game can do now. If you hit a decision with
several reasonable options, ask me with the sker-explain-and-solve-problem skill instead of choosing.
```

---

## Task C: the sound samples go to the audio RAM during the splash (finding 12.2)

```
Task for superdevkit, via the /pr skill: start a branch "sfx-upload-in-splash", do the work, open the pull
request, then stop. Don't merge, even if I approve later in this session.

Background: Blubber Blasen (the game that uses the kit) reported this as its finding 12, point 2. Read
docs/superkit-findings.md in the main checkout (untracked, not in your worktree), section "12.".
Don't quote the game's code or paths in anything that goes to GitHub.

The finding: sdevkit_sfx_init(bank) (lib/snes/sfx.c, declared in lib/include/sdevkit/snes.h) is called by
the game in game_init, which lib/snes/main.c runs after the splash screen (sdevkit__splash). It copies all
samples to the audio RAM: for the game 43,686 bytes, 6.9 frames of the first tick, then about 2 seconds of
black screen while the sound CPU takes them. If the upload ran while the splash shows, the black would
mostly go away. The game can't do it itself.

The catch: the kit learns the bank only in game_init, after the splash. Find a way, e.g. the bank known at
link time from what "superdevkit sfx" generates, or a hook the game can implement, or the upload split into
pieces that run in the splash's frames. This is a design decision: lay out the options with the
sker-explain-and-solve-problem skill and let me choose before you build. The choice gets an ADR record:
make it with dev/new_adr.sh "Title".

Done when: the examples with sound (Schach, Steinschlag) start without the black pause; the desktop
(lib/rachael, lib/desktop) behaves the same; dev/check_example.sh and dev/compare_example.sh pass in Mesen
for those examples (Mesen is in .mesen, linked into your worktree by the pr skill); the recorded backend
hashes are updated if the ROMs change (-PbackendOutput=update, and say in the pull request why); lib/README.md
and docs/sound.md describe it. ./gradlew build passes.

Rules: follow CLAUDE.md and the pr skill, lib/C_STYLE.md and lib/SNES_HARDWARE.md for the C. Don't write
docs/game-findings.md (another session collects the answers for the game); say in the pull request what the
game has to change, if anything.
```

---

## Task D: a build step before the build, and "superdevkit gen" (finding 10)

```
Task for superdevkit, via the /pr skill: start a branch "build-before-and-gen", do the work, open the pull
request, then stop. Don't merge, even if I approve later in this session.

Background: Blubber Blasen (the game that uses the kit) reported this as its finding 10. Read
docs/superkit-findings.md in the main checkout (untracked, not in your worktree), section "10.".
Don't quote the game's code or paths in anything that goes to GitHub.

Two things for the superdevkit command (cli/, superdevkit.Main and superdevkit.project):
1. A game with converters of its own has to run them before "superdevkit build", into a folder it lists in
   its sources. A step in superdevkit.toml that runs commands first, e.g. [build] before = ["..."], would
   make "superdevkit build <target>" the whole build. It must show up in --plan, --dry-run and --verbose
   like the other steps, and fail the build when a command fails.
2. A game that keeps a build system of its own next to the kit's (the game has a CMake desktop build until
   M8) needs what "superdevkit build" generates (build/gen: graphics, sound effects, music.h and the music
   file list) without the rest of the build. A command such as "superdevkit gen [FOLDER]" that runs only the
   conversions of the project file.

Names and the exact form are yours to propose; if there are real alternatives (e.g. per-target "before"
steps), ask me with the sker-explain-and-solve-problem skill. A new key in superdevkit.toml or a new command
is a decision about the kit's interface: record it with dev/new_adr.sh "Title".

Done when: both are built and tested like the other commands (the TOML reader, the plan output), the README
section on superdevkit.toml and the command's help describe them, ./gradlew build passes. Leave the book
(docs/book) alone; it is brought in step at the release.

Rules: follow CLAUDE.md and the pr skill. Don't write docs/game-findings.md (another session collects the
answers for the game); say in the pull request what the game can do now.
```

---

## Task E: examples/m1 goes (transition plan 5.3)

```
Task for superdevkit, via the /pr skill: start a branch "remove-m1", do the work, open the pull request,
then stop. Don't merge, even if I approve later in this session.

docs/superdevkit-transition.md, section 5.3, says examples/m1 goes now that examples/hello exists
("hello (built: examples/hello; still to do: the book's getting-started on it, and examples/m1 gone)").
examples/m1 holds only calls.c, the program of milestone M1; it is not in the recorded backend hashes.
Remove examples/m1 and every reference to it: tests that compile it (check ExamplesTest and grep the tests),
README.md (line 178 uses it as the example of a plain "dampf build"; take a small program that stays),
docs/roadmap.md,
docs/superdevkit-transition.md, and the book's specs in doc-specs/ (reference/output-files, guide/debugging,
guide/writing-c, _styleguide.md) where they name m1 as an example: point them at hello or another example
that shows the same thing. Don't edit the generated pages in docs/book; they are written anew at the release.
Check first whether anything m1 shows (stepping through C in Mesen, a particular output file) is not shown by
any other example; if so, ask me before removing it.

Also in docs/superdevkit-transition.md and docs/roadmap.md: "random programs that reach the optimizer"
(5.4 and M9) exist now as RandomLoopProgram (docs/testing.md); mark them as done there, as the documents do
for other built steps.

Done when: no reference to examples/m1 is left outside docs/archive, docs/adr and the generated book pages;
./gradlew build passes.

Rules: follow CLAUDE.md and the pr skill.
```

---

## Later, not for parallel sessions yet

- **Finding 11** (what the game needs before its own desktop frontend can go): 7 items, best as 2 or 3
  pull requests after A to E; it blocks the end of M8.
- **M8, the web.**
- **Finding 9 / M9:** B2/B3, register allocation.
- **A release (0.7.5):** the first tag (finding 14); with it the game can confirm finding 13.
