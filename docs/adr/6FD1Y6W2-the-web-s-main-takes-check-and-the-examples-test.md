# ADR-6FD1Y6W2: The web's main takes --check, and the examples' test plays it in headless Chromium

**Context.** ADR-6FCVAA30 left the web without `--check` and `--replay`: the desktop's build was
the reference of the checks. M8 asks for the examples in the browser, checked by a test as
`ExamplesTest` checks the desktop. emcc's WebAssembly is not gcc's x86-64: pointers and `long` are
32 bits wide, and clang is another compiler. A game, Rachael or the DSP that computes differently
there would play differently in the browser while every check passes on the desktop. The page
starts `main` without options, and a test has no other way into it.

**Decision.** The web's `main` (`lib/desktop/main.c`) takes the desktop's options, except
`--view-shot`, which draws into a hidden window. The page still passes none. The build exports
Emscripten's file system (`-sEXPORTED_RUNTIME_METHODS=callMain,FS,HEAPF32`).
`tools/web_check/web_check.js` loads a web build in headless Chromium with Playwright, from the
folder through Playwright's routing, writes `check.txt` into `Module.FS`, calls
`Module.callMain(["--check", ...])` and copies the files `main` wrote back to disk. `ExamplesTest`
builds every example for the web and compares the web's checksums, sound and recording with the
SNES's, as it compares the desktop's, where emcc runs, raylib was fetched for the web and
Playwright is installed in `tools/web_check`.

**Alternatives.**

- The check in the window: the page plays `check.txt` through the browser's frames, as `--replay`
  plays a recording. It would cover the frame clock and Asyncify too, but a headless browser
  renders at 16 to 23 frames a second without a GPU, so with 4 ticks a frame at most the longest
  check (13,400 ticks) takes minutes, and the timing of the browser enters a test about the
  game's state. Left for a later test of the window on the web.
- A page of its own for the test, or a `?check=` parameter on the game's page: more to keep in
  step with `shell.html` for what `Module.callMain` does already.
- Playwright in `tools/lab_test`, shared with the BRR Lab: that folder is the Lab's test suite
  with its own configuration; the web's check is a script, not a test of a page.

**Consequences.** Every example plays the same game in emcc's WebAssembly as on the SNES and the
desktop, tick for tick, with the same sound and the same screenshots (on 2026-11-22 all 145
screenshots of the seven examples were the desktop's, byte for byte). `ExamplesTest` takes about
two minutes longer where the web runs. The frame clock, Asyncify, the page and the save RAM in
IndexedDB are not covered by it. CI has neither Emscripten nor Playwright and skips the web.
Two Playwright installs share one browser, since both folders pin the same version.
