# lib: the library of superdevkit

The library a game uses ([ADR-6F3CJX8T](../docs/adr/6F3CJX8T-superdevkit-is-the-project-and-dampf-is-its.md)):
one console API, implemented once per platform. A game includes the headers in `include/sdevkit/`
and is compiled together with the implementation of its platform; it calls nothing else. Every
name it can use starts with `sdevkit_` or `SDEVKIT_`
([ADR-6F3CK2AR](../docs/adr/6F3CK2AR-every-name-of-the-console-api-starts-with.md)).

| Folder | What |
| --- | --- |
| `include/sdevkit/snes.h` | The console API's core ([ADR-6FNPPCSX](../docs/adr/6FNPPCSX-the-console-api-is-the-hardware-at-once-with.md)): the hardware at once. Every PPU register (`sdevkit_reg_write`, `sdevkit_reg_write_twice`, `SDEVKIT_PPU_*`, and macros that build values: `SDEVKIT_BGSC`, `SDEVKIT_OBSEL`, `SDEVKIT_OAM_ATTR`, ...), copies by DMA into VRAM, CGRAM and OAM (`sdevkit_dma`, `sdevkit_vram_copy`, `sdevkit_oam_copy`, ...), HDMA (`sdevkit_hdma_*`), sound effects (`sdevkit_sfx_*`), music (`sdevkit_music_*`), save RAM (`sdevkit_sram`), and the debug calls (`sdevkit_dbg_*`). |
| `include/sdevkit/loop.h` | The frame rhythm: the library's main loop calls `game_init`, then per frame `game_tick(pad)` while the picture is drawn and `game_vblank` in the vertical blank, which every game defines; the joypads' bits `SDEVKIT_KEY_*`. The kit writes nothing in the vertical blank itself. |
| `include/sdevkit/shadow.h`, `sprites.h`, `queue.h`, `input.h`, `easy.h` | The optional layers above the core, each opt-in: the registers and HDMA channels in memory with their marks (`sdevkit_ppu`, `sdevkit_hdma`, `sdevkit_marks`, `sdevkit_shadow_commit`), a sprite list (`sdevkit_sprite`, `sdevkit_sprites_commit`), an upload queue with a budget the SNES and the PC decide the same (`sdevkit_queue_*`), the buttons pressed since the last tick (`sdevkit_input_update`), and all of them with one commit (`easy.h`, `sdevkit_commit`). |
| `include/sdevkit/publish.h` | Not for games yet: the data the tick publishes for interrupt code ([ADR-6FPV1EQV](../docs/adr/6FPV1EQV-interrupt-code-reads-a-copy-of-the-tick-s-data.md), [ADR-6FRDS030](../docs/adr/6FRDS030-interrupt-code-reads-the-copy-of-the-tick-whose.md)). `SDEVKIT_PUBLISH(type, name, tick, vblank)` defines the game's struct, its two published copies, `game_tick`, which runs the game's tick and then copies the struct into the copy interrupt code does not read, and `game_vblank`, which makes that copy the current one and then runs the game's vertical blank; `SDEVKIT_PUBLISHED(name)` gives interrupt code the data of the tick whose vertical blank came last, complete. Macros only, the same C on every platform ([below](#the-published-datas-cost)). |
| `doxygen/` | The HTML reference of the public headers: `doxygen lib/doxygen/Doxyfile` from the repository's top folder writes `build/doxygen/html/index.html` (the `Doxyfile` and the main page, `mainpage.md`). |
| `layers/` | The optional layers' logic, the same on both platforms (`layers.h`, included by `snes/layers.c` and `rachael/layers.c`), written against the core; each platform adds the queue's commit, and Rachael the check of the marks. |
| `snes/` | The SNES: the core in C with registers as `volatile` pointers (`snes.c`, `hw.h`), the layers (`layers.c`) and the main loop (`main.c`), compiled by dampf with the game. See its [README](snes/README.md). |
| `rachael/` | The desktop and the web: [Rachael](#rachael), the SNES rebuilt in portable C (`ppu.c`: memories, ports, registers and the core; `render.c`; `layers.c`). `rachael.h` is its interface to the frontends (`sdevkit__vblank`, `sdevkit__render`, ...), not for games. Taken over from Blubber Blasen's `src/snes`; new against the game's copy: 16 x 16 background tiles, the ports of VRAM, CGRAM and OAM, and row y of the picture is scanline y + 1 as on the SNES. |
| `sound/` | The sound effects' logic, the same on both (`sfx_voices.h`, included by `snes/sfx.c` and `rachael/sfx.c`; [docs/sound.md](../docs/sound.md)): the upload of the driver, the sample directory and the samples, the voices counted down in frames, the priorities, one key on per frame. The SNES sends the bytes through the ports to the SPC700, Rachael puts them into her audio RAM and her DSP (`rachael/dsp.c`). |
| `desktop/` | The desktop's frontend (`main.c`, Linux and Windows): a window with raylib and the retro TV filter (`crt.c`, [RETRO-EFFECT.md](desktop/RETRO-EFFECT.md); Tab, `--no-crt`), `--mute` for a window without sound, `--seed N` for `game_init` and `--set name=value` for what the game registered with the debug view, before the first tick (a recording keeps both, and dampf's emulator refuses such a recording, since the SNES has neither), the keyboard and a gamepad as the joypad, the frame rhythm of `loop.h`; `--check check.txt` plays without a window and writes screenshots, the checksums and the sound (`sound.txt`: every DSP write; `--wav file`: the DSP's output). The window plays the sound through raylib's audio stream. Every session in the window is recorded (the joypad and the checksum of every tick): F11 flags the moment and saves it as `<game>_rec_<n>.txt`, in the format of `check.txt`; `--replay file` plays it in the window, pausing at its marks and at the first tick whose checksum differs, with F5 (pause), F6 (a tick) and F7 (fast); `--jump T` plays fast to tick T and pauses, and Shift+F6 goes a tick back (the program starts again and replays up to the tick before, so not on the web); `dev/replay.sh <rom> <recording>` plays it on the SNES side, in dampf's emulator. F1 opens the debug view beside the picture (`view.c`, ADR-6F3CK03Z): the run (pause, a tick, slow motion with F8, fast), what the game registered with `sdevkit_dbg_toggle`, `_range`, `_color` and `_action` to change while it runs, its boxes (`sdevkit_dbg_box`) over the picture, CGRAM, VRAM's tiles and the sprites; a change made there is noted in the recording, whose replay plays another game from there. `--view-shot file.png` draws the view after a check, in a hidden window. Save RAM (`sdevkit_sram`) is `<game>.srm` next to the program. Built by `superdevkit build linux` and `win64` (MSVC), and for the web by `superdevkit build web` (emcc) with `web/`. |
| `web/` | What the web adds to the desktop's frontend ([ADR-6FCVAA30](../docs/adr/6FCVAA30-the-web-runs-the-desktop-s-frontend-which-waits.md)): the page (`shell.html`: the canvas fills the browser's window, a click starts the game, since the browser plays sound and gives the page the keyboard only after one, and the keys of the game and the debug view stay with it), and `web.c`: `desktop/main.c`'s loops wait there for the browser's frames (Asyncify), a clock of 60 ticks a second says how many ticks each frame runs; the save RAM is `/save/<game>.srm` in IndexedDB; F11's recording and F12's screenshot become downloads. The debug view (F1) and its run keys (F5 to F8) are only in `superdevkit build web --debug`, which also defines `SDEVKIT_WEB_DEBUG` for the game ([ADR-6FD7KEJV](../docs/adr/6FD7KEJV-on-the-web-the-debug-view-is-only-in-a-build-with.md)); a page built without it is ready to put online. `main` takes the desktop's options on the web too (not `--view-shot`): the page passes none, and the examples' test hands it a `--check` ([tools/web_check](../tools/web_check/README.md)). `superdevkit build web` writes `build/web/index.html` (with `index.js`, `index.wasm`, and `index.data` with the music), `superdevkit run web` serves it on this machine and opens the browser. |
| `splash/` | The splash screen every game starts with (ADR-6F60Z5JJ and ADR-6F60ZA6T): its course (`splash.h`, included by both main loops) and the logo (`splash_logo.h`: BG1 tiles and the stars as sprites, made by `dev/art/splash.py` from the parts that `dev/art/splash_parts.py` cuts from `dev/art/splash/logo_source.png`). |
| `C_STYLE.md` | How to write a game's C for dampf: rejected, allowed, slow, fast, no difference; its speed rules are measurements (`StyleGuideTest`). |
| `SNES_HARDWARE.md` | What the SNES allows and punishes, which of it the kit has, and how each rule is checked. |

