# lib: the library of superdevkit

The library a game uses (ADR [record 29](../docs/adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)):
one console API, implemented once per platform. A game includes the headers in `include/` and is
compiled together with the implementation of its platform; it calls nothing else.

| Folder | What |
| --- | --- |
| `include/snes/snes.h` | The console API: the PVSnesLib-shaped functions and the `snesXxx()` extensions, with their constants and the sprite table `sdevkit_oam_memory`. Its last section, the PC side (`snes_xxx()`), is the PC implementation's interface, not for games. |
| `include/snes/loop.h` | The frame rhythm: the library's main loop calls `game_init`, then per frame `game_tick` and `game_vblank`, which every game defines; the pad bits `KEY_*`. |
| `snes/` | The SNES: the API in C with registers as `volatile` pointers (`snes.c`, `hw.h`) and the main loop (`main.c`), compiled by dampf with the game. See its [README](snes/README.md). |
| `pseudo/` | The desktop: the pseudo SNES, taken over from Blubber Blasen's `src/snes`: the memories, the registers and the API in portable C, and a renderer that builds the picture line by line as the PPU does (`render.c`). It counts every byte the SNES would lose (written outside the vertical blank, or more than a vertical blank can copy). New against the game's copy: 16 x 16 background tiles, and row y of the picture is scanline y + 1 as on the SNES. |
| `desktop/` | The desktop's frontend (`main.c`, Linux now, Windows next): a window with raylib, the keyboard and a gamepad as the joypad, the frame rhythm of `loop.h`; `--check check.txt` plays without a window and writes screenshots. Built by `tools/build_desktop.sh`. |
| `web/` | The web, later (M8, [the plan](../docs/superdevkit-transition.md)). |

On the SNES a game is built with `-I lib/include -I lib/snes` in front of its own include paths and
`lib/snes/*.c` among its sources (dampf); on the desktop with `-I lib/include -I lib/pseudo` and
`lib/pseudo/*.c lib/desktop/*.c` (the system's C compiler, `tools/build_desktop.sh`). The same
game code gives the same picture on both, pixel for pixel (`dev/compare_example.sh`).

## Rules

- Every function of the console API exists on every platform. A feature not built on one
  platform yet is a stub there, which does nothing; the table below says which.
- Game code contains no platform `#ifdef`s. A game that must know whether a feature works asks
  for the feature (`#if SDK_HAS_SOUND`, say), never for the platform. There is no such macro
  yet, since every function is built everywhere.
- The library is ordinary code for dampf: the subset, `<stdint.h>` and `<dampf.h>`.

`LibApiTest` checks that every function of `snes.h` (before its PC side) is defined in `snes/` and
in `pseudo/`, and has its row here.

## Built and stub, per function and platform

Desktop means `pseudo/` with `desktop/` (Linux now).

| Function | SNES | desktop |
| --- | --- | --- |
| `sdevkit_set_mode` | built | built |
| `sdevkit_set_brightness` | built | built |
| `sdevkit_set_screen_on` | built | built |
| `sdevkit_set_screen_off` | built | built |
| `sdevkit_set_color_effect` | built | built |
| `sdevkit_set_color_intensity` | built | built |
| `sdevkit_set_palette_color` | built | built |
| `sdevkit_dma_copy_vram` | built | built |
| `sdevkit_dma_copy_cgram` | built | built |
| `sdevkit_dma_clear_vram` | built | built |
| `sdevkit_bg_set_gfx_ptr` | built | built |
| `sdevkit_bg_set_map_ptr` | built | built |
| `sdevkit_bg_set_scroll` | built | built |
| `sdevkit_bg_set_enable` | built | built |
| `sdevkit_bg_set_disable` | built | built |
| `sdevkit_bg_set_enable_sub` | built | built |
| `sdevkit_bg_set_disable_sub` | built | built |
| `sdevkit_bg_init_tile_set` | built | built |
| `sdevkit_bg_init_map_set` | built | built |
| `sdevkit_oam_init` | built | built |
| `sdevkit_oam_init_gfx_set` | built | built |
| `sdevkit_oam_init_gfx_attr` | built | built |
| `sdevkit_oam_set` | built | built |
| `sdevkit_oam_set_xy` | built | built |
| `sdevkit_oam_set_ex` | built | built |
| `sdevkit_oam_set_visible` | built | built |
| `sdevkit_oam_clear` | built | built |
| `sdevkit_reg_write` | built | built |
| `sdevkit_hdma_set` | built | built |
| `sdevkit_hdma_enable` | built | built |
| `sdevkit_dma_fits` | built | built |
| `sdevkit_dbg_vram` | built (a single zero word: the SNES cannot read VRAM) | built |
