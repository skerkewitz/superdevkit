# dampf

C-subset compiler for the 65816 / SNES, written in Java. Read kickoff.md first; docs/subset.md and
docs/optimizations.md hold the current state of M0.

- Build and test: `./gradlew build`. With the game: `./gradlew test -Pblubberblasen=/mnt/d/Develop/AiProject/BlubberBlasen`.
- Blubber Blasen (the first user and test case) is at `/mnt/d/Develop/AiProject/BlubberBlasen`
  (Windows `D:\Develop\AiProject\BlubberBlasen`). Its `tools/snesbench` is the benchmark that
  compares compilers in Mesen 2 headless (`Mesen.exe --testrunner` with a Lua script).
- Code targets Java 25 (`options.release = 25`); the machine has JDK 27.
- Preprocessing is `gcc -E -nostdinc -undef -D__DAMPF__=1` with dampf's own `<stdint.h>`.
  Do not let host headers or host macros in.
- The grammar files under `src/main/antlr` are vendored unchanged from grammars-v4; changes to
  the support classes are listed in `src/main/antlr/dampf/parser/README.md`.
- Correctness comes before speed: every behaviour must match gcc on x86-64 bit for bit.
