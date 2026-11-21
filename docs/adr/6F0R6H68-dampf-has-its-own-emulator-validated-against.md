# ADR-6F0R6H68: dampf has its own emulator, validated against SingleStepTests and Mesen

**Context.** The program tests have to run on every build, inside the JVM, also on machines
without Mesen (which is a Windows program and not in git). The static timing (ADR-6F0R6JD6) and the
optimizations need cycles per function and per tick, counted with the memory speeds of the SNES.

**Decision.** `dampf.emu` is a 65816 CPU with LoROM and WRAM, without graphics or sound, that
counts master cycles with the SNES's memory speeds (WRAM and SlowROM 8, I/O 6, internal cycles
6, DRAM refresh 40 per scanline). It runs the execution tests, the random programs, the replays
and the game's benchmark. It is validated against the SingleStepTests 65816 suite (native mode,
all 256 opcodes, registers, flags, memory, cycles and the number of memory accesses) and against
Mesen on the game's benchmark (the same checksums on all ticks, the same master cycles within a
few cycles out of millions).

**Consequences.** The tests need no Mesen; when one is there, the execution tests and the replay
through the platform layer run in it as well. Emulation mode is modelled only as far as the
start-up needs. Everything that needs the PPU (the playable ROM, bytes lost outside the vertical
blank) needs Mesen.
