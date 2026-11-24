# ADR-6FM9TXV4: Interrupt handlers run in contexts of their own

**Context.** dampf gives every function's locals fixed places and lets functions that are never
active at the same time share them (ADR-6F0R668G), and the runtime's scratch registers
`dampf_regs` and `dampf_ptr` lie in the direct page at `$0000`. Code that an interrupt runs is
active at the same time as anything, so a handler written in C would overwrite live locals and
scratch registers. The CPU's multiplier cannot be saved at all, since its factors cannot be read
back. The brainstorm of 2026-11-24 (`docs/brainstorm/interrupts-and-asm_2026-11-24.md`, Part 1)
chose three of its ideas as the groundwork for C in interrupt handlers: interrupt contexts as
roots of the call graph (1), an entry stub that puts each interrupt on a direct page of its own
(3), and no hardware multiplier in interrupt code (4A). This record is that groundwork.

**Decision.**

- A function becomes a handler with `DAMPF_INTERRUPT(nmi)` or `DAMPF_INTERRUPT(irq)` from
  `<dampf.h>` in front of its definition. It must be `void name(void)`, there is at most one per
  interrupt, C code never calls it and the entry function cannot be one. On the PC the macro is
  empty, and the handler is an ordinary function.
- Each handler is the root of a context: the handler and every function it reaches. The main
  code is the entry function's context. A function reached from two contexts is an error that
  names both call paths. Copies of a function per context (the brainstorm's idea 2) are not
  built.
- Each context has a direct page of its own, made of whole pages right below the stack: the NMI's
  highest, then the IRQ's. The first 32 bytes of the page are the context's copy of the scratch
  registers, and its frames follow from byte `$20`, laid out by the main code's rule, so the leaves
  lie in the direct page. A context whose frames take at most 224 bytes takes one page (with two
  small handlers, `$1D00` for the NMI and `$1C00` for the IRQ). Direct-page operands are written `z:dampf_frame+n` in
  every context, relative to D; absolute operands and the addresses of locals use the context's
  own symbol, `dampf_frame_nmi` or `dampf_frame_irq`. The main code's frames end below the lowest
  page.
- The runtime has an entry stub per handler, assembled only into a program that has that
  handler. It jumps into bank `$80`, saves A, X and Y in 16 bits, the data bank and the direct
  page, sets the data bank to `$7E` and D to the context's page, acknowledges the interrupt (RDNMI
  read in 8 bits, TIMEUP), calls the handler and restores all of it before `RTI`. It takes 66
  cycles from the interrupt to the handler's first instruction (71 for the NMI) and 31 from the
  handler's return to the end of `RTI`. With an IRQ handler the start-up clears the I flag before
  it calls the entry function; no IRQ comes until the program switches the timer on.
- Code of an interrupt context never uses the CPU's multiplier. The runtime's `dampf_mul16_soft`
  and `dampf_mul32_soft`, which shift and add, take the place of `dampf_mul16`, `dampf_mul32` and
  the multiplication of two bytes without a call.
- The timing report gives each interrupt a line of its own (`dampf_irq`, `dampf_nmi`): the stub's
  cycles plus the handler's bounds.
- Both code generators do all of this. The naive one keeps no frames in low RAM, so its contexts
  take one page each.
- It is built in the compiler and not offered to games. A feature is usable only when the SNES
  and Rachael both have it (ADR-6FKTVDE8), and the kit's NMI, the data the tick publishes for the
  interrupts and Rachael's IRQ come in later steps. The kit and the examples switch no interrupt
  on.

**Alternatives.**

- A fixed name for the handlers (`game_irq`, and one for the kit's NMI) instead of a marker. It
  would turn the groundwork into an API for games before the kit's IRQ is designed; the marker
  belongs to the compiler, and the kit can name its functions later.
- One direct page for both interrupts. The NMI can come while the IRQ handler runs, and would
  overwrite its scratch registers and its frames in the direct page.
- Fixed pages, `$1C00` and `$1D00`, which would limit each context to 224 bytes of frames. Placing
  the pages by the size of the frames costs nothing and still gives a small handler one page.
- `SEI` and `CLI` around every use of the multiplier in the main code (the brainstorm's 4B). It
  costs every multiplication of the tick and delays the IRQ by up to the length of one.
- Copying the scratch registers to the stack in the stub: 32 bytes each way, more cycles than
  switching D.

**Consequences.**

- A program with handlers has less room for the main code's frames: `$0020`-`$1BFF` (7,136 bytes)
  with two small handlers, `$0020`-`$1CFF` (7,392) with one, instead of `$0020`-`$1DFF` (7,648).
- A handler multiplies about five times slower than the main code (`dampf_mul16_soft` 22 to 532
  cycles against 108), and the timing report shows it.
- A program without handlers compiles as before: the same code, the same runtime, the same ROM.
- An interrupt costs at least 97 cycles (IRQ) or 102 (NMI) on top of its handler, plus the end of
  the instruction it interrupted. An NMI that comes during the IRQ handler adds its own time to the
  IRQ's.
- dampf's emulator models the NMI and the timer IRQ. `exec/interrupts.c` runs interrupts in the
  middle of the main code's multiplications and checks the results and the lines the handlers run
  on, in dampf's emulator and in Mesen.
- Other hardware state that a handler and the main code might both use (the latched counters of
  the PPU, the VRAM address, DMA channels) is not protected. That is for the kit to settle with
  the published data and its NMI, the brainstorm's ideas 5 and 6.
