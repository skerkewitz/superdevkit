# ADR-6F60ZA6T: The splash screen is the new design in parts on BG1, and its stars are sprites

**Context.** The splash logo of ADR-6F60Z5JJ had to be replaced for legal reasons. The user's new
design (`dev/art/splash/logo_source.png`, 230 x 174 pixels with 255 colours) has the text "Made with
SuperDevKit", a "Powered by Dampf" sticker, a steam locomotive and stars on a dark background. It is
a scaled picture with soft edges, too many colours for the SNES and too large for one palette of 16.
ADR-6F60Z5JJ kept the splash off the sprite table, because the checks of the examples count a tick at
every DMA to OAM.

**Decision.** `dev/art/splash_parts.py` cuts the design into its parts (the text, the sticker, the
locomotive, the stars) on one solid background, each with its own colours, at most 15 a part, in
`dev/art/splash/parts/`. `dev/art/splash.py` puts the design in the middle of the screen, where no
8 x 8 tile holds pixels of two parts (the script stops if one does), and makes the text, the sticker
and the locomotive BG1 tiles with BG palettes 0, 1 and 2, and each star a sprite of 8 x 8 or
16 x 16 with sprite palette 0. The splash writes the sprite table without a DMA, through a
function each platform defines (`sdevkit__splash_sprites`): on the SNES the CPU writes OAM with the
screen off, and after the reset the main loop writes the game's sprite table back the same way; on
the desktop it writes Rachael's copy of the table, which the reset clears. The other sprites are
hidden during the splash.

**Alternatives.**

- The design as one picture in one palette: 15 colours for text, brass and stars together lose the
  text's bands and the locomotive's shading.
- The stars as BG tiles: they lie between the other parts and would need tiles of their own, with a
  fourth palette, on the same layer; as sprites they can be anywhere.
- An OAM copy by DMA for the stars: each copy would count as a tick in the checks.

**Consequences.** The logo is 334 BG tiles (about 10.7 KB), 17 sprites and 30 colours in four
palettes, against 158 tiles and 13 colours before. The splash's VRAM layout changed: the map at word
0x3C00, the sprite tiles at word 0x4000. The checks of the examples are unchanged: hello's pictures
of the splash are the same on the SNES and the desktop, and no tick is counted for it.
