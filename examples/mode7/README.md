# mode7

The smallest example of mode 7: a checkerboard of 1024 x 1024 pixels with a red border and a mark
in the shape of an L turns and zooms around the middle of the screen, with a sprite in front of
it. It is there to show that the SNES and the desktop draw mode 7 the same, pixel for pixel.

```bash
superdevkit build snes examples/mode7     # the SNES: examples/mode7/build/snes/mode7.sfc
superdevkit run examples/mode7            # the desktop: builds build/linux/mode7 (win64 on Windows) and plays it
dev/compare_example.sh mode7                  # both with the input of check.txt, the pictures compared
```

A (on the desktop X) changes what lies outside the map: it repeats, it is transparent (the
backdrop shows), or it is tile 0 everywhere. B (Z) mirrors the screen. The zoom goes from one to
six map pixels a screen pixel, so the map's edge comes into view.

## How it is made

Everything is in `src/mode7.c` and computed there, no image files: four tiles of 8 x 8 pixels
with a byte a pixel, the map of 128 x 128 tiles, the sprite, and a table of sines for the matrix.

- **The console API's mode 7** (`sdevkit/snes.h`, `sdevkit_mode7_*`): the map goes into the low
  byte of the first 0x4000 VRAM words, the tiles into the high byte; a matrix in 8.8 fixed point,
  a centre, and BG1's scroll say which map pixel each screen pixel shows.
- **Registers in the vertical blank.** The matrix and the select are written in `game_vblank`:
  on the SNES a register written during the picture takes effect from that line on, which
  Rachael, who applies it to the whole frame, would not show.
- **The sprite** sits at VRAM word 0x4000, above what mode 7 fills, with priority 2: in mode 7,
  sprites of priority 1 to 3 are in front of the layer, 0 behind it.

The check (`check.txt`): six pictures, at the widest zoom for each way of handling the outside of
the map, and one mirrored. In Mesen and on the desktop they are the same, pixel for pixel.
