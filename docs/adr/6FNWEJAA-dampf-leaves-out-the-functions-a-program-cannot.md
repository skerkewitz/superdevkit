# ADR-6FNWEJAA: dampf leaves out the functions a program cannot reach

**Context.** dampf compiled every function of every file into the ROM, and every global into RAM,
whether the program used it or not. With the console API's optional layers (ADR-6FNPPCSX) that
cost a game that does not use them their code and their state: 1.7 to 6.2 KB of ROM per example
counting the kit's other unused functions, and the layers' RAM (a sprite table of 544 bytes, the
queue, the register shadow). A layer was to cost only what a game calls.

**Decision.**

- After lowering to MIR and before the optimizer, dampf drops the functions that the entry, the
  interrupt handlers and the functions written in assembly (with their C bodies, and every C
  symbol their assembly names) do not reach through calls and taken addresses
  (`dampf.mir.Reachable`). A global's initial contents reach what they point to.
- A global goes with them only when dropped functions used it and nothing kept does. A global no
  function names at all stays, so a test or a debugger that reads it by name still finds it.
- Before the optimizer, not after: a function that is called keeps its body even where every call
  is inlined, so the timing report keeps its entry (budgets, the C guide's measurements, the cost
  table of lib/README.md).

**Alternatives.**

- After the optimizer, dropping also what inlining made unused: the examples' code would shrink
  by another 25 to 35 % (measured), but such a function would vanish from the timing report, and
  `DAMPF_BUDGET` on it could no longer be checked. A later step could keep the report's entry and
  drop only the code.
- Library files as separate objects the linker picks by symbol, as C toolchains do: dampf compiles
  the whole program at once, so the call graph is already there.

**Consequences.** The examples' code shrank by 4 to 48 % (Hello 11,434 to 7,721 bytes, Er-Tüp
85,988 to 79,789) and is now smaller than with the PVSnesLib API for every one of them; their
work per tick did not change. A program's ROM no longer holds a function it does not call, which a
debugger or a test that calls a function by its label must know: such a test calls it from its
entry. The execution tests compare a global the ROM leaves out by its initial value, which the PC's
run must still have.
