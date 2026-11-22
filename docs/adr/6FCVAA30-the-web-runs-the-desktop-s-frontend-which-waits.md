# ADR-6FCVAA30: The web runs the desktop's frontend, which waits for the browser's frames with Asyncify

**Context.** M8 brings the target `web`: the game on Rachael in the browser, compiled with
Emscripten (`emcc`) and linked with raylib's web build. The desktop's frontend (`lib/desktop/main.c`)
runs its own loop, `while (!WindowShouldClose())`, with `SetTargetFPS(60)` pacing it, and the
splash screen (`lib/splash/splash.h`, shared with the SNES) is a loop of its own that draws a frame
per call. A browser does not let a page loop: the page draws when the browser asks for a frame
(`requestAnimationFrame`) and must return in between. raylib's web backend makes
`WindowShouldClose()` sleep 16 ms through Asyncify, and its frame limit waits by spinning
(`nanosleep` has no thread to sleep in), so the desktop's loop as it is would run at about 30
frames a second and block the browser's thread. Screens run at 60, 120, 144 Hz and more, so the
browser's frames are not the game's ticks either.

**Decision.** The web builds the desktop's `main.c` with emcc, and adds what only the web needs in
`lib/web` (`web.c`, `web.h`, `shell.html`). Both loops, the main loop and the splash's, stay as
they are. On the web they wait in `sdevkit__web_frame()`, a function in JavaScript (`EM_ASYNC_JS`)
that returns to the browser and comes back with its next frame. A clock of 60 ticks a second
decides how many ticks are due in that frame: one on a 60 Hz screen, one every other frame on a
120 Hz one, two on a 30 Hz one. At most 4 are run in one frame. A tab that was hidden does not
catch up the time it was away, and neither does a machine too slow for the game, which then runs
slower, as on the desktop. The splash shows a frame per tick and lets the ticks of a frame that are
not shown go by, so it lasts its four seconds on every screen. Asyncify (`-sASYNCIFY`) does the
waiting. With `-sASYNCIFY_IGNORE_INDIRECT` it instruments only the direct calls that lead to the
wait: `main`, the window's loop, the splash. The game's code and Rachael run without it.

On the web the window is the browser's: the canvas fills it and follows its size. The save RAM is
`/save/<game>.srm` in Emscripten's IndexedDB file system, read before the game starts and written
back when it changes. The music is packed into the page's data (`--preload-file`). F11's recording
and F12's screenshot are offered as downloads. The page (`lib/web/shell.html`) starts the game with
a click, since a browser plays sound and gives a page the keyboard only after one, and keeps the
keys the game and the debug view use (the arrows, Tab, F1, F5 to F8, F11, F12) from the browser.
There is no `--check` or `--replay` on the web. The desktop's build stays the reference of the
checks.

**Alternatives.**

- `emscripten_set_main_loop`, the browser calling a function per frame, as raylib's own web
  examples do: no Asyncify, but the main loop becomes a function of its state, and the splash,
  which is shared with the SNES (`lib/snes/main.c`), would need a second form for the web, or the
  SNES's would change with it. Two loops that must behave alike are what the checks of the desktop
  could no longer vouch for.
- A main loop of its own for the web in `lib/web`: the same split, with every feature of the
  window (the debug keys, the recording, the music, the save RAM) written twice.
- raylib's `WindowShouldClose()` with Asyncify, as raylib does by default on the web: about 30
  frames a second, and no clock for screens that are not 60 Hz.

**Consequences.** The web gets every feature of the desktop's window without a second copy:
the CRT filter (its shaders had a GLSL ES branch from Blubber Blasen's web build), the debug view,
the sound effects through raylib's audio stream, the music, the gamepad. The wasm file is larger
and the instrumented functions a little slower, which does not matter for a frontend that waits
for the browser. A function that can wait must be reached by direct calls: a wait behind a function
pointer would stop the program. raylib's prebuilt web package (5.5) links with the current emcc.
Its audio code reads `Module.HEAPF32`, which emcc no longer exports by default, so the link
exports it. The symbol check reads WebAssembly objects (`superdevkit.project.Symbols`); emcc writes
wasm objects, not LLVM bitcode, as long as there is no `-flto`.
