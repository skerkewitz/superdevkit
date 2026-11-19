<!-- GENERATED from doc-specs/guide/snes-hardware.spec.md by /docs-gen. Do not edit; change the spec or its sources. -->
# The SNES hardware

A game of the [dev kit](/glossary#dev-kit) reaches the console only through its console API
(`sdevkit/snes.h`), and it runs on two platforms: on the SNES, compiled by dampf, and on the
desktop, compiled by the PC's C compiler against [Rachael](/glossary#rachael), the SNES rebuilt
in portable C. A feature is usable only when both platforms have it. If Rachael did not know a
feature, the desktop would show a different picture than the SNES, and every check on the
desktop would be worthless for that game. This page is made from `lib/SNES_HARDWARE.md`, the
guide to the hardware that ships with the kit.

You test on the desktop and in [Mesen](/glossary#mesen), not on a console, so each rule below
says how it is checked, in one of three words:

- **Enforced**: Rachael or the build reports a breach, and the desktop check fails on it.
- **Measured**: a check shows it without failing on it: what only the console's timing shows,
  in Mesen (`dev/check_example.sh`), or a count of the desktop check that a game may mean.
- **Documented**: the kit does not check it; you take care yourself.

## What the dev kit has

Use what is built in both columns of this table:

| Feature | SNES (`lib/snes`) | Rachael | Functions |
| --- | --- | --- | --- |
| Mode 1 (BG1 and BG2 with 16 colours, BG3 with 4), 8 x 8 and 16 x 16 tiles, scrolling | built | built | `sdevkit_set_mode`, `sdevkit_bg_init_tile_set`, `sdevkit_bg_set_map_ptr`, `sdevkit_bg_set_scroll` |
| Sprites, at most 32 and 34 tiles a line | built | built, and counted | `sdevkit_oam_init_gfx_set`, `sdevkit_oam_set`, `sdevkit_oam_set_ex`, `sdevkit_oam_clear` |
| Windows, main and sub screen, colour math, brightness | built | built | `sdevkit_set_color_effect`, `sdevkit_set_color_intensity`, `sdevkit_bg_set_enable_sub`, `sdevkit_set_brightness`, `sdevkit_reg_write` |
| HDMA in direct mode: one register, two, or one written twice (a scroll register: a wave down the screen) | built | built | `sdevkit_hdma_set`, `sdevkit_hdma_enable` |
| DMA to VRAM and CGRAM, within the vertical blank's budget; down a column of a map | built | built, lost bytes counted | `sdevkit_dma_copy_vram`, `sdevkit_dma_copy_vram_column`, `sdevkit_dma_copy_cgram`, `sdevkit_dma_fits` |
| Modes 0 and 2 to 6 | not built | not built, reported | |
| Mode 7: one layer of 1024 x 1024 pixels, rotated, scaled and mirrored, and what lies outside it; with HDMA on BGMODE and TM, mode 1 above it (a status line on BG3) | built | built | `sdevkit_mode7_load_map`, `sdevkit_mode7_load_tiles`, `sdevkit_mode7_set_matrix`, `sdevkit_mode7_set_center`, `sdevkit_mode7_set_select` |
| Mode 7's second layer (EXTBG), direct colour | not built | not built, reported | |
| Mosaic, high resolution, interlace | not built | not built, reported | |
| Raster interrupts (H/V IRQ), the timers | not built: the game is never interrupted | not built | |
| Sound (SPC700, DSP) | not built | not built | |
| Save RAM | not built | not built | |
| Mouse, multitap, Super Scope | not built | not built | |
| The CPU's multiplier and divider (`$4202`-`$4206`), mode 7's multiplier (`$2134`-`$2136`) | not used: dampf's runtime multiplies in software | not needed: the game is compiled for the PC | |

DMA (direct memory access) copies a block of memory without the CPU; HDMA writes a value to a
register at the start of every line of the picture. VRAM holds the tiles and maps, CGRAM the
colours, OAM the sprite table. `SnesHardwareGuideTest` checks that every function named in a row
built on both platforms exists in `lib/snes` and in `lib/rachael`.

"Reported" covers the features a game could reach anyway: `sdevkit_reg_write` sets any register
of the PPU, the SNES's picture processing unit. Rachael counts every write she does not draw: a
mode other than 1 and 7, mosaic, `SETINI` (`$2133`, interlace, high resolution and EXTBG),
direct colour, and the ports of VRAM, CGRAM and OAM, because writes there would go around her
memories. The SNES would show
something else there, so the desktop check fails on such a write as it fails on a lost byte
(*enforced*). The check runs the game without a window, with the input of a `check.txt`:

```sh
dev/build_desktop.sh examples/hello
examples/hello/build/linux/hello --check examples/hello/check.txt --out examples/hello/build/linux
```

It ends with a line that counts the frames, the lost bytes, the undrawn writes and the lines
over the sprite limits, and exits with 1 when the lost bytes or the undrawn writes are not zero:

```
frames 400 ticks 400 lost 0 undrawn 0 sprites 0
```

A game called `mode3` that calls `sdevkit_reg_write(SDEVKIT_PPU_BGMODE, 3)` gets, before that
line:

```
mode3: 1 register writes Rachael does not draw, the first $2105 = $03 (the SNES would show something else)
frames 3 ticks 3 lost 0 undrawn 1 sprites 0
```

Every function of the console API also has a cost on the SNES, in CPU cycles from best to worst
case, calls included. The table *Built and stub, per function and platform* in `lib/README.md`
lists them; `LibCostTest` fails when they are not what dampf gives today. `sdevkit_set_mode`
costs 45 cycles, `sdevkit_oam_set` 315 to 427, and `sdevkit_oam_clear` 23 to 103,703, because
it can clear the whole sprite table. A DMA function also uses as many bytes of the vertical
blank's budget as it copies.

## Video memory

- **Write VRAM, CGRAM and OAM only in the vertical blank or with the screen off.** The
  [vertical blank](/glossary#vertical-blank) is the pause between two pictures; with the
  screen off (forced blank) the PPU accepts writes at any time. During the picture the PPU drops
  what comes, and so does Rachael. Prepare the data in `game_tick` and upload it in
  `game_vblank`; `game_init` runs with the screen off. Rachael counts the bytes written during
  the picture as lost, and the desktop check and the Mesen check of an example fail on a lost
  byte (*enforced*).
- **Copy with DMA, never with a loop of the CPU.** A vertical blank takes about 5,700 bytes of
  DMA: 37 lines of 1,364 [master clocks](/glossary#master-clock), 8 a byte, less the 544 bytes
  of the sprite table. Rachael counts every byte over that as lost, and the desktop check prints
  `lost frame N: B bytes` for each frame that loses some (*enforced*). Ask
  `sdevkit_dma_fits(n)` whether `n` more bytes fit.
- **Keep the sprite table in RAM and upload all of it each frame.** The kit's main loop copies
  `sdevkit_oam_memory` in every vertical blank; a game only writes that copy (*enforced by
  the kit*).
- **Upload what changed, not everything**: a row of the map, a tile of an animation. Animate by
  copying a new picture over a tile that the map names in many places (*documented*).

## Sprites

- **At most 32 sprites and 34 tiles of 8 pixels on one line.** Over 32, the sprites with the
  highest numbers in the sprite table are dropped. Over 34 tiles, the sprites are fetched from
  the highest number down, so those with the lowest numbers lose their tiles. Rachael drops the
  same sprites and tiles as the SNES and counts the lines over each limit; the desktop check
  adds both counts up over all frames and prints the sum as `sprites N`. A game may mean it,
  when it flickers on purpose, so the check does not fail on it (*measured*).
- **Put the most important sprite at the lowest number.** It is drawn in front, and it is the
  last to be dropped by the 32-sprite limit, but the first to lose tiles over 34
  (*documented*).
- **Flicker** by rotating the order in the table from frame to frame when a line needs more
  (*documented*).

## CPU and memory

- **FastROM**: code and constant data in the [banks](/glossary#bank) from `$80` are read at
  3.58 MHz, WRAM at 2.68 MHz. dampf builds [FastROM](/glossary#fastrom-and-slowrom) and places
  the frames of functions in the [direct page](/glossary#direct-page), whose instructions are a
  byte shorter (*documented*; the cycles are in the [timing report](/glossary#timing-report)).
- **A DMA does not cross a 64 KB bank**: the source address wraps inside its bank. dampf places
  no object across a bank boundary, so a copy of one object is safe; a copy that runs from one
  object into the next is not (*documented*).
- **Read the joypads after the automatic read** at the start of the vertical blank. The kit's
  main loop does it and hands the pads to `game_tick` (*enforced by the kit*).
- **A tick that takes longer than a frame** makes the game wait for the next vertical blank. A
  [tick](/glossary#tick) is one step of the game logic, a [frame](/glossary#frame) one picture;
  the game then runs at 30 frames a second, slower but never wrong. Rachael does not show it,
  because she runs at the PC's speed; the Mesen check counts frames and ticks (*measured*). Plan
  the work of a frame with the timing report and the [hint](/glossary#hint) `DAMPF_BUDGET`;
  [Writing fast C](/guide/writing-fast-c) says what costs time in C.

## The console is not the emulator

At power on, a console's registers and video memory hold whatever happens to be there, while
emulators start with zeros. The kit's start-up on the SNES (`sdevkit__reset_video`) clears
every PPU register, VRAM, CGRAM and the sprite table before `game_init`, and C zeroes the game's
static variables, so a game of the kit starts from known values on both (*enforced by the
kit*). What stays a risk is a local variable read before it is written: compile the
[PC build](/glossary#pc-build) with `-Wall -Wextra` and fix every "may be used uninitialized".

PAL consoles run at 50 frames a second, NTSC consoles at 60, and their vertical blank is
longer. One ROM runs on both with NTSC's limits, so a game that counts in frames runs a sixth
slower on PAL (*documented*).

## Not usable yet

Sound, save RAM, raster effects by interrupt and the mouse each wait until their
feature is built in both columns of the table; [Limits](/reference/limits) says what is planned.
Many raster effects work today with HDMA, which writes a value per line to a register:
scrolling, colours and windows that change down the screen.
