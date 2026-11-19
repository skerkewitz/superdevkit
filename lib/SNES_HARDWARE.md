# The SNES hardware for superdevkit games

What the console allows, punishes and rewards, and which of it the kit has. The companion of
[C_STYLE.md](C_STYLE.md), which says what dampf makes of C code; this guide is about the machine,
which has not changed since 1990. Written for whoever writes a game, an AI first: it tests on
Rachael (the desktop) and in Mesen, never on a console, so each rule says how it is checked.

**Three kinds of rule.** *Enforced*: Rachael or the build reports a breach, and the desktop
check fails on it. *Measured*: a check shows it without failing on it: what only the console's
timing shows, in Mesen (`dev/check_example.sh`), or a count of the desktop check that a game may
mean. *Documented*: the kit does not check it; take care yourself.

## What the kit has

A game reaches the hardware only through the console API (`sdevkit/snes.h`), and a feature is
usable only when both platforms have it: one Rachael does not know would make the desktop show
something else than the SNES, and every check on the desktop would be worthless for that game.
Use what is built in both columns. `SnesHardwareGuideTest` checks that each feature built on
both has its functions in `lib/snes` and in `lib/rachael`.

| Feature | SNES (`lib/snes`) | Rachael | Functions |
| --- | --- | --- | --- |
| Mode 1 (BG1 and BG2 with 16 colours, BG3 with 4), 8 x 8 and 16 x 16 tiles, scrolling | built | built | `sdevkit_set_mode`, `sdevkit_bg_init_tile_set`, `sdevkit_bg_set_map_ptr`, `sdevkit_bg_set_scroll` |
| Sprites, with the limits of 32 a line and 34 tiles a line | built | built, and counted | `sdevkit_oam_init_gfx_set`, `sdevkit_oam_set`, `sdevkit_oam_set_ex`, `sdevkit_oam_clear` |
| Windows, main and sub screen, colour math, brightness | built | built | `sdevkit_set_color_effect`, `sdevkit_set_color_intensity`, `sdevkit_bg_set_enable_sub`, `sdevkit_set_brightness`, `sdevkit_reg_write` |
| HDMA in direct mode | built | built | `sdevkit_hdma_set`, `sdevkit_hdma_enable` |
| DMA to VRAM and CGRAM, within the vertical blank's budget | built | built, lost bytes counted | `sdevkit_dma_copy_vram`, `sdevkit_dma_copy_cgram`, `sdevkit_dma_fits` |
| Modes 0 and 2 to 6 | not built | not built, reported | |
| Mode 7: one layer of 1024 x 1024 pixels, rotated, scaled and mirrored, and what lies outside it | built | built | `sdevkit_mode7_load_map`, `sdevkit_mode7_load_tiles`, `sdevkit_mode7_set_matrix`, `sdevkit_mode7_set_center`, `sdevkit_mode7_set_select` |
| Mode 7's second layer (EXTBG), direct colour | not built | not built, reported | |
| Mosaic, high resolution, interlace | not built | not built, reported | |
| Raster interrupts (H/V IRQ), the timers | not built: the game is never interrupted (`sdevkit/loop.h`) | not built | |
| Sound (SPC700, DSP) | not built | not built | |
| Save RAM | not built | not built | |
| Mouse, multitap, Super Scope | not built | not built | |
| The CPU's multiplier and divider (`$4202`-`$4206`), mode 7's multiplier (`$2134`-`$2136`) | not used: dampf's runtime multiplies in software | not needed: the game is compiled for the PC | |

"Reported": `sdevkit_reg_write` can set any PPU register, and Rachael counts every write she
does not draw (a mode other than 1 and 7, mosaic, `SETINI` for interlace, high resolution and
EXTBG, direct colour, and the ports of VRAM, CGRAM and OAM, which go around her memories). The desktop check prints
`undrawn N` and fails on it, as on a lost byte (*enforced*).

