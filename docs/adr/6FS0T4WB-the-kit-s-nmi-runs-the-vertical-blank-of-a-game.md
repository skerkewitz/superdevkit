# ADR-6FS0T4WB: The kit's NMI runs the vertical blank of a game that publishes

**Context.** The brainstorm of 2026-11-24 (`docs/brainstorm/interrupts-and-asm_2026-11-24.md`,
Part 1, idea 6) decided that the kit owns the NMI and runs the game's vertical-blank code in it, so
that a tick that overruns no longer delays the vertical blank: today `sdevkit__frame` in
`lib/snes/main.c` waits for the vertical blank after each tick and then calls `game_vblank`, so
while a tick overruns nothing writes the picture. With the game's IRQ (ADR-6FRX1GYC) that is
visible: the IRQ's writes stay in the registers, and the next frame starts with the scroll the IRQ
left there. The machinery is built: interrupt contexts (ADR-6FM9TXV4), copies per context
(ADR-6FQ4NSHE), the published data (ADR-6FPV1EQV, ADR-6FRDS030).

Code in the NMI runs in the middle of a tick, so it can read only data the tick published. Every
`game_vblank` today reads the tick's variables, and so do the optional layers (`sdevkit_commit`,
the queue, the shadow registers). The user decided on 2026-11-26 that only a game that publishes
gets its vertical blank run by the NMI; every other game, the examples and the layers keep
`game_vblank` as it is. ADR-6FRDS030 left one point open for this step: an NMI that comes while a
tick fills the free copy must not switch to that copy.

**Decision.**

- `SDEVKIT_PUBLISH(type, name, tick, vblank)` also defines the kit's NMI handler,
  `DAMPF_INTERRUPT(nmi) void sdevkit_publish_nmi(void)`: if a copy is ready, it makes it the
  current one, and then it calls the game's `vblank`. The macro's signature does not change.
- The macro's `game_vblank`, which the kit's loop calls at the start of the vertical blank after
  each tick, does on the SNES only this: it switches the NMI on with `sdevkit_publish_nmi_on()` the
  first time, and waits until the NMI has cleared "ready". On the PC, which has no NMI, it switches
  and calls `vblank` itself, as before. The kit's loops do not change.
- The choice between the two is a constant, not a platform `#ifdef` in a public header:
  `DAMPF_HAS_INTERRUPTS` in `<dampf.h>`, 1 with dampf and 0 on the PC. dampf drops the branch not
  taken, and the code generator from MIR leaves out the main code's copy of `vblank` that only the
  PC's branch called: `vblank` exists once in the ROM, in the NMI's context, with no frames in the
  main code's area.
- The open point of ADR-6FRDS030 is closed by the wait: the next tick begins only after the NMI has
  switched, so while a tick runs and fills the free copy, no copy is ready and the NMI switches
  nothing. A tick that overruns has the NMI run `vblank` again on the same copy, in every vertical
  blank.
- `sdevkit_publish_nmi_on`, declared in `sdevkit/publish.h` for the macro (a game does not call
  it), lives in `lib/snes/irq.c`, which owns NMITIMEN: two bytes keep the timer's bits and the
  NMI's bit, so `sdevkit_irq_line`, `sdevkit_irq_off` and the NMI's switch keep each other's bits
  and the joypads' automatic read. It is called in the vertical blank, where RDNMI's flag is set,
  so the NMI comes right after its write and serves that vertical blank. On Rachael it does nothing.
- The rules for `vblank`, in `sdevkit/publish.h`: it reads the game's data only through
  `SDEVKIT_PUBLISHED`, writes no variable the main code writes, uses none of the optional layers
  and no sound effects, and what it writes depends only on the published data, so running it again
  writes the same.
- The desktop checks the last rule for free. The build writes `sdevkit__game_nmi`, the macro's
  handler or NULL, beside `sdevkit__game_irq` (the generated file is now `game_interrupts.c`).
  With `--check`, the frontend runs the handler a second time after each `game_vblank`, in a
  vertical blank of its own, compares the picture's state (VRAM, CGRAM, OAM, the registers, the
  scroll, mode 7, the fixed colour, HDMA, the IRQ's row), counts every byte that changed
  (`repeated`, which fails the check) and puts Rachael back as she was.

