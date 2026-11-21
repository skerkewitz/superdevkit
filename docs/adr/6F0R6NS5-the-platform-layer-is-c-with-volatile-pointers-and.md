# ADR-6F0R6NS5: The platform layer is C with `volatile` pointers, and the main loop waits for the vertical blank

**Context.** Blubber Blasen's `video.c` calls 19 functions of a PVSnesLib-shaped API
(`src/snes/snes.h`), which are a pseudo SNES on the PC. The playable ROM needs them on the
console. Frames have fixed places (ADR-6F0R668G), so C code must not interrupt other C code.

**Decision.**

- The platform layer (`platform/snes`) is written in C. It reaches the hardware through
  pointers to `volatile` memory: one access at a time, in order, with exactly the width of the
  type. A cast from a pointer to an integer gives the SNES address (`bank << 16 | address`) for
  DMA sources. No intrinsics are needed.
- There is no interrupt handler that runs C. The main loop waits for the vertical blank, then
  copies the OAM shadow and calls `game_vblank`.

**Consequences.** A tick that needs more than a frame waits for the next vertical blank instead
of being interrupted. The checker does not tell platform code from game code: a cast from an
integer to a pointer is accepted anywhere. Raster effects through an IRQ, and any C code in an
NMI, are not possible this way.
