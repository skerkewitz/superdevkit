# ADR-6FRDS030: Interrupt code reads the copy of the tick whose vertical blank came last

**Context.** Interrupt code reads the game's data through a copy that `SDEVKIT_PUBLISH`'s
`game_tick` made right after each tick (ADR-6FPV1EQV). That record expected the game's IRQ to read
the data of the tick before on the SNES and on the desktop alike, and saw a difference only for a
tick that overruns. Planning the IRQ step showed another one, in the common case.

On the SNES the kit's loop (`lib/snes/main.c`) runs `game_tick`, then `sdevkit__frame`: it waits
for the vertical blank and calls `game_vblank`, which writes the picture of that tick. That
picture is drawn while the next tick runs. So an IRQ in the picture of tick N reads tick N's copy
only until tick N+1 ends and copies; a short tick ends early in the picture (hello's takes half a
frame), and every IRQ below that point reads tick N+1's data. On the desktop the frontend
(`frame_step` in `lib/desktop/main.c`) runs `game_tick`, `game_vblank` and then draws the picture,
calling the IRQ while it draws: the IRQ always reads tick N. A split that uses the published data
would be a frame ahead of the rest of the picture on the SNES, depending on how long the tick
took, and the same on the desktop never: the picture would differ between the two whenever the
data changes every tick.

Moving the copy within `game_tick` cannot fix it. Copied at the start of the next tick, the SNES
reads tick N, but the desktop draws before any code of the next tick runs and would read tick
N-1. Only `game_vblank` or the kit's loop runs between a tick and its picture on both.

**Decision.**

- Two published copies, `sdevkit_published_<name>[2]`, and two variables:
  `sdevkit_publish_side`, the copy interrupt code reads (the current one; 16 bits, so that
  interrupt code reads it with one load and no masking, 9 cycles less than a byte), and
  `sdevkit_publish_ready`, a byte, set when the other copy holds a tick that no `game_vblank` has
  switched to yet. The flag `sdevkit_publishing` goes.
- `SDEVKIT_PUBLISH(type, name, tick, vblank)` takes the game's vertical blank too and defines both
  callbacks:
  - `game_tick` calls `tick(pad)`, copies `name` into the copy that is not the current one, and
    then sets "ready" (a volatile write);
  - `game_vblank` makes that copy the current one if "ready" is set, by one write of
    `sdevkit_publish_side`, clears "ready" (both volatile), and then calls the game's `vblank()`.
- `SDEVKIT_PUBLISHED(name)` reads `sdevkit_publish_side` volatile and gives a pointer to the
  current copy.
- Interrupt code then reads the data of the tick whose `game_vblank` came last, which is the tick
  the picture on the screen was made from, on the SNES with any tick length and on the desktop and
  the web. Nothing interrupt code reads is ever written while it could read it: the tick fills the
  copy that is not current, and the switch is one byte.
- The kit's loops do not change: a game that does not include `sdevkit/publish.h` compiles to the
  same code as before (the examples' ROMs are byte for byte the same).

**Alternatives.**

- The switch in the kit's loop (`sdevkit__frame` and `frame_step`) with a byte the kit owns: the
  same effect without the game's `vblank` in the macro, but every game would pay for it, with a
  write each frame, and every example's ROM would change.
- The copy in the vertical blank (the macro wraps `game_vblank` and copies there): one copy is
  enough, but the copy takes the vertical blank's time, about 1,400 cycles for 128 bytes of the
  6,000 to 8,000 the vertical blank has for DMA and register writes.
- Leaving it as it was, and documenting that the IRQ reads the newest tick: the SNES and the
  desktop would differ whenever the tick ends above the IRQ's line, so Rachael would no longer
  show the SNES's picture, and a split would move against the rest of the picture by a frame now
  and then.

**Consequences.**

- RAM: a second copy of the struct and three bytes instead of one.
- Cycles, from the timing report of a tick that changes one word, against the same game without
  the macro: `game_tick` takes 411 cycles more for a struct of 32 bytes, 1,419 for 128 and 5,451
  for 512, 22 more than the one copy with the flag; `game_vblank` takes 16 more when no copy is
  ready and 38 when it switches.
- `exec/published.c` runs ticks and `game_vblank` back to back while an IRQ and the NMI check the
  published data: no torn data, the tick's number never going back, and during a tick always the
  copy of the tick whose `game_vblank` came last, in dampf's emulator and in Mesen, with both code
  generators. With `game_tick` filling the current copy instead, the interrupts find torn data and
  the wrong tick. `dev/publish_check` gives the same checksum after every tick on the SNES (dampf's
  emulator and Mesen), the desktop and the web. `PublishedDataTest` checks the order of the two
  bytes in dampf's code.
- The game's IRQ (the next step) shows the same picture on the SNES and on Rachael when the
  published data changes every tick.
- Open for the NMI step. Once the kit's NMI runs `game_vblank`, it can come in the middle of a
  tick. If the tick has already chosen the copy to fill (the one that is not current), and the NMI
  then switches to it because the tick before left "ready" set, the tick goes on to fill the copy
  that interrupt code now reads. The NMI step must close that, for example by having `game_tick`
  choose the copy only after "ready" is clear (a tick that finds it set waits for the switch or
  leaves the copy for the next tick), or by having the NMI switch only between ticks, with a byte
  the loop sets while a tick runs. It is not built now: no NMI runs `game_vblank` yet.
