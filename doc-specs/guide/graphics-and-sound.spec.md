---
title: Graphics and sound
sources:
  - tools/README.md
  - cli/src/main/java/superdevkit/Main.java
  - cli/src/main/java/superdevkit/gfx/Png2Snes.java
  - cli/src/main/java/superdevkit/gfx/Png.java
  - cli/src/main/java/superdevkit/sfx/*.java
  - cli/src/main/java/superdevkit/project/Project.java
  - cli/src/main/java/superdevkit/project/Build.java
  - cli/src/main/java/superdevkit/project/Desktop.java
  - cli/src/main/java/superdevkit/project/Web.java
  - tools/brr_lab.html
  - cli/src/test/java/superdevkit/gfx/Png2SnesTest.java
  - docs/sound.md
  - examples/hello/superdevkit.toml
  - examples/hello/src/hello.c
  - examples/catchme/superdevkit.toml
  - examples/catchme/src/game.c
  - examples/ertuep/superdevkit.toml
  - examples/steinschlag/superdevkit.toml
  - examples/steinschlag/assets/sfx/sfx.json
  - examples/steinschlag/src/steinschlag.h
  - examples/steinschlag/src/game.c
  - lib/include/sdevkit/snes.h
  - lib/include/sdevkit/loop.h
  - lib/include/sdevkit/easy.h
  - lib/include/sdevkit/shadow.h
  - lib/include/sdevkit/queue.h
  - lib/snes/snes.c
  - lib/SNES_HARDWARE.md
audience: Game programmers who have pictures and sounds made on the PC and want them in a game
---

## Purpose

Show how a picture drawn on the PC becomes data a game can upload, and how sound effects and
music get into a game. The reader leaves knowing which PNG files the converter takes, what it
writes, how a game's `superdevkit.toml` names them and the build includes the result, and which
API calls take that data; and for sound, which file lists the effects, what the build makes of it,
the calls that play effects and music, and where each plays.

## Sections

The page opens, before the first H2, with the path from a PNG to the screen in one paragraph:
the `[graphics]` table of `superdevkit.toml` names the pictures, `superdevkit build` converts
them with `superdevkit png` into C in `build/gen/` and compiles that folder with the game, the
game uploads the arrays with the console API.

1. **Tiles and palettes** – `tiles` and `tiles2`: what a PNG must be (colours, indexed or RGB,
   transparency as colour 0), the order of the tiles and why a sheet 128 pixels wide suits
   16 x 16 sprites, what `NAME.c` and `NAME.h` contain; `tiles2` for BG3 of mode 1 (the HUD
   of `examples/ertuep/superdevkit.toml`). `palette` for a second palette of the same tiles (the
   palettes of `examples/catchme/superdevkit.toml`). One example: the `[graphics]` of
   `examples/hello/superdevkit.toml` and the API calls that upload their arrays. Say that `tiles`
   writes tiles and a palette but no map: a background's map is made by the game, as `hello.c`
   builds its text map in code, and how the map's entries name a tile and a palette (the map
   entry macros, linked to the API page).
2. **Mode 7 pictures** – `mode7`: the size of the PNG, one byte a pixel, `first` and why mode 7
   shares CGRAM, identical tiles stored once and the limit of 256, what it writes and which
   `sdevkit_mode7_*` calls take it. The example from `examples/ertuep/superdevkit.toml` (its
   Festzelt, with `first = 33`).
3. **Converting in the build** – the `[graphics]` table: its four keys, an entry as a file or as
   `{ png = ..., name = ... }` (and `first` for mode 7), the C name taken from the file name. What
   `superdevkit build` does with it for every target: the conversions first, into `build/gen/`,
   then `-I build/gen` and `build/gen/` in the compile, shown with the plan of
   `superdevkit build snes --plan` in `examples/hello`. In one or two sentences that
   `superdevkit png` runs one conversion by hand, with its usage line, linking the reference.
4. **Sound** – sound effects first: a game lists them in `assets/sfx/sfx.json` (effects with an
   id, a priority and samples from WAV files, one of them active; the number of voices), names
   that file in `[sound] sfx`, and the build converts it with `superdevkit sfx` into `sfx.c` and
   `sfx.h` in `build/gen/` (BRR, the SNES's sample format, and the 64 KB audio RAM, in a
   sentence). One example from `examples/steinschlag` (its `superdevkit.toml`, part of its
   `sfx.json`, what the header names, `sdevkit_sfx_init` in `game_init` and `sdevkit_sfx_play`
   where it happens). The members of an effect's sample that the example shows (`rate`,
   `treble`, `gain`, `trim`, `volume`, `priority`), each with its unit and default in a phrase
   (the table of docs/sound.md); their full ranges stay in docs/sound.md. What happens when the
   voices are busy, in two or three sentences. Where
   effects play: on the SNES through the SPC700 and on the desktop and the web through
   Rachael's DSP, the same choice of voice frame by frame. Then, in one short paragraph, what
   `tools/brr_lab.html` is for: the editor of `sfx.json`, which plays each sample as the SNES
   would and shows the total against the audio RAM before the build. Not its controls one by
   one. Last, music: `[sound] music`, the `MUSIC_*` names of `music.h`, `sdevkit_music_play`
   and `sdevkit_music_stop`, the file formats; it plays from its file on the desktop and the
   web, and on the SNES the calls do nothing yet, in one sentence linked to
   [Limits](/reference/limits). The CatchMe example.

## Pay attention to

- The converters' subcommands, arguments and the names they write exactly as `Png2Snes.java`,
  `Sfx.java` and `Build.java` define them; error messages only where the source has them.
- The keys of `superdevkit.toml` and of `sfx.json` exactly as `Project.java` and `Sfx.java`
  accept them.
- Byte sizes as the source gives them (32 bytes a 4-bit tile, 16 a 2-bit one, 64 a mode 7 tile).
- Run `superdevkit build snes` (or `--plan`) on a copy of an example to check what the page
  shows of the build; never build in `examples/` itself.

## Out of scope

- The VRAM layout and the limits of tiles and sprites: [The console API](/reference/console-api)
  and [The SNES hardware](/guide/snes-hardware); but say where the hello calls put their tiles,
  maps and palettes (the arguments of the calls shown), and that sprite palette n starts at CGRAM
  colour 128 + 16 n.
- The API calls themselves: [The console API](/reference/console-api).
- Every key of `superdevkit.toml` and the options of `superdevkit png` and `superdevkit sfx`:
  [The superdevkit command](/reference/superdevkit).
- The steps of the sound conversion (resampling, treble, BRR encoding), the SPC700 driver and
  the upload protocol, and every member of `sfx.json` with its range: `docs/sound.md` has them.
