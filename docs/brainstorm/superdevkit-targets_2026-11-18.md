# superdevkit: the desktop and web targets, and what a project is

Brainstorming session of 2026-11-18. The plan made from it is
[superdevkit-transition.md](../superdevkit-transition.md).

## Problem

An MVP of superdevkit, whose desktop and web builds are for rapid prototyping and sharing for
feedback, and whose SNES build is the real product. A game is a folder with its game code, its
assets and a reference to superdevkit, created by `superdevkit init`. `superdevkit build
<target>` converts the assets and builds for one target: the SNES with dampf, which the kit
brings; Windows, Linux and the web with the user's own compiler; macOS later. `superdevkit run`
starts it on the machine you are on. On the desktop and the web the kit's frontend brings a debug
view to which a game adds what it needs; the SNES has none. Blubber Blasen is the test: at the end
its folder holds only its game, its assets and the reference to superdevkit.

Constraints: an MVP first, made to work at all, improved later. The kit brings only dampf; the
compilers for the other targets are the user's. Game code has no platform `#ifdef`s, and every
function of the console API exists on every platform (ADR record 29). The game logic stays
deterministic, so that recordings and replays keep working.

Before the session the open points were: how sound and music, save RAM and the game's debug view
enter the API; recordings, autoplay and screenshots; a CRT filter and scaling; and whether the
full switch of the game is part of M5.

## Who is affected

- **A new SNES developer starting from an example** wants three commands (create, build, run),
  sound and saving without knowing how they work, and does not want to learn Gradle, CMake and
  Python first.
- **An experienced developer** has their own editor and debugger and wants to see what the build
  does; a build they cannot look into loses them.
- **Someone running it in CI** (GitHub Actions for a game's tests) needs a build without a window,
  with a plain call.
- **Someone giving feedback** on a prototype installs nothing: a link to the web build is the
  easiest way to share.
- **Someone on macOS** can use raylib, but nobody can test it now; the docs say so.
- **The game's agent** rebuilds Blubber Blasen onto the kit and needs a clear contract.

## Ideas

### Background: how Blubber Blasen and its frontend talk today

The game draws by calling the console API (`setMode`, `dmaCopyVram`, `oamSet`); on the PC these
write into the pseudo SNES, whose renderer the frontend calls after each frame. For everything
else the direction is reversed: the frontend asks the game. After each tick it calls `game_sfx()`
(a bit mask of the sound effects the tick requested), `game_music()` (the track that should be
playing), `game_sram()` and `game_sram_dirty()` (128 bytes to save), and for the debug view about
20 `game_debug_*()` functions, whose answers it draws with Dear ImGui (`debug_ui.cpp`).

### A. Sound and save RAM

- **A1, pull as today.** `loop.h` gets `game_sfx()`, `game_music()` and `game_sram()`, which every
  game defines; the main loop asks for them after each tick. No change for Blubber Blasen, and a
  tick only computes and triggers nothing.
- **A2, the game calls.** `soundPlay(id)`, `musicPlay(track)`, `sramWrite(...)` in the console
  API, built on the desktop and stubs on the SNES. The pattern of PVSnesLib and of the rest of the
  console API.
- **A3, sounds as assets with numbers.** The project file lists sounds and music; a generated
  header gives their numbers; the kit packs the files per target (files on the desktop, data in
  the web build, samples for the SPC700 later). The game never names a file, so the code fits the
  SNES later.
- **A4, save RAM as memory.** A fixed block of memory the game reads and writes, saved by the kit
  when it changed (a file, IndexedDB in the browser, the battery RAM of a cartridge later). How the
  SNES really works; no save call to forget.

### B. The debug view

- **B1, an ImGui callback.** The game defines `debug_panels()` in C or C++, which the kit calls in
  its ImGui frame. Blubber Blasen's `debug_ui.cpp` would move almost unchanged.
- **B2, registered values.** The game registers switches, ranges and colours; the kit draws generic
  controls. No C++ or ImGui in the game. With the kit's standard views (collision boxes, VRAM,
  palettes, sprites) this covers what one always wants.

### C. Project, commands, build

- **C1, the CLI in Java with a TOML project file.** One tool from the same distribution as dampf;
  it reads the project file, runs the converters, calls dampf and the user's compiler.
- **C2, CMake as the backbone.** A project is a CMake project with a module of the kit; good for
  IDEs, painful when it fails, and hard to drive for an AI.
- **C3, `init` copies an example.** No generator; the template is a working project the tests
  build anyway.
