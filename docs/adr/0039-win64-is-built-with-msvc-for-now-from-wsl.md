# 39. win64 is built with MSVC, for now from WSL

**Context.** The desktop frontend ran on Linux only, built under WSL (`tools/build_desktop.sh`).
WSL sees no gamepad: the Windows side keeps every input device, the WSL kernel has no joystick
drivers, and a Bluetooth controller cannot be handed over at all. Playing with a joypad needs a
Windows program. ADR record 31 left the compiler of `win64` open ("MinGW's gcc or MSVC, decided in
the step"). The development machine has Visual Studio on its Windows side.

**Decision.** `win64` is compiled with MSVC (`cl`, Visual Studio 2022 or later with the C++ tools
for x64) and linked with raylib's ready-built MSVC package (`raylib-5.5_win64_msvc16.zip`, `/MD`).
Until the `superdevkit` command, `tools/build_desktop_windows.sh` builds it from WSL: it finds
Visual Studio with `vswhere`, sets it up with `vcvars64.bat` and runs `cl` through `cmd.exe`, in the
three steps of the Linux build: the game alone without the host's headers (`/X`), the symbol check
(`nm` reads MSVC's objects), the link as a Windows program without a console. The Linux build stays
the reference of the checks.

**Alternatives.**

- MinGW-w64 (gcc, or clang with MinGW's headers), as a cross compiler on Linux: needs neither
  Windows nor Visual Studio, and is gcc like the Linux build; but it is a second toolchain to
  install, while Visual Studio is what a Windows user of the kit has.
- clang for the MSVC target with Microsoft's SDK fetched to Linux (`xwin`): Microsoft's licence
  accepted on Linux, and raylib would have to be built for it.
- Building on Windows only, from a Windows shell: the repository and the checks live in WSL.

**Consequences.** Every example builds with MSVC without a warning (Schach has one implicit
narrowing gcc does not report), and its `--check`, run from WSL, writes the same checksums and the
same screenshots as the Linux build, byte for byte, although `long` has 32 bits there. Three
things were needed: `#pragma function(memset, memcpy)` in dampf's `<string.h>` (MSVC refuses to
define its intrinsics), the trap of `DAMPF_RANGE` without `<assert.h>` for MSVC (which calls itself
hosted even with `/X`), and `hashes.txt` written in binary mode (no CR). The symbol check knows
MSVC's own symbols (`__ImageBase`, the `/GS` checks). The `superdevkit` command will call `cl` the
same way, directly on Windows.
