# Proposal: a guide to the SNES hardware for superdevkit games

Status: proposal, 2026-11-19. Nothing is built yet. The companion of
[c-style-guide-proposal.md](c-style-guide-proposal.md).

## Two guides

The C style guide says what dampf makes of C code. It changes with every optimization, so each of
its speed rules is a measurement checked by a test. This guide says what the SNES itself allows,
punishes and rewards. The hardware has not changed since 1990, and more than thirty years of
games, homebrew and emulator work have settled most questions about it; what is open is mostly
how much of it the kit can use today.

Like the other guide, its first reader is an AI writing game code, which follows a rule exactly
and everywhere and tests mostly on Rachael and in Mesen, never on a console. Three consequences:
every rule says how it is checked, rules about the difference between emulators and the console
are the most important ones, and every feature says whether the kit has it.

## What the kit has: a feature is usable only when Rachael has it

A game reaches the hardware only through the console API, and the API has to exist on every
platform (`lib/README.md`, "Rules"). A feature that `lib/snes` could drive but Rachael does not
know is not usable in a game: the desktop would show something else than the SNES, and
`dev/compare_example.sh` and every check on the desktop would be worthless for that game. So the
guide marks each feature with its state in the kit, and a game uses only what is built on both
sides, or at least present in Rachael as a dummy that says it is one.

From `lib/rachael/render.c`, `lib/README.md` and `lib/snes/README.md` as of today:

| Feature | SNES (`lib/snes`) | Rachael |
| --- | --- | --- |
| Mode 1 (BG1 and BG2 with 16 colours, BG3 with 4), 8 x 8 and 16 x 16 tiles, scrolling | built | built |
| Sprites, with the 32 per line and 34 tiles per line limits | built | built, and counted |
| Windows, main and sub screen, colour math, brightness | built | built |
| HDMA in direct mode | built | built |
| DMA to VRAM and CGRAM, with the vertical blank's budget | built | built, lost bytes counted |
| Modes 0 and 2 to 6 | not built | not built |
| Mode 7, and its multiplier at `$2134`-`$2136` | not built | not built |
| Mosaic, high resolution, interlace | not built | not built |
| Raster interrupts (H/V IRQ), the timers | not built (the game is never interrupted, `loop.h`) | not built |
| Sound (SPC700, DSP) | not built | not built (planned with record 32) |
| Save RAM | not built | not built (planned with record 32) |
| Mouse, multitap, Super Scope | not built | not built |
| The CPU's multiplier and divider (`$4202`-`$4206`) | used by no one; dampf's runtime multiplies in software | not needed: the game is compiled for the PC |

One gap is not in this table, and the guide has to close it: `sdevkit_reg_write` writes any PPU
register, and Rachael stores what she does not draw. A game that sets mosaic or mode 7 through it
gets a different picture on the two platforms, silently. Rachael should report a write to a
register or a bit she does not draw, as she reports a lost byte, so that such a write is found on
the desktop. That is step 1 below.

## What the guide says

Rules, each in one sentence with the reason, the cost where it has one, and how it is checked.
The first ones, from the common sources (fullsnes by nocash, the SNESdev wiki, Anomie's timing
documents), to be checked against them when the guide is written:

**Video memory.**

- Write VRAM, CGRAM and OAM only in the vertical blank or with the screen forced blank;
  otherwise the bytes are lost or land elsewhere. Checked by Rachael (`dma_lost`) and by the Mesen
  checks of the examples.
- Copy with DMA, never with a CPU loop. A vertical blank takes about 5.7 KB of DMA
  (`SDEVKIT__VBLANK_DMA_BYTES`); more is lost, and Rachael counts it.
- Keep OAM as a copy in RAM and upload all of it each frame; the kit's main loop does this.
- Plan the frame's uploads: what changes (a tile animation, a row of the map) goes up, the rest
  stays. Steinschlag's README shows how.

