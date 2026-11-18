# lib: the library of superdevkit

The library a game uses (ADR [record 29](../docs/adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)):
one console API, implemented once per platform. A game includes the headers in `include/` and is
compiled together with the implementation of its platform; it calls nothing else.

| Folder | What |
| --- | --- |
| `include/snes/snes.h` | The console API: the PVSnesLib-shaped functions and the `snesXxx()` extensions, with their constants and the sprite table `oamMemory`. Its last section, the PC side (`snes_xxx()`), is the PC implementation's interface, not for games. |
| `include/snes/loop.h` | The frame rhythm: the library's main loop calls `game_init`, then per frame `game_tick` and `game_vblank`, which every game defines; the pad bits `KEY_*`. |
| `snes/` | The SNES: the API in C with registers as `volatile` pointers (`snes.c`, `hw.h`) and the main loop (`main.c`), compiled by dampf with the game. See its [README](snes/README.md). |
| `pseudo/`, `desktop/`, `web/` | Windows, Linux and the web, later (M7 and M8, [the plan](../docs/superdevkit-transition.md)): the pseudo SNES from Blubber Blasen's `src/snes`, which implements the same API, and the frontends. Until then the game's pseudo SNES is the PC column of the table. |

Build a game with `-I lib/include -I lib/snes` in front of its own include paths and
`lib/snes/*.c` among its sources.

## Rules

- Every function of the console API exists on every platform. A feature not built on one
  platform yet is a stub there, which does nothing; the table below says which.
- Game code contains no platform `#ifdef`s. A game that must know whether a feature works asks
  for the feature (`#if SDK_HAS_SOUND`, say), never for the platform. There is no such macro
  yet, since every function is built everywhere.
- The library is ordinary code for dampf: the subset, `<stdint.h>` and `<dampf.h>`.

`LibApiTest` checks that every function of `snes.h` (before its PC side) is defined in `snes/`
and has its row here.

## Built and stub, per function and platform

PC means Blubber Blasen's pseudo SNES for now.

| Function | SNES | PC |
| --- | --- | --- |
| `setMode` | built | built |
| `setBrightness` | built | built |
| `setScreenOn` | built | built |
| `setScreenOff` | built | built |
| `setColorEffect` | built | built |
| `setColorIntensity` | built | built |
| `setPaletteColor` | built | built |
| `dmaCopyVram` | built | built |
| `dmaCopyCGram` | built | built |
| `dmaClearVram` | built | built |
| `bgSetGfxPtr` | built | built |
| `bgSetMapPtr` | built | built |
| `bgSetScroll` | built | built |
| `bgSetEnable` | built | built |
| `bgSetDisable` | built | built |
| `bgSetEnableSub` | built | built |
| `bgSetDisableSub` | built | built |
| `bgInitTileSet` | built | built |
| `bgInitMapSet` | built | built |
| `oamInit` | built | built |
| `oamInitGfxSet` | built | built |
| `oamInitGfxAttr` | built | built |
| `oamSet` | built | built |
| `oamSetXY` | built | built |
| `oamSetEx` | built | built |
| `oamSetVisible` | built | built |
| `oamClear` | built | built |
| `snesRegWrite` | built | built |
| `snesHdmaSet` | built | built |
| `snesHdmaEnable` | built | built |
| `snesDmaFits` | built | built |
