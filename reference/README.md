# Reference functions

Functions of the examples written by hand in 65816 assembly. They are not
part of the programs; they are the yardstick for dampf's code: the same function, compiled by dampf and written by hand,
run on the same inputs, and the ratio of their cycles says how far dampf is from good assembly
([docs/optimizations.md](../docs/optimizations.md), "Reference assembly benchmarks"). Where a
reference is faster, the reason becomes a hint or an optimization rule.

## Linking a reference function

```bash
cd examples/ertuep
dampf build @ertuep.args --backend naive --replace random16=../../reference/ertuep_random16.s -o build/ref.sfc
```

`--replace` keeps the function's parameters and frame and emits no code for it; the assembly file
provides the code under the function's label. The file has to follow dampf's calling convention
(the naive code generator's, for now):

- the parameters are in the function's static frame, `_<function>__<parameter>` (imported);
- the result goes back in A (8-bit results zero- or sign-extended to 16 bits; 32-bit results:
  low word in A, high word in X);
- called with `JSL`, returns with `RTL`; A, X and Y are 16 bit on entry and exit; DBR = `$7E`,
  D = 0; X, Y and the direct page scratch `dampf_ptr` may be changed;
- the code goes into the segment `C_<function>`, so the ROM builder packs it like a compiled
  function.

A reference function must compute exactly what the C does, for every input, not only for the
ones the program uses. A reference of an example is named `<example>_<function>.s`;
`ReferenceFunctionsTest` builds each example with all its references (naive code generator),
plays its `check.txt` in dampf's emulator and compares every tick's checksum with the normal
build. Their cycles: `dampf.emu.ExampleProfile` on both builds (the same input makes the same
calls, so the ratio of the totals is the ratio per call).

## The functions

| Function | File | What it does | Cycles: naive, by hand |
| --- | --- | --- | --- |
| `random16` (Er-Tüp) | `examples/ertuep/src/objects.c` | A 16-bit xorshift | 94 : 41 master cycles a tick, 2.3 times |
| `sine` (Er-Tüp) | `examples/ertuep/src/objects.c` | A signed byte from a table in ROM | 14 : 10, 1.4 times |

The code generator from MIR inlines both of Er-Tüp's functions into their callers; the naive one
calls them, and its code is the comparison.
