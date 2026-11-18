# 31. The superdevkit command builds a project from its TOML file and calls the compilers itself

**Context.** superdevkit is to make SNES development simple (record 29): a game is a folder with
its code, its assets and a reference to the kit, and the kit builds it for the SNES and, for
prototyping and sharing, for the desktop and the web. The session of 2026-11-18
([brainstorm](../brainstorm/superdevkit-targets_2026-11-18.md)) weighed how a project is described
and built.

**Decision.**

- A command `superdevkit`, written in Java and shipped in the same distribution as dampf:
  `init <name> [--from <example>]` copies an example and sets its name; `build <target>` builds for
  one target (`snes`, `win64`, `linux`, `web`; macOS later), always with a target named; `run`
  builds and starts the machine's own desktop target, or `snes` (Mesen) or `web` (a local server)
  when named. Every command takes `--verbose` (every step and call printed), `--dry-run` (checks
  that everything needed is there and does nothing) and `--plan` (both).
- A project is described by `superdevkit.toml`: its name, the superdevkit version it needs or a
  path to a superdevkit folder (for working on the kit and a game at once), the sources per target
  and the assets. The CLI reads it with a TOML reader of its own, for the subset the file uses.
  The CLI is found on the path; it says so when the project needs another version.
- The CLI calls the compilers itself, one call per file and one to link, with fixed options per
  target: no CMake, no Make, no shell scripts. The kit brings only dampf; for the desktop and the
  web the user provides the compiler (gcc or clang, MinGW or MSVC, Emscripten). raylib comes
  ready-built per target, pinned to one version. The CLI writes a `compile_commands.json` for
  editors.

**Alternatives.**

- CMake as the backbone, with a module of the kit: good for IDEs and every compiler is known to
  it, but painful when it fails and hard to drive for an AI; hidden behind the CLI it would still
  surface its errors, a second layer to debug.
- Shell scripts per target: the same transparency as direct calls, but they do not run on Windows
  without extra tools.
- Zig (`zig cc`) as the compiler: one install for Windows and Linux from any machine, but macOS
  still needs Apple's SDK and the web still needs Emscripten, so it is an abstraction without a
  cleaner structure.
- A generator for `init`: a template that is a working example the tests build cannot go stale.

**Consequences.** A beginner learns three commands; an experienced developer sees every call and
gets `compile_commands.json`; CI makes one call. There is no incremental build at first: every
build compiles everything, which a game of this size does in seconds. Options per platform (Windows
above all) need care, and a raylib update is a deliberate step of the kit. The CLI comes with the
desktop targets (M7); until then, examples and games build for the SNES with dampf and an argument
file (record 28).
