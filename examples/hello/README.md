# hello

The smallest game of superdevkit: text on the screen, and a ball that the joypad moves, with its
position shown as numbers. About a hundred lines of C in one file, for a first look at how a
superdevkit game is put together and for stepping through C in Mesen.

```bash
examples/hello/build.sh            # -> examples/hello/build/hello.sfc
```

Open `build/hello.sfc` in Mesen with `build/hello.dbg` next to it, and step through
`src/hello.c` in its debugger. `dev/check_example.sh hello` plays it headless with the input of
`check.txt`.

| File | What |
| --- | --- |
| `src/hello.c` | the game: the three functions every superdevkit game defines (`game_init`, `game_tick`, `game_vblank`), text in a background map, a sprite |
| `assets/font_gfx.png` | the font, as background tiles; `assets/ball_gfx.png` the ball, as a sprite (drawn by `dev/art/hello.py`) |
| `hello.args` | the call of dampf (ADR record 28) |
| `build.sh` | converts the assets with `tools/png2snes.py` and calls dampf |

What it shows:

- `game_init` runs with the screen off, so it can copy as much as it likes into VRAM and
  CGRAM; at the end it switches the screen on with `setBrightness(15)`.
- `game_tick` changes the background map in RAM (`map`), never VRAM: the PPU only takes new
  data in the vertical blank, and `game_vblank` copies the map there when it changed.
- The sprite table is written with `oamSet` in `game_tick`; the library copies it in the
  vertical blank itself.
- Sprites are placed one line higher than where they should appear, and the background is
  scrolled up by one line: the SNES shows both one line lower otherwise.
