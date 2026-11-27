# ADR-6FVMCY48: A function inlined at every call keeps its timing entry but no code

**Context.** ADR-6FNWEJAA drops the functions a program cannot reach before the optimizer, so a
function that is called keeps its body in the ROM even where the inliner has copied it into
every caller. That body never runs. It was kept for the timing report: its line (best and worst
case), the `DAMPF_BUDGET` on it and the measurements built on the report (the C guide's, the
cost table of lib/README.md). Dropping it after the optimizer was measured on 2026-11-25: the
examples' code 25 to 35 % smaller, but the function left the report. The record named a later
step that keeps the report's entry and drops only the code; the user decided on 2026-11-27 to
build it.

**Decision.** This amends ADR-6FNWEJAA.

- After the optimizer, `Reachable.needCode` finds the functions that still need code of their
  own: those the entry, the interrupt handlers, the assembly functions and their C bodies, every
  C symbol their assembly names, and every global the module keeps reach through calls and
  taken addresses. A function no call is left of (all inlined, or gone with dead code) needs none.
- The code generator from MIR compiles, places and times such a function as before and leaves
  only its code out of the ROM, as it does with the C bodies of the assembly functions. Its frame
  is placed as before, so its bounds are the same numbers. The timing report keeps its line,
  marked `(inlined at every call, no code of its own)`, and its budget is checked.
- A function keeps its code where a call of it is left: the inliner found it too large, its
  caller too large, or the pass limit stopped it; and where assembly names it.
- `Compiler.Options.withInlinedCode(true)` keeps every copy, for tests that read a function's own
  code. It is not a command-line option.

**Alternatives.**

- Timing the body without generating its code (from MIR): the bounds come from the code as
  written, after the LIR passes, so the report's numbers would change or need a second model.
- Generating the body into a section the linker throws away: the same result with the linker in
  the way; leaving it out of the list of modules is simpler.
- Keeping the body (ADR-6FNWEJAA as it was): a quarter to a third of the code for nothing that
  runs.

**Consequences.** The examples' code shrank by 25 to 35 % (206,985 to 149,558 bytes in all; Mode 7 6,428 to
4,181 bytes, Ertüp 79,789 to 59,838); their work per tick and every number of their timing
reports are the same. Such a function has no label in the `.lbl` and `.dbg` files and no
`.proc` in the `.s` file: Mesen and `dampf profile` see its copies as part of their callers, with
the callee's source lines, as before. A test or a debugger that calls it by its label must call
something that keeps it. Its frame still takes its place in RAM, as before. The naive code
generator does not inline and is unchanged.
