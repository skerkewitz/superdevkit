---
title: The superdevkit command
sources:
  - cli/src/main/java/superdevkit/Main.java
  - cli/src/main/java/superdevkit/project/*.java
  - cli/src/main/java/superdevkit/gfx/*.java
  - cli/src/main/java/superdevkit/sfx/*.java
  - cli/build.gradle.kts
  - README.md
  - lib/README.md
  - examples/*/superdevkit.toml
audience: Users looking up a command of the kit, an option, or a key of the project file
---

## Purpose

Every command of `superdevkit`, every option and target, and every key of `superdevkit.toml`,
with what it does, its default and the exit status. This page owns them: other pages link here.
The compiler's own command, `dampf`, is on [Command line](/reference/command-line).

## Sections

The page opens, before the first H2, with the usage lines exactly as `superdevkit` prints them.

1. **init** – a new project from an example: its arguments, which example is the default, what
   it copies and changes (the name, the `CLAUDE.md` it writes), and where.
2. **build** – the targets `snes`, `linux`, `win64` and `web`, the folder argument and its
   default, and for each target what it runs (in a short table: compiler, what is fetched the
   first time) and what it writes under `build/<target>/`. The options `--verbose`, `--plan`,
   `--dry-run` and `--debug`, with what `--debug` means per target. The argument file a SNES
   build writes (`build/snes/<name>.args`) and that `dampf` takes it.
3. **run** – the default target, and what each target starts (the desktop's program, Mesen for
   `snes`, the browser for `web`).
4. **png** – the converter: its kinds (`tiles`, `tiles2`, `palette`, `mode7`), arguments and what
   it writes. Short: [Graphics and sound](/guide/graphics-and-sound) explains its use.
5. **sfx** – the sound effects' converter: arguments and what it writes. Short, linking
   [Graphics and sound](/guide/graphics-and-sound).
6. **The project file** – `superdevkit.toml`: every key and table the reader accepts (`name`,
   `superdevkit`, `kit`, `sram`, `sources`, `include`, `[graphics]`, `[sound]` and their
   entries), each with its meaning, type, default and whether it is required, and what an
   unknown key does. One whole example from `examples/`.
7. **Environment** – the variables the command reads (`CC`, `EMCC`, `MESEN`, `BROWSER`, and any
   other the sources show), each with its default.
8. **Exit status** – the values and when each happens.

## Pay attention to

- Usage, option names, messages and keys exactly as `Main.java` and `Project.java` have them.
- Run `superdevkit build <target> --plan` on an example for each target and show one real plan;
  do not open a window or a browser while writing the page.
- Give each command, target and key an H2 or H3 so that other pages can link to it.

## Out of scope

- dampf's own options: [Command line](/reference/command-line).
- Playing and debugging the desktop and web builds: [The desktop](/guide/the-desktop),
  [The web](/guide/the-web).
