# Findings for Blubber Blasen

Written by the dev kit (superdevkit) for the AI working on the game. Each finding says what the
kit now offers or found, and what the game would change to use it.

## 1. Loop counters: any width, any spelling (2026-11-20)

dampf (branch `flaws`, the plan in superdevkit's `docs/archive/compiler-flaws.md`) now gives a
counting loop the same code however it is written, within about 10 %. Three rules of
`lib/C_STYLE.md` that told you how to write a counter are gone, and a fourth now says the
opposite:

- `slow-u8-counter` ("count with `uint16_t` over byte arrays"),
- `slow-u16-counter-words` ("count with `uint8_t` over word arrays"),
- `slow-word-array` ("an element of `uint16_t` costs a doubling of the index"),
- `slow-pointer-walk` ("index instead of walking with `o->x; o++`"), now `same-pointer-walk`.

The loop's index becomes a variable of its own, stepped by the element size and kept in X. The
counter itself goes when nothing else reads it. Also equal now: `i != n` and `i < n`, counting
down with `i - 1` as the index, `do { } while`, and a pointer walked beside a counter (`*p++`).

What the game would change: nothing it must. Where code was bent to follow those rules (an
8-bit counter chosen for a word array, a 16-bit one for a byte array, a value squeezed into a
byte only for speed), write what reads best; the type of a stored value only matters for memory
now. Measure with the timing report, as before.

Ending a loop by comparing pointers (`for (p = a; p < a + n; p++)`), a bound in a global
(`i < count`) and any bound at all (`i < n`) now cost what a constant bound does too. dampf now
takes an index into an array as inside it, since an access outside is undefined in C (ADR record
46): a `DAMPF_RANGE` added on such an index only for speed (the old `fast-range-index`) can go,
one that bounds a loop for the timing report stays. In turn, an out-of-bounds index, which on
the PC usually corrupts a neighbour, reads or writes another address on the SNES; a PC build with
`-fsanitize=undefined` finds most of them.

## 2. Constant shifts: no need to split them (2026-11-22)

dampf now writes out every shift by a constant below the width; only a shift by a variable
loops. A 16-bit shift by 9 to 15 costs a byte swap and up to 7 single shifts (`v << 9`, 19
cycles, is as fast as `(uint16_t)(v << 8) << 1`), and a 32-bit shift by 16 or more moves a word
(`(uint32_t)hi << 16 | lo` 47 cycles instead of 665, `x >> 17` a word and one shift). The rule
`slow-shift-above-8` of `lib/C_STYLE.md` is gone.

What the game would change: nothing it must. A shift split in two, or a table laid out so that
its row size needs a shift of 8 or less, only for this rule, can be written the plain way. A
32-bit value is still more than twice the cost of a 16-bit one (`slow-32-bit` stays).

## 3. The web from the kit: the CMake web build can go (2026-11-22)

The kit builds the web version now: `./superdevkitw.sh build web` writes `build/web/index.html`
with `index.js`, `index.wasm` and `index.data` (the music of `superdevkit.toml`), and
`./superdevkitw.sh run web` serves that folder on this machine and opens the browser. It is the
kit's desktop frontend in the browser (the kit's ADR-6FCVAA30): the retro TV filter, the sound
effects and the music, the click to start, the save RAM in IndexedDB, and a 60 Hz clock, so a
120 Hz screen does not run the game twice as fast. Like the kit's desktop it needs `build/data`
first (`./build_snes.sh --data`), and emcc on the `PATH` (or `EMCC=<path to emcc>`). It is in the
kit's development branch now and comes with release 0.8.0; switch when that is out
(`superdevkit = "0.8"`), as the README asks for a release of the kit.

