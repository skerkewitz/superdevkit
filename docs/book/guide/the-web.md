<!-- GENERATED from doc-specs/guide/the-web.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# The web

The web build is your game as a page for the browser. The same C code is compiled by
Emscripten's `emcc` to WebAssembly, against [Rachael](/glossary#rachael), the SNES rebuilt in
portable C, and the frontend of [The desktop](/guide/the-desktop): the window, the keys, the
sound and the debug view are the desktop's, and the browser only changes how they reach you. Any
browser opens the page, and you can put it online for your players.

## Building for the web

Run `superdevkit build web` in the game's folder:

```sh
superdevkit build web
```

It needs Emscripten's `emcc`, found on the `PATH` or named by the `EMCC` variable
(`EMCC=/path/to/emcc`). Without it the build stops when it first calls the compiler:

```
error: emcc: no Emscripten (emcc): the target web builds with emcc; install the Emscripten SDK and put emcc on the PATH, or name it with EMCC=path (...)
```

It also needs raylib, the library behind the window, in its package for the web. The first web
build fetches raylib 5.5 from GitHub into the [superdevkit home](/glossary#superdevkit-home)
(`<home>/raylib/web`), and every build after it reuses it.

The build has the three steps of the desktop: each of the game's files compiled alone, without
the host's headers; the symbol check, which accepts only calls into the console API; and the
link, with Rachael, the desktop's frontend (`lib/desktop`), what the web adds to it (`lib/web`)
and raylib. [The desktop](/guide/the-desktop) explains why the first two steps are there. Use
`--plan` to see the steps without running them; for a game made with `superdevkit init mygame`,
with the compiler's options cut short:

```
superdevkit build web: mygame (kit <home>)
  png    tiles assets/font_gfx.png font_gfx build/gen
  png    tiles assets/ball_gfx.png ball_gfx build/gen
  raylib 5.5 for web in <home>/raylib/web (fetched from GitHub first)
  mkdir  build/web/obj
  emcc   emcc -std=c99 -O2 ... -c src/hello.c -o build/web/obj/src_hello.o
  emcc   emcc -std=c99 -O2 ... -c build/gen/ball_gfx.c -o build/web/obj/gen_ball_gfx.o
  emcc   emcc -std=c99 -O2 ... -c build/gen/font_gfx.c -o build/web/obj/gen_font_gfx.o
  music  0 tracks into build/web/music, their list build/web/obj/music_files.c
  check  the game's objects call nothing but lib/include/sdevkit (ADR-6F3CK2AR)
  emcc   emcc -std=c99 -O2 ... -o build/web/index.html
  write  build/compile_commands.json
everything the build needs is there
```

The build writes the page into `build/web/`:

| File | What |
|---|---|
| `index.html` | the page, with the game's name as its title |
| `index.js` | Emscripten's code that loads and starts the game |
| `index.wasm` | the game, Rachael and the frontend in WebAssembly |
| `index.data` | the game's music; only when the game has any |

The folders `obj/` and `music/` beside them are the build's own work and are not part of the
page. Open the page from a web server, not as a file: a browser loads WebAssembly only from a
server that sends it with the type `application/wasm`. `superdevkit run web` is such a server.

## Playing in the browser

Use `superdevkit run web` to build the game and play it:

```sh
superdevkit run web
```

After the build it serves `build/web` on this machine, at `127.0.0.1` on port 8080, or on the
next free port when 8080 is taken, and opens the page in your browser:

```
serving build/web at http://127.0.0.1:8080/ (Ctrl+C ends)
```

The server answers only this machine and runs until you press Ctrl+C. It lets the browser cache
nothing, so after another `superdevkit build web` a reload of the page shows the new build.
Set `BROWSER` to the command of the browser you want; it gets the address. `BROWSER=true`
opens none, and the page waits at the address for you. Without `BROWSER` the command takes
`xdg-open` on Linux, `open` on macOS and Windows' default browser on Windows and from WSL.

The page shows `Loading mygame ...` while the browser loads the files, then
`Click to play mygame`. The game starts with that click: a browser plays a page's sound and
gives it the keyboard only after the player has clicked on it. If the game stops with an error,
the page says `The game stopped:` and the error. What the game and the frontend print goes to
the browser's console.

The canvas fills the browser's window and follows its size. The picture fills it as far as its
shape allows, centred, as in the desktop's window. The splash screen plays first, as on the
SNES.

The keys are the desktop's: the keyboard and a gamepad are [joypad](/glossary#joypad) 1, as
the [key table of the desktop](/guide/the-desktop#playing) shows, and `Tab` turns the retro TV
filter off and on. A browser takes some of these keys for itself: the arrow keys and the space
bar scroll, `Tab` moves the focus, `F5` reloads. The page keeps the arrow keys, `Space`, `Tab`,
`Backspace`, `Enter`, `F11` and `F12` from the browser, and in a build with `--debug` also
`F1` and `F5` to `F8`. With `Ctrl`, `Alt` or the Meta key held it keeps none, so the browser's
own shortcuts still work.

## What the browser changes

**The frame clock.** In the desktop's window the program waits for each
[frame](/glossary#frame) itself. In a browser a page draws only when the browser offers it a
frame, at the rate of the screen, which may be 60 Hz, 120 Hz or more. So the web runs the game
by a clock of 60 [ticks](/glossary#tick) a second: on a 60 Hz screen each browser frame runs one
tick, on a 120 Hz screen every other frame does, on a 30 Hz screen each frame runs two. At most
four ticks run in one browser frame. A tab in the background gets no frames, so the game stands
still there, and when you come back it does not catch up the time it was away; a machine too
slow for the game makes it run slower, as on the desktop. The waiting is done by Emscripten's
Asyncify, which lets the desktop's loops stay as they are (ADR-6FCVAA30).

**The save RAM.** A game with `sram` in its [project file](/glossary#project-file) keeps its
save RAM in the browser's IndexedDB, as the file `/save/<game>.srm` of the page's file system.
It is read before the game starts and written back when it changed, which the frontend looks at
every half second. The browser keeps it per site: the same game at another address, another port
included, starts with save RAM of zeros, and it is not the `<game>.srm` file of the desktop.

**The music.** The desktop plays the music from files next to the program. The web build packs
them into `index.data`, which the browser loads with the page before the game starts.

**Recordings and screenshots.** `F11` and `F12` work as on the desktop, and the browser offers
the file as a download: the recording `<game>_rec_<n>.txt` and the screenshot `<game>_<n>.png`.
A recording plays in the desktop's window with `--replay`, as
[Recordings and replays](/guide/the-desktop#recordings-and-replays) describes.

## Debugging on the web

On the desktop, `F1` opens the debug view and `F5` to `F8` pause, step, speed up and slow down
the game. On the web both are only in a build for debugging:

```sh
superdevkit build web --debug
superdevkit run web --debug
```

A page built without `--debug` is what you put online, and on it the debug view would give the
players pause, slow motion, the video memory and whatever the game registered there to change
while it runs. So without `--debug` the frontend does not read `F1` or `F5` to `F8`, and the
page leaves those keys to the browser, where `F5` reloads the page. `F11` and `F12` stay in
every build.

The build with `--debug` is also compiled without optimization (`-O0`) and with debug
information (`-g`), and it defines `SDEVKIT_WEB_DEBUG` for every file, the game's too. A game
can test that macro to keep its own debug code, a cheat key say, to that build. It is defined in
no other build: not on the desktop, where the debug view is always there, and not on the SNES.

## Putting it online

Copy the `index.*` files of `build/web/` to any web server that serves static files, from a
build without `--debug`. The server must send `index.wasm` with the type `application/wasm`;
the files need nothing else from it. `obj/` and `music/` stay behind.

The page keeps nothing on the server. What a player's game saves stays in the player's browser,
in IndexedDB, tied to the address of your site: moving the page to another address starts every
player with empty save RAM, and the save RAM you played with through `superdevkit run web` on
your own machine is not on the site.

## Checked like the desktop

The web's `main` takes the options of the desktop's, `--check` among them; only the page passes
none. The kit's tests build every example for the web and play its `check.txt` in headless
Chromium, then compare every tick's checksum and the sound with the SNES's, as they do for the
desktop: the game, Rachael and the sound compiled by `emcc` play the same game as on the SNES,
tick for tick. The frame clock, the page and the save RAM in IndexedDB are not part of that
check. The script behind it, `tools/web_check/web_check.js`, takes a web build, a `check.txt`
and a folder for what the check writes. It needs Playwright, installed once in
`tools/web_check` with `npm ci` and `npx playwright install --only-shell chromium`. For CatchMe,
after `superdevkit build web` in its folder, from the top of the repository:

```sh
node tools/web_check/web_check.js examples/catchme/build/web examples/catchme/check.txt out
```

For a game of your own, run `node <home>/tools/web_check/web_check.js build/web check.txt out`
in its folder. The script writes what the check wrote (`hashes.txt`, `sound.txt`, the
screenshots) into `out`, and its exit status is the one of the
[desktop's check](/guide/the-desktop#the-check-without-a-window), or 2 when the page did not
start.
