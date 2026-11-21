# ADR-6F0R6C4B: LoROM with FastROM, code from bank $80, one segment per function, `JSL` for every call

**Context.** Code and constant data are larger than one bank, and an access through the banks
from `$80` up can run at FastROM speed: 6 master cycles per ROM access instead of 8. Flash
cartridges and emulators run FastROM.

**Decision.**

- LoROM, up to 4 MB, banks `$80` to `$FF` with 32 KB each, never their mirrors from `$00`. The
  reset code jumps from bank `$00` to bank `$80` and switches ROM to 3.58 MHz (`MEMSEL`); the
  header says FastROM. `dampf build --slowrom` builds for a SlowROM cartridge.
- Bank `$80` holds the parts with fixed places: start-up, the runtime's routines, the initial
  values of variables, the header and the vectors.
- Every function and every constant object is a segment of its own; no segment crosses a bank
  boundary, and segments are packed into banks largest first.
- Every call is `JSL`/`RTL`, since caller and callee may be in different banks.

**Consequences.** Play got faster from 1.31 to 1.10 frames per tick when FastROM came in
(2026-11-17, [optimizations-log.md](../optimizations-log.md#fastrom-2026-11-17)); Calypsi gains
as much with it. A single function or constant object is at most 32 KB, and start-up, runtime
and initial values together must fit into bank `$80`. HiROM is not built.
