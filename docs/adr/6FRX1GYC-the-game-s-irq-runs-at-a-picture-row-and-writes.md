# ADR-6FRX1GYC: The game's IRQ runs at a picture row and writes after waiting for the horizontal blank

**Context.** dampf builds interrupt handlers in C with contexts of their own (ADR-6FM9TXV4), a
function they share with the main code is copied per context (ADR-6FQ4NSHE), and interrupt code
reads the data of the tick the picture was made from (ADR-6FRDS030). The brainstorm of 2026-11-24
(`docs/brainstorm/interrupts-and-asm_2026-11-24.md`, Part 1, idea 8) left open how the game marks
its IRQ, how it sets the line, and whether a horizontal position is offered. A feature is usable
only when the SNES and Rachael both have it (ADR-6FKTVDE8), and Rachael draws row by row, with no
time inside a row.

Measured in Mesen (hClock: master cycles within a scanline, 1,364 a line):

- A register written for picture row r (scanline r + 1) shows cleanly only if the write lands
  between hClock 1,108 of scanline r, after the last pixel of row r - 1, and about 1,366, when
  the PPU fetches the first tiles of the next row: 258 master cycles, about 41 CPU cycles. A
  later write leaves the row's first tiles with the old coarse scroll (5, then 12, then 19 pixels
  torn, one step every 30 master cycles).
- The entry stub takes 388 master cycles from its first instruction to the handler's, and the
  handler starts 470 to 550 master cycles after HTIME's dot, the IRQ's latency jittering by about
  50.
- Reading the published copy and one scroll value takes a handler about 33 CPU cycles before its
  first write, and the two writes of a scroll register 17. Timed so that its first write could not
  land before hClock 1,108, a handler had about 41 cycles to its last write, and a published
  scroll did not fit: `dev/irq_check` showed row 72 torn by 6 to 24 pixels in every shot.

**Decision.**

- The game's IRQ is `game_irq`, which the game defines as `DAMPF_INTERRUPT(irq) void
  game_irq(void)`: a fixed name, so that Rachael can call it, declared in the optional header
  `sdevkit/irq.h`. `superdevkit build` sees from the game's objects whether it defines it and
  generates `game_irq.c` beside them (`sdevkit__game_irq`, the handler or NULL), which the
  frontend hands to Rachael; C has no optional symbol that gcc, MSVC and emcc all take.
- `sdevkit_irq_line(row)` runs it before picture row `row` (0 to 223), every frame until
  `sdevkit_irq_off`; a row from 224 on switches it off. The handler may set the next row itself:
  below the current one it comes in the same picture, at or above it in the next. No horizontal
  position is offered. The kit owns NMITIMEN, VTIME and HTIME.
- The kit times the IRQ early in scanline r (VTIME r, HTIME 50), so that the handler has time to
  read its data and decide, and the handler then calls `sdevkit_irq_wait_hblank()` and writes. On
  the SNES the wait polls HVBJOY's horizontal blank bit, read as 16 bits with RDIO, which makes a
  loop of 12 cycles. The handler starts long after the blank at the start of the scanline has
  ended, so the wait cannot return on that one; it waits only for the bit to be set.
- Rachael calls `game_irq` before drawing row `row`, after that row's HDMA, as the SNES does. A
  register written in it before the wait counts as written while the picture is drawn, with its
  register and value, so a handler that forgets the wait fails the desktop check; after the wait
  the writes are free. VRAM, CGRAM and OAM stay closed: their bytes count as lost. An HDMA call
  in the handler counts as a write while the picture is drawn too.
- The numbers, measured in Mesen with `dev/irq_check` (HTIME 50, an HDMA channel of one byte on
  every line, and the same without HDMA):
  - the handler's first instruction comes at hClock 712 to 762;
  - its code before the wait has about 53 CPU cycles before the horizontal blank begins (338
    master cycles from the latest start; dampf's cycles are about 6.3 master cycles each there);
    `dev/irq_check`'s handler takes about 40, a plain published scroll about 33;
  - the wait returns at hClock 1,138 to 1,208 without HDMA, a jitter of one round of its loop,
    about 12 CPU cycles; one HDMA channel of one byte on the line delays it by about 6 cycles;
  - the writes after the wait must end by about hClock 1,366: about 25 CPU cycles from the wait's
    return without HDMA, about 19 with one HDMA channel of one byte. A scroll register takes 17.
- A handler whose code before the wait takes longer reaches the wait inside the blank, and the
  wait returns at once: its writes come late and may tear the row. One later still, after the
  blank, waits for the next row's blank: its writes show a row lower. The wait cannot tell
  either from a handler in time, and Rachael has no time to see it; the Mesen check
  (`dev/compare_example.sh`) shows it as a difference in the picture, and dampf's check of the
  IRQ window (M11 of the roadmap) is to catch it in the build.

**Alternatives.**

- A check in dampf instead of the wait: the kit times the IRQ to start before the blank, and the
  timing report finds the cycles from the handler's start to its first and last register write
  and fails the build when they leave the window. The guarantee at compile time is better, but
  it is compiler work in the timing (M11), and the cycles of a handler depend on where the IRQ
  interrupted, which the window has to absorb. It is planned on top of the wait: the cycles from
  the wait to the last write.
- Faster code only, without a wait: `xba` in `sdevkit_reg_write_twice` instead of a second load
  (7 cycles less) and no `cmp #$0000` after a load (3 less) would bring a published scroll to
  about 33 of the 41 cycles, tight and unchecked. The faster code is planned anyway (M12), for
  more room after the wait.
- `VTIME` and `HTIME` as they are, for the game to set: Rachael cannot draw a change in the
  middle of a row, so the picture would differ from the SNES's on the IRQ's row.
- The IRQ at the start of the line, `VTIME` only: its writes land in the middle of the row, which
  is torn on the SNES and clean on Rachael.
- A macro, `SDEVKIT_IRQ(handler)`, that defines `game_irq` around a static function of the game,
  as `SDEVKIT_PUBLISH` does for the tick: it hides the marker, but the handler is the game's own
  code, and a marker on its definition says so plainly.
- Weak symbols for an optional `game_irq`: gcc and emcc have them, MSVC only through
  `/alternatename`; the build already reads the game's objects.
- A wait that first waits for the bit to clear and then for it to set: it costs a round of the
  loop more, and a handler that starts in the middle of the scanline finds the bit clear anyway.

**Consequences.**

- Games can use the IRQ: a split screen, a status bar, a second scroll, the same on the SNES and
  on Rachael. `dev/irq_check` splits the screen twice with values that change every tick, with
  short ticks and long ones, and its pictures are Mesen's pixel for pixel; `IrqCheckTest` plays it
  on the SNES (dampf's emulator, and Mesen where it is), the desktop and the web, and
  `RachaelTest` checks that a handler without the wait fails the desktop check.
- An IRQ costs the entry stub's 97 cycles and the handler's, and its wait for the blank: about
  (1,100 - 712) / 6.3 = 60 cycles, less what the handler does before the wait.
- The handler has little room after the wait: one scroll register, or two one-byte registers;
  more goes in HDMA, or in a second IRQ row.
- A game that defines no `game_irq` and calls no `sdevkit_irq_*` compiles to the same ROM as
  before; its desktop build links a `game_irq.c` that names none.
- dampf's emulator models the timer's IRQ, but its horizontal position only roughly, so the
  timing numbers come from Mesen.
