# ADR-6F3CJW9N: Options can be kept in an argument file, given by `--config` or `@file`

**Context.** A build of Blubber Blasen's benchmark is a call of `dampf build` with two include
paths, 33 `-D` options, an entry, an output and three globs of source files. No one types that;
today a shell script does. A game that builds with dampf from its own repository needs the same
call in a form it can check in. gosu-flint solved the same problem with an argument file
(gosu-flint's record 32). The 33 `-D` options turned out not to be needed by dampf: the
benchmark renames the PVSnesLib functions to its stubs (`bb_*`) so that they do not clash with
PVSnesLib's library when 816-tcc or Calypsi builds it, and dampf has no such library.

**Decision.**

- `--config <file>`, or its short form `@file`, reads an argument file: one option with its
  value per line, or a source file or folder; `#` starts a comment, blank lines are ignored, and
  a value may be quoted. Its arguments go in front of those of the command line, so the command
  line can add to them; for an option that takes one value (`-o`, `--entry`), the command line
  wins, and repeatable options (`-I`, `-D`) are added.
- Relative paths in the file are resolved against the file's folder, so that the file works from
  any current folder and can be checked in.
- A folder stands for the `.c` files directly in it, not those in subfolders: a subfolder such as
  `src/game/debug/` would otherwise end up in the ROM unasked. A folder works on the command line
  as well.
- One file per call: `--config` or `@file` may be given once, and a file may not name another.
  A variant of a build is the same file with arguments added on the command line
  (`dampf build @bb.args bench/bench.c -o build/bench.sfc`).
- No file is ever picked up by convention; a file is used only when it is named, so the command
  line shows every input of a run.
- `build`, `check` and `parse` take it. Errors name the file and the line.

**Alternatives.**

- Several files on the command line (`@common.args @rom.args`), none nesting: no repetition
  between builds, at the price of a rule for options given twice. Built when a game checks in a
  second build that differs from the first in more than one or two options.
- A file that includes another, as gcc's `@file` can: the most flexible, but which options a
  build gets is only clear after following every include, and cycles need a rule.
- A file found by convention (`dampf.args` in the current folder): a run would change without the
  command line showing it.

Both of the first two can be added later without breaking a file: a line `@x` in a file is an
error today.

**Consequences.** A game checks in one file per build. dampf's own scripts for the game use one
file too. The rules are those of gosu-flint's record 32, so both tools read their files the same
way, apart from the short form `@file` and folders standing for their `.c` files.
