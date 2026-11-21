# ADR-6F0R6BSK: Structs have no padding, and a `sizeof` that differs from the PC is an error

**Context.** On the PC a pointer is 8 bytes and gcc pads struct fields to their alignment
(`struct { uint8_t a; int16_t b; }` is 4 bytes). dampf uses 4-byte pointers (ADR-6F0R6A8V) and no
padding (3 bytes). A program that uses such a `sizeof` would compute other values than its PC
build, against ADR-6F0R64GD.

**Decision.** Structs are laid out without padding. A `sizeof` whose value differs from gcc's
on x86-64 is rejected, with a message that gives both values. The exception is a division whose
quotient is the same on both sides: `sizeof a / sizeof a[0]`, the element count, also over an
array of pointers.

**Alternatives.**

- Report the PC's value but keep the compact layout: silently wrong as soon as code depends on
  the layout.
- Lay structs out like gcc: costs RAM, and pointers still differ.
- Accept the difference as a documented exception: it would only help code that needs the SNES
  size, such as a DMA length taken from a padded struct, which the game does not have.

None of the options would have made the code faster.

**Consequences.** The game's four uses of `sizeof` are all accepted. Code that needs the size of
a padded struct or a pointer on the SNES has no way to get it today.
