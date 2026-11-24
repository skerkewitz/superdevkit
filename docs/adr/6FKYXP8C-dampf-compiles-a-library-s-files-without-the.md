# ADR-6FKYXP8C: dampf compiles a library's files without the program's defines

**Context.** A project's defines are for its own files, not for the kit's library (ADR-6FEWEE95).
The desktop and web builds keep to that, since they call the C compiler once per file. On the
SNES, dampf compiles the game and `lib/snes` as one program with one list of `-D` options, so a
define of `[snes]` reached the library as well: `SETUP_LINES=0` would have changed the margin of
`sdevkit_dma_fits` in `lib/snes/snes.c`. The argument file a SNES build writes
(`build/snes/<name>.args`) has to say the same as the build, since `dampf build @file` and the
benchmark read it.

**Decision.** `dampf build`, `check` and `parse` take `--lib <file or folder>`: the sources of a
library, compiled into the program like the other files but without the `-D` defines. `-I` and
`--sram` (with its `SDEVKIT_SRAM_SIZE`) reach them as before. An argument file takes the option
like the others, its path relative to the file. `superdevkit build snes` passes `lib/snes` with
`--lib` and writes it so into the argument file. `compile_commands.json` lists the game's files
only, with the defines they get, as before.

**Alternatives.**

- `-D` defines that apply only to the files after them on the command line: gcc's `-D` applies
  to every file, and a reader of the command line would expect the same of dampf's.
- Defines per file (`--define-for file=NAME`): one line per file and define in the argument file,
  for a distinction that is only ever between the program and its library.
- Leaving the library alone and telling games not to use its names (what the README said): a
  game cannot know every name of the library, and a new name in a later kit would change a game's
  build without a word.

**Consequences.** A game's defines on the SNES mean the same as on the desktop and the web, and
the README's warning about names the library uses is gone. The `-D` options of an argument file
written before this change still reach `lib/snes`, since it names the library's files without
`--lib`; a new build writes the file again. The game's files still see their defines in the
kit's headers they include, as on the desktop.
