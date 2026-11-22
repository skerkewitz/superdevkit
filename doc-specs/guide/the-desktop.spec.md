---
title: The desktop
sources:
  - lib/desktop/main.c
  - lib/desktop/view.c
  - lib/desktop/RETRO-EFFECT.md
  - lib/README.md
  - cli/src/main/java/superdevkit/Main.java
  - cli/src/main/java/superdevkit/project/Desktop.java
  - cli/src/main/java/superdevkit/project/Run.java
  - cli/src/main/java/superdevkit/project/Raylib.java
  - cli/src/main/java/superdevkit/project/Symbols.java
  - docs/adr/6F60Z709-win64-is-built-with-msvc-for-now-from-wsl.md
  - dev/replay.sh
  - dev/example_check.lua
  - compiler/src/main/java/dampf/emu/ExampleCheck.java
  - examples/hello/check.txt
audience: Game programmers who build their game for the PC to play, test and debug it there before it runs on the SNES
---

## Purpose

Explain how a game is built for the PC, what the desktop build checks, how to play it and use
its debug keys and debug view, how its headless check works, and how a session is recorded and
replayed. The reader learns why the desktop is where most of a game is developed and what it
cannot tell them (the console's time).

## Sections

The page opens, before the first H2, with what the desktop is in two or three sentences: the
same game code compiled by the PC's C compiler against [Rachael](/glossary#rachael), the picture
the same as the SNES's pixel for pixel, but the PC's speed, not the console's.

1. **Building for the desktop** – `superdevkit build linux` on Linux and `superdevkit build
   win64` with MSVC, on Windows or from WSL: what each needs, the three steps (the game alone
   without the host's headers, the symbol check, the link), where the program is written, and
   raylib fetched on the first build. What the symbol check rejects and why. `superdevkit run`,
   which builds the game and starts it in its window.
2. **Playing** – the window, the keyboard and gamepad as the joypad (the key table from
   `main.c`), resizing, the retro TV filter (Tab, `--no-crt`), `--scale`, screenshots with F12.
   The sound: the effects through Rachael's DSP, the music from the track's file next to the
   program. Save RAM: `<game>.srm` next to the program, when it is read and written. That the web
   build runs the same frontend in the browser, in one sentence linking
   [The web](/guide/the-web).
3. **The check without a window** – `--check` and `--out`: the file format of `check.txt`
   (`ticks`, `press`, `shot`, `splash`, `sram`) with the example of `examples/hello/check.txt`,
   what is written (the screenshots, `hashes.txt`, `sound.txt`, `--wav`), the line it prints and
   when its exit status is 1: lost bytes, undrawn register writes and unheard DSP writes fail it,
   sprites over the limits do not.
4. **Recordings and replays** – every session in the window is recorded; F11 marks the moment
   and saves `<game>_rec_<n>.txt`; the file's lines (`mark`, `hash`, `sram`); `--replay` and
   where it pauses; F5, F6, F7, F8; `--record` with `--check`. Playing a recording on the SNES
   side: `dev/replay.sh` with the game's ROM, in dampf's emulator; what it prints, and when a tick
   differs; that the ROM must be built from the same source as the program that recorded.
5. **Debugging on the PC** – the debug view (F1, `view.c`): its four tabs, what the game
   registers with `sdevkit_dbg_toggle`, `_range`, `_color` and `_action` and its boxes
   (`sdevkit_dbg_box`); that a change made there leaves the recording, and what a replay does
   then. The desktop build carries debug information (`-g`, and on Windows a `.pdb` next to the
   program), so the game's C runs under gdb or the Visual Studio debugger like any PC program:
   one example of starting it under gdb. `--debug` builds without optimization, so that the
   debugger shows every variable; the picture and the checksums stay the same. What it does not
   show (the 65816 code dampf writes): link [Debugging in Mesen](/guide/debugging).
6. **What the desktop does not tell you** – no console time: a tick too slow for the SNES runs
   at full speed here. The window keeps 60 frames a second; only the check runs as fast as the
   PC can. Where to find the SNES's time instead (the timing report, Mesen), as links.

## Pay attention to

- Options, keys and the printed lines exactly as `lib/desktop/main.c` has them; its usage message
  verbatim.
- The file names the build writes, as `Desktop.java` writes them. Use `superdevkit build linux
  --plan` and `win64 --plan` on an example to see the steps; do not open a window while writing
  the page.
- That Windows is built from WSL or on Windows, and why the gamepad works there and not under
  WSL, only as far as `Desktop.java`, `Run.java` and ADR-6F60Z709 say it.

## Out of scope

- The rules Rachael counts (lost bytes, undrawn registers, the sprite limits) in detail:
  [The SNES hardware](/guide/snes-hardware).
- Debugging on the SNES side in Mesen: [Debugging](/guide/debugging).
- How the filter works inside (`RETRO-EFFECT.md`): one sentence and no more.
- Making sound effects and music, and the project file's `[sound]` and `sram`:
  [Graphics and sound](/guide/graphics-and-sound) and
  [The superdevkit command](/reference/superdevkit). The options of `superdevkit` itself are on
  that reference page.
- The web build: [The web](/guide/the-web).
