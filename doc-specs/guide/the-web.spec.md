---
title: The web
sources:
  - lib/web/web.c
  - lib/web/web.h
  - lib/web/shell.html
  - lib/desktop/main.c
  - lib/README.md
  - cli/src/main/java/superdevkit/project/Web.java
  - cli/src/main/java/superdevkit/project/Serve.java
  - cli/src/main/java/superdevkit/project/Raylib.java
  - cli/src/main/java/superdevkit/project/Desktop.java
  - cli/src/main/java/superdevkit/Main.java
  - tools/web_check/README.md
  - tools/web_check/web_check.js
  - docs/adr/6FCVAA30-the-web-runs-the-desktop-s-frontend-which-waits.md
  - docs/adr/6FD1Y6W2-the-web-s-main-takes-check-and-the-examples-test.md
  - docs/adr/6FD7KEJV-on-the-web-the-debug-view-is-only-in-a-build-with.md
audience: Game programmers who want their game to run in a browser, to share it or put it online
---

## Purpose

Explain how a game is built for the browser, how to play it there and put it online, and how it
differs from the desktop. The reader learns that the web is the desktop's frontend in the
browser, so everything [The desktop](/guide/the-desktop) says about playing holds, and what the
browser changes.

## Sections

The page opens, before the first H2, with what the web build is in two or three sentences: the
same game code, compiled by Emscripten's `emcc` against [Rachael](/glossary#rachael) and the
desktop's frontend, in a page any browser opens.

1. **Building for the web** – `superdevkit build web`: what it needs (emcc, found on the `PATH`
   or named by `EMCC`; raylib's web package, fetched on the first build), the three steps as on
   the desktop (each file alone, the symbol check, the link), and the files it writes into
   `build/web/` (`index.html`, `index.js`, `index.wasm`, and `index.data` with the music when
   the game has any; `obj/` and `music/` are the build's own). That the page must come from a
   web server, not a file.
2. **Playing in the browser** – `superdevkit run web`: the server on this machine and the port
   it takes, the browser it opens (`BROWSER`). The click that starts the game and why browsers
   need it. The canvas filling the window. The keys are the desktop's (link the key table of
   [The desktop](/guide/the-desktop)); the keys the page keeps from the browser.
3. **What the browser changes** – the frame clock: the game runs at 60 ticks a second on any
   screen (60 Hz, 120 Hz), at most four ticks per browser frame, and a hidden tab does not catch
   up. The save RAM in the browser's IndexedDB (`/save/<game>.srm`), kept per site. The music
   packed into `index.data`. F11's recording and F12's screenshot as downloads.
4. **Debugging on the web** – the debug view (F1) and the run keys (F5 to F8) only in a build
   with `--debug` (`superdevkit build web --debug`, `run web --debug`), which is also built
   without optimization and defines `SDEVKIT_WEB_DEBUG` for the game, so a game can keep its own
   debug code to that build. Why: a page put online should give its players neither.
5. **Putting it online** – the `index.*` files of `build/web/` on any static web server, built
   without `--debug`. What a player's browser keeps (the save RAM) and that it is tied to the
   site's address.
6. **Checked like the desktop** – the web's `main` takes `--check` like the desktop's; the kit's
   tests play every example's `check.txt` in headless Chromium and compare every tick's checksum
   and the sound with the SNES (`tools/web_check`). One short paragraph; the command of
   `tools/web_check/README.md` for a game's own check.

## Pay attention to

- File names, options, environment variables and keys exactly as `Web.java`, `Serve.java`,
  `shell.html` and `main.c` have them.
- Run `superdevkit build web --plan` on an example to show what the build does; do not open a
  browser while writing the page.

## Out of scope

- How Asyncify works inside: one sentence and a link to the ADR at most.
- The desktop's own features in detail (the filter, recordings, the check's file format):
  [The desktop](/guide/the-desktop).
