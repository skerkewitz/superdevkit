# ADR-6F0R64GD: Every result matches gcc on x86-64 bit for bit

**Context.** Blubber Blasen's game logic is built and tested on the PC with gcc and clang, and
its replays are the oracle for the SNES build. If the SNES build computed anything differently,
the PC's tests would say nothing about the console. Plain `int` is 32 bits on the PC and 16 bits
with the other 65816 compilers (816-tcc, Calypsi).

**Decision.** Every operation gives exactly the result gcc and clang give on x86-64. Integer
constants and the integer promotions use a 32-bit `int`, overflow of unsigned types wraps,
conversions to narrower types truncate, signed division rounds towards zero, and `>>` of a
negative value is an arithmetic shift. Game code says what width it means:

- `int8_t` to `uint32_t` only; plain `int`, `short`, `long`, `unsigned`, `signed`,
  `long long`, `float`, `double` and `_Bool` are rejected, in casts as well (the exception is
  `int main(void)`, which the PC build needs);
- a constant that is a 64-bit `long` on the PC is rejected;
- dampf's `<stdint.h>` has the PC's underlying types (`int32_t` is `int`).

dampf narrows an operation to 8 or 16 bits only where it can prove the result is identical.

**Alternatives.** The 16-bit `int` of the other 65816 compilers: cheaper where the promotions
widen, but the PC build would compute something else.

**Consequences.** Narrowing is the optimizer's work (width analysis and value ranges), not the
programmer's. Correctness comes before speed: no optimization may change a result. The tests
compare every program with gcc (ADR-6F0R6G1M). Where the two machines cannot agree, dampf rejects
the construct (ADR-6F0R6BSK) or says what agrees (a pointer cast to an integer agrees only in the
distance between two places of one object).
