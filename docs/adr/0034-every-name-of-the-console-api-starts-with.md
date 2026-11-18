# 34. Every name of the console API starts with `sdevkit_`, and a game reaches nothing else

**Context.** `lib/include/snes/snes.h` mixed three naming schemes: PVSnesLib's (`setMode`,
`oamSet`, the variable `oamMemory`), the kit's extensions (`snesRegWrite`, `snesDmaFits`) and the
PC side of the pseudo SNES (`snes_vblank`, `snes_render`); record 32 planned a fourth (`sfxPlay`,
`snesSram`, `debugToggle`). Unprefixed names already clashed: raylib names its keys `KEY_UP`,
`KEY_A`, so the desktop frontend had to `#undef` the twelve joypad macros of `loop.h`. On the
desktop a game is linked into one program with raylib, libc and the pseudo SNES, so a game could
call any of their functions, or collide with one of their names. On the SNES the library's helpers
(`platform_oam_upload`, ...) shared the game's namespace too. Record 29 had named the feature macro
`SDK_HAS_SOUND`. The plan is [prefix_snes_api_transition.md](../prefix_snes_api_transition.md).

**Decision.**

- Every function and variable the kit offers a game starts with `sdevkit_`, every macro and
  constant with `SDEVKIT_`, followed by snake_case (`oamSetXY` becomes `sdevkit_oam_set_xy`,
  `KEY_A` becomes `SDEVKIT_KEY_A`, `SNES_PADS` becomes `SDEVKIT_PADS`); feature macros are
  `SDEVKIT_HAS_<feature>`. The headers are `lib/include/sdevkit/`.
- Calls that only make sense with the debug view start with `sdevkit_dbg_`. They exist on every
  platform (empty on the SNES, record 32) and never change what the game computes. The calls of
  record 32 are named `sdevkit_sfx_play`, `sdevkit_music_play`, `sdevkit_music_stop`,
  `sdevkit_sram` and `sdevkit_dbg_toggle`, `_range`, `_color`, `_action`, `_box`.
- The library's own names are `static` where possible, otherwise `sdevkit__` (two underscores),
  declared outside `lib/include`. The PC side of the pseudo SNES is such an internal interface,
  except `snes_vram`, which a game calls for a debug check: it is `sdevkit_dbg_vram`.
- The public headers use the `<stdint.h>` types; `u8`, `u16`, `s16`, `u32` leave them.
- The callbacks the game defines (`game_init`, `game_tick`, `game_vblank`) and dampf's own names
  stay as they are; no kit name starts with `game_`. The C library keeps its standard names, and
  a game gets only what dampf's include folder declares.
- A game reaches nothing else, and the build checks it: on the desktop the game is compiled
  without the host's headers and its undefined symbols must be the kit's; a test checks that every
  external name in `lib/` is `sdevkit_*`, `sdevkit__*` or `main`.
- The old header `lib/include/snes/` stays as a deprecated compatibility header mapping the old
  names, until Blubber Blasen uses the new ones; `tools/sdevkit_rename.py` converts a game.

**Alternatives.**

- Keep PVSnesLib's names: PVSnesLib code would compile unchanged, but the clashes stay, and being
  PVSnesLib's form is no reason by itself (record 32). The rename script makes up for it.
- A short prefix (`sdk_`, `sx_`): more likely taken by another library, and less clear in a stack
  trace or a symbol list.
- An `sdevkit_ext_` category for what the desktop can do and the SNES cannot: added only when a
  feature needs it, and it may then change only what is shown, never game state.

**Consequences.** Names cost nothing on the console: the linker turns them into addresses. Code
written for PVSnesLib needs the rename script. The desktop build compiles the game in a step of its
own, so it can check the game's symbols before linking. Amends record 29 (the header layout and
`SDK_HAS_SOUND`) and record 32 (the names of its calls).
