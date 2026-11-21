# ADR-6F60Z67N: The examples compare a checksum per tick on the SNES and the desktop, and the game names its state

**Context.** The examples were checked by their pictures: `dev/compare_example.sh` plays a
`check.txt` in Mesen and on the desktop and compares a few screenshots pixel for pixel. A
difference in the game's logic shows there only when it reaches a screenshot, and then without
the tick it started in; and the comparison needs Mesen, so it never ran in the build. Blubber
Blasen has had a checksum per tick in its replays from the start. The Er-Tüp proposal
([er-tuep-proposal.md](../archive/er-tuep-proposal.md)) asked for the same for the examples before a game
with many objects is built.

**Decision.** After every tick the checks compute a checksum of the game's state, FNV-1a with 32
bits: the bytes the game names during the tick with `sdevkit_dbg_hash(data, bytes)`, in the order
named, then the sprite table, which the kit's main loop names itself. On the SNES the call writes
only the address and the size to port `$21FC`, and the main loop writes `$21FD` after `game_init`
and after each tick; the checker reads the memory and hashes it, so a call costs the game about 80
cycles whatever its size. dampf's emulator gets the timing of the picture the main loop polls (the
vertical blank, the joypads, the line counter), and `ExamplesTest` plays every example's
`check.txt` in it and on the desktop (Rachael) and compares the two lists in every build. Mesen's
check writes the same list, and `dev/compare_example.sh` compares it too.

**Alternatives.**

- The checker hashes the game's memory by itself, without a call: the layouts of the SNES and the
  PC differ (ADR-6F0R6BSK: structs have padding on the PC, pointers have other sizes), so only the
  game knows which bytes are the same on both.
- The game computes the checksum: costs the SNES the hashing, thousands of cycles a tick for a
  cave or an object table, and would change the timing the checks measure.
- A checksum of the pictures (VRAM, CGRAM) per tick: an upload deferred to the next vertical blank
  (`sdevkit_dma_fits`) moves between ticks on the two sides, and Lua is too slow to hash 64 KB a
  tick.

**Consequences.** A difference between dampf's code and gcc's, or between `lib/snes` and Rachael,
is found in every build, with the first tick it shows in, without Mesen; all five examples agreed
from the first run. Every game of the kit gets the sprite table compared without a line of code; a
game that names its state is compared more closely (Schach names its position, Steinschlag its
cave). What a game names must be the same bytes on both: arrays of `uint8_t` and `uint16_t`,
structs without padding on the PC, no pointers. dampf's emulator also reports frames per tick for
every example (`build/emu-check/frames.txt`), close to Mesen's but not equal: it has no DMA.
