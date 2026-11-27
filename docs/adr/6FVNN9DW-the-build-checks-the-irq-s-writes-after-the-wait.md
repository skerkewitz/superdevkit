# ADR-6FVNN9DW: The build checks the IRQ's writes after the wait, counted without HDMA

**Context.** The game's IRQ waits for the horizontal blank before its row and then writes PPU
registers, which show cleanly only if they land before the PPU fetches the row's first tiles
("The game's IRQ runs at a picture row", ADR-6FRX1GYC). Mesen measured the room: from the wait's
latest return, hClock 1,208, to about 1,366, 158 master cycles (about 25 CPU cycles) without HDMA
on that line; one HDMA channel of one byte took about 6 CPU cycles of it. A write too late showed
only in the Mesen check, as a torn row. With master cycles in the timing ("Timing, assembly comments
and budgets count master cycles", ADR-6FVMDV79) the build can count the handler's way from the
wait to its last write. HDMA runs at the start of the same blank and delays the wait's return, but
which channels run on the IRQ's line is decided at run time: `sdevkit_hdma_enable` takes a
variable, the shadow layer writes HDMAEN from memory, and each channel's bytes depend on its mode
and table.

**Decision.**

- The timing report has a line for the game's IRQ handler: the master cycles from the end of its
  wait for the horizontal blank (the branch out of the loop that polls HVBJOY, where dampf inlined
  `sdevkit_irq_wait_hblank`, or the wait's `RTL`) to the end of its last write to a PPU register
  ($2100-$213F at a long address), the worst case over every way, calls included.
- Above 158 master cycles the build fails, with an error at the handler that gives the cycles and
  says that the count is without HDMA on the line. A loop between the wait and a write fails the
  build too, since no bound can be shown. A second wait on the way starts the count anew; work
  after the last write is free. With `--opt-limit` the errors are warnings, as for budgets.
- HDMA is not counted: the check is the HDMA-free case. The report and the error state it, and
  the documents say how much a channel takes (about 34 master cycles or more: 18 once a line, 8 a
  channel, 8 a byte, more for a new entry or indirect tables).

**Alternatives.**

- The worst case of all channels a game could switch on: seven channels with four bytes and
  indirect tables take far more than the 158 master cycles, so every handler would fail.
- The channels found in the program: a union of the constant arguments of `sdevkit_hdma_enable`
  and the modes of `sdevkit_hdma_set`. Sound only where every write of HDMAEN is such a call with
  a constant, which the shadow layer and a variable argument break; left open.
- A statement by the game of the HDMA on the IRQ's lines (a hint such as `SDEVKIT_IRQ_HDMA(n)` or
  a build option): exact and simple, but something new that users write; left open for the user
  to decide.
- Keeping the Mesen check alone: it sees a torn row only in the frames and rows it shoots.

**Consequences.**

- A handler that writes too late is found at build time, without HDMA on its line; with HDMA
  the game keeps the margin itself, and the Mesen check stays the proof for that case.
- `dev/irq_check`, whose handler writes one scroll register (102 master cycles) with one HDMA
  channel of one byte on every line, fits; three scroll registers after the wait do not.
- Writes through a pointer and to registers outside $2100-$213F (the CPU's timer, NMITIMEN) are
  not counted: they do not tear the row, or dampf's code does not make them.
