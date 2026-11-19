---
title: The console API
sources:
  - lib/include/sdevkit/snes.h
  - lib/include/sdevkit/loop.h
  - lib/README.md
  - lib/snes/README.md
  - lib/snes/main.c
  - lib/snes/snes.c
  - lib/rachael/rachael.h
  - lib/rachael/ppu.c
  - lib/splash/splash.h
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
includes (`-I <home>/lib/include`, `#include "sdevkit/snes.h"`). It also says once what a cost
range in the entries is: the best and the worst path the timing report finds through the
function, from a program that calls it with zeros, not a cost per argument; and that a game's own
timing report can differ a little, since dampf compiles the whole program at once.

1. **The frame rhythm** – `game_init`, `game_tick`, `game_vblank`: when each runs, what belongs
   in it, what the main loop does between them (the pads, the copy of the sprite table), what
   happens when a tick takes longer than a frame, and the splash screen before `game_init`. The
   pad bits `SDEVKIT_KEY_*` as a table. One complete minimal game (from `examples/hello`, cut
   down if it is long) that shows all three.
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
8. **Debug calls** – `sdevkit_dbg_vram` and `sdevkit_dbg_hash`: what each does on each platform,
   and what data `sdevkit_dbg_hash` may be given.
9. **The old names** – the deprecated headers in `lib/include/snes/` and
   `tools/sdevkit_rename.py`, which converts a game to the `sdevkit_` names.

In sections 2 to 8, each function is an entry of its own (H3 with its name): its declaration as
in the header, what it does in one to three sentences, its parameters where the header's names
do not say it, the registers it writes where that matters, and its cost on the SNES from the
table in `lib/README.md` (CPU cycles, and the bytes of the vertical blank a DMA function uses).

## Pay attention to

- Declarations exactly as in `sdevkit/snes.h`, including the parameter names.
- The cost of every function exactly as in the table of `lib/README.md`, and where the cost
  depends on the call ("23 to 104,215" for `sdevkit_oam_init`), why, when the source says it.
- What is different on Rachael (no time; `sdevkit_dbg_vram` is a single zero word on the SNES),
  said where it applies, not collected at the end.
- A function the header says must not be called in a vertical blank, or only in one, says so in
  its entry. Where a call belongs (`game_init`, `game_tick`, `game_vblank`) is said only where a
  source says it.
- Constants whose names read against their meaning (the `SCBLK` and `MATWIN` ones of colour
  math, PVSnesLib's names) get one sentence on how to read the name.

## Out of scope

- What the hardware allows and punishes, and which features the kit has:
  [The SNES hardware](/guide/snes-hardware); each entry links there where a rule applies.
- How to write the C of a game fast: [Writing fast C](/guide/writing-fast-c).
- Rachael's interface to the frontends (`rachael.h`, `sdevkit__*`): not for games.
