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
  - cli/src/main/java/superdevkit/project/Desktop.java
audience: A first-time user with a Linux or WSL machine who wants to write a SNES game with superdevkit
---

## Purpose

Take a reader from nothing to a superdevkit home on their disk and a first game of their own,
outside it: `examples/hello` copied into a folder, built for the SNES and for the desktop, and
open in Mesen. Afterwards they have seen every tool the kit needs work once and know where
everything is in the home.

## Sections

The page opens, before the first H2, with what a superdevkit home is in two sentences: one
folder with the compiler, the kit's library, the tools, the examples and this book; a game lives
in a folder of its own and names the home.

1. **What you need** – the prerequisites, cut to what this page needs: Java 25 or newer,
   Python 3 (the converters), a C compiler for the desktop build, and Mesen
   (MesenCE) for looking at the result. ca65 and ld65 are needed only to develop dampf; say so
   in one sentence and point to the README of the repository.
2. **Get superdevkit** – today the home is built from the repository: `./gradlew installDist`
   writes it to `build/install/superdevkit`, which may be copied anywhere. Say in one sentence
   that release downloads come later, with a link to the [roadmap](/inside/roadmap). The page
   writes `<home>` for the folder; the reader may put `<home>/bin` on their `PATH`. Running the
   tests is for developing the kit: one sentence, a link to [Testing](/inside/testing).
3. **What is in the home** – the folders as a table, each with a phrase, from the distribution
   in `cli/build.gradle.kts` (what it copies where); `docs/` is this book.
4. **A first game** – copy `<home>/examples/hello` to a folder of the reader's own, change the
   paths of `hello.args` and `build.sh` to name the home, show both files as they then are,
   run `build.sh`, and show what it prints. Show the short structure of `src/hello.c`
   (`game_init`, `game_tick`, `game_vblank`) with a link to
   [The console API](/reference/console-api), not the whole file.
5. **What you get** – the files written next to the ROM, in a short list, each with a phrase;
   link [The generated files](/reference/output-files).
6. **On the desktop** – the same game built with `<home>/dev/build_desktop.sh` and played in a
   window; the keys of the joypad from the example's README; link
   [The desktop](/guide/the-desktop).
7. **Look at it in Mesen** – open the ROM with the `.dbg` file next to it, step through
   `game_tick` in the C source; link [Debugging in Mesen](/guide/debugging) for more.
8. **When something goes wrong** – the two or three failures a new user meets: a header not
   found, a wrong path to the home in the args file, a construct outside the
   subset (show the message format with one real example from `dampf check`). Link
   [Command line](/reference/command-line).

## Pay attention to

- Run every command on the page, in a copy of the home outside the repository and a game folder
  outside both, and copy what it prints; replace the machine's paths by `<home>` and
  `~/mygame`.
- From WSL, the Windows Mesen works; say how the ROM is opened from there, as the README does.
- The desktop window is not opened while writing the page: take the keys from the sources.

## Out of scope

- The other commands of dampf (`check`, `parse`, `profile`, `mir`, `bench`): the command line page.
- Compiling a single C file without the kit (`--entry`): [Compiling a game](/guide/compiling-a-game)
  and the command line page.
- Graphics: [Graphics and sound](/guide/graphics-and-sound).
