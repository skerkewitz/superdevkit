# ADR-6F60Z4AZ: One ROM for NTSC and PAL, with NTSC's limits, and PAL a sixth slower for now

**Context.** The playable ROM is to run on a console (M5, step 5.7), and the consoles at hand
are PAL ones. Whether a SNES is PAL or NTSC is a property of the console, not of the ROM: the
PPU sends 50 pictures a second on PAL and 60 on NTSC, and a game can read which from bit 4 of
`STAT78` (`$213F`). PAL's vertical blank is longer, so it takes more DMA than NTSC's. The kit's
main loop runs one tick per frame (`loop.h`), so on PAL a game runs at five sixths of its speed.
Making up for that by running six ticks in five frames needs two ticks to fit into one PAL frame
(20 ms); Blubber Blasen takes 13.2 ms per tick on average, so that frame's picture would be shown
twice.

**Decision.** One ROM for both regions; the header keeps saying North America (NTSC). NTSC's
limits are the limits of the design: the vertical blank's DMA budget (`SDEVKIT__VBLANK_DMA_BYTES`)
and the frame time are NTSC's, so a ROM that loses no byte on NTSC loses none on PAL. On PAL the
game runs a sixth slower, as many PAL releases of the time did; `lib/snes` does not make up for
it for now. Rachael stays NTSC. The ROM is checked in Mesen with the region set to PAL before it
goes to a console.

**Alternatives.**

- Six ticks in five frames on PAL, in `lib/snes`'s main loop: the same speed and still
  deterministic, but a picture shown twice every fifth frame in a game near its frame budget.
  Possible later, after measuring.
- Speeds scaled by the region in the game code: every game would have to do it, and the game
  would compute differently on the two regions, against the PC checksums.
- A PAL build of its own: two ROMs to test, for no gain over one.

**Consequences.** Nothing to build for PAL now beyond the check in Mesen. A game must not count
on PAL's longer vertical blank. Sound, once built, keeps its tempo on PAL, since the SPC700 has
its own clock; only the game logic slows down. If the slower game is a problem on the console,
the six-ticks loop is the next step, as a new record.
