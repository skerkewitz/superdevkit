# ADR-6FSHCP06: Rachael draws modes 0 to 4, mosaic and EXTBG; the picture's other sizes wait for a game

**Context.** A feature is usable only when the SNES and Rachael both draw it (ADR-6FKTVDE8). The
SNES side writes any register (ADR-6FNPPCSX), but Rachael drew modes 1 and 7 only and reported
every other picture feature as undrawn, so the desktop check failed on it: modes 0 and 2 to 6,
256-colour tiles and direct colour, mosaic, mode 7's second layer (EXTBG), SETINI's high
resolution, overscan and interlace, VMAIN's address remapping, DMA from the PPU to memory, and HDMA
with indirect tables. Most SNES games beyond mode 1 use some of these. No game of the kit needed
them yet.

**Decision.**

- Rachael draws modes 0, 2, 3 and 4 as the SNES does: the bits a pixel per layer (2, 4 or 8), mode
  0's 32 colours per layer, each mode's order of layers and sprites, offset per tile in modes 2
  and 4 (BG3's map holds a scroll per column of tiles; mode 2 two rows, horizontal and vertical,
  mode 4 one, bit 15 choosing), and direct colour for 256-colour tiles (BBGGGRRR, the map entry's
  palette bits the lowest bit of each channel) in modes 3, 4 and 7.
- Mosaic on every layer, mode 7's too, with the PPU's vertical counter: from scanline 1, a block of
  lines at a time, each showing its first line.
- Mode 7's second layer (EXTBG, SETINI bit 6): BG2 shows mode 7's pixel again, bit 7 its priority,
  bits 0 to 6 its colour. Both layers take BG1's vertical mosaic.
- VMAIN's address remapping, and DMA from the PPU to memory: VRAM, CGRAM and OAM read back through
  their ports. VRAM's port reads a word it fetched before, as the SNES's does: setting the address
  fetches the word there, and a read that steps the address fetches the word at the address before
  the step, so a run of reads gives the first word twice. Like a copy to them, a read is allowed in
  the vertical blank and with the screen off; outside, its bytes are lost.
- `sdevkit/snes.h` names what a game needs for them: `SDEVKIT_BG_MODE0`, `_MODE2`, `_MODE3`,
  `_MODE4`, `SDEVKIT_OPT_BG1`, `_BG2`, `_VERTICAL`, `SDEVKIT_MOSAIC()`, `SDEVKIT_SETINI_EXTBG`,
  `SDEVKIT_CM_DIRECT_COLOR`, `SDEVKIT_VMAIN_REMAP_2BPP`, `_4BPP`, `_8BPP`, `SDEVKIT_DMA_FROM_PPU`
  and the read ports. No function is new: the core's `sdevkit_reg_write` and `sdevkit_dma` reach
  all of it. The picture converter writes 8-bit tiles (`superdevkit png tiles8`, `[graphics]
  tiles8`).
- Still undrawn and reported: modes 5 and 6, high resolution, overscan and interlace. Each changes
  the picture's size (512 pixels wide, 239 or 448 lines high), and with it Rachael's output, the
  desktop's window and its retro filter, the web's canvas, the screenshots and the comparison with
  Mesen. They wait for a game that needs them.
- Still undrawn and reported: HDMA with indirect tables. Its table holds 16-bit addresses of the
  data in one bank, which the same C cannot write for the SNES and the PC (a pointer on the PC is
  8 bytes, and C has no constant for the low 16 bits of an address). It would need a new call that
  builds the table per platform. It waits for a game that needs it (the user's decision of
  2026-11-26); until then direct HDMA does the same with larger tables, the data written out per
  entry.
- `examples/modes` shows every feature on a screen of its own. Its check compares the SNES (Mesen)
  and the desktop pixel for pixel.

**Alternatives.**

- Everything at once, the picture's sizes too. The other sizes touch every frontend and the
  comparison of pictures, for features few games use (high resolution is mostly text; interlace a
  few still pictures). Built for no game, they would be paid for in every frontend change.
- A call that builds an indirect HDMA table per platform (16-bit addresses on the SNES, pointers on
  the PC), now. Possible, but an API without a game to try it on; direct HDMA reaches the same
  pictures.
- A function to read a single byte from a port (`sdevkit_reg_read`). DMA reaches all three
  memories, and reading the PPU happens in the vertical blank, where a game copies anyway.

**Consequences.**

- The desktop check of a game that uses these features passes where it failed. A game that sets
  modes 5 or 6, SETINI's other bits or indirect HDMA still gets `undrawn` and a failing check.
- `examples/modes`: eleven pictures of nine screens, the same in Mesen and on the desktop pixel for
  pixel, and the same checksums after all 420 ticks; Rachael's tests check the ports
  (`RachaelTest`).
- Rachael is a little slower for mode 1: the desktop check of Er-Tüp took 18.17 s instead of 17.70
  s (2.6 % more), Steinschlag's 2.34 s instead of 2.25 s (4 %), CatchMe's the same (1.89 s).
- The examples' ROMs are unchanged: the header gained macros only.
