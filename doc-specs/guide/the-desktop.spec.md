---
title: The desktop
sources:
  - lib/desktop/main.c
  - lib/desktop/RETRO-EFFECT.md
  - lib/README.md
  - cli/src/main/java/superdevkit/project/Desktop.java
  - cli/src/main/java/superdevkit/project/Raylib.java
  - cli/src/main/java/superdevkit/project/Symbols.java
  - dev/replay.sh
  - compiler/src/main/java/dampf/emu/ExampleCheck.java
  - examples/hello/check.txt
audience: Game programmers who build their game for the PC to play, test and debug it there before it runs on the SNES
---

## Purpose

Explain how a game is built for the PC, what the desktop build checks, how to play it and use
its debug keys, how its headless check works, and how a session is recorded and replayed. The
reader learns why the desktop is where most of a game is developed and what it cannot tell
them (the console's time).

## Sections

The page opens, before the first H2, with what the desktop is in two or three sentences: the
same game code compiled by the PC's C compiler against [Rachael](/glossary#rachael), the picture
the same as the SNES's pixel for pixel, but the PC's speed, not the console's.

1. **Building for the desktop** – `dev/build_desktop.sh` on Linux and
   `dev/build_desktop_windows.sh` from WSL with MSVC: what each needs, the three steps (the game
   alone without the host's headers, the symbol check, the link), where the program is written,
   and raylib fetched on the first run. What the symbol check rejects and why. The call for a
   game outside the home.
2. **Playing** – the window, the keyboard and gamepad as the joypad (the key table from
   `main.c`), resizing, the retro TV filter (Tab, `--no-crt`), `--scale`, screenshots with F12.
3. **The check without a window** – `--check` and `--out`: the file format of `check.txt`
   (`ticks`, `press`, `shot`, `splash`) with the example of `examples/hello/check.txt`, what is
   written (the screenshots, `hashes.txt`), the line it prints and when its exit status is 1:
   lost bytes and undrawn register writes fail it, sprites over the limits do not.
4. **Recordings and replays** – every session in the window is recorded; F11 marks the moment
   and saves `<game>_rec_<n>.txt`; the file's lines (`mark`, `hash`); `--replay` and where it
   pauses; F5, F6, F7; `--record` with `--check`. Playing a recording on the SNES side:
   `dev/replay.sh` with the game's ROM, in dampf's emulator; what it prints, and when a tick
   differs; that the ROM must be built from the same source as the program that recorded.
5. **Debugging on the PC** – the desktop build carries debug information (`-g`, and on Windows
   a `.pdb` next to the program), so the game's C runs under gdb or the Visual Studio debugger
   like any PC program: one example of starting it under gdb. `DEBUG=1` builds without
   optimization, so that the debugger shows every variable; the picture and the checksums stay
   the same. What it does not show (the 65816
   code dampf writes): link [Debugging in Mesen](/guide/debugging).
6. **What the desktop does not tell you** – no console time: a tick too slow for the SNES runs
   at full speed here. The window keeps 60 frames a second; only the check runs as fast as the
   PC can. Where to find the SNES's time instead (the timing report, Mesen), as links.

## Pay attention to

- Options, keys and the printed line exactly as `lib/desktop/main.c` has them; its usage message
  verbatim.
- The file names the build writes, as the scripts write them.
- That Windows is built from WSL, and why the gamepad works there and not under WSL, only as far
  as the scripts' comments say it.

## Out of scope

- The rules Rachael counts (lost bytes, undrawn registers, the sprite limits) in detail:
  [The SNES hardware](/guide/snes-hardware).
- Debugging on the SNES side in Mesen: [Debugging](/guide/debugging).
- How the filter works inside (`RETRO-EFFECT.md`): one sentence and no more.
