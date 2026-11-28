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

## 6. A release holds `dev/replay.sh`, and 0.8.0 has a tag (2026-11-23)

Your finding 14 is closed. The kit's distribution has `dev/replay.sh`, both in the home that
`installDist` writes and in a release's zip, and release 0.8.0 is tagged `v0.8.0`. Versions before
it have no tag.

What the game would change: nothing it must. It can pin the kit to the tag instead of a commit
named "Version x.y.z", and call `<home>/dev/replay.sh` from a release.

## 7. A 32-bit value combined bit by bit costs what two halves cost (2026-11-23)

dampf now splits a `uint32_t` that is only loaded, combined with `&`, `|` or `^` and stored into two
16-bit values itself: `hash ^= key` costs 36 CPU cycles, the same as on two `uint16_t` halves
(84 before). The C guide's `slow-32-bit` now speaks only of arithmetic, and `same-32-bit-bits` is
new. What the game would change: nothing it must. A value kept as two halves only for the old rule
can be one `uint32_t` again. A test of such a value with `!= 0` or `==` is split too: a loop that
tests 32-bit rows against a mask costs 40 % less. Additions, ordered comparisons and
multiplications of 32-bit values still cost more than twice their 16-bit form.

## 8. The console API: the PVSnesLib calls are gone, the kit copies no sprite table (2026-11-25)