What each function costs on the SNES, in CPU cycles, is the last column of the table in
[README.md](README.md#built-and-stub-per-function-and-platform).

## Video memory

- **Write VRAM, CGRAM and OAM only in the vertical blank or with the screen off** (forced
  blank), never during the picture: the PPU drops what comes then. Prepare in `game_tick`,
  upload in `game_vblank` (*enforced*: Rachael counts lost bytes, and the Mesen check of an
  example fails on one).
- **Copy with DMA**, never with a loop of the CPU. A vertical blank takes about 5,700 bytes of
  DMA (37 lines of 1,364 master cycles, 8 a byte, less the sprite table's 544); more is lost
  (*enforced*). `sdevkit_dma_fits(n)` says whether `n` more bytes fit.
- **Keep the sprite table in RAM and upload all of it each frame**: the kit's main loop copies
  `sdevkit_oam_memory` in every vertical blank; a game only writes the copy.
- **Upload what changed, not everything**: a row of the map, a tile of an animation. Animate by
  copying a new frame over a tile that the map names in many places (Steinschlag's README shows
  both).

## Sprites

- **At most 32 sprites and 34 tiles of 8 pixels on one line.** Over 32, the sprites with the
  highest numbers in the sprite table are dropped; over 34 tiles, the sprites are checked from
  the highest number down, so those with the lowest numbers lose their tiles (*measured*: Rachael
  counts both, and the desktop check prints the lines over a limit as `sprites N`; a game may
  mean it, flickering on purpose, so the check does not fail on it).
- **Put the most important sprite at the lowest number**: it is drawn in front, and it is the
  last to be dropped by the 32-sprite limit (but the first to lose tiles over 34).
- **Flicker** by rotating the order in the table from frame to frame when a line needs more
  (*documented*).

## CPU and memory

- **FastROM**: code and constant data in the banks from `$80` are read at 3.58 MHz, WRAM at
  2.68 MHz; dampf builds FastROM and places its function frames in the direct page, whose
  instructions are a byte shorter (*documented*; the cycles are in the timing report).
- **A DMA does not cross a 64 KB bank**: the source address wraps inside its bank. dampf places
  no object across a bank boundary, so a copy of one object is safe; a copy that runs from one
  object into the next is not (*documented*).
- **Read the joypads after the automatic read** at the start of the vertical blank: the kit's
  main loop does, and hands them to `game_tick` (*enforced by the kit*).
- **A tick that takes longer than a frame** makes the game wait for the next vertical blank: it
  then runs at 30 frames a second, never wrong. Rachael does not show it, she has the PC's speed;
  the Mesen check counts frames and ticks (*measured*). Plan the work of a frame with the timing
  report and `DAMPF_BUDGET` (C_STYLE.md, part 6).

## The console is not the emulator

- **Start values**: at power on, a console's registers and video memory hold whatever is there,
  where emulators start with zeros. The kit's start-up on the SNES (`sdevkit__reset_video`)
  clears every PPU register, VRAM, CGRAM and the sprite table before `game_init`, and C zeroes
  the game's static variables, so a game of the kit starts from known values on both (*enforced
  by the kit*). What stays a risk is a local variable read before it is written: compile the PC
  build with `-Wall -Wextra` and fix every "may be used uninitialized".
- **PAL** consoles run at 50 frames a second, NTSC at 60, with a longer vertical blank. One ROM
  runs on both with NTSC's limits; a game that counts in frames runs a sixth slower on PAL
  (*documented*; decided in ADR record 36,
  [docs/adr/0036](../docs/adr/0036-one-rom-for-ntsc-and-pal-with-ntsc-s-limits-and.md)).

## Not usable yet

Sound, save RAM, raster effects by interrupt, the mouse: each waits for its feature in
both columns of the table. Many raster effects work today with HDMA (a value per line for a
register: scrolling, colours, windows).

## Sources

The rules on the hardware are from the SNESdev wiki (snes.nesdev.org: sprites, DMA registers)
and fullsnes by nocash; the state of the kit from `lib/README.md`, `lib/rachael/` and
`lib/snes/`. A feature added later changes its row in the same commit, on both platforms.
