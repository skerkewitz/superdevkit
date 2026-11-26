# ADR-6FQ4NSHE: A function reached from several contexts is compiled once per context

**Context.** Interrupt handlers in C run in contexts of their own: the main code, the NMI handler
and the IRQ handler each have frames, a direct page and scratch registers apart from the others
(ADR-6FM9TXV4). That record made a function reached from two contexts an error and left copies
per context (the brainstorm's idea 2, `docs/brainstorm/interrupts-and-asm_2026-11-24.md`) until
a real program needed them. The game's IRQ needs them: a raster handler writes registers, and
the only way a game may write one is the console API (`sdevkit_reg_write`, ...), which the kit's
own main code calls too (the splash screen), and which a game calls in `game_vblank`. So a
`game_irq` that calls `sdevkit_reg_write_twice` was rejected, and so would be a call of the
function that sets the IRQ's line, from `game_init` and again from the handler. The only way
around was to write each function twice by hand, which `dev/publish_check` did with a macro.

**Decision.**

- A function that more than one context reaches is compiled once per context. Each copy has its
  own parameters and locals, so its frames lie in its context's area (the IRQ's and the NMI's
  pages, or the main code's frames), and the functions of a context call that context's copies.
  The copies are made in the AST before code is generated (`dampf.check.ContextCopies`), so both
  code generators compile them as ordinary functions.
- The original belongs to the first context that reaches it, in the order main code, NMI, IRQ,
  and keeps its name and label. A program without handlers, or whose contexts share no
  function, is not changed: the same code, the same symbols, the same ROM.
- A copy's label is two underscores, the context and the original's label: `__irq_clamp` for
  `_clamp`, `__irq_clamp__x` for its local `x`. The C name such a label would come from begins
  with an underscore, which C reserves at file scope, and a label that is taken anyway gets a
  number, as the front end numbers its own. Its name in messages and in the debug file is
  `clamp@irq`; Mesen's labels, the timing report and the code segments (`C__irq_clamp`) name
  each copy.
- Shared, not copied: globals, `static` locals, string literals and the constant templates of
  initialised local arrays. A `static` local of a copied function is one variable for all its
  copies.
- Each copy is a function of its own for everything after: its inlining decisions, its line in
  the timing report, its `DAMPF_BUDGET` (taken over from the original), and in an interrupt
  context the software multiply (ADR-6FM9TXV4).
- What stays an error: a function written in assembly (`DAMPF_ASM` or `--replace`) that an
  interrupt handler reaches, whether the main code reaches it too or not (its convention assumes
  the direct page 0 and the main code's frames, ADR-6FMATG2V); the entry function marked as a
  handler; a second handler of the same interrupt; a handler that C code calls; a handler that is
  not `void name(void)`.

**Alternatives.**

- Keep the error and give the console API a second set of names for interrupt code
  (`sdevkit_irq_reg_write`, ...): two names for every function a handler might need, and the
  game's own helpers still written twice by hand.
- Make the register functions macros on the SNES, volatile stores without a call: the first
  platform `#ifdef` in the public headers, and every other shared function, the kit's and the
  game's, still an error.
- Copies in MIR instead of the AST: the code generator from MIR only, while the naive one stays
  the reference both must match (ADR-6F0R6FT6).
- One copy for both interrupt contexts: the NMI can come while the IRQ handler runs and would
  overwrite the frames of the copy it interrupted.

**Consequences.**

- The ROM holds each shared function once more per extra context that reaches it. In
  `exec/context_copies.c`, where the main code, the IRQ and the NMI all call a chain of three
  helpers and a counter, each interrupt's copies take 554 bytes of code from MIR (the originals
  624; the copies are smaller, since they call the software multiply instead of the inline
  multiplier sequences) and 430 bytes from the naive code generator, the same as the originals;
  the constant table they read is in the ROM once.
- A copy costs no cycles of its own: its calls are the same calls. A copy in an interrupt
  context multiplies in software, as ADR-6FM9TXV4 says (in that test the helper that multiplies
  takes at most 545 cycles in the main code and 2,625 in the interrupts').
- An interrupt context's frames grow by the frames of its copies, and with them its pages below
  the stack.
- A global or `static` local that several contexts write is still the program's to guard: an
  interrupt between a read and a write of the main code loses the interrupt's write. The test
  calls the counter only while no interrupt is on.
- The examples' ROMs, `dev/publish_check`'s before its simplification and `BackendOutputTest`'s
  hashes are unchanged. `dev/publish_check` and `exec/published.c` now call one check function
  from both handlers.
- The game's IRQ can call the console API: the next step of M10.