**Alternatives.**

- Every game's `game_vblank` in the NMI. One way for all, but every game would have to publish
  what its vertical blank reads: every example and the optional layers would move to published
  data, and the game too. The user chose the opt-in.
- The kit's loop switching the copies and calling the NMI's work (the brainstorm's sketch): the
  loop cannot call code that only some games define (ADR-6FPV1EQV), and every game would pay.
- Copying in the vertical blank (the macro copies into a single published copy in `vblank`'s
  place): no race at all, but the copy takes the vertical blank's time, about 1,400 cycles for 128
  bytes (ADR-6FRDS030).
- A platform `#ifdef` in `publish.h` (`#ifdef __DAMPF__` around two versions of the macro): the
  public headers have none, and game code asks for features, not platforms (lib/README.md,
  "Rules"). `DAMPF_HAS_INTERRUPTS` asks whether handlers run.
- Deciding at run time whether the NMI runs (`sdevkit_publish_nmi_on` returning 0 on the PC):
  `vblank` would stay reachable from the main code, so dampf would compile it twice, in the main
  code's context and in the NMI's, with frames in both: ROM and RAM for nothing.
- `game_vblank` running `vblank` itself the first time, before the NMI is on: the same second copy
  of `vblank`. Switching the NMI on in the vertical blank lets the NMI serve the first one.
- The NMI checking that a tick is running and skipping `vblank` then (the brainstorm's idea 9, a
  "busy" flag): the frame would keep the IRQ's last writes, which is what this step removes.

**Consequences.**

- A game that publishes has its `vblank` run in every vertical blank on the SNES, also while a tick
  overruns; the picture keeps the last tick's scroll and splits. On the desktop and the web nothing
  changes: they model no overruns, and the checksums of the ticks are the SNES's.
- The cost on the SNES, from the timing report: the NMI takes 118 to 140 CPU cycles beyond
  `vblank` (the entry stub's 102, ADR-6FM9TXV4, the switch, 16 cycles with nothing to switch and 38
  with, and the call), in every vertical blank. The copy after the tick is as before. The NMI's
  direct page and frames take a page of RAM below the stack, which the main code's frames lose
  (`$1D00`; with the game's IRQ `$1C00` too). `game_vblank` in the main code is a wait, unbounded
  in the timing report. Keeping NMITIMEN's bits makes `sdevkit_irq_line` 57 cycles instead of 39
  and `sdevkit_irq_off` 35 instead of 20 (lib/README.md); a handler calls them after its writes,
  outside the horizontal blank's window.
- `vblank` multiplies in software, as all interrupt code, and the functions it shares with the
  main code are compiled once more (ADR-6FQ4NSHE).
- A game that does not publish compiles to the same ROM as before: all seven examples are byte for
  byte the same.
- `exec/published.c` runs ticks of which every fourth is longer than a frame, with the NMI running
  `vblank` and an IRQ on three lines: no torn data, no tick going back, the IRQ always reading the
  copy the NMI switched to last, and the NMI coming during ticks (and running `vblank` there) and
  outside them, in dampf's emulator and in Mesen, with both code generators. Without the wait in
  `game_vblank` the IRQ reads the wrong copy; with an NMI that runs `vblank` only when it switches,
  the NMI's runs during ticks are missing. `dev/irq_check` plays ticks longer than two frames (B
  held); its pictures on the SNES are the desktop's in Mesen, and its checksums the same on the
  SNES, the desktop and the web. Its shots show the first frame after each tick's switch, not a
  frame the NMI repeated: the repeat is covered by `exec/published.c` and by the desktop's check
  of the rule. A copy of `dev/irq_check` whose `vblank` writes a count of its own runs fails the
  desktop's check (`IrqCheckTest`).
- `dev/publish_check` moves its NMI check into `vblank`, and its own IRQ in the vertical blank
  writes the timer's registers itself after the kit switched the NMI on.
- The game-facing guide (`sdevkit/publish.h`) says what `vblank` may do. A game on the optional
  layers keeps `game_vblank`; the layers could publish their own state later (docs/roadmap.md).
