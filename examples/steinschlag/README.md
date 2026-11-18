# Steinschlag

A miner digs through a cave for gems. Boulders and gems fall when nothing holds them and roll
off each other and off walls; whatever they land on after a fall is crushed, the miner too.
Beetles crawl along the walls, and touching one ends badly. Enough gems open the exit, before
the time runs out. An example of superdevkit that scrolls: the cave is larger than the screen.

```bash
examples/steinschlag/build.sh          # -> examples/steinschlag/build/steinschlag.sfc
```

The joypad moves the miner; with B or Y held it digs or takes a gem next to it without moving.
A boulder moves when pushed twice into a space. Start starts a game.

Not verified yet: the camera scrolling far into the cave. In Mesen with scripted input
(`dev/check_example.sh steinschlag`) it plays without losing a byte and manages 0.98 ticks per
frame, but the scripted miner gets stuck on boulders near the start; the scrolling will be
checked on the desktop target (roadmap M7), where a game can be steered and looked at more
easily.

## How it is made

| File | What |
| --- | --- |
| `src/cave.c` | the caves (made from a seed, plus walls, beetles, the exit) and their physics, in ticks |
| `src/game.c` | the screen, the camera, the miner, the status line, the course of the game |
| `src/steinschlag.h` | what the two share |
| `assets/` | `cave_a_gfx.png`, `cave_b_gfx.png`, `cave_c_gfx.png` (the cave's elements as 16 x 16 tiles, one palette each), `miner_gfx.png` (the sprite), `font_gfx.png` (the status line); drawn by `dev/art/steinschlag.py` |
| `steinschlag.args`, `build.sh`, `check.txt` | the build, and the input of the check in Mesen |

Things worth looking at:

- **16 x 16 background tiles.** One bit of `BGMODE` (`BG1_TILES_16`) makes layer 1 read 16 x 16
  tiles, so one map entry is one cell. A map of 64 x 32 entries is then 1024 x 512 pixels, the
  whole cave fits, and scrolling in x and y is only the scroll registers (`bgSetScroll` in
  `game_vblank`), no copying of columns.
- **Two layers.** The status line is layer 2, in 8 x 8 tiles with the priority bit, so it stays
  in front of the scrolling cave; the miner's sprite has priority 2, below it.
- **Animation by tiles.** Every gem on the map names the same tile; every eight frames the next
  frame of the gem is copied over that tile in VRAM, and every gem sparkles, without touching
  the map. The open exit and the beetles' legs too.
- **Physics in ticks, spread over frames.** The cave moves every eight frames; each frame looks
  at three of its 22 rows. A cell handled in a tick holds the tick's parity, so a boulder that
  fell into the next row is not moved twice.
- **What costs time on the 65816.** A multiplication by a variable and a 32-bit division are
  routines of the runtime: the miner's pixel position is advanced by 2 pixels a frame instead of
  computed, the score is 16 bits, and each field of the status line is written only when it
  changes. Before that, a tick took 1.23 frames on average.

Not built: sound.
