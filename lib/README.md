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
| `include/snes/` | Deprecated: the old names (PVSnesLib's, `KEY_*`, `u8`...) mapped to the new ones, for the snapshot of Blubber Blasen until the game switches. Written by `tools/sdevkit_rename.py --write-compat`, which also converts a game. |
| `snes/` | The SNES: the API in C with registers as `volatile` pointers (`snes.c`, `hw.h`) and the main loop (`main.c`), compiled by dampf with the game. See its [README](snes/README.md). |
| `pseudo/` | The desktop: the pseudo SNES, taken over from Blubber Blasen's `src/snes`: the memories, the registers and the API in portable C, and a renderer that builds the picture line by line as the PPU does (`render.c`). `pseudo.h` is its interface to the frontend (`sdevkit__vblank`, `sdevkit__render`, ...), not for games. It counts every byte the SNES would lose (written outside the vertical blank, or more than a vertical blank can copy). New against the game's copy: 16 x 16 background tiles, and row y of the picture is scanline y + 1 as on the SNES. |
| `desktop/` | The desktop's frontend (`main.c`, Linux now, Windows next): a window with raylib, the keyboard and a gamepad as the joypad, the frame rhythm of `loop.h`; `--check check.txt` plays without a window and writes screenshots. Built by `tools/build_desktop.sh`. |
| `web/` | The web, later (M8, [the plan](../docs/superdevkit-transition.md)). |

On the SNES a game is built with `-I lib/include` in front of its own include paths and
`lib/snes/*.c` among its sources (dampf). On the desktop `tools/build_desktop.sh` compiles the
game alone, without the host's headers (`-nostdinc`, dampf's include folder and `lib/include`),
checks that it calls only the console API (`tools/check_game_symbols.py`), then links it with
`lib/pseudo/*.c lib/desktop/*.c` and raylib. The same game code gives the same picture on both,
pixel for pixel (`dev/compare_example.sh`).

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
`pseudo/`, and has its row here. `LibNamesTest` checks the names: every name of the public headers has the
prefix, every external name in `lib/` is `sdevkit_*`, `sdevkit__*` or `main`, and the
compatibility headers are what the rename script writes.

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
