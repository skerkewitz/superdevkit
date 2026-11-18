# CatchMe

A mouse eats the crumbs of a maze while four cats chase it. A chili makes the cats run, and for a
while the mouse can catch them. An example of superdevkit for someone new to the SNES: one
screen, no scrolling, one background layer and a handful of sprites.

## Build and play

```bash
examples/catchme/build.sh            # the SNES: examples/catchme/build/catchme.sfc
tools/build_desktop.sh examples/catchme   # the desktop (Linux): examples/catchme/build/linux/catchme
```

On the desktop the keyboard is the joypad: the arrows, Z (B), X (A), A (Y), S (X), Q and W (L and
R), Enter (Start). `dev/compare_example.sh catchme` plays it on both with the input of `check.txt`
and compares the pictures, pixel for pixel.

Open `build/catchme.sfc` in Mesen (with `build/catchme.dbg` next to it, Mesen shows the C
source while you step). `dev/check_example.sh catchme` plays it headless with the input of
`check.txt` and checks that no byte reaches the PPU outside a vertical blank.

The joypad moves the mouse; Start starts a game. Crumbs give 10 points, a chili 50, a cat
caught while it runs 200, 400, 800 and 1,600; the cheese that shows up twice a level gives 100
per level. An extra life at 10,000. Each level the mouse and the cats get faster and a chili
works for less time.

## How it is made

| File | What |
| --- | --- |
| `catchme.args` | the call of dampf: the include paths, the entry, the sources (ADR record 28) |
| `build.sh` | converts the assets with `tools/png2snes.py`, then calls dampf (until the `superdevkit` command, roadmap M7) |
| `src/catchme.h` | what the files share |
| `src/game.c` | the course of the game (title, ready, play, a life lost, a level cleared, game over), score and lives, and the frame rhythm: `game_init`, `game_tick`, `game_vblank` (`snes/loop.h`) |
| `src/maze.c` | the maze as text, the background map in RAM, the walls drawn from their neighbours, the status line |
| `src/actors.c` | the mouse and the cats: moving cell by cell at speeds in sixteenths of a pixel, the tunnel, how each cat chases |
| `assets/` | the graphics as PNG: `maze_gfx.png` (background tiles and font), `sprites_gfx.png` (the sprites, 128 pixels wide so the tiles are in VRAM order), `pal_*.png` (the palettes of the cats and the other states). Drawn by `dev/art/catchme.py`; edit them in any paint program |
| `check.txt` | the input for `dev/check_example.sh` |

Things worth looking at for a first SNES game:

- **The frame rhythm.** `game_tick` runs while the picture is drawn and only prepares: it moves
  everything, fills the sprite table (`oamSet`) and changes the background map in RAM.
  `game_vblank` runs in the vertical blank, the only time the PPU accepts new data: it copies the
  map (2 KB) when it changed, and changes the wall colours when a level is cleared. The library
  copies the sprite table itself.
- **Cells and tiles.** The maze has cells of 16 x 16 pixels, so the 16 x 16 sprites fit its
  corridors; on the screen each cell is 2 x 2 background tiles of 8 x 8. A wall tile is picked by
  which of its neighbours are walls, from 16 drawn variants, so nobody draws the maze twice.
- **Speeds without fractions.** The subset has no floating point: a speed of 15 means 15/16 of a
  pixel per frame, and the remainder carries over (`Actor.sub`).
- **Four chasers with one rule.** Each cat goes to the open cell closest to a target; only the
  targets differ: the mouse, three cells ahead of it, nowhere in particular, or its own corner
  when close.
- **One line of scroll.** `bgSetScroll(0, 0, 0x3FF)`: the SNES shows the second line of a
  background on the first line of the screen; scrolling it up by one fixes that. Sprites are
  placed one line higher for the same reason.

Not built: sound (superdevkit has none on the SNES yet).
