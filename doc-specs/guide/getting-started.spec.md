---
title: Getting started
sources:
  - README.md
  - cli/build.gradle.kts
  - lib/README.md
  - compiler/src/main/java/dampf/Main.java
  - compiler/src/main/java/dampf/ConfigFile.java
  - compiler/src/main/java/dampf/frontend/Preprocessor.java
  - examples/hello/README.md
  - examples/hello/superdevkit.toml
  - examples/hello/src/hello.c
  - cli/src/dist/README.md
  - cli/src/main/java/superdevkit/Main.java
  - cli/src/main/java/superdevkit/project/Init.java
  - cli/src/main/java/superdevkit/project/Kit.java
  - cli/src/main/java/superdevkit/project/Build.java
  - cli/src/main/java/superdevkit/project/Run.java
  - cli/src/main/java/superdevkit/project/Raylib.java
  - cli/src/main/java/superdevkit/project/Desktop.java
audience: A first-time user with a Linux or WSL machine who wants to write a SNES game with superdevkit
---

## Purpose

Take a reader from nothing to a superdevkit home on their disk and a first game of their own,
outside it: made with `superdevkit init` (a copy of `examples/hello`), built and run with the
`superdevkit` command for the SNES and for the desktop, and open in Mesen. Afterwards they have
seen every tool the kit needs work once and know where everything is in the home.

## Sections

The page opens, before the first H2, with what a superdevkit home is in two sentences: one
folder with the compiler, the `superdevkit` command, the kit's library, the tools, the examples
and this book; a game lives in a folder of its own, with a `superdevkit.toml` that names the
kit's version, and the home's `superdevkit` command builds it.

1. **What you need** – the prerequisites, cut to what this page needs: Java 25 or newer, a C
   compiler for the desktop build, and Mesen (MesenCE) for looking at the result; Emscripten's
   `emcc` only for the web. dampf has its own preprocessor, assembler and linker, so nothing
   else is installed for the SNES. ca65, ld65 and clang are needed only to develop the kit; say
   so in one sentence and point to the README of the repository.
2. **Get superdevkit** – today the home is built from a checkout of the repository, which is
   not public yet (roadmap, *Publishing*): `./gradlew installDist` writes it to
   `build/install/superdevkit`, which may be copied anywhere. Show how `<home>/bin` goes on the
   `PATH` (one `export` line for the shell). Say in one sentence
   that release downloads come later, with a link to the [roadmap](/inside/roadmap). The page
   writes `<home>` for the folder; the reader puts `<home>/bin` on their `PATH`, and the page
   writes `superdevkit` and `dampf` from then on. Running the tests is for developing the kit:
   one sentence, a link to [Testing](/inside/testing).
3. **What is in the home** – the folders as a table, each with a phrase, from the distribution
   in `cli/build.gradle.kts` (what it copies where) and its `README.md` (`cli/src/dist`);
   `docs/` is this book.
4. **A first game** – `superdevkit init mygame` makes the game's folder, a copy of `hello`
   (`--from` names another example); say what it changes (the name, the kit's version instead
   of the examples' path, a `CLAUDE.md`), show what it prints and the `superdevkit.toml` it
   writes. A plain copy of `<home>/examples/hello` works too, once its `kit` line is replaced by
   the version: one sentence. Build with `superdevkit build snes` in the game's folder and show
   what it prints. Show the short structure of `src/hello.c`
   (`game_init`, `game_tick`, `game_vblank`) with a link to
   [The console API](/reference/console-api), not the whole file.
5. **What you get** – the files the build writes into `build/` (`build/snes/` with the ROM and
   the args file, `build/gen/`, `build/compile_commands.json`), in a short list, each with a
   phrase; link [The generated files](/reference/output-files).
6. **On the desktop** – the same game built with `superdevkit build linux` and played in a
   window with `superdevkit run`; the keys of the joypad from the example's README; link
   [The desktop](/guide/the-desktop). Then, in a sentence or two, the browser:
   `superdevkit build web` and `superdevkit run web`, with a link to [The web](/guide/the-web).
7. **Look at it in Mesen** – open the ROM with the `.dbg` file next to it (or
   `superdevkit run snes`, which takes the Mesen that `MESEN` names, else the kit's `.mesen/`
   (Run.java), with one example of setting `MESEN` for the Linux Mesen; `run snes` hands Mesen
   a Linux path, so the Windows Mesen from WSL opens the ROM through `\\wsl.localhost` instead), step
   through `game_tick` in the C source; link [Debugging in Mesen](/guide/debugging) for more.
8. **When something goes wrong** – the two or three failures a new user meets: the command run
   outside the game's folder, a header not found, a construct outside the subset (show the
   message format with one real example, and `dampf check` on the args file the build wrote).
   Link [The superdevkit command](/reference/superdevkit) and
   [Command line](/reference/command-line).

## Pay attention to

- Run every command on the page, in a copy of the home outside the repository and a game folder
  outside both, and copy what it prints; replace the machine's paths by `<home>` and
  `~/mygame`.
- From WSL, the Windows Mesen works; say how the ROM is opened from there, as the README does.
- The desktop window, the browser and Mesen are not opened while writing the page: take the keys
  from the sources, and check `run` and the web with `--plan`.

## Out of scope

- The other commands and options of `superdevkit` (`png`, `sfx`, `win64`, `--plan`, ...): the
  [superdevkit command](/reference/superdevkit) page.
- The other commands of dampf (`check`, `parse`, `profile`, `mir`, `bench`): the command line page.
- Compiling a single C file without the kit (`--entry`): [Compiling a game](/guide/compiling-a-game)
  and the command line page.
- Graphics: [Graphics and sound](/guide/graphics-and-sound).
