# ADR-6F8NBESR: An access to an array tells dampf its index is inside it

**Context.** `a[i]` with a 16-bit `i` in a global or frame slot is fastest as `lda a,x` with `i`
in X. X adds as an unsigned number, so the code generator did that only where the value ranges
showed `i >= 0`; otherwise it computed a 24-bit pointer for every access, three to four times
the cycles (`l_u16_local_bound` of the spelling test, the C guide's `fast-range-index`, which
told the programmer to add a `DAMPF_RANGE`). C makes an access outside its object undefined,
ADR-6F0R6A8V keeps every object within 32 KB, and `PointerOffsets` relies on the same rule already.
What gcc does with such an access on the PC is read or write a neighbour; on the SNES, an index
of -1 in X reads 65,535 bytes further on instead.

**Decision.**

- A global or frame slot plus a 16-bit value, read only as the address of loads and stores
  (directly, or through a constant offset of at most 0), is indexed with X without a range: a
  defined access puts the value in 0..size of the object.
- The tests catch a program that breaks the rule before it runs on a SNES: the MIR interpreter
  stops on a load or store outside the global or slot its address is based on, as it stops on a
  hint that does not hold, and the PC build's UBSan finds most such accesses as well.
- The value ranges do not learn from an access yet (the index lies in 0..size after it): no
  measured case needs it.

**Alternatives.** Keep asking for a `DAMPF_RANGE` on every such index: the hint states the same
fact, but a program without it pays for a C rule it keeps anyway. Bounds checks on the SNES: a
compare and a branch per access, for programs that are wrong in C.

**Consequences.** An out-of-bounds access, which on the PC usually only corrupts a neighbour,
reads or writes another address on the SNES, possibly in another bank; the interpreter in the
tests and UBSan on the PC are where it is found. `fast-range-index` is retired. A field after an
index (`a[i].y` as `a + i * 4 + 2`) still needs the range, since a defined access there only
shows `i * 4 >= -2`.
