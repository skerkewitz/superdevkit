# ADR-6FKZFDJ3: Enum objects take only values of their enum and the smallest width that holds them

**Context.** On the PC an enum object takes 4 bytes: gcc gives an enum the type `unsigned int`,
or `int` when an enumerator is negative. dampf stored enum objects in 4 bytes too, and accepted
any integer stored into one. The game's only enum object, a state of the game, has a handful of
enumerators and would fit in a byte; with 4 bytes every access to it is 32-bit work on the
65816. Narrowing the object is only safe if it never holds a value outside its enumerators,
which C does not promise. This was decision 2 of [subset.md](../subset.md#decisions), open with a
recommendation until the user took it on 2026-11-24, as recommended.

**Decision.** The checker allows only two kinds of value to be stored into an object of an enum
type: an enumerator of that enum, and a value of that enum type (another such object, a call of
a function that returns the enum, an assignment to such an object, a `?:` whose two branches are
such values). A cast `(enum E)x` is allowed from a value of `E` or from a constant that equals
one of `E`'s enumerators, nothing else. `++`, `--` and compound assignments on an enum object are
rejected, and so is a conversion between a pointer to an enum and a pointer to any other type
except `void`. Every other store into an enum object is an error.

dampf then stores an enum object in the smallest width that holds all of its enumerators:
unsigned 8 or 16 bit when none is negative, signed 8 or 16 bit otherwise, and 32 bit when 16 do
not reach. Read, the value is promoted to the enum's type on the PC (`unsigned int` or `int`),
so every computation with it gives gcc's result. Both code generators use the same widths, since
they come from the type.

**Alternatives.**

- Keep 4 bytes. Nothing to check, but every state variable costs 32-bit loads, stores and
  compares; games would keep writing `uint8_t` with the enumerators as constants instead of the
  enum, as the examples do.
- Narrow the object without the rule. A value outside the enumerators (`state = 300`) would be
  cut off on the SNES and kept on the PC, against ADR-6F0R64GD.
- Allow any constant in the range of the enumerators, or check values at run time in the PC
  build. Broader than the decision asked for, and a check at run time finds a mistake only when
  the path runs. Can be added later if real code needs it.

**Consequences.** `sizeof` of an enum type, an enum object, or a struct with an enum field now
differs from the PC and is rejected by ADR-6F0R6BSK; the element count `sizeof a / sizeof a[0]`
of an array of enum objects stays allowed. Struct fields and array elements of an enum type take
1 or 2 bytes (4 only for an enum beyond 16 bits), so structs that hold them shrink. A store through a `void *` (`memset`, `memcpy`)
is not checked; zero always fits, and copying a whole object keeps its value. By the survey in
subset.md the game stores only values of its own enum into its enum object, so it should keep
to the rule; where it does not, `dampf check` names the place.
