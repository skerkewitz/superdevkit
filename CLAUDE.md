# dampf

C-subset compiler for the 65816 / SNES, written in Java. Read kickoff.md first; docs/subset.md and
docs/optimizations.md hold the current state of M0.

- Build and test: `./gradlew build`. This runs everything, including the execution tests in dampf's
  own Mesen (`.mesen/`, not in git) and the parse test on the game snapshot.
- A new language feature needs an execution test in `src/test/resources/exec/` (PC vs SNES).
- dampf works on the snapshot `examples/blubberblasen` (never edit it; refresh with
  `tools/update_game_snapshot.sh /mnt/d/Develop/AiProject/BlubberBlasen`). Never run Mesen or write
  files inside the game's own folders: another agent is working there.
- Anything worth changing in the game goes into `docs/game-findings.md` (written for the AI working
  on the game; the user forwards it). After dampf is finished: write `docs/game-guide.md` from it.
- Blubber Blasen (the first user and test case) is at `/mnt/d/Develop/AiProject/BlubberBlasen`
  (Windows `D:\Develop\AiProject\BlubberBlasen`). Its `tools/snesbench` is the benchmark that
  compares compilers in Mesen 2 headless (`Mesen.exe --testrunner` with a Lua script).
- Code targets Java 25 (`options.release = 25`); the machine has JDK 27.
- Preprocessing is `gcc -E -nostdinc -undef -D__DAMPF__=1` with dampf's own `<stdint.h>`.
  Do not let host headers or host macros in.
- The grammar files under `src/main/antlr` are vendored unchanged from grammars-v4; changes to
  the support classes are listed in `src/main/antlr/dampf/parser/README.md`.
- Correctness comes before speed: every behaviour must match gcc on x86-64 bit for bit.
