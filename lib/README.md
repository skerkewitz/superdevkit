# lib: the library of superdevkit

The library a game uses (ADR [record 29](../docs/adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)):
one console API, implemented once per platform. A game includes the headers in `include/sdevkit/`
and is compiled together with the implementation of its platform; it calls nothing else. Every
name it can use starts with `sdevkit_` or `SDEVKIT_`
([record 34](../docs/adr/0034-every-name-of-the-console-api-starts-with.md)).

| Folder | What |
| --- | --- |
| `include/sdevkit/snes.h` | The console API: PVSnesLib's functions renamed (`setMode` is `sdevkit_set_mode`), the extensions PVSnesLib has no function for (`sdevkit_reg_write`, `sdevkit_hdma_set`, `sdevkit_dma_fits`), their constants (`SDEVKIT_*`), the sprite table `sdevkit_oam_memory`, and the debug calls (`sdevkit_dbg_*`). |
| `include/sdevkit/loop.h` | The frame rhythm: the library's main loop calls `game_init`, then per frame `game_tick` and `game_vblank`, which every game defines; the pad bits `SDEVKIT_KEY_*`. |
| `snes/` | The SNES: the API in C with registers as `volatile` pointers (`snes.c`, `hw.h`) and the main loop (`main.c`), compiled by dampf with the game. See its [README](snes/README.md). |
| `rachael/` | The desktop (and the web, M8): [Rachael](#rachael), the SNES rebuilt in portable C (`ppu.c`, `render.c`). `rachael.h` is its interface to the frontends (`sdevkit__vblank`, `sdevkit__render`, ...), not for games. Taken over from Blubber Blasen's `src/snes`; new against the game's copy: 16 x 16 background tiles, and row y of the picture is scanline y + 1 as on the SNES. |
| `sound/` | The sound effects' logic, the same on both (`sfx_voices.h`, included by `snes/sfx.c` and `rachael/sfx.c`; [docs/sound.md](../docs/sound.md)): the upload of the driver, the sample directory and the samples, the voices counted down in frames, the priorities, one key on per frame. The SNES sends the bytes through the ports to the SPC700, Rachael puts them into her audio RAM and her DSP (`rachael/dsp.c`). |
| `desktop/` | The desktop's frontend (`main.c`, Linux and Windows): a window with raylib and the retro TV filter (`crt.c`, [RETRO-EFFECT.md](desktop/RETRO-EFFECT.md); Tab, `--no-crt`), the keyboard and a gamepad as the joypad, the frame rhythm of `loop.h`; `--check check.txt` plays without a window and writes screenshots, the checksums and the sound (`sound.txt`: every DSP write; `--wav file`: the DSP's output). The window plays the sound through raylib's audio stream. Every session in the window is recorded (the joypad and the checksum of every tick): F11 flags the moment and saves it as `<game>_rec_<n>.txt`, in the format of `check.txt`; `--replay file` plays it in the window, pausing at its marks and at the first tick whose checksum differs, with F5 (pause), F6 (a tick) and F7 (fast); `dev/replay.sh <rom> <recording>` plays it on the SNES side, in dampf's emulator. F1 opens the debug view beside the picture (`view.c`, ADR record 32): the run (pause, a tick, slow motion with F8, fast), what the game registered with `sdevkit_dbg_toggle`, `_range`, `_color` and `_action` to change while it runs, its boxes (`sdevkit_dbg_box`) over the picture, CGRAM, VRAM's tiles and the sprites; a change made there is noted in the recording, whose replay plays another game from there. `--view-shot file.png` draws the view after a check, in a hidden window. Save RAM (`sdevkit_sram`) is `<game>.srm` next to the program. Built by `superdevkit build linux` and `win64` (MSVC). |
| `web/` | The web, later (M8, [the plan](../docs/superdevkit-transition.md)). |
| `splash/` | The splash screen every game starts with (ADR records 37 and 42): its course (`splash.h`, included by both main loops) and the logo (`splash_logo.h`: BG1 tiles and the stars as sprites, made by `dev/art/splash.py` from the parts that `dev/art/splash_parts.py` cuts from `dev/art/splash/logo_source.png`). |
| `C_STYLE.md` | How to write a game's C for dampf: rejected, allowed, slow, fast, no difference; its speed rules are measurements (`StyleGuideTest`). |
| `SNES_HARDWARE.md` | What the SNES allows and punishes, which of it the kit has, and how each rule is checked. |

