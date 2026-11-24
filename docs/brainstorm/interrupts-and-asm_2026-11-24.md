# Brainstorm: C in interrupt handlers, and hand-written assembly with the MIR code generator

2026-11-24. Two items of M10 ([roadmap.md](../roadmap.md#m10-language-platform-and-usability-0100))
that needed a design before code. Nothing was changed during the session; the facts about the code
below were read from it.

# Part 1: C in interrupt handlers

## Problem

A superdevkit game cannot run code on the hardware's timing, only in its own loop. Today the NMI
and IRQ vectors in `runtime.s` return at once; `sdevkit__frame` in `lib/snes/main.c` polls for
the vertical blank after each tick and then calls `game_vblank`. A tick that runs longer than a
frame makes the whole frame wait (the game runs at 30 frames a second, correctly).

Two needs, both wanted:

- **Every frame**: work in each vertical blank even when a tick overruns (uploads, scroll, palette
  fades, later feeding a sound player).
- **Mid-screen**: work at a chosen scanline through an IRQ (a status bar under a scrolling area,
  effects HDMA cannot do).

Settled in the session:

- **The kit owns the NMI.** The game writes no NMI handler; the kit runs the frame's fixed work in
  it and calls game code from it in a way the kit defines.
- **The game owns the IRQ**, written in C.
- **Interrupt code shares data with the tick, read-only**: it reads what the tick prepared and
  sees it only complete (never a 16-bit value with its low byte new and its high byte old). It
  writes no game state.

Constraints: every result matches the PC bit for bit; the checks' checksum after every tick stays
comparable between the SNES, the desktop and the web; a feature is usable only when the SNES and
Rachael both have it (ADR-6FKTVDE8), so the desktop needs an equivalent; interrupt code must not
trample what the interrupted code uses.

Why C cannot run in an interrupt today, four kinds of shared state:

- **Frames.** dampf gives every function's locals fixed places, and functions never active at the
  same time share them (ADR-6F0R668G). Code an interrupt runs is active at the same time as
  anything, so it would overwrite live locals.
- **The runtime's scratch registers** `dampf_regs` and `dampf_ptr` at `$0000-$001F`.
- **The hardware multiplier.** The runtime multiplies on it, and its inputs cannot be read back,
  so it cannot be saved and restored: an interrupt that multiplies between a write and the read
  corrupts the interrupted product.
- **The CPU state**: register widths, the data bank (`$7E`) and the direct page register (0).

## Who is affected

- **A beginner** never wants to hear the word interrupt. A kit that owns the NMI and keeps
  `game_vblank` changes nothing for them, except that a slow tick no longer stalls the screen.
- **An experienced SNES developer** (from PVSnesLib or assembly) expects to install handlers. They
  get the IRQ; the NMI stays the kit's.
- **The desktop and the web** have no interrupts. Rachael and the frontend call things after the
  tick, so every interrupt feature needs a desktop equivalent the checks can compare.
- **The checks** compare a checksum per tick; anything an interrupt changes mid-tick would break
  them, which the read-only rule prevents.

## Background: how dampf lays out locals

`dampf.backend.FrameLayout` builds a compiled stack: every function's locals have fixed addresses
from `$0020` (`dampf_frame`), a function's frame lies above the frames of all it calls, so the
leaves land in the direct page (below `$0100`, one byte shorter to address), and functions never
active at the same time share addresses. The call graph has one root, `main`, which reaches
`game_init`, `game_tick` and `game_vblank` through the loop.

Checked after the ratings, by reading the back end:

- The scratch registers are in the `ZEROPAGE` segment and every access to them, in the runtime and
  in both code generators, is a direct-page access (`[dampf_ptr],y` included). Code that runs with
  another direct page register gets its own copy of all 32 bytes without any change.
- The one assumption of direct page 0 is the frames' direct-page addressing: a frame place below
  `$0100` is written `z:dampf_frame+n`, correct only because D is 0. The decision hangs on
  `Selection.FRAME_START` and the symbol `dampf_frame`, used in `Selection`, `PlaceColouring`,
  `MemorySelection`, `SelectionPlan`, `MirCodeGen` and `Peephole` (which already takes the frame
  start as a parameter).
- The naive code generator gives each function its own static frame from `$2000` and never shares
  one, so it is safe for frames except for a function reached from two contexts.
- The hardware multiplier is used in two places of `ArithSelection` and in the runtime's
  `dampf_mul16`.
- An interrupt's own direct page needs a page of RAM. `$1E00-$1FFF` is the stack, so the page comes
  from the frame area, which shrinks by 256 bytes (from about 7.6 KB).

## Ideas

**1. Interrupt contexts as extra roots of the call graph.** dampf treats the NMI's code and the
game's IRQ function as two more roots and gives every function they reach frames in an area of its
own. The call graph gets three roots, main, NMI and IRQ (an NMI can interrupt an IRQ, so they need
separate areas too), and `FrameLayout` lays out each context in its own range, so no interrupt
frame overlaps a main frame. The game marks its IRQ function (a fixed name `game_irq` or a marker).
A function reached from two contexts is rejected with a clear error, or copied (idea 2). *Why:*
exact and static, costs the main code nothing, and stays inside ADR-6F0R668G.

**2. One copy of a shared function per context.** A function that both the tick and an interrupt
call is compiled twice, each copy with frames in its context (`clamp_main`, `clamp_irq`), callers
pick their context's copy; the kit's `sdevkit_*` functions an IRQ calls are copied the same way.
*Why:* game authors write normal C and never meet the error of idea 1; only the ROM pays.

**3. An entry stub that saves the CPU state, with the interrupt on its own direct page.** A short
assembly entry pushes A, X and Y in 16 bits, P, the data bank and the direct page register, sets
the data bank to `$7E` and the direct page to a page of the interrupt's own. Every access to the
scratch registers then hits the interrupt's own copy; the interrupt context's frames are laid out
relative to its direct page, with the same rule as the main code (leaves in the direct page).
`optimizations.md` notes the idea. *Why:* about 12 instructions instead of copying 32 bytes, and
the scratch registers are solved for good.

**4. A rule for the multiplier.** A: in interrupt contexts dampf multiplies in software. B: the
main code wraps each multiplier sequence in `SEI`/`CLI`. *Why:* the multiplier is the one state
that cannot be saved; either closes the hole, and the timing report shows the cost.

**5. The tick publishes, interrupts read the published copy.** The data interrupts read is a
double buffer the game declares once (for example `SDEVKIT_SHARED(struct frame_regs)`); the tick
writes the back copy, interrupts read the front copy, and the kit swaps them with one write right
after the tick (on the desktop at the same point). *Why:* the "read-only, complete" guarantee is
the kit's, not each game's discipline, and the per-tick checksums stay as they are.

**6. The kit's NMI runs what today's loop does after the tick.** The upload of the sprite table,
`game_vblank` and the sound move into the NMI and run on the published data; the loop starts the
next tick as soon as the last one ends. If a tick overruns, the NMI still uploads the last
published frame, keeps scroll and fades going and feeds the sound. `game_vblank` becomes code of
the NMI context. *Why:* frame-exact work a slow tick cannot delay; a beginner still writes
`game_vblank` as before.

**7. No C in the NMI: the tick fills a queue the NMI works through.** The NMI is hand-written
assembly that executes a published list of DMA transfers and register writes; the kit offers calls
such as `sdevkit_queue_vram` and `sdevkit_queue_reg`, and `game_vblank` disappears. *Why:* the
"vblank queue" of many commercial games: the fastest NMI, no compiler change for it, deterministic
by construction; only the IRQ needs C.

**8. Rachael calls the IRQ at its line while she draws.** Rachael already draws line by line (row y
as scanline y + 1, HDMA per line); when the game has set an IRQ line she calls `game_irq()` there,
compiled by gcc, and draws on with what it wrote. Since the IRQ only reads published data, it does
not matter that the tick has already finished on the desktop. *Why:* the rule that both platforms
have a feature is met, and a split screen looks the same on both, pixel for pixel.

**9. Wild: the whole game runs inside the NMI.** As in many NES games, the main loop does nothing;
the NMI does the upload, then the tick, and a "still busy" flag makes an NMI that arrives during an
overrunning tick do only the upload. *Why:* no second main-context root, the vertical blank is
always served first, a proven pattern on 6502 machines.

**10. Lazy: interrupts only while the loop waits.** NMI and IRQ are enabled only inside
`sdevkit__wait_vblank`, when no game code runs, so no contexts are needed. An overrunning tick
still delays the NMI, and no IRQ can fire during the tick, which is when the picture is drawn.
*Why:* almost free; shows what the cheap baseline buys.

From other people's tools: PVSnesLib has `nmiSet(handler)` and a compiler with a real stack, so C
in an interrupt works at the price of slower code everywhere; cc65 users (NES, C64) must save the
zero page "registers" in a C interrupt handler, which cc65's documentation warns about; libSFX
(assembly) offers a vertical-blank hook and a transfer queue, much like idea 7.

## Ratings

| Idea | Realistic? | Cost (time) | Fragility | Fully solves it? | Works for others? | New problems | Reversible? | Fits the plans? |
|---|---|---|---|---|---|---|---|---|
| 1. Contexts as roots | Yes: `dampf.check.CallGraph` and `FrameLayout` exist; this adds roots and ranges. | 1-2 weeks: layout, the checker's errors, timing per context, tests. | Medium: the frame RAM holds three areas; only a context with its own direct page has its leaves there (3). | Frames only; the rest needs 3, 4 and 5. | Yes, plain C for all. | More frame RAM; the error "reached from two contexts" unless 2 is built. | Medium: a new kind of root touches the back end. | Yes: what ADR-6F0R668G's fixed frames imply; optimizations.md sketches it. |
| 2. A copy per context | Yes. | Days. | Low, but Mesen and the timing report show two functions per name. | Removes the only error 1 leaves. | Good for beginners, who never meet the error. | Larger ROM; "which copy am I in" when debugging. | Easy. | Neutral. |
| 3. Entry stub, own direct page | Yes (checked: the scratch registers need no change, the frames' direct-page addressing hangs on one constant and one symbol in six places). | A few days. | Low. | The CPU state and the scratch registers, completely. | Yes. | About 40-60 cycles at entry and exit, which a raster IRQ's timing must include; the frame area shrinks by 256 bytes. | Easy. | Fits; no prerequisite (the M10 item "SNES facts out of the code generators" is not needed first). |
| 4A. No hardware multiply in interrupt code | Yes: a flag per context in `ArithSelection` and the runtime's call. | Hours. | Low. | Yes. | Fine: raster IRQs rarely multiply. | A software multiply in interrupt code is about ten times slower; rare, and the timing shows it. | Easy. | Fits. |
| 4B. `SEI`/`CLI` around multiplies | Yes. | Hours. | Low. | Yes. | No for raster effects: every multiply of the tick delays the IRQ by up to about 15 cycles, visible jitter on a split line. | Costs every multiply in hot code, visible in the benchmarks. | Easy. | Against the speed goals. |
| 5. Tick publishes, interrupts read | Yes: kit code plus a macro. | Days. | The classic gotcha: after a swap the back copy is a frame old, so a game that updates only part of it publishes stale values. The kit copies front to back, or the rule is "write all of it every tick". | The read-only, complete guarantee, fully. | Veterans know double buffering; beginners need one clear rule. | Twice the RAM for shared data. | Medium: once games use it, it is an API. | Fits: the per-tick checksums stay untouched. |
| 6. The NMI runs the frame work | Yes, once 1, 3, 4A and 5 exist. | Days on top of those; the loop and the checks change. | Medium: `game_vblank` becomes interrupt code and cannot call tick helpers unless 2 exists; the checks count ticks by a mark the loop writes, and the Mesen check's frames-per-tick figure changes meaning. | The NMI part, fully. | Beginners: nothing changes; veterans: what they expect. | An overrun no longer drops cleanly to 30 frames a second, the screen keeps the last published frame; the examples' recorded checks may need new recordings (not checked). | Hard once games rely on it. | Fits. |
| 7. NMI as an assembly queue | Yes, no compiler change for the NMI. | About a week: the API, the NMI routine, Rachael's side. | Low. | The NMI part; nothing for the IRQ. | Veterans like it; beginners lose the simple `game_vblank`. | Every example and Blubber Blasen move off `game_vblank`; the queue's size and the DMA budget need limits. | Hard: it replaces an API every game uses. | Allowed before 1.0, but a big break for the game. |
| 8. Rachael calls the IRQ at its line | Yes: she draws line by line, with HDMA per line. | Days. | Medium: only the line start is modelled, not an IRQ at a horizontal position mid-line; either IRQs are limited to the vertical position, or the horizontal timing is modelled. | The desktop side of the IRQ, needed whatever else is chosen. | Yes; the desktop check can compare split screens. | — | Easy. | Required by ADR-6FKTVDE8 for any IRQ feature. |
| 9. Whole game in the NMI | Yes. | Days. | High: a missed re-entry guard corrupts everything; the IRQ during the tick is an interrupt in an interrupt. | Same as 6. | NES veterans like it; anyone debugging in Mesen sees one giant NMI. | Harder debugging, odd timing reports. | Medium. | No advantage over 6. |
| 10. Interrupts only while waiting | Yes. | Hours. | Low. | No: no IRQ during the tick, no protection against an overrun. | Only games with short ticks. | — | Easy. | Only as a stepping stone. |

## Recommendation

For the IRQ: **1 + 3 + 4A + 5 + 8**, the minimum that is correct and checkable: the game's IRQ is a
root with its own frames, an entry stub saves the CPU state and switches to the interrupt's own
direct page, interrupt code never uses the hardware multiplier, interrupts read only the published
copy, and Rachael calls the IRQ at its line (line starts only).

Then **6**, the NMI on the same machinery, rather than the queue (7): the machinery exists by then,
`game_vblank` stays, and no game migrates.

**2** waits: a function reached from two contexts gets a clear error, and copies come when a real
game needs them. The error shows whether they are needed at all.

Order: 1, 3 and 4A; then 5; then 8; then 6.

Trade-off: RAM for a third frame area, the interrupt's direct page and the double-buffered data;
the rule "write the whole published struct every tick" or a kit copy that costs cycles; and for the
experienced developer, the NMI stays the kit's.

## Decision

Taken as recommended (2026-11-24).

## Open questions

- How the game marks its IRQ function (a fixed name `game_irq` or a marker) and sets its line;
  whether an IRQ at a horizontal position is offered (then Rachael must model it).
- The published buffer's rule: the kit copies front to back after a swap (cycles) or the tick must
  write all of it (a rule to check, perhaps in the desktop check).
- How the checks count ticks and frames once the NMI runs the frame work, and whether the examples'
  recorded checks stay valid (find out by running them on a prototype).
- Which page the interrupts' direct page takes, and whether NMI and IRQ need one each.
- What `game_vblank` may call once it is NMI code, before copies (2) exist.

# Part 2: hand-written assembly with the code generator from MIR

## Problem

Game authors and dampf's reference benchmarks can both replace a C function with hand-written 65816
assembly in a build with the code generator from MIR, the default. Today `--replace name=file.s`
works only with the naive code generator, whose convention is fixed: each parameter in a named
static place (`_<function>__<param>`), the result in A (and X for 32 bits), `JSL`/`RTL`, A, X and Y
in 16 bits, the data bank `$7E`, the direct page 0, X, Y and `dampf_ptr` free to change.

Settled in the session:

- **Both users**: game authors' hot routines and dampf's reference benchmarks.
- **Leaves only**: an assembly function calls no C.
- **The C function stays**: it is what the PC build compiles, and the specification the assembly
  must match bit for bit.
- **dampf computes the cycles** from the assembly, so budgets and the timing report keep working.
- **The declaration is in C**, on that function.

## Who is affected

- **An experienced SNES developer** wants to write one hot routine by hand in a game built with the
  default code generator, as with cc65 or PVSnesLib; today `--replace` forces the naive generator on
  the whole program.
- **dampf's developers** want reference functions measured against the optimized code, not the
  naive code (Er-Tüp's `random16` and `sine` so far; candidates are the pair loop, Mandelbrot's
  iteration and Steinschlag's cave).
- **A beginner** writes no assembly but may get a library routine that is hand-written and has to
  fit in without them noticing.

## Background: what the MIR code generator needs from a callee

- where the arguments and the result go (today the callee's parameter places, assigned by
  `PlaceColouring`; the caller stores the arguments straight into them);
- how many bytes of frame it uses, so `FrameLayout` puts the caller's frame above them (a leaf's
  frame lies in the direct page);
- what it clobbers (today every call clobbers A, X, Y and the scratch registers; registers across
  calls are not built);
- the CPU state at entry and exit (16-bit registers, data bank `$7E`, direct page 0);
- its best and worst cycles.

## Ideas

**1. A fixed convention with named places.** An assembly function gets its parameters in named
frame places, like the naive generator's `_<f>__<p>`, and declares the bytes of frame it needs for
its locals. dampf reserves a leaf frame of that size and exports one symbol per parameter and a
base for the locals as equates the `.s` file imports (`lda z:_random16__seed`); callers store the
arguments there; the result comes back in A (and X for 32 bits); everything else counts as
clobbered. *Why:* the convention the existing reference functions already use, explicit, no new
analysis, and one convention for both code generators.

**2. Arguments in registers.** The first argument in A, the second in X, the third in Y, the result
in A; the caller loads them right before `JSL`, so the function needs no frame unless it has
locals; more than three or 32-bit arguments fall back to 1. *Why:* what an assembly programmer
writes anyway, the cheapest call, and a first step towards M12's "first argument in A".

**3. dampf reads the assembly as its own code.** dampf's assembler parses the `.s` file into LIR, so
the clobbered registers, the direct-page bytes it touches and the cycles come from the code, with
no declaration; its places could even be renamed by `PlaceColouring` and shared like C locals.
*Why:* nothing to declare, nothing to get wrong, timing as exact as for C.

**4. Cycles from the assembly, loop bounds as comments.** dampf builds the control flow of the
assembly and computes best and worst cases; every backward branch needs a bound written beside it
(`; dampf: at most 16`), or the build stops, as for a C loop without a bound under `DAMPF_BUDGET`;
the emulator tests check measured cycles against the bounds. *Why:* exact timing from the same
cycle table as C.

**5. The declaration as a hint macro on the C function.**
`uint16_t random16(uint16_t s) DAMPF_ASM("asm/random16.s") { ... }` expands to nothing on the PC,
which compiles the body; dampf links the assembly instead and checks that the file exists and
exports the label. `--replace` stays as a command-line override. *Why:* declaration and
specification in one place, no build-file entry.

**6. Wild: dampf ejects its own code as the starting point.** `dampf eject random16` writes the
function's optimized assembly as a `.s` file in the fixed convention, every place named, and adds
the `DAMPF_ASM` line to the C file. *Why:* nobody starts from an empty file, the result is correct
from the first build, and "my version against dampf's" is trivial to measure.

**7. Drop the requirement: assembly only with the naive generator.** Nothing changes. *Why:* no cost.

**8. From other people's tools: inline assembly in C** (gcc and Calypsi style): an `__asm__` block
in a C function with operands and clobbers; dampf binds C values to its operands; the PC build needs
an `#ifdef` with a C version. *Why:* what experienced C programmers expect, at the smallest scale.

## Ratings

| Idea | Realistic? | Cost (time) | Fragility | Fully solves it? | Works for others? | New problems | Reversible? | Fits the plans? |
|---|---|---|---|---|---|---|---|---|
| 1. Named places, declared frame | Yes: the naive generator does it; `FrameLayout` only reserves a leaf frame of a given size. | Days. | Medium: nothing checks that the assembly stays inside its declared bytes; one byte too far silently corrupts the caller's frame. | The convention; cycles need 3 or 4. | Veterans: familiar (cc65 is similar); the reference functions move almost unchanged. | — | Medium: once games use the symbols, it is an interface. | Fits: one convention for both code generators. |
| 2. Arguments in registers | Yes. | Days: selection loads A, X, Y before the call, in both generators. | Low. | Only up to three 16-bit arguments. | Veterans like it most. | Two conventions in one program until M12 changes C's. | Medium. | Overlaps M12 row 7, which would decide it for all functions; doing it now pre-empts that. |
| 3. Assembly read into LIR | Partly: dampf's assembler reads its own output, a small ca65 subset; hand-written files use more (macros, `.proc`); `SEP`/`REP` widths through hand-written control flow are the hard part. | Weeks for all of it. | High: every directive or idiom must be understood or rejected. | Completely, with no declaration. | Plain code yes; a veteran's macros are rejected. | dampf becomes responsible for arbitrary assembly. | Hard. | Beyond the plans; its analysis half is what 4 needs. |
| 4. Cycles from the assembly, bounds as comments | Yes, on dampf's assembler front end plus the timing code: the analysis half of 3. | About a week: control flow from labels and branches, widths from `SEP`/`REP`, best and worst case. | Medium: only the subset dampf's assembler reads; anything else is an error. | Cycles, and it can check 1's weak spot: that the code touches only its own places, keeps the entry and exit state and calls nothing. | Yes; a missing bound is an error with a message. | — | Easy. | Fits: same cycle table, report and budgets. |
| 5. `DAMPF_ASM` on the C function | Yes: hint macros exist, the parser takes GNU attributes. | Hours to days. | Low. | The declaration. | Yes. | — | Easy. | Fits the hints rule: "this assembly computes what this C computes" is a fact hint, checkable by running both on many inputs in dampf's emulator, as `ReferenceFunctionsTest` does for two functions. |
| 6. `dampf eject` | Yes, once 1 exists. | Days. | Low. | No: a convenience on top. | Beginners and intermediates gain most. | — | Easy. | Fits; new reference benchmarks get cheap. |
| 7. Naive generator only | Yes. | None. | — | No: fails both users. | No one. | Reference benchmarks keep measuring naive code. | — | Contradicts M10. |
| 8. Inline assembly | Hardly: binding C values to operands inside MIR and LIR; an `#ifdef` for the PC. | Weeks. | High. | At the smallest scale. | Veterans from gcc or Calypsi. | Breaks "one source, also compiled for the PC"; the checker cannot see inside. | Hard. | Against the subset's approach. |

## Recommendation

**5 + 1 + 4**, with 4's checks closing 1's weak spot:

- `DAMPF_ASM("file.s")` on the C function, whose body stays the PC's reference;
- named places as the convention, the same for both code generators; the result in A (and X for 32
  bits), everything else clobbered;
- dampf reads the `.s` file with its own assembler, computes best and worst cycles with bounds as
  comments, and checks that the code touches only its own places, leaves the CPU state as it found
  it and calls nothing;
- dampf runs the assembly against the C body on many inputs in its emulator, as the hints rule asks
  of a fact hint.

Later: `dampf eject` (6) as the easy way to start a file, once 1 exists; arguments in registers (2)
only together with M12 row 7, so a program never has two conventions.

Trade-off: an assembly file is limited to the subset of ca65 dampf's assembler reads; a veteran's
macros get an error and have to be expanded by hand. In return the timing and the safety checks are
exact.

The two parts meet: the checks of 4 are what a hand-written interrupt entry would need, and the
convention of 1 is what an assembly IRQ handler could use one day.

## Decision

Taken as recommended when the session ended (2026-11-24).

## Open questions

- The exact syntax of the bound comment, and whether other facts (the frame size, the inputs for
  the check against C) also go into comments in the `.s` file or into the C declaration.
- Which part of ca65's syntax dampf's assembler has to read for hand-written files, and how an
  unsupported directive is reported.
- How the check against the C body chooses its inputs (random, edge values, the parameters' ranges
  from `DAMPF_RANGE`), and whether it runs in every build or in `superdevkit build --check` only.
- Whether `--replace` stays once `DAMPF_ASM` exists.
