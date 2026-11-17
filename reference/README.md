# Reference functions

Hot functions of Blubber Blasen written by hand in 65816 assembly. They are not part of the game;
they are the yardstick for dampf's code: the same function, compiled by dampf and written by hand,
run on the same inputs, and the ratio of their cycles says how far dampf is from good assembly
([docs/optimizations.md](../docs/optimizations.md), "Reference assembly benchmarks"). Where a
reference is faster, the reason becomes a hint or an optimization rule.

## Linking a reference function

```bash
dampf build ... --replace level_solid_px=reference/level_solid_px.s
REPLACE="level_solid_px=reference/level_solid_px.s" tools/bench_game.sh
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
ones the game uses: the benchmark's checksums against the PC check that.

## The functions

| Function | File | What it does |
| --- | --- | --- |
| `level_solid_px` | `src/game/level.c` | Is a pixel position inside a solid tile of the room? |
