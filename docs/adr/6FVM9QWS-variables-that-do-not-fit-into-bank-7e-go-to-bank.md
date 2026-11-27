# ADR-6FVM9QWS: Variables that do not fit into bank $7E go to bank $7F, largest first, chosen by dampf

**Context.** The SNES has 128 KB of WRAM in banks `$7E` and `$7F`. Until 2026-11-27 every
variable lived in `$7E` (ADR-6F0R69B6), so a program had at most 57,343 bytes for its globals
and static locals, half of WRAM, and dampf's own linker did not even check that they fit.
DBR stays `$7E`, so a 16-bit address reaches only that bank; a variable in `$7F` needs long
addresses (`lda f:x`, `sta f:x,x`), one cycle more at a fixed address, and several instructions
have no long form (`stz`, `inc`, `dec`, `ldx`, `ldy`, `stx`, `sty`, the shifts, `bit`, the
`abs,Y` forms). The target module (ADR-6FPTPCDM) had already put the bank of a pointer to a
variable and how a variable is reached into one place each. Nothing in the subset or the
earlier records said how a program chooses the bank of a variable.

**Decision.**

- dampf chooses the bank; the program cannot see it and has nothing to write for it. While all
  variables fit into `$7E` (`$2000-$FFFE`), all of them stay there, and the code is what it was
  before.
- When they do not fit, the largest variable goes to `$7F`, then the next largest, until the
  rest fits into `$7E`; one that no longer fits into what is left of `$7F` (`$0000-$FFFE`) is
  passed over for the next smaller one. Among equal sizes the program's order decides. Large
  arrays are reached through an index or a pointer, where the long form costs least, and
  moving the largest first moves the fewest objects.
- Variables that an assembly function names (`DAMPF_ASM`, `--replace`) stay in `$7E`, where its
  16-bit addresses reach them; so do the naive code generator's static frames.
- A variable in `$7F` is reached long, as constant data in ROM is; a pointer to it carries bank
  `$7F`; its initial values are copied there by the start-up, which also clears the rest. The
  code generators use only instructions that have a long form for it: a zero is stored with
  `lda #0` and `sta`, an index goes into X, not Y.
- Variables that fit into neither bank are a compile error at the largest one left over, which
  says how many bytes the variables need and how many each bank holds.
- The check of the assembly functions fills the part of `$7F` the variables leave free with
  random bytes and points pointer parameters into it; it needs 4 KB there.

**Alternatives.**

- A hint per variable (`DAMPF_BANK7F` or a section name): the program would decide, but it
  would have to know the bank's size, its own variables' sizes and which accesses are hot, and
  every program that fits today would gain a hint it does not need. Kept open as an override
  if a measured program shows that the automatic choice moves the wrong object.
- Choosing by a profile (the coldest bytes to `$7F`): better in theory, but the build would
  depend on a profile, and the examples and the game fit into `$7E`, so there is nothing to
  measure it on yet.
- Switching DBR to `$7F` around accesses: cheaper `abs` accesses, but every switch costs
  instructions, and code that reaches both banks would switch back and forth (the reason
  ADR-6F0R69B6 gives against switching DBR for ROM tables).
- `$7F` for all variables of one kind (static locals, arrays): simple, but it would change the
  code of programs that fit today.

**Consequences.** Programs whose variables fit into `$7E` build byte for byte the same ROM (the
examples did). A program can use up to 122,878 bytes of variables. A variable in `$7F` costs
one cycle more per access at a fixed address, none at an index (the absolute form pays a cycle
for the 16-bit index, the long one does not), and four for a stored zero (`lda #0` and `sta`
instead of `stz`). `dampf.backend.VariableBanks` holds the rule, the linker has the segments
`BSS7F` and `DATA7F` at `$7F:0000` and refuses a 16-bit address of a variable there, and the
runtime clears and copies them only when a program has them. A 16-bit pointer into RAM (subset
decision 3) now needs a proof of the bank as well as of RAM. The layout is in
[memory-map.md](../memory-map.md).
