# ADR-6FVWK4JE: The build checks the IRQ's writes after the wait, less the HDMA the game declares

**Context.** The game's IRQ waits for the horizontal blank before its row and then writes PPU
registers, which show cleanly only if they land before the PPU fetches the row's first tiles
("The game's IRQ runs at a picture row", ADR-6FRX1GYC). Mesen measured the room: from the wait's
latest return, hClock 1,208, to about 1,366, 158 master cycles (about 25 CPU cycles) without HDMA
on that line. A write too late showed only in the Mesen check, as a torn row. With master cycles
in the timing ("Timing, assembly comments and budgets count master cycles", ADR-6FVMDV79) the
build can count the handler's way from the wait to its last write. HDMA runs at the start of the
same blank and delays the wait's return, but which channels run on the IRQ's line is decided at
run time: `sdevkit_hdma_enable` takes a variable, the shadow layer writes HDMAEN from memory, and
each channel's bytes depend on its mode and table. Measured in Mesen with `dev/irq_check`'s
handler and other tables, the wait returned 36 master cycles later with one channel of one byte,
50 with two, 84 with four, 108 with four writing 7 bytes, 42 with one of two bytes; a new entry
of the table on the line changed nothing there.

**Decision.**

- The timing report has a line for the game's IRQ handler: the master cycles from the end of its
  wait for the horizontal blank (the branch out of the loop that polls HVBJOY, where dampf inlined
  `sdevkit_irq_wait_hblank`, or the wait's `RTL`) to the end of its last write to a PPU register
  ($2100-$213F at a long address), the worst case over every way, calls included, and the room.
- The handler declares the HDMA on its line: `SDEVKIT_IRQ_HDMA(channels, bytes)` (sdevkit/irq.h),
  a statement in `game_irq` with two constants, the channels that may run and the most bytes they
  write on the line together. Declared more than once, the channels of all and the most bytes
  count. No declaration means no HDMA on the IRQ's line.
- dampf takes the declared HDMA's worst case from the 158: 18 master cycles once, 16 per channel
  (8 for the channel, 8 for a new entry's line count byte) and 8 a byte, the figures of fullsnes
  ("SNES Timings"), which lie above every Mesen measurement above. When the worst case after the
  wait is above the room, the build fails at the handler with the cycles, the room and the
  declaration; a loop between the wait and a write fails it too. A second wait on the way starts
  the count anew; work after the last write is free. With `--opt-limit` the errors are warnings,
  as for budgets.
- Rachael checks the declaration, like the kit's other stated facts: on the IRQ's line she compares
  the channels that run (one that reads its table's end too) and the bytes they write with the
  declaration of that call, and the desktop's check fails when more runs, naming the row and the
  declared and the running channels and bytes. A game that forgets or understates its HDMA fails on
  the PC.

**Alternatives.**

- The HDMA-free case only, with the margin left to the game: the first form of this decision,
  before the user's choice; unsound for a game with HDMA on the line.
- The worst case of all channels a game could switch on: seven channels with four bytes and
  indirect tables take far more than the 158 master cycles, so every handler would fail.
- The channels found in the program from constant arguments of `sdevkit_hdma_enable` and the
  modes of `sdevkit_hdma_set`: sound only where every write of HDMAEN is such a call, which the
  shadow layer and a variable argument break.
- A build option for the HDMA: the fact belongs to the game's code, next to the handler, and the
  desktop build, which checks it, would not see an option of dampf's.
- Keeping the Mesen check alone: it sees a torn row only in the frames and rows it shoots.

**Consequences.**

- A handler that writes too late is found at build time, its HDMA included; the desktop check
  makes the declaration a fact.
- `dev/irq_check` declares its one channel of one byte (42) and fits: 102 of 116. The same
  handler with a second scroll register written first (186) fails the build and tears row 72 in
  Mesen.
- Writes through a pointer and to registers outside $2100-$213F (the CPU's timer, NMITIMEN) are
  not counted: they do not tear the row, or dampf's code does not make them.
- `sdevkit_irq_hdma`, which the macro calls on the desktop, is a function of the console API that
  does nothing on the SNES; a direct call of it is not seen by dampf, so the documents name the
  macro.
