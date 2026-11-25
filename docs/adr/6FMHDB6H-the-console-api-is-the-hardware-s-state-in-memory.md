# ADR-6FMHDB6H: The console API is the hardware's state in memory, which the kit commits in the vertical blank

**Context.** The console API was PVSnesLib's functions renamed (ADR-6F3CK2AR). On the hot path a
game paid for that shape: `sdevkit_oam_set` cost 217 to 329 cycles a sprite, so Er-Tüp wrote the
sprite table itself, and every register write went to the hardware at once, so a game had to
know that scroll, palette and DMA calls belong in `game_vblank`, a rule that broke only on the
console. The setup calls took eight positional parameters each, counted sprites in byte offsets
and switched layers on as a side effect. The features not built yet (modes 0 and 2 to 6, mosaic,
high resolution, indirect HDMA, the IRQ, more input devices) would each have needed new calls or
changed signatures. The brainstorm of 2026-11-24
([console-api-shadow-state_2026-11-24.md](../brainstorm/console-api-shadow-state_2026-11-24.md))
collected fifteen ideas, measured two, and recommended this one; the user took it on 2026-11-25
with speed first and usability second, compatibility with nothing, and growth by additions only
once the core stands.

**Decision.**

- A game changes the picture by writing memory laid out as the hardware is: `sdevkit_ppu`, the
  PPU's registers named after them (BG1HOFS is `sdevkit_ppu.hofs[0]`); `sdevkit_hdma[1..7]`, the
  HDMA channels' registers; `sdevkit_oam`, the sprite table in the hardware's format. The kit
  writes it into the hardware in the vertical blank after each tick (the commit), Rachael at the
  same point, and the checks hash it after every tick.
- The commit writes INIDISP every frame and every other register only when the tick marks its
  group in `sdevkit_marks` (scroll, colour math, setup with the layers, windows, mode 7, HDMA).
  Rachael finds a group changed without its mark and the desktop check fails on it, so a
  forgotten mark shows on the desktop; a mark costs a game one OR and spares the vertical blank
  every register the tick did not change.
- Copies to VRAM and CGRAM during play are queued (`sdevkit_queue_dma` and its helpers) as raw
  DMA descriptors and run in the commit. What fits is decided when the game asks, in bytes, the
  same on the SNES and the PC: 3,840 bytes a frame, 136 more for each copy's setup, at most 24
  copies; a copy that does not fit is refused, and the game asks again in the next tick.
- Writes that go to the hardware at once end in `_now` (`sdevkit_vram_now`, `sdevkit_color_now`,
  `sdevkit_screen_off_now`) and are for a screen that is off. `sdevkit_commit_now` commits once
  in `game_init`, for a picture before a long job.
- `game_tick` takes no parameter and reads the joypads from `sdevkit_input`; `game_vblank` is
  gone: a game has no code in the vertical blank.
- Above the core, helpers that cost little: `sdevkit_sprite`, a sprite list drawn anew each tick
  (the kit hides what the list filled the tick before and not in this one), and macros that
  build register values from constants (`SDEVKIT_BGSC`, `SDEVKIT_OBSEL`, `SDEVKIT_OAM_ATTR`).

**Alternatives.**

- Keep the calls and make them cheaper: the vertical blank rule stays, and every feature not
  built yet needs new calls.
- One frame struct named by meaning instead of by register: readable, but every field is a design
  that a later feature may not fit; the hardware's own layout cannot miss a field.
- Commit every register every frame: measured at 971 cycles, about 15 % of the vertical blank's
  DMA time, five times what a light game paid before.
- Sprites as parallel arrays packed by the kit: in C 36 % slower than writing the sprite table.
- A budget decided at commit time from the line counter, as `sdevkit_dma_fits` was: more bytes
  on a quiet frame, but the SNES and Rachael would take different copies.

**Consequences.** The commit takes about 260 cycles of the vertical blank on a frame with nothing
marked or queued. Its worst case (every group, seven HDMA channels, a full queue) ends on line
260 of 261 in Mesen, which is where the queue's budget comes from (lib/README.md). The fixed
budget is smaller than what the old adaptive check let through on a quiet frame. A queued copy
costs more than the old direct call (about 200 cycles in the tick and 150 in the vertical blank,
against 116), and the kit does more per tick than before (it hashes the registers too and works
out the newly pressed buttons): a small game pays a few hundred cycles a tick more, a frame with
many sprites saves (docs/optimizations-log.md has the measurements). The checks now
compare scroll, layers, colour math and the queue between the SNES and the PC, which they could
not see before. With the interrupt design of 2026-11-24 the NMI would run only the kit's commit,
so it needs no C context for game code; only the game's IRQ does. Every example and the game
migrate; `tools/sdevkit_rename.py` converts into names that are gone. Amends ADR-6F3CK2AR (the
callbacks a game defines, the PVSnesLib form), ADR-6F0R6NS5 and ADR-6F3CJX8T (what the main loop
does in the vertical blank) and ADR-6F60ZC7C (sound effects start after the commit, not after
`game_vblank`).
