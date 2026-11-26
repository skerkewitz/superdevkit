# modes

The SNES's picture modes beyond mode 1, one screen each, with a label at the top. It is there to
show that the SNES and the desktop draw all of them the same, pixel for pixel, and how a game
sets each one up with the console API's core.

```bash
superdevkit build snes examples/modes     # the SNES: examples/modes/build/snes/modes.sfc
superdevkit run examples/modes            # the desktop: builds build/linux/modes (win64 on Windows) and plays it
dev/compare_example.sh modes              # both with the input of check.txt, the pictures compared
```

A (on the desktop X) shows the next screen, B (Z) the one before:

| Screen | What it shows |
| --- | --- |
| Mode 0: four layers | BG1 to BG4, each with 4 colours a tile and 32 colours of its own (reds, greens, blues, greys), scrolling apart; BG1's tiles with the priority bit in the middle |
| Mode 2: offset per tile | BG1's stripes moved down column by column in a wave, BG2's checker moved sideways column by column: both from BG3's map, which is no layer in mode 2 |
| Mode 3: 256 colours | a picture of 256 colours (8-bit tiles, `superdevkit png tiles8`) as BG1, in a frame of BG2 with 16 colours |
| Mode 3: direct colour | 8-bit tiles whose pixels are colours themselves (BBGGGRRR), the map entries' palette bits adding the lowest bit of each channel |
| Mode 4: 256 colours and OPT | the picture, its columns moved up and down or sideways by one row of offsets per tile, over BG2's stripes of 4 colours |
| Mosaic | mode 1 with mosaic on BG1 and on BG3 (the stripes on the left), the blocks growing from 1 to 16 pixels; BG2 along the bottom without it |
| Mode 7: EXTBG | mode 7's rings turning; with its second layer, pixels with bit 7 are in front of a row of sprites of priority 1, the others behind them |
| Mode 7: mosaic | the same rings in mode 7's one layer, with mosaic |
| VRAM: remap and read back | a picture written as a bitmap, row by row, through VMAIN's address remapping; a text row read back from VRAM by DMA and written twice (once with the port's first, repeated word dropped, once as it comes, a tile to the right); a palette read back from CGRAM and written with two colours swapped; the label's sprites read back from OAM and written lower |

## How it is made

`src/modes.c` uses the console API's core (`sdevkit/snes.h`). A screen is set up in `game_vblank`
with the screen off (`SDEVKIT_FORCED_BLANK`): registers, tiles, maps, colours and the sprites of
the label. Then the tick computes what moves (scrolls, the offsets per tile, the mosaic, mode 7's
matrix), and `game_vblank` writes it. On the frame a screen is set up, those writes come before
the screen is switched on again: the setup takes more than a vertical blank, and a copy after it
would arrive while the picture is drawn, which the desktop's check counts as lost.

Most of the pictures are computed in `src/modes.c`: the tiles of 2, 4 and 8 bits, the maps,
mode 7's map, the bitmap. Two come from files that `dev/art/modes.py` draws: `assets/font_gfx.png`,
the letters of the labels (sprites, so every mode can show them), and `assets/picture_gfx.png`,
the picture of 256 colours (`tiles8` in `superdevkit.toml`). Its colours 1 to 3 are the frame's,
and 240 to 255 stay free for the sprites' palette 7.

- **Offset per tile** (`SDEVKIT_OPT_BG1`, `SDEVKIT_OPT_BG2`, `SDEVKIT_OPT_VERTICAL`): in modes 2
  and 4, BG3's map holds a scroll per column of tiles. The tick writes one row (mode 4) or two
  (mode 2) into `opt[]`, and `game_vblank` copies them to BG3's map.
- **Direct colour** (`SDEVKIT_CM_DIRECT_COLOR`): an 8-bit pixel is BBGGGRRR, and the map entry's
  three palette bits are the lowest bit of blue, green and red.
- **Mode 7's second layer** (`SDEVKIT_SETINI_EXTBG`): BG2 shows mode 7's pixels again, bit 7 as
  their priority and bits 0 to 6 as their colour.
- **Reading back** (`SDEVKIT_DMA_FROM_PPU`): VRAM's port gives the word it fetched when the
  address was set twice, so a copy of n words takes n + 1 and drops the first.

The check (`check.txt`): eleven pictures, every screen once, and both mosaics twice. In Mesen
and on the desktop they are the same, pixel for pixel, and so are the checksums after every tick.
