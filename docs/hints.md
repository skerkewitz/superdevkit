# Hints

Hints tell dampf what a human assembly programmer would know about the program but the C code
does not say. This is the agreed design; where the hints fit into the milestones is in
[roadmap.md](roadmap.md).

| Hint | Built |
| --- | --- |
| `DAMPF_RANGE` | yes |
| `DAMPF_BUDGET` | yes ([timing.md](timing.md)) |
| `DAMPF_ASM` | yes (see "As built") |
| `DAMPF_TRIPS` | no, postponed (see "As built") |
| `DAMPF_AT_MOST` | no, to be looked into ([roadmap.md](roadmap.md), M11) |
| `restrict` | no, rejected by the front end: measured on 2026-11-25 to gain nothing ([optimizations.md](optimizations.md#tried-and-rejected)) |
| `DAMPF_LIKELY` / `DAMPF_UNLIKELY`, unroll hint, `DAMPF_PIN_X`, `DAMPF_SPLIT`, `DAMPF_TABLE`, `DAMPF_DP` | no |
| subrange types (`DAMPF_RANGED`, `DAMPF_AS`) | no ([subrange-types.md](subrange-types.md)) |

## The idea

Most tricks of an assembly programmer rest on knowledge: "this value is always below 16", "these
two pointers never overlap", "this branch is rare". Knowledge can be written down. dampf lets the
programmer state it in the C source as hints, so the compiler knows what the human would know.

Hints are macros from `<dampf.h>`. In the dampf build they expand to built-ins the dampf parser
recognises (for example `__dampf_range(...)`). In the PC build they expand to plain C: a checked
assertion for hints that state a fact, nothing for the others (see the rule below). The source
stays valid C, and both builds expand the same macros of the same header, so both see the same
code. Inside dampf a fact hint becomes an assumption in the IR ([architecture.md](architecture.md)).

## Catalogue

The working names are the names.

| What a human knows | Hint in C | What dampf does with it |
| --- | --- | --- |
| "i is always below 16" | `DAMPF_RANGE(i, 0, 15)` | 8 bit instead of 16 where that is enough, no range checks, index directly in X, arrays can be aligned so indexed reads never cross a page ([timing.md](timing.md)) |
| "these pointers never overlap" | `restrict` (or a dampf macro) | Values stay in registers instead of being reloaded after a store through a pointer |
| "this branch is rare" | `DAMPF_UNLIKELY(cond)` / `DAMPF_LIKELY(cond)` | The common path falls through without a jump. Often unnecessary once profile data from replays is used ([optimizations.md](optimizations.md)). |
| "this loop runs at most 16 times" | `DAMPF_TRIPS(16)` | Unrolling, an 8-bit counter, and a loop bound for the timing analysis |
| "unroll this small loop" | an unroll hint (`DAMPF_TRIPS` with a small bound may cover it) | The loop is unrolled |
| "X holds the current bubble during the whole update" | `DAMPF_PIN_X(cur)` for a block or a module | The value stays in X, also across function boundaries |
| "store this array of structs as one array per field" | `DAMPF_SPLIT` on the array | One array per field, accessed as `lda bub_x,x` |
| "this function only ever gets 256 different inputs" | `DAMPF_TABLE` on the function | dampf evaluates the function for every input **at compile time** and places the results as a table in ROM. At run time the call becomes one indexed read. |
| "this variable belongs in the direct page" | `DAMPF_DP` | A fixed direct page slot |
| "this branch runs at most 4 times per call of the function, however often the loop around it runs" | `DAMPF_AT_MOST(4)` at the start of the branch | The timing analysis counts the branch's cost 4 times per call instead of once per iteration: a pair loop's rare hit (an enemy killed, an item collected) no longer makes its worst case a hundred frames. On the PC a counter per call checks it |
| "this function must finish within N cycles" | `DAMPF_BUDGET(n)` | A compile error if its worst case is higher ([timing.md](timing.md)) |
| "this hand-written assembly computes what this C function computes" | `DAMPF_ASM("file.s")` on the function's definition | dampf links the assembly instead of compiling the body, times it, and runs it against the body ("As built") |

`DAMPF_TABLE` shows how far this goes: with a known input range, the compiler can do what a human
would do with a hand-made table or a generator script.

Blubber Blasen's `BB_FAST` macro (the direct page placement for Calypsi) maps onto `DAMPF_DP`.

## Representation decisions stay in C

Some knowledge is not a fact about the program but a decision about how it represents things,
such as using bit 7 as a flag. Those decisions are written in C by the programmer, and dampf
recognises the patterns. If a state is defined as `0x80`, then `if (flags & 0x80)` becomes `BIT` +
`BMI` without a compare. The programmer decides *what* the code represents; dampf takes care of
the instructions.

## Rule: every hint that states a fact is a checked assertion on the PC

A wrong hint produces wrong code, silently. If `i` ever reaches 16 despite
`DAMPF_RANGE(i, 0, 15)`, the SNES build misbehaves without any error. Therefore every hint that
states a fact is checked in the PC and test builds:

- `DAMPF_RANGE(i, 0, 15)` asserts `0 <= i && i <= 15` on the PC. Ranges that belong to a type
  rather than to one point in the code are subrange types (`DAMPF_RANGED`, with the checked
  conversion `DAMPF_AS`), see [subrange-types.md](subrange-types.md).
- `DAMPF_TRIPS(16)` counts loop iterations on the PC and asserts the count stays within 16.
- `restrict` can be checked on the PC by an address-overlap assertion where practical.
- `DAMPF_PIN_X`, `DAMPF_SPLIT`, `DAMPF_DP` and `DAMPF_TABLE` do not state facts, they request a
  transformation; they need no assertion. (`DAMPF_TABLE` does require the function to be pure;
  the subset checker verifies that.)
- `DAMPF_BUDGET` expands to nothing on the PC; the emulator test builds can check the measured
  cycles against it ([timing.md](timing.md)).
- `DAMPF_ASM` expands to nothing on the PC, which compiles the C body. dampf checks the fact
  itself: every build runs the assembly against the C body in its emulator ("As built").

The replays run with these assertions enabled, so a wrong hint is caught on the PC before it can
do damage on the SNES.

## Limits of hints

1. **Knowledge alone is not enough.** dampf must also know the matching trick. The reference
   assembly benchmarks ([optimizations.md](optimizations.md)) are the loop that keeps teaching it.
2. **Too many hints make code unreadable.** Hints belong only where measurement shows the code is
   hot. Most of the code should carry none.

## As built

`<dampf.h>` comes with dampf (in its own include directory, and as `include/dampf.h` in the
superdevkit home, beside the launchers' `bin/`). With dampf (`__DAMPF__` defined) its macros expand to built-ins
the front end reads; on the PC to `assert`. For the PC build, `gcc -idirafter <dampf>/include`
makes `<dampf.h>` reachable while the host's own `<stdint.h>` still comes first. The execution
tests compile the PC side that way.

**`DAMPF_RANGE(v, lo, hi)`**, a statement: the local variable or parameter `v` (an integer type)
lies in `lo..hi` at this point; both bounds are constants of `v`'s type. Misuse is a compile
error (a global or a `static` local, bounds that are not constants or do not fit the type, `lo`
above `hi`).

- **In MIR** the hint becomes comparisons that branch to a block ending in `unreachable` when
  `v` is outside the range (the role of LLVM's `llvm.assume`). The range analysis treats them
  like any condition: `v` has the range in every block the hint dominates, so
  `while (s[n]) { DAMPF_RANGE(n, 0, 10); n++; }` gives the loop a bound for the timing analysis
  and the counter a range for the narrowing.
- **The MIR interpreter** fails at `unreachable` ("a hint does not hold"), so the tests check
  every hint on the MIR level as well.
- **The code generator** computes the ranges first and then takes the checks out
  (`dampf.mir.Hints`): every branch to such a block becomes a jump to its other side. The hint
  costs no cycles.
- **On the PC** it is `assert`, written as one unsigned comparison so that `DAMPF_RANGE(n, 0,
  10)` on an unsigned `n` gives no "always true" warning under `-Wextra`. `NDEBUG` switches it
  off.
- **The naive code generator** ignores the hint.

**`DAMPF_BUDGET(n)`**, in front of a function definition: see [timing.md](timing.md). The naive
code generator has no timing and does not check budgets.

**`DAMPF_ASM("file.s")`**, after the parameter list of a function's definition
(`uint16_t random16(void) DAMPF_ASM("asm/random16.s") { ... }`): the function is written by hand
in 65816 assembly, in that file, relative to the C file
([ADR-6FMATG2V](adr/6FMATG2V-hand-written-assembly-replaces-a-c-function.md) records why it
looks so). dampf expands it to `__attribute__((dampf_asm("file.s")))` and links the file in place
of the compiled body; the PC compiles the body. The body stays the specification. On a
declaration that is not a definition, and on the entry function, it is an error; parameters and
results are integers and pointers. `dampf build --replace name=file.s` does the same from the
command line, with either code generator, and wins over a `DAMPF_ASM` of the same function (a file
path given there is relative to where dampf runs).

The assembly function is a leaf: it calls nothing. Its convention is the same for both code
generators:

- its label is the C function's, `_name`, exported, in a segment of its own named `C_...`
  (by convention `C_name`);
- its frame starts at `dampf_frame` ($0020, in the direct page), below every caller's frame. The
  parameters lie there in their order, 2 bytes for 8 and 16 bits and 4 for 32 bits and pointers
  (address, bank, 0). dampf exports their places as `_name__param` and the first byte after them
  as `_name__locals`; the file imports them with `.importzp` and reaches them as `z:_name__x`.
  An 8-bit parameter is the low byte of its place; the byte above it is undefined;
- its own locals are bytes from `_name__locals` up. dampf takes their number from the code: the
  highest byte an access to `_name__locals+n` touches;
- the result is in A: an 8-bit one in A's low byte (the caller extends it as its type says), a
  32-bit one or a pointer with the high word in X;
- it is called with `JSL` and returns with `RTL`, with A, X and Y 16 bit, DBR = $7E and D = 0 on
  entry and exit, the interrupt flag as it was and nothing left on the stack. A, X, Y, the flags
  and the runtime's scratch registers (`dampf_regs`, `dampf_ptr`) may change;
- a global it names (`_total`) stays in bank $7E, so a 16-bit address reaches it, also in a
  program whose variables do not all fit there ([memory-map.md](memory-map.md#bank-7f)).

dampf assembles the file with its own assembler, which reads the part of ca65 dampf writes:
instructions; labels, also cheap local (`@x`) and unnamed ones (`:`, `:+`); a label and an
instruction on one line; `.proc`/`.endproc`, `.segment`, `.import`, `.importzp`, `.export`,
`.global`, `.byte`, `.word`, `.res`, equates, `.p816`, `.smart off`, `.a8`, `.a16`, `.i8`,
`.i16`. Macros, `.include`, `.if` and other directives are an error at their line. From the label
on, dampf follows the machine code and rejects, each with the line:

- a call (`JSR`, `JSL`), a jump out of the function's segment, an indirect jump, a return other
  than `RTL`, `XCE`, `BRK`, `COP`, `STP`, `WAI`, `TXS`, `TCS`, and `MVN`/`MVP` (not followed yet);
- an `RTL` with the CPU state changed: 8-bit registers, bytes left on the stack, another DBR or
  D, the interrupt flag changed, decimal mode; `PHP`/`PLP`, `PHB`/`PLB` and `PHD`/`PLD` restore
  what they saved;
- ways that meet with different register widths or stacks, and an immediate assembled with
  another width than its register has there (`.a8`/`.a16` and `.i8`/`.i16` must follow `SEP` and
  `REP`);
- a place in the frames that is not its own: a fixed address from $0020 to $1FFF, `dampf_frame`,
  another function's place, an access beyond a parameter's bytes, an indexed access to its own
  places (an array belongs in a segment of the file, such as BSS). Globals, I/O registers and the
  scratch registers are allowed, as for C;
- a backward branch or jump without a bound. The bound is a comment on the branch's line,
  `; dampf: at most N`: the loop that starts at the branch's target runs at most N rounds each
  time it is entered. Loops nest and are entered at their first instruction.

The same walk gives the best and the worst case in master cycles ([timing.md](timing.md)), so the
timing report lists the function (`(assembly, file.s)`), a caller's bounds include it, and
`DAMPF_BUDGET` works on it.

**The check against C.** In every build with the code generator from MIR, dampf compiles the C
body as well, under the label `_dampf_check_name`, and links it with the program and the
assembly into a second ROM, in memory; the ROM it writes does not contain it. In its emulator, from
the state after the program's start-up, it calls the body and the assembly on 256 inputs per
function (64 for a function without parameters): the edge values of each parameter, then random
values from a fixed seed, within the range of a `DAMPF_RANGE` on the parameter at the start of
the body; the globals the body reads (also in the functions it calls) as after start-up, then all
zero, all ones and random, except those that hold pointers; pointer parameters pointing into the
part of bank $7F the program's variables leave free (all of it, unless they do not fit into
$7E: [memory-map.md](memory-map.md#bank-7f)), which the check fills with random bytes and of
which it needs 4 KB; and the byte above an 8-bit parameter not 0. The result (its bytes) and all of RAM from $7E:2000 to $7F:FFFF must be the
same after both, and the assembly's cycles must lie within the bounds dampf computed, so a bound
comment set too low fails as well. A difference stops the build with the inputs and both
results:

```
game.c:4:6: error: the assembly does not compute what the C body of 'add' computes: for x = 1 ($0001)
with total = $FFFF, the C body leaves total ($7E:2000) at $00, the assembly at $01 (asm/add.s, DAMPF_ASM)
```

(for `void add(uint16_t x) DAMPF_ASM("asm/add.s") { total = (uint16_t)(total + x); }` and an
assembly that stores `x` without adding the old total; the message is one line).

The check takes about 0.1 s for a small program and 0.4 s for Er-Tüp with two assembly
functions (most of it linking the second ROM), against 13 s for the whole build, so it runs in
every build and not only in `dampf check`. It does not see writes to I/O registers. The naive
code generator links the same file and does not run the check.

The MIR passes treat an assembly function as unknown code: calls of it are not inlined, its
parameters are not rewritten (`PointerParams`, `IndexParams`) and get no ranges from the callers,
and it may write any memory. The MIR interpreter and the tests' PC side run the C body.

An assembly function that an interrupt handler reaches (`DAMPF_INTERRUPT`,
[subset.md](subset.md)) is a compile error that names the function and the handler. Its
convention assumes D = 0 and its frame at `dampf_frame` in the main code's area, while an
interrupt context has its own direct page and frames, and nothing stops its code from using the
CPU's multiplier, which interrupt code must not touch
([ADR-6FM9TXV4](adr/6FM9TXV4-interrupt-handlers-run-in-contexts-of-their-own.md)). The two do
not fit yet.

**`DAMPF_TRIPS` is postponed.** On the PC it would have to count the rounds per entry into the
loop, and a macro in front of or inside a loop cannot see the entry (nor a `break` or `return`
on the way out) without changing the loop. `DAMPF_RANGE` on a counter in the loop covers the
loops of the game that need a bound (the `while (s[n])` loop above, a queue index); a loop
without any counter can be given one.
