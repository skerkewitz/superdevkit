# Er-Tüp

A side-scrolling shooter in a German pub, its brewery and a beer tent, and a parody of the genre's SNES games. The ship is a
flying beer tap; its companion is a crate of beer that docks at the front or the back, can be
launched and comes back; caps and bottles are the shots; the cork of a bottle of sparkling wine is
the charged shot, and greed is punished when the bottle foams over. Hit enemies drop empties, which
tumble down and are the score, as deposit: "PFAND 12,75 €". The slowdown the genre is known for is
a power-up nobody wants. The idea is in [docs/er-tuep-proposal.md](../../docs/er-tuep-proposal.md).

```bash
examples/ertuep/build.sh                  # the SNES: examples/ertuep/build/ertuep.sfc
dev/build_desktop.sh examples/ertuep    # the desktop (Linux): examples/ertuep/build/linux/ertuep
dev/build_desktop_windows.sh examples/ertuep   # Windows, with a gamepad: build/windows/ertuep.exe
```

On the desktop the keyboard is the joypad: the arrows, Z (B), X (A), Enter (Start); on Windows a
gamepad too (WSL sees none).
`dev/compare_example.sh ertuep` plays it on both with the input of `check.txt` and compares the
pictures and the checksum of every tick.

## Playing

The arrows fly. **B** fires: the ship a cap from its spout, the crate bottles from where it is.
**A** launches the crate when it is docked and calls it back when it is not; it docks on the side
of the ship it reaches, and docked at the back it fires backwards. Docked or flying it takes enemy
shots and hurts what it touches. Start starts a game; Select on the title chooses the stage it
starts with.

| Item | What it does |
| --- | --- |
| Six-pack | the crate (the first one flies in from the left), then six bottles more, up to 24. A volley costs a bottle: more than 12 fire a fan, more than 6 a pair, the rest single bottles; an empty crate fires caps |
| Sekt | from now on, holding B shakes the bottle: the neck in the status line fills; let go for the cork, which flies through everything; at full charge it bounces off walls. Held too long, it foams over |
| Espresso | the ship is faster, three times |
| The other six-pack | one colour off: the stutter. Four seconds of authentic slowdown™: the picture every fourth frame, flicker, the joypad six ticks late. The game does not slow down |
| Empties | 8 cents a bottle, 25 a can; the crate collects them too. Every 5 € of deposit is a life |

Walls kill. A life lost starts again at the last checkpoint (*Zwischenstand*), without the crate.

At the end of the cellar a **slot machine** (*Spielautomat*) blocks the way; the stage waits until
it is beaten. Its reels spin and stop one by one, and what they show is what it pays out: beer
mugs a fan of foam, pretzels three pretzels, bells a ring of coins, three sevens the jackpot, to
the player. At the end of the kitchen the lights go out and **der Pfandautomat** comes, a
bottle-return machine as large as the screen. Closed, it prints receipts at you. Open, its intake
can be hurt, but it swallows what is shot into it and gives it back: "Flasche wird nicht
angenommen". The cork is not deposit; it hurts. Below half its strength the front breaks open and
it rocks and fires everything at once; broken, it pays out its deposit.

**Stage 2, das Sudhaus** (the brewhouse), follows with what was won: copper kettles, pipes and
catwalks, then Mett, raw minced pork, which grows over the walls, pulses and narrows the way, while
the whole picture sways. Hop cones fly in waves that turn toward the ship, and Mettigel crawl along
the floor and throw onion rings. At its end stands **die Sudpfanne**, the brew kettle: closed it
blows steam; open, a creature of Mett rises from it with two tentacles that wave toward the ship.
Only its head can be hurt; the tentacles hurt the ship.

**Stage 3, das Festzelt** (the beer tent), is seen from above, in mode 7: the ship flies a loop
through the tent's aisles, past the beer tables and the band stand, and at each corner the floor
turns a quarter under it. Roast chickens learned to fly and dive at the ship, gingerbread hearts
swing past throwing pretzels, wasps come in swarms. In the middle the band's **tuba** blocks the
way: it blows notes, and with every blast the floor jumps. The more bottles the crate holds, the
more the picture sways: it turns back and forth, breathes and waves line by line. At the end
**das Kettenkarussell**, a chain carousel, comes out of the dark, turning: its chairs fly out on
their chains, the riders throw foam, and the faster it turns, the further they reach. Its crown
opens now and then and plays the organ; only then can it be hurt. Below half its strength it turns
the other way round, wilder. Then the pub has closed, and the deposit is counted.

The title shows a demo after a few seconds, by turns of the three stages' busiest waves and their
bosses, flown by an autopilot.

## How it is made

| File | What |
| --- | --- |
| `src/stage.c` | the two stages as text (14 rows of 224 characters each), their tiles by neighbours, the camera, the columns streamed into both maps, the waves, the checkpoints |
| `src/player.c` | the ship, the crate's state machine, firing, the Sekt, the shots |
| `src/objects.c` | enemies (a state machine per kind), bullets, items and empties, particles, the pair loops |
| `src/draw.c` | every object into the sprite table |
| `src/game.c` | the screen, the status line, the course of the game, the stutter |
| `src/boss.c` | the bosses: der Pfandautomat and die Sudpfanne as pictures on layer 2, their states and attacks, the Mett creature's sprites |
| `src/festzelt.c` | stage 3 in mode 7: the camera's loop, the matrix, the split with the status line, the drunk sway, das Kettenkarussell |
| `src/bossmap.c` | their pictures in three states each, as cells (written by `dev/art/ertuep.py`) |
| `src/walls.c` | the pictures of the walls behind, as cells (written by `dev/art/ertuep.py`) |
| `assets/` | the sheets: the stage's three rooms, their walls, the sprites (three sheets), the status line, the tent and the carousel as mode 7 pictures; drawn by `dev/art/ertuep.py` |
| `ertuep.args`, `build.sh`, `check.txt` | the build, and the input of the checks |