A game is built by `superdevkit build` from its `superdevkit.toml` (the `cli`). On the SNES with
`-I lib/include` in front of its own include paths and `lib/snes/*.c` among its sources (dampf).
On the desktop (`linux`, `win64`) and the web the game is compiled alone, without the host's headers
(`-nostdinc`, dampf's include folder and `lib/include`), its objects are checked to call only the
console API (`superdevkit.project.Symbols`, which reads ELF, COFF and WebAssembly objects), then it
is linked with `lib/rachael/*.c lib/desktop/*.c` (and `lib/web/*.c` for the web) and raylib. The same game code gives the same picture on both,
pixel for pixel (`dev/compare_example.sh`).

## Rachael

Rachael is the SNES rebuilt in portable C: its video memory, CGRAM and OAM, its registers, DMA
and HDMA, and a renderer that builds the picture line by line as the PPU does. It is not an
emulator: it runs no 65816 code. The game is compiled for the PC with the system's C compiler
and calls the same console API as on the SNES, so the same source runs on the desktop and in the
browser, and gives the same picture as the SNES, pixel for pixel
(`dev/compare_example.sh` checks it). Rachael also counts every byte the SNES would lose (written
outside the vertical blank, or more than a vertical blank can copy), so what fits on the desktop
fits on the console, and every register or HDMA channel written while the picture is drawn, which
the SNES would show from the line being drawn on (switching the screen off is allowed). She also
counts every register write she does not draw (a mode other than 1 and 7, mosaic, `SETINI`,
direct colour, `VMAIN`'s address remapping, a DMA from the PPU, indirect HDMA;
`sdevkit__undrawn`): the SNES would show something else there, and the desktop check fails on
each of them as on a lost byte.

If you know Wine, Rachael is to the SNES what Winelib is to Windows. Wine runs Windows programs on
Linux without emulating a PC: it implements the Windows API on Linux ("Wine Is Not an Emulator").
Winelib goes one step further: the program's source is compiled on Linux against Wine's API, and
the result is an ordinary Linux program. Rachael does the same for a SNES game. Its source is
compiled by the PC's C compiler against the console API (`sdevkit/snes.h`), Rachael implements
that API, and the result is an ordinary program for the PC. No 65816 runs, no ROM is loaded,
nothing is translated at run time.

The difference to Winelib lies under the API. Wine can hand most calls to Linux (a window, a
file); the SNES's API only writes to video memory and registers, and nothing on the PC does what
they do. So Rachael also rebuilds the hardware behind the API: the memories, the registers, DMA
and the PPU that turns them into a picture. That is why the picture is the console's, pixel for
pixel.

What Rachael does not give, because it is no emulator, is the console's time. The game runs at
the PC's speed: a tick that takes two frames on the SNES takes one on Rachael, and code too slow
for the console is never too slow there. dampf's timing report and the runs in Mesen
(`dev/check_example.sh`) say how fast a game is on the SNES. A game must also reach the console
only through the API: the desktop build checks that what a game's objects call is the kit's
(`superdevkit build linux`, `win64` and `web`), since Rachael cannot see a write that goes around it.

Three words, kept apart in all documents of the kit:

| Word | What it is | Where |
| --- | --- | --- |
| Rachael | the console rebuilt in C, the same on every PC target | `rachael/` |
| frontend | what one PC target adds to Rachael: the window, the keyboard and gamepad as the joypad, the frame rhythm of `loop.h`, the headless check | `desktop/` (Linux and Windows), `web/` with `desktop/` (the browser) |
| target | what a game is built for: `snes` (dampf and `snes/`, no Rachael), `linux`, `win64` and `web` (the system's C compiler or `emcc`, Rachael and that target's frontend) | `superdevkit build <target>` |

So a game runs "on the SNES" or "on Rachael"; "the desktop" and "the web" name targets and their
frontends, never Rachael itself. A difference in the picture between the SNES and a PC target is
a bug in Rachael (or in `snes/`); a difference between two PC targets is a bug in a frontend.

The name is the replicant of Blade Runner who is so close to the real thing that only a very
long Voight-Kampff test gives her away; the name is [ADR-6F60Z3ZB](../docs/adr/6F60Z3ZB-the-pseudo-snes-is-called-rachael.md).

## Rules

- Every function of the console API exists on every platform. A feature not built on one
  platform yet is a stub there, which does nothing; the table below says which.
- Game code contains no platform `#ifdef`s. A game that must know whether a feature works asks
  for the feature (`#if SDEVKIT_HAS_SOUND`, say), never for the platform. There is no such
  macro yet: every function can be called everywhere, and a stub (music on the SNES) does
  nothing. `SDEVKIT_WEB_DEBUG` names a build, not a platform: a web build made for debugging
  ([ADR-6FD7KEJV](../docs/adr/6FD7KEJV-on-the-web-the-debug-view-is-only-in-a-build-with.md)).
- The library's own names are `static`, or `sdevkit__` (two underscores) when files share them,
  declared outside `include/`. No name of the kit starts with `game_`.
- The library is ordinary code for dampf: the subset, `<stdint.h>` and `<dampf.h>`.
- The public headers are the API's documentation: a game is written from them alone, so they
  explain the hardware at the names that reach it (the picture, the frame, every register and its
  bits, the formats of tiles, maps, colours and sprites, timing), in Doxygen comments, one for
  every function, type, variable, field and macro (`LibDocTest`; where Doxygen is installed, it also
  builds the HTML from them without a warning).

`LibApiTest` checks that every function of `sdevkit/snes.h` and of the layers' headers is defined
in `snes/` and in `rachael/` (or in `layers/`, which both include), and has its row here. `LibNamesTest` checks the names: every name of the public headers has the
prefix, and every external name in `lib/` is `sdevkit_*`, `sdevkit__*` or `main`.

## Built and stub, per function and platform

Desktop means `rachael/` with `desktop/` (Linux, Windows, and with `web/` the browser). The last column is what a call costs on the
SNES: best to worst case in CPU cycles, calls included, from the timing report of a program that
calls every function (`LibCostTest` writes it with `LIB_COSTS=update`, and fails when it is not
today's). A call with constants is often less: dampf inlines the small ones, and a register
write with a constant register is one store. The layers' commits are in the next section.

| Function | SNES | desktop | CPU cycles on the SNES |
| --- | --- | --- | ---: |
| `sdevkit_reg_write` | built | built | 25 |
| `sdevkit_reg_write_twice` | built | built | 43 |
| `sdevkit_dma` | built | built (VRAM, CGRAM, OAM and the registers by their ports; from the PPU to memory is counted as undrawn) | 94 |
| `sdevkit_vram_copy` | built | built | 116 |
| `sdevkit_vram_column_copy` | built | built | 116 |
| `sdevkit_vram_fill` | built | built | 120 |
| `sdevkit_cgram_copy` | built | built | 100 |
| `sdevkit_cgram_set` | built | built | 47 |
| `sdevkit_oam_copy` | built | built | 104 |
| `sdevkit_mode7_map_copy` | built | built | 116 |
| `sdevkit_mode7_tiles_copy` | built | built | 116 |
| `sdevkit_dma_fits` | built (from the line the picture is at) | built (from the bytes copied in this vertical blank) | 81 to 86 |
| `sdevkit_hdma_set` | built | built (the direct modes; another is counted as undrawn) | 84 |
| `sdevkit_hdma_enable` | built | built | 21 |
| `sdevkit_shadow_commit` | built | built (and counts a group changed without its mark) | 135 to 2,143 |
| `sdevkit_sprite` | built | built | 21 to 133 |
| `sdevkit_sprites_commit` | built | built | 60 to 560 |
| `sdevkit_queue_dma` | built | built | 59 to 198 |
| `sdevkit_queue_vram` | built | built | 29 |
| `sdevkit_queue_vram_column` | built | built | 29 |
| `sdevkit_queue_cgram` | built | built | 29 |
| `sdevkit_queue_color` | built | built | 79 to 246 |
| `sdevkit_queue_mode7_map` | built | built | 29 |
| `sdevkit_queue_mode7_tiles` | built | built | 29 |
| `sdevkit_queue_room` | built | built | 42 to 53 |
| `sdevkit_queue_commit` | built | built | 48 to 3,672 |
| `sdevkit_input_update` | built | built | 86 |
| `sdevkit_commit` | built | built | 249 to 6,381 |
| `sdevkit_sfx_init` | built | built | 51 to unbounded |
| `sdevkit_sfx_play` | built | built | 39 to 158 |
| `sdevkit_music_play` | empty, not built yet: dampf removes the calls (`DebugCallsTest`) | built (the track's file, over and over, in the window) | 6 |
| `sdevkit_music_stop` | empty, not built yet: dampf removes the calls | built | 6 |
| `sdevkit_sram` | built (the cartridge's battery RAM at $70:0000, `dampf --sram`) | built (`<game>.srm` next to the program) | 11 |
| `sdevkit_dbg_vram` | built (a single zero word: the SNES cannot read VRAM) | built | 14 |
| `sdevkit_dbg_hash` | built (the address and size to a port; the checker hashes) | built | 79 |
| `sdevkit_dbg_toggle` | empty: dampf removes the calls (`DebugCallsTest`) | built (the debug view, F1) | 6 |
| `sdevkit_dbg_range` | empty: dampf removes the calls | built (the debug view) | 6 |
| `sdevkit_dbg_color` | empty: dampf removes the calls | built (the debug view) | 6 |
| `sdevkit_dbg_action` | empty: dampf removes the calls | built (the debug view) | 6 |
| `sdevkit_dbg_text` | empty: dampf removes the calls | built (a line the debug view shows) | 6 |
| `sdevkit_dbg_value` | empty: dampf removes the calls | built (a value the debug view shows) | 6 |
| `sdevkit_dbg_box` | empty: dampf removes the calls, and the loops left empty | built (over the picture in the debug view) | 6 |
| `sdevkit_dbg_fill` | empty: dampf removes the calls, and the loops left empty | built (a filled box in the debug view) | 6 |
| `sdevkit_dbg_arrow` | empty: dampf removes the calls, and the loops left empty | built (an arrow in the debug view) | 6 |

## The commit's time

The kit writes nothing in the vertical blank; a game's `game_vblank` has all of it. The optional
layers' commit (`sdevkit_commit`, `layers/layers.h`) is the one piece of the kit that runs there
when a game calls it: a screen to be switched off goes off first, then the sprite list (up to four
DMAs, 544 bytes at most), the registers of the groups marked, the queue, and INIDISP last. In CPU
cycles on the SNES, from the timing report of `dev/commit_stress` (2026-11-25):

| Part | Cycles |
| --- | ---: |
| `sdevkit_commit`, nothing marked, no sprite, nothing queued | 238 |
| `sdevkit_shadow_commit`, every group and seven HDMA channels | 2,143 |
| `sdevkit_sprites_commit`, at most | 560 |
| `sdevkit_queue_commit`, a copy's setup | 153 |
| `sdevkit_commit`'s worst case without the queue | 2,695 |

What the queue may take follows from that worst case. The vertical blank is 37 lines of 1,364
master cycles, less 40 a line for the DRAM's refresh: 48,988. The commit's worst case without the
queue takes 18,865 of them at 7 master cycles a CPU cycle (code from FastROM at 6, data from WRAM
at 8), the sprites' DMA 4,352 (8 a byte): 25,771 are left, 3,221 bytes of DMA. A copy's setup of
153 cycles is 1,071 master cycles, as long as 134 bytes. Hence `SDEVKIT_QUEUE_BYTES` 3,072 and
`SDEVKIT_QUEUE_COST` 136 (`sdevkit/queue.h`), with 149 bytes to spare, the same on the SNES and on
Rachael, so both take and refuse the same copies. The numbers hold when the commit starts
`game_vblank`; a game that does its own copies before it has less.

`dev/commit_stress.sh` checks it on the console's time: a program whose every tick asks for the
worst case (every group, the seven channels, the queue filled to its budget with 24 copies, then
with one copy of all its bytes, in turn; the sprite list full, then empty, in turn) plays 600 ticks
in Mesen. On 2026-11-25 no byte reached VRAM or OAM outside the vertical blank, the last DMA
started on line 256, and the commit's last write (INIDISP) was on line 259 of the vertical blank's
225 to 261. Run it again when the layers' code or dampf's code for it changes.

## The published data's cost

A game that publishes data for interrupt code (`sdevkit/publish.h`) pays for it after every tick:
the copy of its struct into the copy interrupt code does not read, a loop of 21 cycles per two
bytes through dampf's struct copy, and the write of the byte that marks it ready; and in
`game_vblank` the check of that byte and the switch, 16 cycles when there is nothing to switch and
38 when there is. In CPU cycles on the SNES, from the timing report of a tick that changes one word,
against the same game without the header (2026-11-26):

| Struct | Cycles after the tick |
| --- | ---: |
| 32 bytes | 411 |
| 128 bytes | 1,419 |
| 512 bytes | 5,451 |

About 75 cycles and 10.5 a byte; a frame has about 50,000. The RAM is two copies of the struct and
three bytes. Interrupt code finds the current copy with a load and a branch (about 15 cycles in
`SDEVKIT_PUBLISHED`) and then reads it indexed (`lda copy+n,x`, a cycle more per access than a
global). A game that does not include the header pays nothing.
