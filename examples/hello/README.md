# hello

The smallest game of superdevkit: text on the screen, and a ball that the joypad moves, with its
position shown as numbers. About a hundred lines of C in one file, for a first look at how a
superdevkit game is put together and for stepping through C in Mesen.

```bash
examples/hello/build.sh            # the SNES: examples/hello/build/hello.sfc
tools/build_desktop.sh examples/hello   # the desktop (Linux): examples/hello/build/linux/hello
```

On the desktop the keyboard is the joypad: the arrows, Z (B), X (A), A (Y), S (X), Q and W (L and
R), Enter (Start). `dev/compare_example.sh hello` plays it on both with the input of `check.txt`
and compares the pictures, pixel for pixel. Its check also takes five pictures of the splash
screen every game starts with (ADR record 37), in the middle of the fades too.

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
  CGRAM; at the end it switches the screen on with `sdevkit_set_brightness(15)`.
- `game_tick` changes the background map in RAM (`map`), never VRAM: the PPU only takes new
  data in the vertical blank, and `game_vblank` copies the map there when it changed.
- The sprite table is written with `sdevkit_oam_set` in `game_tick`; the library copies it in the
  vertical blank itself.
- Sprites are placed one line higher than where they should appear, and the background is
  scrolled up by one line: the SNES shows both one line lower otherwise.
