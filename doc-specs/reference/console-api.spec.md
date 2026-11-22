---
title: The console API
sources:
  - lib/include/sdevkit/snes.h
  - lib/include/sdevkit/loop.h
  - lib/README.md
  - lib/snes/README.md
  - lib/snes/main.c
  - lib/snes/snes.c
  - lib/snes/sfx.c
  - lib/sound/sfx_voices.h
  - lib/rachael/rachael.h
  - lib/rachael/ppu.c
  - lib/rachael/sfx.c
  - lib/rachael/music.c
  - lib/rachael/sram.c
  - lib/rachael/debug.c
  - lib/splash/splash.h
  - docs/sound.md
  - cli/src/main/java/superdevkit/project/Project.java
  - cli/src/main/java/superdevkit/project/Build.java
  - tools/sdevkit_rename.py
  - compiler/src/test/java/dampf/lib/LibApiTest.java
  - compiler/src/test/java/dampf/lib/LibCostTest.java
  - compiler/src/test/java/dampf/lib/LibNamesTest.java
  - examples/hello/src/*.c
audience: Game programmers who write a game with the dev kit and look up what a function of the kit takes, does and costs
---

## Purpose

The reference of everything a game may call or define: the frame rhythm of `sdevkit/loop.h` and
every function and constant of `sdevkit/snes.h`, with what each takes, what it does on the SNES
and on Rachael, and what it costs. A reader who knows the function's name finds its entry; a
reader who knows what they want done finds the group it is in. The headers stay the source of
truth; the page says so in one sentence and names them.

## Sections

The page opens, before the first H2, with the one rule that holds for all of it: a game reaches
the console only through this API, which exists on every platform, and the two headers it
includes (`#include "sdevkit/snes.h"`; `superdevkit build` passes `-I <home>/lib/include` and
compiles the library of the target with the game). It also says once what a cost range in the
entries is: the best and the worst path the timing report finds through the function, from a
program that calls it with zeros, not a cost per argument; and that a game's own timing report
can differ a little, since dampf compiles the whole program at once.

1. **The frame rhythm** – `game_init`, `game_tick`, `game_vblank`: when each runs, what belongs
   in it, what the main loop does between them (the pads, the copy of the sprite table, the sound
   effects the tick asked for), what happens when a tick takes longer than a frame, and the splash
   screen before `game_init`. The pad bits `SDEVKIT_KEY_*` as a table. One complete minimal game
   (from `examples/hello`, cut down if it is long) that shows all three.
2. **Screen and colours** – `sdevkit_set_mode`, brightness, screen on and off, palette colours.
3. **Backgrounds** – the `sdevkit_bg_*` functions, the map entry macros and `SDEVKIT_SC_*`, and
   the layer masks `SDEVKIT_BG1_ENABLE` to `SDEVKIT_OBJ_ENABLE`: say that `sdevkit_bg_set_enable`
   and its siblings take a layer number (0 to 3), and what the masks are for.
4. **Sprites** – the `sdevkit_oam_*` functions and `sdevkit_oam_memory`.
5. **DMA and HDMA** – the copies to VRAM and CGRAM, `sdevkit_dma_fits`, the column copy, the
   two HDMA calls and the table format they read: the channel number of `sdevkit_hdma_set`
   against the mask of `sdevkit_hdma_enable`, and why channel 0 is not for HDMA. The example
   draws something the HDMA effect can be seen on.
6. **Colour math and registers** – `sdevkit_set_color_effect`, `sdevkit_set_color_intensity`,
   their constants, `sdevkit_reg_write` and the `SDEVKIT_PPU_*` names.
7. **Mode 7** – the `sdevkit_mode7_*` functions and `SDEVKIT_M7_*`.
8. **Sound effects** – `sdevkit_sfx_init`, `sdevkit_sfx_play`, the bank `sdevkit_sfx_bank` that
   the build writes (a game only passes it on) and `SDEVKIT_SFX_OFF`: when an effect starts, which
   one gives way when the voices are busy, and what is dropped.
9. **Music** – `sdevkit_music_play` and `sdevkit_music_stop`, the track numbers the build writes,
   and that the calls do nothing on the SNES yet.
10. **Save RAM** – `sdevkit_sram` and `SDEVKIT_SRAM_SIZE`: how a game asks for it (`sram` in its
    project file), where it is kept on each platform, and what it holds the first time, so that a
    game marks its data as valid. A short example that keeps a high score.
11. **Debug calls** – `sdevkit_dbg_vram` and `sdevkit_dbg_hash`: what each does on each platform,
    and what data `sdevkit_dbg_hash` may be given; then the registrations of the debug view
    (`sdevkit_dbg_toggle`, `_range`, `_color`, `_action`) and `sdevkit_dbg_box`, which are empty on
    the SNES.
12. **The old names** – `tools/sdevkit_rename.py`, which converts a game from the old names
   (PVSnesLib's, `KEY_*`, `u8`...) to the `sdevkit_` ones; the kit has no header for the old
   names any more.

In sections 2 to 11, each function is an entry of its own (H3 with its name): its declaration as
in the header, what it does in one to three sentences, its parameters where the header's names
do not say it, the registers it writes where that matters, and its cost on the SNES from the
table in `lib/README.md` (CPU cycles, and the bytes of the vertical blank a DMA function uses).

## Pay attention to

- Declarations exactly as in `sdevkit/snes.h`, including the parameter names.
- The cost of every function exactly as in the table of `lib/README.md`, and where the cost
  depends on the call ("23 to 102,679" for `sdevkit_oam_init`), why, when the source says it.
- What is different on Rachael (no time; `sdevkit_dbg_vram` is a single zero word on the SNES;
  music and the debug view's calls are empty on the SNES and dampf removes them), said where it
  applies, not collected at the end.
- A function the header says must not be called in a vertical blank, or only in one, says so in
  its entry. Where a call belongs (`game_init`, `game_tick`, `game_vblank`) is said only where a
  source says it.
- Constants whose names read against their meaning (the `SCBLK` and `MATWIN` ones of colour
  math, PVSnesLib's names) get one sentence on how to read the name.

## Out of scope

- What the hardware allows and punishes, and which features the kit has:
  [The SNES hardware](/guide/snes-hardware); each entry links there where a rule applies.
- How to write the C of a game fast: [Writing fast C](/guide/writing-fast-c).
- How the effects and the music are made and converted (`sfx.json`, the BRR Lab,
  `superdevkit sfx`): [Graphics and sound](/guide/graphics-and-sound); how the debug view is used:
  [The desktop](/guide/the-desktop).
- Rachael's interface to the frontends (`rachael.h`, `sdevkit__*`): not for games.