- **C4, wild: the desktop runs the ROM.** Desktop and web run the SNES ROM in an emulator in the
  kit (dampf's emulator plus a PPU) instead of compiling the C code natively; one build, no foreign
  compiler, exactly the SNES's behaviour and speed.
- **C5, changing the requirement: Mesen as the desktop.** `run` builds the ROM and starts Mesen;
  sharing is the ROM file. Almost no frontend work, and Mesen's debugger.
- **E1, the CLI calls the compiler directly.** The project file lists the sources, so the CLI
  makes the compiler and linker calls itself, with fixed options per target; raylib ready-built
  per target from its releases; a `compile_commands.json` for editors.
- **E2, Zig as the compiler.** `zig cc` brings its own Clang, linker and C libraries and builds
  Windows and Linux from any machine.
- **E3, CMake hidden behind the CLI.**

### D. The milestone

- **D1**, the full switch of the game in M5. **D2**, M5 stays the SNES ROM and the switch is the
  next milestone. **D3**, split by target: the SNES, then the desktop, then the web.

## Ratings

| Idea | Realistic? | Cost | Fragility | Fully solves it? | Works for others? | New problems | Reversible? | Fits the plans? |
|---|---|---|---|---|---|---|---|---|
| A1 pull | yes, exists | none for the game | low | yes | awkward for new games: a bit mask and two functions even without sound | at most 32 effects | yes | yes |
| A2+A3 call with numbers | yes | a day | low: numbers, not names | yes | natural for everyone; on the SNES a stub now, a queue to the sound processor later | Blubber Blasen changes about 20 places | yes | yes, record 29 |
| A4 save RAM as memory | yes | half a day | low, if saved on change and on exit | yes | intuitive for SNES developers and beginners | IndexedDB writes are asynchronous | yes | yes |
| B1 ImGui callback | yes | small | low | more than needed | needs C++ in the game | ImGui in every game | yes | not what was wanted |
| B2 registered values + standard views | yes | 2–3 days | low | yes | beginners and experts; Blubber Blasen's CPU-partner view maps onto boxes and switches, losing its text panel | the calls stay in the SNES build as empty functions (dampf inlines them away; to check) | yes | yes |
| C2 CMake | yes | a week | high | yes | IDE users gain; when it fails it is painful, hard for an AI to drive | a second layer to debug | hard | not wanted |
| E1 direct calls | yes | 2–3 days, mostly Windows (MinGW against MSVC) | medium: options per platform; raylib pinned | yes for the MVP | one command; `compile_commands.json` for IDEs; one call in CI | no incremental build at first | yes | yes |
| E2 Zig | yes | small on E1 | low | Windows and Linux only: macOS still needs Apple's SDK (Zig cannot ship Apple's frameworks), the web still needs Emscripten | one install fewer | Zig is pre-1.0 | yes | no gain in structure |
| E3 CMake hidden | yes | a week | high: its errors surface anyway | yes | hides the complexity, does not remove it | two layers | hard | not wanted |

## Recommendation

A2 with A3 for sound, A4 for save RAM, B2 with standard views for the debug view, C1 with C3 and
E1 for the build, D3 for the milestones. The trade-off: Blubber Blasen changes its sound calls
from a bit mask to calls and its debug view from ImGui code to registrations; the kit stays
simple for every game after it. An experienced developer gives up a build system of their own,
but sees every call (`--verbose`, `--plan`) and gets `compile_commands.json`.

## Decision

- Sound and music are postponed on the SNES (stubs there); on the desktop and the web they are
  built, in the form recommended (A2 + A3), since Blubber Blasen has sound already.
- Save RAM as memory (A4).
- The debug view exists on the desktop and the web only; it has the kit's standard views
  (collision boxes, the SNES's VRAM, palettes and sprites), and a game registers switches, ranges
  and colours and draws boxes (B2). No fully customisable view.
- The CLI is Java with a TOML project file (C1); `init` copies an example (C3); the CLI calls
  the compilers directly (E1), with `--verbose`, `--dry-run` and `--plan` (both together). No
  CMake, no Make, no shell scripts, no Zig.
- The project file names the superdevkit version it needs, and the CLI is found on the path; a
  path to a superdevkit folder is allowed too, for working on the kit and a game at once.
- Targets: `snes`, `win64`, `linux`, `web`; macOS later, without a test platform now.
- Milestones split by target (D3): M5 the SNES as planned, then the desktop (Windows and Linux,
  with the CLI and `init`), then the web.
- Maybe later: the desktop running the ROM (C4), Mesen as the desktop (C5), `bundle`/`deploy`.

## Open questions

- Whether dampf removes a call of an empty function entirely, so that the debug registrations cost
  nothing on the SNES: to check once the API exists (an execution test with the cycle count).
- Windows: MinGW's gcc or MSVC as the first compiler of `win64`; raylib has ready-built libraries
  for both. To decide when the target is built.
- How Blubber Blasen's recordings, autoplay and screenshots fit: they stay the game's for now.
