# 29. superdevkit is the project, and dampf is its compiler

**Context.** Three projects had grown into each other. dampf, the compiler, held a platform layer
for the SNES (`platform/snes`) and a copy of Blubber Blasen to be developed against. Blubber
Blasen held the game and, next to it, the PVSnesLib-shaped console API (`src/snes/snes.h`) with
its implementation on the PC (the pseudo SNES and the raylib and web frontends). Taken apart
there are three: the game, the compiler, and a development kit for SNES games.

dampf alone is of little use: it compiles the whole program from C (record 5) and links no
foreign libraries, so every SNES game built with it needs a hardware layer written in dampf's
subset. Tools in this field ship compiler and library together (PVSnesLib, cc65).

**Decision.**

- The project is a development kit for SNES games, superdevkit; dampf is its compiler. Until it
  is published, it stays in this repository, which keeps its name.
- The layout:

  ```
  compiler/    dampf (today still src/, moved when a second build world comes in)
  lib/         the library a game uses: include/snes.h, snes/ (the console), pc/ (the PC, later)
  tools/       for game developers (checks in Mesen, replays to C, converters)
  dev/         for developing the dev kit (benchmarks, profiles, the snapshot, subset scan)
  examples/    own example projects: <name>/README.md, <name>.args, src/, assets/, build/
  snapshots/   copies of other projects to test against (Blubber Blasen), never edited here
  reference/   hand-written reference functions
  docs/        the ADR, the roadmap, the working documents, the book
  ```

- Compiler and library stay apart by rule. The compiler knows nothing about the library: no code
  in the compiler refers to `lib/`; only the dev kit's integration tests (a replay through the
  platform layer, the playable ROM) use both, and their names say so. The library is ordinary
  code for dampf: the subset, `<stdint.h>` and `<dampf.h>`, nothing else; what it needs beyond
  that (an intrinsic, an interrupt handler) dampf offers to every program.
- superdevkit owns the console API, `lib/include/snes.h`. Every function of it exists on every
  platform. A feature not built on one platform yet is a stub there that does nothing, and a
  table in the dev kit's documentation shows per function and platform whether it is built or a
  stub; a test checks that every function of `snes.h` exists in each implementation.
- The main loop belongs to the library: each platform has the `main` that starts the machine
  and then runs the frame rhythm, calling three functions every game defines: `game_init(seed)`
  once, then per frame `game_tick(pads)` while the picture is drawn and `game_vblank()` in the
  vertical blank, after the library has copied the sprite table. Their declarations are part of
  the console API, so the rhythm is the same on every platform.
- Game code contains no platform `#ifdef`s. A game that must know whether a feature works asks
  for the feature (`#if SDK_HAS_SOUND`), never for the platform; platform `#ifdef`s exist only
  inside `lib/`.
- The version of record 26 is the dev kit's. Amending record 26: 0.5.0 is no longer the first
  public version; publishing is a step of its own, not scheduled yet ([roadmap.md](../roadmap.md)).

**Alternatives.**

- superdevkit in a repository of its own, dampf alone in this one: dependencies one way by the
  repository boundary, but every feature that spans compiler and library (intrinsics, C in
  interrupts, the vertical blank, sound) needs two releases, and dampf's tests would need a copy
  of the platform layer. Better if dampf were to go public on its own as a compiler for people
  with engines of their own.
- The dev kit inside dampf as a folder of a compiler project: the same advantages, but the PC
  side (C, raylib, a C++ debug view, a web build) has no place in a Java project.
- The main loop in the game, as with PVSnesLib, where every game writes its own `main` with the
  library's help: more freedom, but every game repeats the start-up and the order within a frame
  (the sprite table copied in the vertical blank, the game's uploads after it), which is easy to
  get wrong and would differ between the platforms of one game.
- The dev kit inside the game until a second game exists: no new structure, but the boundary
  exists only on paper, and game and dev kit code mix.

**Consequences.** A game depends on one thing, superdevkit of a version. A feature that spans
compiler and library lands in one commit. dampf's documentation stays its own, as one part of the
dev kit's. The repository has two build worlds once the PC side comes in (Gradle for the
compiler, something like CMake for the PC side). The game keeps its own copy of `snes.h` until
its PC side moves to `lib/pc`; until then the two copies must say the same. Stubs hide what is
missing (no sound on the SNES, and no error saying why), which the table and the release notes
make up for. A game that needs another rhythm (game logic at 30 Hz on the heaviest ticks, record
22) needs it from the library, not from a `main` of its own. Blubber Blasen's PC platform plays
sound by reading what the game reports (`game_sfx()`, `game_music()`); how sound enters the
console API is decided when the SNES side gets sound.
