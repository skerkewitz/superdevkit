# 19. dampf assembles and links itself, with ca65 and ld65 as the oracle

**Context.** dampf wrote ca65 source and built the ROM with ca65 and ld65 from cc65, which a
user had to install (on Arch Linux from source). For the code itself an assembler of dampf's own
gains nothing: with whole-program compilation the compiler already knows every instruction size
and controls placement through segments. dampf uses only a small part of ca65 (its own output
and the runtime) and of ld65 (its own memory map). The original plan placed this last, after M5.

**Decision.** dampf assembles and links itself (`dampf.asm`, milestone M6), so that using dampf
needs nothing but Java once the preprocessor is its own (record 7). Decided and done on
2026-11-18, ahead of M5. It accepts the ca65 subset dampf writes and its runtime uses; anything
else is an error. `dampf build --assembler ca65` keeps the old path. ca65 and ld65 stay as the
oracle in the tests: `BackendOutputTest` builds every program both ways, and the ROMs must be
the same byte for byte, the label files must name the same addresses and the debug files must say
the same (record 20).

**Consequences.** Developing dampf still needs cc65 2.19, using it does not. dampf packs the
banks in one step, where ld65 needed two links. Hand-written assembly linked in (`--replace`)
must keep to the documented subset of ca65.
