# 10. Pointers are 4 bytes, and no object crosses a bank or exceeds 32 KB

**Context.** Blubber Blasen's ROM data is larger than one bank (the PVSnesLib build has 310 KB
of ROM), and the pointer fields of `LevelDef` and the `const char *` string tables point into
ROM. So some pointers must be 24 bits. The object pointers like `Bubble *b` only ever point into
RAM.

**Decision.**

- A pointer is 4 bytes in memory: the 16-bit address, the bank and a zero byte (MIR's `ptr`
  type). A pointer to RAM has bank `$7E`, a pointer to ROM the bank of its data.
- No object crosses a bank boundary, so pointer arithmetic only changes the low word, and no
  object is larger than 32 KB, so every offset within one fits a signed 16-bit number.
- The last byte of every ROM bank and of RAM (`$7E:FFFF`) stays free, so a pointer just past
  the end of an object never wraps to the start of its bank and compares greater than every
  pointer into the object.
- A pointer that provably points into one known object becomes a 16-bit offset into it, within
  a function and through parameters (`PointerOffsets`, `PointerParams`).

**Alternatives.** 16-bit pointers for everything whole-program analysis shows to point only into
RAM: recommended in [subset.md](../subset.md) (decision 3) and still open.

**Consequences.** A pointer costs 4 bytes of RAM and is read through the direct page
(`lda [place],y`), or copied to `dampf_ptr` first. Its `sizeof` differs from the PC's 8 bytes,
which record 11 deals with. A single function or constant object can be at most 32 KB.