A game is built by `superdevkit build` from its `superdevkit.toml` (the `cli`). On the SNES with
`-I lib/include` in front of its own include paths and `lib/snes/*.c` among its sources (dampf).
On the desktop (`linux`, `win64`) the game is compiled alone, without the host's headers
(`-nostdinc`, dampf's include folder and `lib/include`), its objects are checked to call only the
console API (`superdevkit.project.Symbols`), then it is linked with `lib/rachael/*.c
lib/desktop/*.c` and raylib. The same game code gives the same picture on both,
pixel for pixel (`dev/compare_example.sh`).

## Rachael

Rachael is the SNES rebuilt in portable C: its video memory, CGRAM and OAM, its registers, DMA
and HDMA, and a renderer that builds the picture line by line as the PPU does. It is not an
emulator: it runs no 65816 code. The game is compiled for the PC with the system's C compiler
and calls the same console API as on the SNES, so the same source runs on the desktop and, with
M8, in the browser, and gives the same picture as the SNES, pixel for pixel
(`dev/compare_example.sh` checks it). Rachael also counts every byte the SNES would lose (written
outside the vertical blank, or more than a vertical blank can copy), so what fits on the desktop
fits on the console. She also counts every register write she does not draw (a mode other than
1 and 7, mosaic, `SETINI`, direct colour, the ports of VRAM, CGRAM and OAM, which would go around
her memories;
`sdevkit__undrawn`): the SNES would show something else there, and the desktop check fails on it
as on a lost byte.

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
(`superdevkit build linux` and `win64`), since Rachael cannot see a write that goes around it.

Three words, kept apart in all documents of the kit:

| Word | What it is | Where |
| --- | --- | --- |
| Rachael | the console rebuilt in C, the same on every PC target | `rachael/` |
| frontend | what one PC target adds to Rachael: the window, the keyboard and gamepad as the joypad, the frame rhythm of `loop.h`, the headless check | `desktop/` (Linux and Windows), `web/` (M8) |
| target | what a game is built for: `snes` (dampf and `snes/`, no Rachael), `linux`, `win64` and `web` (the system's C compiler or `emcc`, Rachael and that target's frontend) | `superdevkit build <target>` |

So a game runs "on the SNES" or "on Rachael"; "the desktop" and "the web" name targets and their
frontends, never Rachael itself. A difference in the picture between the SNES and a PC target is
a bug in Rachael (or in `snes/`); a difference between two PC targets is a bug in a frontend.

The name is the replicant of Blade Runner who is so close to the real thing that only a very
long Voight-Kampff test gives her away; the name is [ADR record 35](../docs/adr/0035-the-pseudo-snes-is-called-rachael.md).

## Rules

- Every function of the console API exists on every platform. A feature not built on one
  platform yet is a stub there, which does nothing; the table below says which.
- Game code contains no platform `#ifdef`s. A game that must know whether a feature works asks
  for the feature (`#if SDEVKIT_HAS_SOUND`, say), never for the platform. There is no such
  macro yet, since every function is built everywhere.
- The library's own names are `static`, or `sdevkit__` (two underscores) when files share them,
  declared outside `include/`. No name of the kit starts with `game_`.
- The library is ordinary code for dampf: the subset, `<stdint.h>` and `<dampf.h>`.

`LibApiTest` checks that every function of `sdevkit/snes.h` is defined in `snes/` and in
`rachael/`, and has its row here. `LibNamesTest` checks the names: every name of the public headers has the
prefix, every external name in `lib/` is `sdevkit_*`, `sdevkit__*` or `main`, and the
compatibility headers are what the rename script writes.

## Built and stub, per function and platform

Desktop means `rachael/` with `desktop/` (Linux now). The last column is what a call costs on the
SNES: best to worst case in CPU cycles, calls included, from the timing report of a program that
calls every function (`LibCostTest` writes it with `LIB_COSTS=update`, and fails when it is not
today's). A DMA function also uses as many bytes of the vertical blank's 5,700 as it copies; those
5,700 are what is left after the main loop's copy of the sprite table (544 bytes each frame).

| Function | SNES | desktop | CPU cycles on the SNES |
| --- | --- | --- | ---: |
| `sdevkit_set_mode` | built | built | 45 |
| `sdevkit_set_brightness` | built | built | 20 |
| `sdevkit_set_screen_on` | built | built | 52 to unbounded |
| `sdevkit_set_screen_off` | built | built | 20 |
| `sdevkit_set_color_effect` | built | built | 36 |
| `sdevkit_set_color_intensity` | built | built | 28 |
| `sdevkit_set_palette_color` | built | built | 47 |
| `sdevkit_dma_copy_vram` | built | built | 116 |
| `sdevkit_dma_copy_vram_column` | built | built | 116 |
| `sdevkit_dma_copy_cgram` | built | built | 100 |
| `sdevkit_dma_clear_vram` | built | built | 111 |
| `sdevkit_bg_set_gfx_ptr` | built | built | 85 to 195 |
| `sdevkit_bg_set_map_ptr` | built | built | 35 |
| `sdevkit_bg_set_scroll` | built | built | 84 |
| `sdevkit_bg_set_enable` | built | built | 61 to 173 |
| `sdevkit_bg_set_disable` | built | built | 64 to 176 |
| `sdevkit_bg_set_enable_sub` | built | built | 61 to 173 |
| `sdevkit_bg_set_disable_sub` | built | built | 64 to 176 |
| `sdevkit_bg_init_tile_set` | built | built | 592 to 782 |
| `sdevkit_bg_init_map_set` | built | built | 145 |
| `sdevkit_oam_init` | built | built | 23 to 103,703 |
| `sdevkit_oam_init_gfx_set` | built | built | 246 to 356 |
| `sdevkit_oam_init_gfx_attr` | built | built | 37 to 147 |
| `sdevkit_oam_set` | built | built | 315 to 427 |
| `sdevkit_oam_set_xy` | built | built | 139 to 251 |
| `sdevkit_oam_set_ex` | built | built | 80 to 192 |
| `sdevkit_oam_set_visible` | built | built | 6 |
| `sdevkit_oam_clear` | built | built | 19 to 103,699 |
| `sdevkit_reg_write` | built | built | 25 |
| `sdevkit_hdma_set` | built | built | 93 |
| `sdevkit_hdma_enable` | built | built | 21 |
| `sdevkit_dma_fits` | built | built | 87 to 92 |
| `sdevkit_mode7_load_map` | built | built | 116 |
| `sdevkit_mode7_load_tiles` | built | built | 116 |
| `sdevkit_mode7_set_matrix` | built | built | 117 |
| `sdevkit_mode7_set_center` | built | built | 65 |
| `sdevkit_mode7_set_select` | built | built | 21 |
| `sdevkit_sfx_init` | built | built | 51 to unbounded |
| `sdevkit_sfx_play` | built | built | 39 to 166 |
| `sdevkit_music_play` | empty, not built yet: dampf removes the calls (`DebugCallsTest`) | built (the track's file, over and over, in the window) | 6 |
| `sdevkit_music_stop` | empty, not built yet: dampf removes the calls | built | 6 |
| `sdevkit_sram` | built (the cartridge's battery RAM at $70:0000, `dampf --sram`) | built (`<game>.srm` next to the program) | 11 |
| `sdevkit_dbg_vram` | built (a single zero word: the SNES cannot read VRAM) | built | 14 |
| `sdevkit_dbg_hash` | built (the address and size to a port; the checker hashes) | built | 79 |
| `sdevkit_dbg_toggle` | empty: dampf removes the calls (`DebugCallsTest`) | built (the debug view, F1) | 6 |
| `sdevkit_dbg_range` | empty: dampf removes the calls | built (the debug view) | 6 |
| `sdevkit_dbg_color` | empty: dampf removes the calls | built (the debug view) | 6 |
| `sdevkit_dbg_action` | empty: dampf removes the calls | built (the debug view) | 6 |
| `sdevkit_dbg_box` | empty: dampf removes the calls, and the loops left empty | built (over the picture in the debug view) | 6 |
