# ADR-6F3CK1V6: MesenCE is the emulator for the console checks and the debugger

**Context.** ADR-6F3CJMJ5 made Mesen 2 the debugger, and its test runner (`--testrunner` with a
Lua script) also ran the execution tests, the replays and the checks of the examples and of the
game's ROM. Three things came up together. The full verification took about 20 minutes, most of
it in Mesen: 4 to 8 minutes per example check. The Mesen 2 repository (SourMesen/Mesen2) is
archived since 2026-06; its successor is MesenCE (nesdev-org/MesenCE), the same code continued
by the community, with the same Lua API, a release for Linux, Windows and macOS, and fixes to
the SNES PPU since. And the console checks only worked with the Windows Mesen started from WSL:
the Linux build of Mesen 2.1.1 aborts on this machine with `std::bad_cast` when it starts.

Measured on the CatchMe check (1500 ticks): 290 s in Windows Mesen 2.1.1 from WSL, 6 s without
the script's callback on the PPU ports. That callback called `emu.getState()` for every byte
written to VRAM, CGRAM or OAM, which builds the whole machine state as a Lua table. The time
came from that script, not from Mesen or WSL.

Without that callback the screenshots stopped matching the desktop build. At full speed Mesen
skips drawing a frame when less than 10 ms passed since the last one drawn
(`SnesPpu.cpp`, unless `Snes.DisableFrameSkipping`), so a screenshot shows an older frame, and
which one depends on the speed of the host. The slow callback had hidden this: every frame took
longer than 10 ms.

**Decision.**

- MesenCE is the emulator for everything that needs a whole console: the debugger (ADR-6F3CJMJ5),
  the execution and replay tests in Mesen, and the checks of the examples and the game's ROM.
  dampf's own emulator stays for the compiler's tests and does not grow into a console
  emulator.
- `dev/fetch_mesen.sh` puts a pinned version into `.mesen/`: the Linux build and the Windows
  build side by side, both portable, with the settings the checks need in its `settings.json`
  (a SNES joypad in both ports, Lua file access, no frame skipping). Scripts and tests take the
  Linux build when it is there, else the Windows one.
- The checks tell a byte written outside the vertical blank from two flags: the vertical blank
  runs from the end of a frame (the `endFrame` event, at scanline 225) to the start of the next
  (`startFrame`), and the screen is off while bit 7 of INIDISP ($2100) is set. They read
  `emu.getState()` only where a byte was lost.

**Alternatives.**

- Keep Mesen 2.1.1: it works on Windows, but it is frozen, and its Linux build does not start
  here.
- A harness around the bsnes libretro core: accurate and fast, but a second emulator beside
  the debugger, which a game developer would have to install and which behaves a little
  differently from the one they debug with.
- Grow dampf's emulator into a console emulator (PPU, DMA, vertical blank): months of work for
  something less exact than an existing emulator.

**Consequences.** The check of an example takes seconds instead of minutes (hello 2 s, CatchMe
and Steinschlag 7 s each, the game's ROM 15 s), and its screenshots match the desktop build
pixel for pixel. On Linux the build needs libICE, libSM and SDL2 even headless, because its UI
toolkit starts first; on Arch the fetch script unpacks them into `.mesen/lib`. A game developer
on Windows uses the Windows build of the same version. A change of the version is a change of
`dev/fetch_mesen.sh` and a run of the checks.