**Sprites.**

- At most 32 sprites and 34 tiles of 8 x 8 on one line. Above 32 sprites, those after the first
  32 in OAM order are dropped; above 34 tiles, the tiles are fetched from the last sprite to the
  first, so the sprites with the lowest numbers lose theirs. Counted by Rachael
  (`range_over_lines`, `time_over_lines`).
- Flicker by rotating the order in OAM when more are needed.

**CPU and memory.**

- Code and constant data in the FastROM banks run at 3.58 MHz, WRAM at 2.68 MHz; the direct page
  is the fastest place for variables. dampf already places its frames there.
- A DMA never crosses a 64 KB bank: data copied in one go must not straddle a bank boundary.
- Read the joypads only after the automatic read at the start of the vertical blank has
  finished. The kit's main loop does this.

**The console is not the emulator.**

- Assume nothing about memory or registers the game has not written: emulators start with zeros,
  a console with whatever is there. An uninitialised variable or register that works in Mesen
  can fail on the console. dampf's static objects are zeroed by the runtime as C requires; the
  risk is the hardware state. Rachael could fill the registers and VRAM she does not get written
  with random values instead of zeros, behind an option of the check, so that the desktop shows
  this.
- PAL runs at 50 frames per second, NTSC at 60, and PAL's vertical blank is longer. A game that
  counts in frames runs a sixth slower on PAL.

**Not usable yet**, each with the feature it waits for: mode 7, sound, save RAM, raster effects
by IRQ, the mouse. The guide says what a game can do instead today (HDMA for many raster
effects).

**What the console API costs**: per call, the cycles on the SNES and the bytes of the vertical
blank it uses, measured as the C guide measures. This is where that question of the C guide
belongs; the numbers live in `lib/README.md`'s table, next to the state of each function.

## How each rule is checked

Every rule is one of three kinds, and the guide says which:

- **Enforced**: Rachael or the build reports a breach (lost bytes, sprites over the line limit,
  later a register Rachael does not draw, random start values). An AI learns these from the
  message, as from the checker's.
- **Measured**: in Mesen, by the console checks of the examples (`dev/check_example.sh`), for
  what only the console's timing shows.
- **Documented**: from the sources, not checked by the kit. Marked as such, so an AI knows it
  has to take care itself.

The goal is to move rules from the third kind to the first.

## Where it lives

As the C guide: one file shipped with the kit (for example `lib/SNES_HARDWARE.md`), pointed to
from each game's `CLAUDE.md` or `AGENTS.md` by `superdevkit init`, and a book page in the user
guide generated from it. The table of features is checked by a test like `LibApiTest`: a feature
marked built must have its functions in `lib/snes` and in `lib/rachael`.

## Steps

1. Rachael reports a register write she does not draw (a register outside her list, or a bit of
   one she ignores, such as mosaic or a mode other than 1), as a count next to the lost bytes;
   the desktop check fails on it as it does on a lost byte.
2. The guide file: the feature table and the rules above, checked against fullsnes and the
   SNESdev wiki, each with its kind.
3. Random start values in Rachael, behind an option of the desktop check, and the examples run
   with it.
4. The costs of the console API, measured, in `lib/README.md`.
5. The book page, its spec, generated; the pointer from `CLAUDE.md`.

Each feature added later (mode 7 for the Mandelbrot, the mouse for Schach, sound, save RAM)
changes its row of the table in the same commit, on both platforms, or as a dummy in Rachael
that the check reports.

## Open questions

- Is a dummy in Rachael that draws nothing, but reports that it was used, enough to let a game
  use a feature? It keeps the desktop honest, but the desktop then cannot check that game's
  picture.
- PAL: decided that the kit's games run on PAL consoles too, from one ROM with NTSC's limits;
  whether the main loop makes up for the slower 50 Hz is open
  ([superdevkit-transition.md](superdevkit-transition.md), 5.7).