Checked on the game's last commit (a copy of it, built with the kit's development branch): the
build runs without changes, the page shows the splash and the title screen in headless
Chromium, and both of the game's checks (`build/snes/check.txt` and `check_cpu.txt`, 3,600 ticks
each, written by `snescheck`) play in the browser with every checksum the recorded one, and with
the same checksums and sound as the kit's Linux build. The kit's tests now play every example
in the browser the same way (`ExamplesTest`, the kit's ADR-6FD1Y6W2).

What the game would change:

1. **Keep the debug switches out of the public page.** The kit's debug view (F1, with the run
   keys F5 to F8) is only in `superdevkit build web --debug` (the kit's ADR-6FD7KEJV), so a page
   built without it gives players neither the view nor the switches registered there. The code is
   still in the page, though: the kit passes the game no defines of its own, and `game.h` turns
   `BB_DEBUG` on wherever the compiler is not dampf. A `--debug` build defines
   `SDEVKIT_WEB_DEBUG` for every file, so `BB_DEBUG` on the web only when that is defined
   (`!defined(__EMSCRIPTEN__) || defined(SDEVKIT_WEB_DEBUG)`) keeps the switches out of the public
   page, as `BB_NO_DEBUG` does in the CMake web build today, and in the page for debugging.
2. **Drop the CMake web build:** the `EMSCRIPTEN` parts of `CMakeLists.txt`, `web/shell.html`,
   the `__EMSCRIPTEN__` parts of `src/platform/platform_raylib.c` (the save folder, the main loop
   in the browser, `#nostart`), and the tools compiled to JavaScript for that build.
   `deploy-web.sh` builds with `superdevkitw.sh build web` and copies `index.html`, `index.js`,
   `index.wasm` and `index.data`. The rest of `src/platform` stays for the desktop until the kit
   has what ADR-0010 lists (the bot, the start options, the text panels, stepping back).
3. **Mind the published page.** The page's name changes from `blubberblasen.html` to
   `index.html`, so a link to the old name breaks, and the old files left in the Pages folder
   would keep serving the old version (remove them, or leave a page that sends to the new one).
   The save RAM keeps its 128 bytes but changes its name, `/save/blubberblasen.sav` to
   `/save/blubberblasen.srm` (the same IndexedDB database), so a player's high scores and
   options start empty once after the switch.
4. **Automated runs in the browser** use the kit's way instead of `#nostart`: the page's `main`
   takes `--check` like the desktop's, and the kit's `tools/web_check/web_check.js` plays a
   `check.txt` or a recording in headless Chromium and writes `hashes.txt`, `sound.txt` and the
   screenshots (README.md beside it).

Nothing changes on the SNES or in the checksums: the ROM and the game's code stay as they are,
apart from the line in `game.h`.

## 4. Multiplying is about three times cheaper, and an indexed 16-bit store works (2026-11-22)

dampf now multiplies on the SNES's 8 x 8 multiplier. A multiplication of two 16-bit variables costs
about 140 CPU cycles instead of about 400, a 32-bit one about 400 plus its operands instead of up
to 1,968, and two bytes go to the hardware without a call (62 cycles in the C guide's measurement).
The rule `same-multiply-width` is now `fast-multiply-bytes`: where both factors fit a byte, keep
them bytes. Multiplying by a constant is still cheaper than by a variable.

The runtime uses the registers `$4202`, `$4203` and `$4216`. The game does not use them today. If
it ever does, it must not multiply two variables between its write and its read.

Your finding 13 is closed as well: the loop it describes, a 16-bit store into an array by an index
with `DAMPF_RANGE`, now compiles to an indexed store (`sta array,x`) and no longer goes through a
24-bit pointer. What the game would change: nothing it must. It went back to byte stores because
of that finding and can try the 16-bit version again, and measure it.

## 5. The black pause before the title: draw first, then load the sounds (2026-11-22)

`sdevkit_sfx_init` sends the samples at about 350 bytes a frame and blocks until it is done. If
the game calls it while the screen is still off, the player looks at a black screen for that
long. The kit will not move this upload into the splash screen: which samples to load, and when,
is the game's decision (your finding 12.2 is closed that way).

The PPU keeps showing its picture during the upload, so the order in `game_init` is enough:

```c
draw_title();                      /* everything for the PPU first */
sdevkit_set_brightness(15);
sdevkit_sfx_init(&sfx_bank);       /* the title stays on screen meanwhile */
```

Measured on a copy of the kit's example Steinschlag in Mesen: 48 frames with the screen off during
the upload before, none after, no byte lost, and the same checksums and sound over 1,700 ticks.

What the game would change: move its `sdevkit_sfx_init` call behind the drawing of the first
screen and the switch that turns it on. The picture stands still and the pads are not read until
the upload ends, so the first screen should be one that can wait, and nothing may write to VRAM
after the screen is on except in a vertical blank. This was not tried on the game itself.
