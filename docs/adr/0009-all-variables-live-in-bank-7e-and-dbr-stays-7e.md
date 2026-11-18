# 9. All variables live in bank $7E, and DBR stays $7E

**Context.** Blubber Blasen needs about 25 KB of RAM, which does not fit into the 8 KB that
bank 0 mirrors. The direct page and the stack are always addressed through bank 0. Many
instructions the code needs (`inc`, `dec`, shifts, `stz`, `ldx`, `ldy`, `stx`, `sty`, `bit`)
have no long (24-bit) addressing.

**Decision.** The data bank register is set to `$7E` at start-up and never changes. All
variables live in bank `$7E`, so 16-bit absolute addresses (`lda abs`, `lda abs,x`) reach all
64 KB of it. The direct page (D = 0) and the stack lie in its first 8 KB, which are the same
memory as `$0000-$1FFF` in bank 0: the runtime's scratch registers at `$0000-$001F`, the frames
from `$0020` (record 6), the stack at `$1E00-$1FFF`, and globals and static locals from
`$2000`.

**Alternatives.**

- DBR = 0 and only 8 KB of RAM with 16-bit addresses, everything else through long addresses:
  the instructions above have no long form, so most variables would need extra instructions.
- Switching DBR between RAM and ROM per access: cheaper reads of ROM tables, but every switch
  costs instructions and makes the code harder to follow. If ROM tables turn out hot, it can be
  done locally (catalogue J in [optimizations.md](../optimizations.md)).

**Consequences.** ROM data is read with long addresses, and so are the I/O registers in bank 0
(`sta f:$002100`). Bank `$7F` is not used. The layout as built is in
[memory-map.md](../memory-map.md).