Things worth looking at:

- **The stage is text.** Each character is a thing (`=` the oak floor, `B` the bar counter, `X`
  brick, `F` a fridge), and a solid thing gets its top, middle or bottom tile from its neighbours
  above and below when its column is built. Changing the stage is editing a picture in the source.
- **Streaming columns.** The map of layer 1 is 64 columns wide (1024 pixels) and the stage 224;
  the column about to come into view is built in RAM and copied down the map in the next vertical
  blank with `sdevkit_dma_copy_vram_column`, which the kit got for this game. The wall behind (layer
  2) scrolls at half the speed and streams its own columns.
- **Object tables.** Every shot, enemy, bullet, item and particle is a 14-byte `Obj` in a table,
  reached through a pointer, moved in 12.4 fixed point; each kind of enemy is a state machine. The
  struct has no padding on the PC either, so the checks hash the tables after every tick
  (`sdevkit_dbg_hash`) and compare the SNES with the desktop.
- **The sprite table written directly**, without `sdevkit_oam_set` (315 cycles and more a call):
  four bytes and two bits a sprite.
- **One `random16()` a statement.** C leaves the order in which a call's arguments are evaluated
  open; two calls of the random generator in one call's arguments may run in another order with
  dampf than with gcc, and the SNES and the PC would play different games. The checks find it.
- **A boss on a background layer.** The Pfandautomat is 160 x 192 pixels, which as sprites would
  be 120 of them, more than a line allows. As a picture on layer 2 it costs no sprite: when it
  comes, the screen goes dark for a moment and its tiles, colours and map replace the wall behind
  the stage, and its scroll registers move it. Its three states share all but nine cells, so a
  change of state copies twelve short rows of the map, one a frame.
- **The screen sways** in the brewhouse: an HDMA table gives layer 1 another horizontal scroll
  every sixteen lines, written each frame into the half the PPU is not reading. The scroll registers
  are written twice each, so the kit's HDMA got the transfer mode for that
  (`SDEVKIT_HDMA_TWICE`). **The Mett pulses** by tiles: every eight frames the next of four
  frames is copied over the three cells the map names, 384 bytes.
- **Tables instead of signed divisions.** The first version of the sway and the tentacles divided
  signed sine values by constants, about a hundred times a frame; each keeps a rounding correction
  (C_STYLE.md, `slow-signed-divide`), and the kettle's fight took two frames a tick. A table each,
  a counter instead of a remainder by a variable and a multiplication by a constant instead of
  one by a variable brought it to one frame with a fifth to spare. The profile that showed it
  comes from dampf's emulator: `java -cp 'build/install/dampf/lib/*' dampf.emu.ExampleProfile
  examples/ertuep/build/ertuep.sfc examples/ertuep/build/ertuep.lbl examples/ertuep/check.txt
  5700 5732` (the cycles per function and per C line over those ticks).
- **Mode 7 under a status line.** Mode 7 has one layer, and the status line is BG3 of mode 1: two
  HDMA channels switch BGMODE and TM after its 16 lines, every frame, from tables in ROM. Mode 7's
  picture (1024 x 1024 pixels, 256 tiles of 8 x 8 at most, converted by `png2snes.py mode7`) fills
  the VRAM below the status line's and the sprites'; its colours are CGRAM 33 to 127. The tent and
  the carousel are two such pictures, swapped while the screen is dark.
- **A loop instead of a scroll.** The camera's distance is the same `cam_x` as in the other
  stages, so waves and checkpoints work unchanged; `festzelt.c` turns it into a point and a
  direction on the loop (four straights and four quarter turns, an arc table in 12.4) and the
  matrix of the frame. The carousel turns the whole picture about its hub, and its chairs, which
  are sprites, are placed at the same angle.
- **The densest waves had to fit.** A full crate fans out three bottles a volley; with sixteen
  enemies and twelve shots the pair loop alone took a sixth of the frame, and the densest waves of
  the brewhouse, which no check had played, two frames a tick. Now the shots go into buckets of 32
  pixels by x, and an enemy looks only at the shots near it (the result is the one of all pairs);
  a wave stops spawning when the table is full, a column is built in two halves, comparisons with
  a sum are cast to `int16_t` (C_STYLE.md, `slow-compare-sum`), and the densest waves have eight
  flies instead of twelve, ten shots and twelve particles at most. The demos fly those waves, and
  the budgets of `check.txt` hold for every tick of them.
- **The stages are the benchmark**: the title's demos fly their busiest waves and the bosses, with an
  autopilot. dampf's emulator writes the frames per tick of the check to
  `build/emu-check/frames.txt` in every build, and the `budget` lines of `check.txt` make the
  build fail when a tick in play takes more than a frame.

Not built yet: sound.
