# superdevkit

A development kit for SNES games written in C: dampf (the C compiler for the 65816), the kit's
library for the SNES, the desktop and the web, the `superdevkit` command that builds a game and
converts its pictures and sounds, the tools, and examples. This folder
is a superdevkit home: everything a game needs is here.

The documentation is the book in `docs/`. Start with `docs/guide/getting-started.md`: what you
need, what is in this folder, and a first game of your own. The console API is in
`docs/reference/console-api.md`, the terms in `docs/glossary.md`. The book of the latest release
is also at https://skerkewitz.github.io/superdevkit/book/, and some of the examples can be played
at https://skerkewitz.github.io/superdevkit/example/.

A release zip brings its own Java, so you do not need to install one. What else you need depends
on the target:

| Target | Command | You need |
| --- | --- | --- |
| SNES | `superdevkit build snes` | nothing else (an emulator such as MesenCE to play the ROM) |
| Windows | `superdevkit build win64` | Visual Studio with "Desktop development with C++" |
| Linux | `superdevkit build linux` | a C compiler and the OpenGL and X11 development libraries |
| Web | `superdevkit build web` | the Emscripten SDK, with `emcc` on the PATH |

`superdevkit run` without a target builds for the system you are on, so on Windows it needs Visual
Studio. Without it, use `superdevkit build snes`.
