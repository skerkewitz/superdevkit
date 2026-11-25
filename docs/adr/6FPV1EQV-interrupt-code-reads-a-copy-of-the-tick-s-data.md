# ADR-6FPV1EQV: Interrupt code reads a copy of the tick's data that the kit makes after each tick

**Context.** Interrupt handlers in C run in contexts of their own (ADR-6FM9TXV4). The brainstorm
of 2026-11-24 (`docs/brainstorm/interrupts-and-asm_2026-11-24.md`, Part 1, idea 5) decided that
interrupt code only reads data the tick prepared, and sees it only complete: never a 16-bit value
with its low byte new and its high byte old, never half of a palette. Interrupts write no game
state, so the checks' checksums per tick stay as they are. Four things were open: the form of
the API; the "classic gotcha" of a double buffer, whose back copy is a frame old after a swap, so
a tick that changes only part of it publishes stale values; a barrier the optimizer respects, so
that every store of the tick is in memory before the swap; and how the desktop and the web, which
have no interrupts, do the same (ADR-6FKTVDE8).

Two facts decided much of it. The kit's loop (`sdevkit__frame` in `lib/snes/main.c`, `frame_step`
in `lib/desktop/main.c`) cannot call code that only some games define: C has no optional symbol
that dampf, gcc, MSVC and emcc all take, so a call there would need a switch in the build
(`superdevkit.toml`, a dampf option, defines for the kit's files) or would change the code of
every game, also of the examples, which have no interrupts. And dampf today moves no store and
removes none: `Schedule` moves only loads and pure instructions, and no load past a volatile
access, a call or a store that may overlap it; `Gvn` reuses loads but no volatile one, and
removes no store; the LIR's `DeadStores` removes only stores to a function's own value places;
`Licm` hoists no volatile load. gcc does remove a store that a later one to the same byte
overwrites with nothing between them that reads it.

**Decision.**

- The API is a header of its own, `sdevkit/publish.h`, opt-in as the layers are (ADR-6FNPPCSX):
  nothing in the core changes, and a game that does not include it pays nothing.
- A game declares once, in one of its C files, `SDEVKIT_PUBLISH(type, name, tick)`: it defines
  the struct `name` (the tick's own, an ordinary variable at a fixed address), its published copy
  `sdevkit_published_<name>`, the byte `sdevkit_publishing`, and `game_tick`, which calls the
  game's `static void tick(const uint16_t pad[SDEVKIT_PADS])` and then publishes. The game then
  writes no `game_tick` of its own. `SDEVKIT_PUBLISH_DECLARE(type, name)` declares both structs
  for its other files.
- Publishing is three writes in `game_tick`, right after the game's tick: `sdevkit_publishing = 1`,
  the struct copied into the published copy, `sdevkit_publishing = 0`. Interrupt code reads
  `SDEVKIT_PUBLISHED(name)`, a pointer to the published copy, or to the tick's own struct while
  the flag is set: the copy is half old and half new then, and the tick's struct is complete,
  since the tick has ended and nothing writes it until the next. The macro is a macro, so no
  function is reached from two contexts.
- Nothing is published stale: the tick writes its own struct as any variable, what it does not
  change keeps its value, and the copy takes all of it every tick. The game need not write all
  of it every tick.
- The barrier: both writes of the flag are volatile, and so is its read in `SDEVKIT_PUBLISHED`.
  dampf keeps every store in the program's order and never reuses or hoists a volatile load, so
  the tick's stores are in memory before the flag is set, the copy is complete before it is
  cleared, and an interrupt reads the flag anew. The volatile writes change nothing in dampf's
  code today (the ROM is the same without them); they are what C requires, and gcc drops the
  first write without them. `PublishedDataTest` checks the order in dampf's code for
  `game_tick`, so a later pass that moves or drops a store fails it.
- RAM: the published copy, as many bytes as the struct, and the flag's byte. The game chooses the
  size with its struct. The tick's own struct is the game's data, which it has anyway.
- The desktop and the web run the same `game_tick`, compiled from the same macro, at the same
  point: after the tick, before the vertical blank. Their checksums per tick are the SNES's.
- Not for games yet. The interrupts that read the data, the game's IRQ (with Rachael calling it
  at its line) and the kit's NMI running `game_vblank`, come next. The game-facing guides say
  nothing of it.

**Alternatives.**

- The swap in `sdevkit__frame` and `frame_step`, as the brainstorm sketched, with a switch in
  `superdevkit.toml` that reaches the kit's files on every target (`--sram` does that for save
  RAM). A setting in the project file for a feature declared in C, a dampf option for a kit
  feature, and two places a game must keep in step. The macro gives the same point after the
  tick from one place, and the same C on every platform.
- A double buffer whose two copies take turns, with an index the kit flips in one write: the
  tick reaches its data through the index or a pointer, which costs every access in the hot code,
  and the back copy is stale after a swap. Then either the kit copies front to back after every
  swap, which costs the same copy as this decision, or the rule is "write all of it every tick",
  which publishes stale values silently when a tick does not, on the SNES only, where no check
  sees it.
- Copying with interrupts off (`SEI` and `CLI` around the copy), without a flag: delays the IRQ by
  the copy, 1,400 cycles for 128 bytes, about seven lines of the picture, which breaks a split.
- Two published copies and the tick's own (three in all), with an index the kit flips: interrupt
  code never reads the tick's struct, at the price of another copy of the RAM, and the same copy
  after every tick.
- A copy with `MVN`, 7 cycles a byte instead of about 10.5: dampf's struct copy is a loop through
  two long pointers. A faster copy belongs to the fine tuning (M12) and would change every
  program's struct copies, so it is not part of this step.

**Consequences.**

- A tick that publishes costs about 55 + 10.5 cycles per byte more (from the timing report:
  32 bytes 389 cycles, 128 bytes 1,397, 512 bytes 5,429; a frame is about 50,000 CPU cycles).
  Interrupt code reads through a 4-byte pointer, a few cycles more per access than a variable.
- A game that does not include `sdevkit/publish.h` compiles to the same code as before; the
  examples' ROMs are unchanged.
- `exec/published.c` runs the tick and the copy back to back while an IRQ, on three lines in
  turn, and the NMI check the published data (a checksum, a 16-bit value whose bytes change
  together, the tick's number never going back), in dampf's emulator and in Mesen, with both code
  generators: none found torn data, and some came while the kit copied. Without the flag's first
  write all of those found torn data. `dev/publish_check` plays the same on the kit's frame
  rhythm, with the IRQ on every line of the tick in turn, and gives the same checksum after every
  tick on the SNES, the desktop and the web (`PublishedDataTest`).
- The scans of the public headers (`LibNamesTest`, `LibDocTest`, and the desktop's symbol check,
  `superdevkit.project.Symbols`) skip a macro's body: its declarations are the game's, made by the
  macro.
- The game's IRQ on the desktop (Rachael calling it at its line, the next step) reads the data the
  tick just published, which on the SNES an IRQ during the next tick reads too. An IRQ that comes
  before an overrunning tick has ended reads the data of the tick before; the desktop does not
  model overruns.
- When the kit's NMI runs `game_vblank` (the step after), the data `game_vblank` reads must be
  published too; whether every game then publishes, and whether the kit's layers publish their
  own state the same way, is decided there.
