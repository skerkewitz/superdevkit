# Proposal: a level editor that compiles pictures to tiles

Status: proposal, 2026-11-20. Nothing is built or scheduled; superdevkit itself comes first. It
would be the first big lab of `superdevkit studio` ([roadmap.md](roadmap.md#future-ideas)).

## The idea

Classic tile editors make their user think in tiles: the graphics come cut into 8 x 8 pieces
that fit the console, and a level is those pieces set one by one. The user had such an editor
for an early try at Blubber Blasen, a primitive one, and it was good enough, but it is the reason
a hobbyist cannot draw for the SNES without learning its rules first.

This editor turns that around. The maker builds a level from smart objects, in layers, placed
to the pixel. Only the build decides which tiles, palettes and maps come out of it, and when the
SNES cannot show it, the build says why, as dampf does when a budget is not met. dampf compiles C
that knows nothing of the 65816; this compiles pictures that know nothing of tiles.

## Smart objects

A smart object is defined once and placed many times.

- **Stretching.** The picture is cut like a 9-patch of a user interface: corners stay, edges and
  middle repeat. A pipe is drawn once and dragged to any height; a platform to any width. Parts
  can also be fixed to the start or the end only, for things like the top of a pipe.
- **Variants.** A part can have alternatives that the build picks by chance, so a long wall does
  not repeat one tile. The choice is made when the level is built, with a seed per level: the level
  looks the same every time, costs no time in the game, and the variants count honestly in the
  tile budget.
- **Styles.** The same object in other colours, a green and a red pipe. On the SNES every map
  entry names its palette, so a style costs no tile, only a palette. Flipped copies cost nothing
  either: the build finds tiles that are mirror images and keeps one.
- **Meaning.** Which parts are solid, where an enemy comes out, where the pipe leads. The build
  writes it as tables and constants for the game's code, as it writes `music.h` today.
- **Edges off the grid.** An object can end 6 pixels into a tile and leave the other 2
  transparent. The build then merges those pixels with whatever lies behind, which makes one
  tile for every pairing of edge and background (see below).

## What the build checks

The limits come from the hardware ([lib/SNES_HARDWARE.md](../lib/SNES_HARDWARE.md)), so the kit
can be strict here without limiting what anyone makes.

- **Colours.** In mode 1 an 8 x 8 tile of BG1 or BG2 uses one palette of 15 colours plus
  transparent, and backgrounds have 8 palettes. Fitting the colour sets of all tiles into them is
  a packing problem with good heuristics; it is the work nobody wants to do by hand. When it does
  not fit, the build names the tiles and the colours: "column 40 to 48 of row 3 needs 11 colours
  that fit none of the 3 free palettes".
- **Tiles and VRAM.** Tiles, maps and sprites share 64 KB, and a map entry reaches 1024 tiles.
  The editor shows each level's use as a bar, as a frame budget is shown.
- **Edges off the grid.** An edge in front of five different background tiles is five tiles, and
  this multiplies quickly. The build counts them and offers a way out: put the object on a layer
  of its own (its transparency is then real and costs no merged tiles), snap it to the grid, or
  make the background behind it plainer.
- **Sprites,** later and with their own rules: 128 at most, 32 on one line, two sizes in a frame.

## Where the work is done

The page in the browser is only the surface. The conversion to tiles and every check are in the
Java CLI, part of `superdevkit build`, and the level and the smart objects are JSON files in the
game's folder. The editor, the build, a CI run and an AI agent then get the same verdict, and an
agent can build or change a level without the page. This is the shape the studio idea has:
a local server, the pages in the browser, the game's files the source.

The existing converter (`superdevkit png`, `Png2Snes`) cuts a picture of at most 16 colours into
tiles in reading order; the editor's build grows out of it.

## Pictures from people and from AIs

A smart object is small: one picture and how it is cut and stretched. That is a size a person
draws in an evening and an image model can make. The editor reduces the picture to SNES colours
and shows at once whether it fits. The user expects to draw the pictures themselves once the
editor takes the tile rules off them.

## What exists elsewhere

Tiled has terrains and Wang sets for automatic tiling; LDtk has rules for automatic layers and
random variants. Both are worth reading before designing the stretching and the variants. Neither
compiles free pictures into the tiles and palettes of a console: they expect graphics that fit
the grid already.

## Steps

1. One background layer, smart objects on the 8 pixel grid, stretching, variants and styles, the
   budget report. Useful alone, and the part that is clear.
2. Edges off the grid, with the merged tiles and their count.
3. Several layers and the choice of layer for an object.
4. Sprites and their limits.

## Open questions

- 16 x 16 tiles (Steinschlag uses them, one bit of `BGMODE`): a setting of the layer, or smart
  objects in 16 x 16 cells.
- Animated tiles (water, lava): part of a smart object, with the VRAM they need in the budget.
- How a level is cut when it is larger than VRAM's map, and what the game streams while it
  scrolls.
- Which of the examples is the first user: Steinschlag's caves, or a new example made for it.
