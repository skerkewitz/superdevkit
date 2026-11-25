# ADR-6FNPPCSX: The console API is the hardware at once, with optional layers above it

**Context.** The console API was PVSnesLib's functions renamed (ADR-6F3CK2AR): its setup calls
took eight positional parameters, `sdevkit_oam_set` cost 217 to 329 cycles a sprite, and the
main loop copied the whole sprite table (544 bytes) into OAM in every vertical blank, whether a
game used sprites or not. ADR-6FMHDB6H replaced it by shadow state: the registers, the HDMA
channels and the sprite table in memory, committed by the kit after every tick, with no
`game_vblank`. Built and measured on 2026-11-25, that design made the vertical blank's rules
impossible to break, but every game paid for it: about 2,500 master cycles a tick of fixed work
(the marks, a second hash, the joypads' edges, an empty queue), and a game that already wrote
its sprite table itself and did its writes in `game_vblank`, as Blubber Blasen and Er-Tüp do,
got nothing for it: Er-Tüp became 3.8 % slower. The user's priorities are speed first and
usability second, with an easier layer for beginners on top, optional; and nobody knows what a
user wants to build, so the API must not force one way of working.

**Decision.**

- The core (`sdevkit/snes.h`) is the hardware as directly as C reaches it, and every write goes
  to the hardware at once: every PPU register by `sdevkit_reg_write` and
  `sdevkit_reg_write_twice` (dampf inlines them and folds the register's address: one store each),
  copies by DMA (`sdevkit_dma`, the general form, and `sdevkit_vram_copy`, `_cgram_copy`,
  `_oam_copy` from any byte, `_mode7_*_copy`, `sdevkit_vram_fill`), one colour by the CPU
  (`sdevkit_cgram_set`), HDMA (`sdevkit_hdma_set`, `sdevkit_hdma_enable`), and macros that build
  register values (`SDEVKIT_BGSC`, `SDEVKIT_OBSEL`, `SDEVKIT_OAM_ATTR`, ...). The PVSnesLib calls
  are gone.
- The frame rhythm keeps `game_init`, `game_tick(pad)` and `game_vblank`. Between them the kit
  only waits, reads the joypads, starts the sound effects after `game_vblank` and marks where a
  tick ends: no copy, no register, no hash of its own. The whole vertical blank is the game's.
- The desktop checks the vertical blank's rule at no cost on the SNES: a byte for VRAM, CGRAM or
  OAM written while the picture is drawn is lost (as before), and a PPU register or an HDMA
  channel written then is counted; the desktop's check fails on either. Switching the screen off
  is allowed at any time.
- Above the core, optional layers in portable C, each opt-in by calling it and costing only what
  it calls: `sdevkit/shadow.h` (ADR-6FMHDB6H's register shadow with its marks, committed by
  `sdevkit_shadow_commit`), `sdevkit/sprites.h` (a sprite list that copies only the entries in
  use), `sdevkit/queue.h` (copies queued in the tick, with a budget the SNES and the PC decide the
  same), `sdevkit/input.h` (the buttons pressed since the last tick), and `sdevkit/easy.h`, all of
  them and `sdevkit_commit()`, for a `game_vblank` of one line.

**Alternatives.**

- Keep ADR-6FMHDB6H's shadow state as the only way: safe for a game that does not know the
  rules, but a fixed cost for every game and no control over the vertical blank.
- Keep the PVSnesLib calls as the core: their setup calls and `sdevkit_oam_set` are the slow and
  rigid part, and every feature not built yet would need calls of its own.
- Registers as `volatile` lvalues in the header (`SDEVKIT_REG_BG1HOFS = x`): the desktop cannot
  see a store to memory, so Rachael could not model the write or check when it happens.

**Consequences.** Measured with dampf's emulator, which counts DMA's time since this change
(8 master cycles a byte), every example on the core does less work a tick than on the PVSnesLib
API (Mode 7 0.66, Mandelbrot 0.93, Steinschlag 0.94, Er-Tüp 0.98), the examples on the layers about
as much or less (Hello 1.00, Schach 0.99, CatchMe 0.90), 0.91 on the geometric mean; all draw the
same pictures, pixel for pixel (docs/optimizations-log.md). The kit's
convenience is opt-in: a game that writes the hardware itself must know the vertical blank's
rules, and the desktop's check tells it when it breaks one. The layers' commit has a worse worst
case than ADR-6FMHDB6H's, since the sprite list and the HDMA channels go through the core, so
the queue's budget is 3,072 bytes a frame (lib/README.md, "The commit's time"). Every example and
the game migrate; most of the game's calls keep their form (`game_vblank`, `sdevkit_reg_write`,
`sdevkit_hdma_set`, `sdevkit_dma_fits`), the copies change their argument order to
destination first. Supersedes ADR-6FMHDB6H; amends ADR-6F3CK2AR (the PVSnesLib form is gone, and
`tools/sdevkit_rename.py`, which converted into these names, is deleted), ADR-6F0R6NS5 and ADR-6F3CJX8T (the
main loop copies no sprite table). The NMI step of the interrupt design runs `game_vblank` again,
as planned before ADR-6FMHDB6H.
