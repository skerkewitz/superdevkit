# Reference functions

Functions of the examples written by hand in 65816 assembly. They are not
part of the programs; they are the yardstick for dampf's code: the same function, compiled by dampf and written by hand,
run on the same inputs, and the ratio of their cycles says how far dampf is from good assembly
([docs/optimizations.md](../docs/optimizations.md), "Reference assembly benchmarks"). Where a
reference is faster, the reason becomes a hint or an optimization rule.

## Linking a reference function

```bash
superdevkit build snes examples/ertuep     # writes examples/ertuep/build/snes/ertuep.args too
dampf build @examples/ertuep/build/snes/ertuep.args \
    --replace random16=reference/ertuep_random16.s -o build/ref.sfc
```

`--replace` links the file in place of the compiled function, as `DAMPF_ASM` would in the source,
with the same convention and the same checks, in the build from MIR (the default) or with
`--backend naive` ([docs/hints.md](../docs/hints.md), "DAMPF_ASM"). In short:

- the parameters are in the function's frame, which starts at `dampf_frame` ($0020); dampf exports
  their places as `_<function>__<parameter>` (`.importzp`), 2 bytes for 8 and 16 bits, 4 for 32
  bits and pointers; an 8-bit parameter is the low byte, the byte above it is undefined; the
  function's own locals start at `_<function>__locals`;
- the result goes back in A (an 8-bit result in the low byte; 32-bit results and pointers: low
  word in A, high word in X);
- called with `JSL`, returns with `RTL`; A, X and Y are 16 bit on entry and exit; DBR = `$7E`,
  D = 0; A, X, Y and the scratch registers (`dampf_regs`, `dampf_ptr`) may be changed;
- the code goes into the segment `C_<function>`, so the ROM builder packs it like a compiled
  function;
- every backward branch carries its bound, `; dampf: at most N`.

dampf times the file (the function's line in the `.timing.txt` file says `assembly`) and runs it
against the C body on 256 inputs in its emulator; a reference that computes anything else for
any input, not only for the ones the program uses, stops the build. A reference of an example is
named `<example>_<function>.s`. `ReferenceFunctionsTest` builds each example with all its
references (code generator from MIR), which runs that check and measures both versions per call,
then plays the example's `check.txt` in dampf's emulator and compares every tick's checksum with
the normal build.

## The functions

| Function | File | What it does | CPU cycles a call: dampf, by hand |
| --- | --- | --- | --- |
| `random16` (Er-Tüp) | `examples/ertuep/src/objects.c` | A 16-bit xorshift | 68 : 68, the same |
| `sine` (Er-Tüp) | `examples/ertuep/src/objects.c` | A signed byte from a table in ROM | 24 : 21, 1.14 times |

The numbers are those of the check, on its inputs, for the functions as dampf compiles them on
their own (2026-11-24). dampf's `sine` masks the byte it read (`and #$00FF`), since its 8-bit
results have a clean high byte; the hand-written one leaves the byte above it as it is, which the
convention allows. In the game the code from MIR inlines both functions into their callers: with
the two references linked, Er-Tüp's benchmark does 132,250 master cycles of work a tick against
132,235 for dampf alone, so dampf's inlined code is a little faster than calls of the hand-written
functions. Against the naive code generator (until 2026-11-24 the comparison, in master cycles
a tick) the references were 2.3 and 1.4 times as fast.
