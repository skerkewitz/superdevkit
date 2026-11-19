---
title: Graphics and sound
sources:
  - tools/README.md
  - tools/png2snes.py
  - tools/brr_lab.html
  - compiler/src/test/python/test_png2snes.py
  - examples/hello/build.sh
  - examples/hello/hello.args
  - examples/hello/src/hello.c
  - examples/catchme/build.sh
  - examples/ertuep/build.sh
  - lib/include/sdevkit/snes.h
  - lib/SNES_HARDWARE.md
  - dev/build_desktop.sh
audience: Game programmers who have pictures and sounds made on the PC and want them in a game
---

## Purpose

Show how a picture drawn on the PC becomes data a game can upload, and what the kit offers for
sound today. The reader leaves knowing which PNG files the converter takes, what it writes, how a
game's build calls it and includes the result, and which API calls take that data.

## Sections

The page opens, before the first H2, with the path from a PNG to the screen in one paragraph:
`tools/png2snes.py` writes C into `build/gen/`, the args file adds that folder, the game uploads
the arrays with the console API.

1. **Tiles and palettes** – `tiles` and `tiles2`: what a PNG must be (colours, indexed or RGB,
   transparency as colour 0), the order of the tiles and why a sheet 128 pixels wide suits
   16 x 16 sprites, what `NAME.c` and `NAME.h` contain; `tiles2` for BG3 of mode 1 (the HUD
   of `examples/ertuep/build.sh`). `palette` for a second palette of the same tiles (the
   palettes of `examples/catchme/build.sh`). One example: the calls of
   `examples/hello/build.sh` and the API calls that upload their arrays. Say that `tiles` writes
   tiles and a palette but no map: a background's map is made by the game, as `hello.c` builds
   its text map in code, and how the map's entries name a tile and a palette (the map entry
   macros, linked to the API page).
2. **Mode 7 pictures** – `mode7`: the size of the PNG, one byte a pixel, `FIRST` and why mode 7
   shares CGRAM, identical tiles stored once and the limit of 256, what it writes and which
   `sdevkit_mode7_*` calls take it. The example from `examples/ertuep/build.sh` (its Festzelt,
   with `FIRST` 33).
3. **Converting in the build** – a game's `build.sh`: the conversion first, `CONVERT_ONLY=1` for
   the desktop build, `-I build/gen` and `build/gen/` in the args file. Taken from
   `examples/hello/build.sh` and `hello.args`, with the paths of a game outside the home.
4. **Sound** – first, in one sentence with a link to [Limits](/reference/limits): the kit
   cannot play sound yet. Then, in one short paragraph, what `tools/brr_lab.html` is for today:
   trying how a sound effect would sound on the SNES and how much of the 64 KB audio RAM it
   takes, before the kit plays sound. Not its controls one by one.

## Pay attention to

- The converter's subcommands, arguments and the names it writes exactly as `png2snes.py`
  defines them; its error messages only where the source has them.
- Byte sizes as the source gives them (32 bytes a 4-bit tile, 16 a 2-bit one, 64 a mode 7 tile).

## Out of scope

- VRAM layout and the limits of tiles and sprites: [The SNES hardware](/guide/snes-hardware).
- The API calls themselves: [The console API](/reference/console-api).
- `tools/sdevkit_rename.py`: [The console API](/reference/console-api#the-old-names).
