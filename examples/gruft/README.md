# Gruft

A hero goes down through a dungeon of rooms and corridors, picks up gold and potions, and fights
rats, bats and ghouls. A turn passes only when the hero moves; the creatures that are near enough
then step towards it or bite. The stairs down lead to the next, deeper level. Every game has a
dungeon of its own, made from a seed. An example of superdevkit whose variables do not fit into
the SNES's first bank of RAM.

```bash
superdevkit build snes examples/gruft     # the SNES: examples/gruft/build/snes/gruft.sfc
superdevkit run examples/gruft            # the desktop: builds build/linux/gruft (win64 on Windows) and plays it
```

On the desktop the keyboard is the joypad: the arrows, Z (B), X (A), A (Y), S (X), Q and W (L and
R), Enter (Start). The joypad moves the hero, a step a press or every 8 frames while held; a step
into a creature fights it. Start starts a game.

The check (`check.txt`) starts a game, walks to the stairs, picking up what lies near the way and
fighting what stands in it, and takes the first steps on the second level; the SNES and the desktop
compute the same in every tick.

## How it is made

| File | What |
| --- | --- |
| `src/dungeon.c` | the dungeon (made from a seed), the hero's turn, the distance map the creatures follow |
| `src/game.c` | the screen, the joypad, the course of the game |
| `src/gruft.h` | what the two share |
| `assets/` | `dungeon_gfx.png` (the dungeon, its creatures and things, the font, as 8 x 8 tiles), `pal_dim.png` (the palette of what the hero remembers); drawn by `dev/art/gruft.py` |
| `superdevkit.toml`, `check.txt` | the project (its sources and pictures), and the input of the check |

Things worth looking at:

- **Both banks of RAM.** The dungeon is 160 x 128 cells. Its maps take one byte a cell each: what
  the cell is (`tiles`), whether the hero has seen it (`seen`), who or what stands on it
  (`occupant`), 20,480 bytes apiece. The distance map takes two bytes a cell, in two halves of
  20,480, since no object may be larger than 32 KB. Together that is more than the 57,343 bytes
  that bank `$7E` holds for variables, so dampf moves the largest of them to bank `$7F`: in this
  build `tiles`, `seen` and `occupant` (`build/snes/gruft.lbl` shows them at `$7F0000`,
  `$7F5000` and `$7FA000`). The C does not change for it. The code reaches them by index
  (`tiles[y][x]`, with long addresses) and through pointers to their rows (`carve`, `look`,
  `draw_seen`), whose bank byte is `$7F` ([docs/memory-map.md](../../docs/memory-map.md#bank-7f)).
- **A distance map without clearing.** After each step, a breadth-first search writes into every
  cell up to 10 steps away how far the hero is; a creature steps to the neighbour with the
  smallest number. An entry keeps the number of its search in its high byte, so an entry from an
  older search does not count, and the 40 KB are cleared only once in 256 searches.
- **Drawing only what changed.** While the hero stays in the middle of the window, a turn draws
  the 11 x 11 cells around it again, not the whole window: what the hero saw a step before turns
  dim (palette 1), what it sees now is lit (palette 0), with the creatures and things on it.
- **What it costs.** A turn takes about three frames, most of it the search, which is not felt
  in a game that waits for the joypad. A new level takes about 50 frames: clearing 60 KB of maps
  with `memset` and carving the rooms and corridors.

Not built: sound.
