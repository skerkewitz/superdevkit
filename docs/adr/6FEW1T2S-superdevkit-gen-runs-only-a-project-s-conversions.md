# ADR-6FEW1T2S: superdevkit gen runs only a project's conversions

**Context.** A game that keeps a build system of its own beside the kit's needs what
`superdevkit build` generates (the graphics, the sound effects, `music.h` and the list of the music
files). Blubber Blasen's CMake build reads the tracks out of `superdevkit.toml` with a regular
expression and writes those files itself, which is a second copy of what the kit does.

**Decision.** `superdevkit gen [FOLDER]` runs the commands of `[build] before` and the project's
conversions, and compiles nothing. It writes `build/gen`, and for a game with music the tracks and
their list (`music_files.c`) in `build/gen/music`. The list is in a folder of its own because it
belongs to a frontend: a build that compiles the `.c` files of `build/gen` must not take it. It
takes `--plan`, `--dry-run` and `--verbose` like `build`.

**Alternatives.**

- An option of `build` (`--only-assets`): `build` always names a target, and this has none.
- The list in `build/gen` itself, as the desktop's in `build/<target>/obj`: dampf would compile it
  when it is given the folder.

**Consequences.** Another build system can call the kit for its generated files. A later
`superdevkit build` gives the same ROM with those files present.