The kit's console API is now a core that is the hardware at once, with optional layers above it
(the kit's ADR-6FNPPCSX, `lib/include/sdevkit/snes.h` and the headers beside it). The frame rhythm
stays as the game has it: `game_init`, `game_tick(pad)` while the picture is drawn, `game_vblank`
in the vertical blank. What changes:

- **The kit no longer copies the sprite table.** The main loop used to copy all 544 bytes of
  `sdevkit_oam_memory` into OAM before `game_vblank`, every frame. That table is gone: the game
  keeps its own (in the hardware's format, every y set to `SDEVKIT_OAM_HIDE_Y` at the start) and
  copies it in `game_vblank` with `sdevkit_oam_copy(byte, src, bytes)`. Copying only the entries
  in use and the high table's bytes for them (`4 * n` bytes from 0, then from 512) saves the rest
  of the 4,352 master cycles the full copy took in each vertical blank.
- **The PVSnesLib setup calls are gone**; every register is written by `sdevkit_reg_write(reg,
  value)` (one store on the SNES) or, for the registers that take two bytes, by
  `sdevkit_reg_write_twice(reg, value)`, with macros for the values.
- **The copies take the destination first**, like `memcpy`.
- **The desktop check also fails on a register written while the picture is drawn** (`drawing N`
  in its summary line; the SNES would change the picture from that line on). Switching the screen
  off may come at any time; everything else belongs in `game_vblank` or `game_init`. A game that
  fades or scrolls in `game_tick` now sees it on the desktop.

What replaces what, for the calls the game uses:

| Before | Now |
| --- | --- |
| `sdevkit_oam_memory` (copied by the kit) | a table of the game's own, copied in `game_vblank` with `sdevkit_oam_copy(0, table, 4 * n)` and `sdevkit_oam_copy(512, table + 512, (n + 3) / 4)`, or all of it with `sdevkit_oam_copy(0, table, SDEVKIT_OAM_BYTES)` |
| `SDEVKIT_ATTR2_DISABLED` | `SDEVKIT_OAM_HIDE_Y` |
| `sdevkit_oam_init` | nothing: the kit's start-up hides every sprite in OAM; the game's own table starts hidden as the game fills it |
| `sdevkit_oam_init_gfx_attr(address, sizes)` | `sdevkit_reg_write(SDEVKIT_PPU_OBSEL, SDEVKIT_OBSEL(address, sizes))` |
| `sdevkit_dma_copy_vram(src, address, bytes)`, `_copy_cgram`, `_copy_vram_column` | `sdevkit_vram_copy(address, src, bytes)`, `sdevkit_cgram_copy(colour, src, bytes)`, `sdevkit_vram_column_copy(address, src, bytes)` |
| `sdevkit_set_palette_color(i, c)` | `sdevkit_cgram_set(i, c)` |
| `sdevkit_set_mode(mode, flags)` | `sdevkit_reg_write(SDEVKIT_PPU_BGMODE, mode \| flags)` and `sdevkit_reg_write(SDEVKIT_PPU_TM, ...)`: `set_mode` also switched on BG1 to BG3 and the sprites |
| `sdevkit_bg_set_disable(n)` (and `_enable`, `_sub`) | `SDEVKIT_PPU_TM` (`_TS`) written with all the layers the game wants (`SDEVKIT_BG1_ENABLE \| ...`): the kit keeps no copy of the register any more |
| `sdevkit_bg_set_map_ptr(n, address, size)` | `sdevkit_reg_write(SDEVKIT_PPU_BG1SC + n, SDEVKIT_BGSC(address, size))` |
| `sdevkit_bg_set_gfx_ptr(n, address)`, `sdevkit_bg_init_tile_set(...)` | `SDEVKIT_PPU_BG12NBA` (`BG34NBA`) written with both layers' tiles, `SDEVKIT_BGNBA(first, second)`; the tile set's copies with `sdevkit_vram_copy` and `sdevkit_cgram_copy` |
| `sdevkit_bg_set_scroll(n, x, y)` | `sdevkit_reg_write_twice(SDEVKIT_PPU_BG1HOFS + 2 * n, x)` and `(SDEVKIT_PPU_BG1VOFS + 2 * n, y)` |
| `sdevkit_set_color_effect(a, b)`, `sdevkit_set_color_intensity(apply, i)` | `SDEVKIT_PPU_CGWSEL`, `SDEVKIT_PPU_CGADSUB`; `sdevkit_reg_write(SDEVKIT_PPU_COLDATA, apply \| i)` |
| `sdevkit_set_brightness(b)`, `sdevkit_set_screen_off`, `sdevkit_set_screen_on` | `sdevkit_reg_write(SDEVKIT_PPU_INIDISP, b)` (`SDEVKIT_FORCED_BLANK` for off); `set_screen_on` waited for a vertical blank, so write it in `game_vblank` |
| `sdevkit_reg_write`, `sdevkit_hdma_set`, `sdevkit_hdma_enable`, `sdevkit_dma_fits`, sound, music, save RAM, the debug calls | unchanged |

The kit's internal names the game's desktop code reads (`sdevkit__vblank`, `sdevkit__render`,
`sdevkit__reg`, `sdevkit__oam`, `sdevkit__cgram`, `sdevkit__stats`) stay; `sdevkit__oam()` is
OAM as the game's copies wrote it, and `SDEVKIT__VBLANK_DMA_BYTES` is 6,244 now that the sprite
table counts against it like any copy.

Optional, if the game wants them: the layers (`sdevkit/easy.h`): a register shadow written in the
tick, a sprite list, a queue of copies with a budget the SNES and the desktop decide the same, the
buttons pressed. They are not needed for anything above.

What else the game gets without changing anything: dampf now writes `sdevkit_reg_write_twice(reg,
v)` and `REG = v; REG = v >> 8` of a variable as two byte loads next to their stores. Measured on
the kit's examples in dampf's emulator, which now counts a DMA's time, every example on the core
does less work a tick than on the old API (Er-Tüp, which kept its own sprite list as the game
does, 0.98).

What the game would change: the calls along the table, and its own sprite table with its copy in
`game_vblank`. Er-Tüp's diff on the kit's branch `new_snes_api` (`examples/ertuep`) shows each kind
of change on a game built the way the game is. Checking that the game builds and plays the same is
the game project's job.

## 9. `DAMPF_BUDGET` counts master cycles: every budget needs a new number (2026-11-27)

dampf's timing report, the cycles in its assembly comments and `DAMPF_BUDGET` now count master
cycles instead of CPU cycles (superdevkit's ADR-6FVMDV79): each access at the speed of the memory
it reaches (code from FastROM 6, WRAM 8, I/O registers 6), the unit of the frame (357,368) and of
the vertical blank (about 49,000 without the DRAM refresh). The worst cases of the kit's examples
are 6.2 to 6.6 times what they were in CPU cycles.

What the game must change: every `DAMPF_BUDGET(n)`. With the old numbers the build fails, and the
error says that the budget may be in CPU cycles. Either multiply each `n` by about 6.5, or better
take the function's new worst case from `<rom>.timing.txt` and give it the margin the old budget
had over the old worst case; a budget that was a share of the frame or of the vertical blank can
now be that share of 357,368 or 49,000 directly. Nothing else changes: the code dampf writes is
the same, byte for byte.

## 10. The IRQ's HDMA: declare it in `game_irq` (2026-11-27)

dampf now checks the game's IRQ at build time (superdevkit's ADR-6FVWK4JE): from the end of
`sdevkit_irq_wait_hblank` to the handler's last PPU register write it may take 158 master cycles
(about 25 CPU cycles), less the HDMA that runs on the IRQ's line, and the build fails when the
writes come later (the row would tear). HDMA is decided at run time, so the handler states it:
`SDEVKIT_IRQ_HDMA(channels, bytes)` from `sdevkit/irq.h`, a statement in `game_irq`, with the
channels that may run on its line (`SDEVKIT_HDMA_CHANNELn` or'ed) and the most bytes they write
there together (1 for `SDEVKIT_HDMA_ONE`, 2 for `_TWO_REGS` and `_TWICE`). Each channel of one
byte takes 42 master cycles of the 158, two of one byte each 66. On the desktop and the web the
check fails when more HDMA runs on the IRQ's line than declared, so a missing declaration shows
there.

What the game must change: if it has a `game_irq` and HDMA channels running on its line (the
game uses HDMA for its colour windows), add the declaration at the start of `game_irq`, with
every channel that can be on at that row. Then build: the timing report's line `_game_irq (after
the wait; room, worst)` shows how much room is left; if the build fails, move work from after
the wait to before it, or write fewer registers there.

## 11. `oam_add`: four changes take 21 % off each call (2026-11-27)

The kit compared dampf's code for the game's three hottest functions in play with hand-written
assembly (superdevkit's `docs/optimizations.md`, "The game's hottest functions by hand"). It
played a copy of the game, solo and with the CPU partner, from two scripted inputs in dampf's
emulator, and every changed version kept all of WRAM identical to today's build at the end of
every tick. `oam_add` with `sprite_palette` inlined into it is the hottest function: 9.8 % of the
work with the CPU partner and 12.3 % solo, 74,737 calls in 5,730 ticks. Four changes to its C,
each behaving as today for every input (also on the PC):

1. **No tinted flag.** Today a flag is set on each path that picks the palette and tested twice
   later. Decide the rare tinted case once and branch to code of its own for it (a small static
   function that records the entry for `video_sprites_end` and writes the sprite with palette 0),
   then return. dampf inlines it. This alone saves 251 master cycles a call.
2. **The palette classes that depend on the palette as two `const` tables of 8**, one per base
   class, indexed by the palette, holding the palette bits as the sprite's attribute wants them,
   with one value that no class has (`$FF`) for "tinted". The other base classes keep their
   computed bits, which are even and never that value. This replaces the chain of tests and
   constant returns of `sprite_palette` (another 89).
3. **`slots` as an offset into its one table instead of a pointer global.** Every value the game
   stores in `slots` points into the same sheet table, so a `uint16_t` offset (the sheet number
   times the row length, set where the pointer is set today) reads the same byte through the
   table as one byte array. dampf copies a pointer global into the function's frame at every call
   and reads through it with a 24-bit address; the offset indexes the table directly (105 less).
4. **`sprites_filled` incremented before the stores** that fill the entry, with the old value kept
   for the entry's address (33 less).

Measured with the CPU partner (solo in brackets): 2,268 master cycles a call on average before,
1,790 after (2,223 and 1,790); the measured worst call 2,548 before, 2,252 after; the work per
tick 2.1 % less (2.6 %); the function 489 bytes before, 400 after. The timing report's worst case
falls from 3,332 to 2,382. The only difference from today is where C leaves the behaviour
undefined: with 136 or more entries the entry's last byte lands past the table, on
`sprites_filled` itself, and the increment then comes before that store instead of after it. The
game never has more than 128 entries.

Two things the game cannot do in C: a hand-written version took 1,067 a call (−5.3 %, −6.6 %
solo), and copying the function's body into its five hottest call sites saved the call's cost of
about 240 a call (−1.3 %, −2.3 % solo with today's body, for 1.7 KB); dampf does neither by
itself yet. The hand-written version exists and can be handed over by the user, for a game that
wants to link it with `DAMPF_ASM`; it is written against the C of change 3.

## 12. `evaluate`: 39 % less time a call from its C (2026-11-27)

The CPU partner's `evaluate` is 8.6 % of the work with the CPU partner (solo never calls it). Five
changes, each giving the same result for every input (measured: the same result in all 5,045
calls of the run and in 17,000 further inputs of game-like, random and edge data against
today's version):

1. **Return before the loops when the danger list is empty**, with the value the loops would
   leave. One call in five has no danger, and the sample loop runs for nothing today (9,347
   master cycles each).
2. **Hoist what does not change out of the sample loop**: the player's position read through the
   pointer once, the action decoded once into its left, right, jump and dash parts (a `switch`
   on it does this best, with an action beyond the last counting as a dash, as today), and the
   dash case decided before the loop instead of in every round.
3. **The two 5-element coordinate arrays of `Danger` as one array of 10**, x of each sample
   first, then y, so that the bytes stay where they are. dampf reaches an array field indexed by
   a variable directly only at the struct's offset 0, and the second array today through a
   24-bit pointer built for every pair (−3,617 a call). `add_danger`, `add_coming_shot` and the
   debug view write the new array.
4. **The end of the danger list computed once** as a pointer, and the list walked with a pointer
   up to it instead of an index below `danger_count`. The pointer walk also gives the timing
   report a bound of 32 dangers instead of 255: the function's static worst case falls from 2.45
   million master cycles to 0.19 million.
5. **The signed values compared biased**: keep `best`, the two distances and their maximum as
   `v ^ 0x8000` in `uint16_t` and compare those unsigned, which keeps their order; flip the
   result back on return. Sums wrap modulo 2^16 as today's casts do. dampf compares two signed
   variables slowly today (an M12 row of the kit's roadmap will make this one unnecessary; drop
   it then).

Measured: 29,409 master cycles a call on average before, 17,923 after, the worst call 65,636
before and 41,756 after; the work per tick with the CPU partner 3.0 % less. The function is 554
bytes before and 529 after. A hand-written version (11,877 a call, −5.3 %) exists and can be
handed over by the user.

## 13. `separate`: keep it as it is for now (2026-11-27)

The bubbles' pair loop is 8.0 % of the work with the CPU partner and 12.2 % solo. A spelling
with a list of the floating bubbles (their indexes and copies of their pixel positions, built
before the pair loop and renewed after a push) takes 10.4 % less a call on average in play, the
work per tick 0.8 % less (1.2 % solo). It is not recommended now: its static worst case is about
30 % larger, and on states where many bubbles touch it is 23 % slower than today, because dampf
compiles the list poorly (its counters in memory, a mask on every byte, every index multiplied by
the struct's size). A list of byte offsets, which the kit's C guide recommends for lists, cannot
be written for `Bubble`: its size differs from the PC's, so `sizeof` is rejected, and its 16
offsets do not fit a byte. It pays once dampf compiles such lists well; the kit will say so in a
finding. A hand-written version (−3.8 %, −6.0 % solo) exists and can be handed over by the user.

## 14. Signed comparisons are fast now: drop change 5 of finding 12 (2026-11-28)

dampf now compares two signed 16-bit variables with one subtraction and the sign of the true
difference, and compiles `a > b ? a - b : b - a` (any of the four relations, either order) and
`v < 0 ? -v : v` as one subtraction and a negation, both exact for every input. Nothing in the
game has to change for it. Measured on a copy of the game in dampf's emulator, with WRAM
identical to today's build at the end of every tick of both scripted runs:

- today's code: `evaluate` 22,581 master cycles a call on average instead of 29,409, the work
  per tick with the CPU partner 3.0 % less, solo 0.8 % less;
- with the changes of finding 12 but without change 5 (the values compared biased) and without
  the `switch` of change 2, `evaluate` takes 16,010 a call; with change 5 and the `switch` it
  takes 17,923, and the work per tick with the CPU partner is 290,203 master cycles against
  288,468 without them. So leave change 5 out: compare the signed values as they are. The
  `switch` of change 2 was measured only together with change 5.
