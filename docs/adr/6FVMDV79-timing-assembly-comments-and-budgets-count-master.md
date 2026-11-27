# ADR-6FVMDV79: Timing, assembly comments and budgets count master cycles

**Context.** The timing report, the cycles in the assembly comments and `DAMPF_BUDGET` counted
CPU cycles ("Every function gets a best and a worst case", ADR-6F0R6JD6). On the SNES a CPU cycle
takes 6, 8 or 12 master cycles, by the memory it reaches: code from FastROM and I/O registers 6,
WRAM 8, the old joypad registers 12. A frame, the vertical blank and the IRQ's window are fixed
in master cycles, so a budget in CPU cycles could only be converted with a guessed ratio
(between 6.3 and 8 in the numbers of the kit's documents). dampf's emulator counted master
cycles already, validated against Mesen; M11 of the roadmap decided master cycles for the report,
the comments and the budgets.

**Decision.**

- Every number of the static timing is in master cycles: the report (`<rom>.timing.txt`), the
  column on the right of the assembly and the sum of a C line (`[ 72 mc ]`), `DAMPF_BUDGET`, the
  bounds of functions written in assembly and the runtime's routines and interrupt stubs.
- An instruction costs its CPU cycles sorted by where they go (`dampf.target.MasterCycles`): the
  code at the ROM's speed (FastROM, or SlowROM with `--slowrom`), the direct page and the stack in
  low RAM, an absolute address in DBR's bank `$7E`, a long address at its number's speed or, for a
  symbol, ROM; internal operations 6. Where the address is not known, a range: through a pointer
  6 to 8 (RAM, ROM, save RAM or a register; the old joypad registers, which dampf's code reads
  only through the automatic read, not counted), a long address with an index every speed from it
  to the end of its bank.
- The emulator and the analysis take the speed of an address from the same function, and a test
  compares the analysis's sorting of every opcode's cycles with the emulator's.
- `DAMPF_BUDGET(n)` keeps its name and counts master cycles. A budget written for an older dampf
  is about a sixth of what it now means (the examples' worst cases are 6.2 to 6.6 times their CPU
  cycles), and fails the build; the error says so whenever the worst case is at most 8 times the
  budget.

**Alternatives.**

- Both units in the report and the comments: twice the numbers to read, and the budget would
  still have to pick one.
- A new name for the budget in master cycles (`DAMPF_BUDGET_MC`), the old one kept or made an
  error: a clean break, but a second name for the same thing; every pre-1.0 version may change
  what users write (the roadmap's "Versions"), and the old values fail loudly anyway, since a
  budget in CPU cycles is far below any worst case in master cycles.
- Converting an old budget automatically by a factor: the factor depends on the code, so the
  result would be a guess.

**Consequences.**

- Budgets, frame and vertical blank numbers and the IRQ's window are in one unit; a function's
  cost includes where its data lies (a table in ROM is cheaper to read than one in WRAM).
- Every budget in a game has to be rewritten once: the game's `DAMPF_BUDGET` values (game-findings,
  2026-11-27) and the user documents.
- The cycle column of the assembly changes, and with it the frozen output of the back end
  (`BackendOutputTest`); the code dampf writes does not.
- `dampf profile` (Mesen's profiler) and the cost choices inside the code generator still count
  CPU cycles.
