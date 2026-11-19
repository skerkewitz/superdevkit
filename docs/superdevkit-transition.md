# superdevkit: the plan

The way from today's repository (dampf, its library for the SNES, a snapshot of Blubber Blasen) to
superdevkit as the session of 2026-11-18 decided it
([brainstorm](brainstorm/superdevkit-targets_2026-11-18.md)): a game is a folder with its code, its
assets and a reference to superdevkit, and the kit builds it for the SNES, Windows, Linux and the
web. This file holds what is decided and not built yet; a step that is built leaves it, and what
was decided goes to the ADR. Milestones and their state are in [roadmap.md](roadmap.md).

Every step ends with a commit on its branch (`m5`, later one per milestone) that passes
`./gradlew build`, which plays every example on the SNES side and the desktop; steps that touch
the console also pass `dev/compare_example.sh` in Mesen.

## The target

```
mygame/                        a game, created by superdevkit init
├── superdevkit.toml           name, superdevkit version (or path), sources and assets per target
├── src/                       the game code, written once, no platform #ifdef
├── assets/                    PNG for graphics, WAV/OGG for sound and music
└── build/<target>/            what superdevkit build <target> writes (not in git)
```

```
superdevkit build snes|win64|linux|web [--verbose] [--dry-run] [--plan]
superdevkit run [snes|web]     the machine's own desktop target by default
superdevkit init <name> [--from <example>]
```

The kit:

| Part | What |
| --- | --- |
| `compiler/` (today `src/`) | dampf |
| `lib/include/sdevkit/` | the console API (`snes.h`), the frame rhythm (`loop.h`), sound, save RAM and debug registrations; every name `sdevkit_`/`SDEVKIT_` (ADR record 34). `lib/include/snes/` maps the old names until the game switches |
| `lib/snes/` | the SNES: the API in C, the main loop |
| `lib/rachael/` | [Rachael](../lib/README.md#rachael), the pseudo SNES, for the desktop and the web: video memory, registers, the renderer, portable C (from Blubber Blasen's `src/snes`) |
| `lib/desktop/` | the frontend of `win64` and `linux` (raylib): window, scaling, input, sound, save file, debug view, screenshots |
| `lib/web/` | what the web build adds to it: the page, IndexedDB for save RAM, the click to start |
| `tools/` | converters for game developers (`png2snes.py`, later sound) |
| `cli/` | the `superdevkit` command (Java) |
| `examples/` | `hello`, `catchme`, `steinschlag`: projects in the shape above |

## The milestones

| Milestone | Version | Goal |
| --- | --- | --- |
| M5 | 0.5.0 | Blubber Blasen builds its SNES ROM with the dev kit from its own repository; the examples build for the SNES |
| M7 | 0.7.0 | Windows and Linux: Rachael and the desktop frontend in the kit, the CLI with `init`, `build`, `run`; the examples and Blubber Blasen run on the desktop from the kit |
| M8 | 0.8.0 | The web: the same games in the browser; Blubber Blasen drops its own PC side |

The milestones planned before (the optimizer, language and platform, timing and debugging, fine
tuning) move from M7–M10 to M9–M12 (ADR record 26 on renumbering). macOS, `bundle`/`deploy`, the
desktop running the ROM in an emulator and Mesen as the desktop are not scheduled.

## M5: the SNES (0.5.0)

Done so far on `m5`: the version 0.5.0; `dev/`, `lib/snes`; the console API and the
main loop in the library (`lib/include/sdevkit/`, `LibApiTest`, `LibNamesTest`); steps 5.1 and 5.2; the three examples.

### 5.1 dampf can be called by a game

Built: argument files, every error of a run, `<string.h>` ([roadmap](roadmap.md#m5-blubber-blasen-builds-with-the-dev-kit-050)).

### 5.2 The converter

Built: `tools/png2snes.py`, tested by `ToolsTest`.

### 5.3 The examples on the SNES

Each example is a project: `README.md`, `<name>.args` (until the CLI of M7, the call of dampf),
`src/`, `assets/` (PNG, drawn for the example), `build/`; a `build.sh` that runs the converter and
dampf, until the CLI replaces it. Each runs in Mesen with a script of scripted input
(`dev/check_example.sh <name>`: screenshots, bytes lost outside the vertical blank).

- **hello** (built: `examples/hello`; still to do: the book's getting-started on it, and `examples/m1` gone): `examples/m1` as a project: a sprite and text on the screen, stepping through C in
  Mesen. The book's getting-started page uses it.
- **CatchMe** (built: `examples/catchme`, plays in Mesen with `dev/check_example.sh`, built by
  `ExamplesTest`): a maze-chase game of its own (its own maze, characters and look): a mouse eats
  crumbs in a maze, four cats with different ways of chasing, a chili that turns the tables for a
  while, score, lives, levels that get faster. 8 x 8 background tiles for the maze, 16 x 16
  sprites. A good first example for a new SNES developer: one screen, no scrolling.
- **Steinschlag** (built: `examples/steinschlag`, its scrolling checked against the desktop): a dig-and-boulders game of its own: a miner digs through dirt, boulders and
  gems fall and roll, gems open the exit, beetles follow the walls, caves larger than the screen.
  Cells of 16 x 16 drawn from 2 x 2 background tiles; the cave is 40 x 22 cells (640 x 352 pixels),
  so the camera scrolls in x and y; the background map holds 64 x 64 tiles (512 x 512 pixels), so
  the columns that come into view are copied into it in the vertical blank (scrolling in x by
  streaming, in y by the scroll register alone).

Done when: the three build, play in Mesen with scripted input without losing a byte, and have a
README; the screenshots look right.

### 5.4 Correctness

- **The miscompilation with inlining up to 80** found and fixed, with an execution test.
- **Random programs that reach the optimizer**, and execution tests for its patterns.

### 5.5 The book (ADR record 30)

dampf's pages into `reference/compiler/` and `inside/compiler/`, `doc-specs/` following; the user
guide becomes the dev kit's (getting started from `hello`); `reference/api/` for the console API.

### 5.6 The handover to the game

In [game-findings.md](game-findings.md), for the game's agent: a build of the SNES ROM with the
dev kit next to the PVSnesLib one (an argument file in the game's repository, `-I lib/include`,
`lib/snes/*.c`), the data as plain C (`map2c`, `gfx2c` without `--snes`), `BB_FAST` with a
`__DAMPF__` branch, an ADR of the game's own; the console API's new names (ADR record 34,
finding 8: `tools/sdevkit_rename.py`), after which the kit drops `lib/include/snes/`.

### 5.7 On a console (needs the hardware)

The playable ROM on a real SNES or flash cartridge, and the DMA margin `SETUP_LINES` measured
again.

On a PAL console too, from the same ROM, with NTSC's limits; on PAL the game runs a sixth slower
for now (ADR [record 36](adr/0036-one-rom-for-ntsc-and-pal-with-ntsc-s-limits-and.md)).
Checked in Mesen with the region set to PAL before the console: the speed, and no byte lost.

## M7: Windows and Linux (0.7.0)

### 7.1 Rachael, the pseudo SNES, in the kit

Built (during M5): `lib/rachael`, with 16 x 16 background tiles and the picture's rows as scanlines
one on, so that it matches the SNES pixel for pixel. Still to do: its tests from the game's
`tests/test_snes.c` (the parts that test its pseudo SNES); the game's copy now differs on purpose
(the two changes), which the handover to the game has to say.

### 7.2 Sound, save RAM and the debug registrations in the API

New headers in `lib/include/sdevkit/`, with the names of ADR record 34, built in `lib/rachael` and
`lib/desktop`, stubs in `lib/snes`, rows in `lib/README.md`'s table:

- `sound.h`: `sdevkit_sfx_play(id)`, `sdevkit_music_play(track)`, `sdevkit_music_stop()`. The
  numbers come from the project file (the converter writes `SDEVKIT_SFX_*` and
  `SDEVKIT_MUSIC_*` into a header); on the desktop the frontend plays the files. On the SNES
  nothing yet; later a queue the main loop sends to the sound processor in the vertical blank.
- `sram.h`: `sdevkit_sram()` (the block) and `SDEVKIT_SRAM_SIZE` from the project file; the
  frontend saves it when it changed and on exit.
- `debug.h`: `sdevkit_dbg_toggle(name, &flag)`, `sdevkit_dbg_range(name, &value, lo, hi)`,
  `sdevkit_dbg_color(name, &colour)`, `sdevkit_dbg_action(name, function)` once at start;
  `sdevkit_dbg_box(x, y, w, h, kind)` per frame. Empty on the SNES; an execution test checks that
  dampf removes the calls (cycles of a tick with and without them).

Done when: `LibApiTest` covers the new headers; an execution test per stub on the SNES.

### 7.3 The desktop frontend

Built for Linux (during M5): `lib/desktop/main.c` with a window, the keyboard and a gamepad, the
frame rhythm, F12 for a screenshot, and `--check` without a window; `tools/build_desktop.sh` and
`tools/fetch_raylib.sh` until the CLI; Windows with MSVC from WSL, `tools/build_desktop_windows.sh`
(ADR record 39); the CRT filter of Blubber Blasen's frontend (`lib/desktop/crt.c`, Tab, `--no-crt`);
every session recorded, F11 saves it, `--replay` with pause and single steps, the recording
played on the SNES side by `dev/replay_example.sh`.
Still to do: sound and music, save RAM, the debug view. The plan as it was:

`lib/desktop` in C with raylib: the window (256 x 224 scaled to whole multiples), the frame
rhythm of `loop.h` (`game_tick`, then `sdevkit__vblank`, `game_vblank`, `sdevkit__render`),
keyboard and gamepad to `SDEVKIT_KEY_*`, sound and music from files, save RAM in a file next to the program, a
screenshot key, the debug view (Dear ImGui through its C binding, or raylib's own immediate mode
GUI, decided in the step): pause, step, slow motion, the registered switches, ranges, colours and
actions, the boxes as overlays, views of VRAM, palettes and sprites. The CRT filter of Blubber
Blasen's frontend as an option.

Done when: CatchMe runs on Linux and Windows from a build of its own sources and the kit's.

### 7.4 The CLI

`cli/`, Java, in the same distribution as dampf, launcher `superdevkit`:

- reads `superdevkit.toml` with a TOML reader of its own (the subset the project file uses);
- `init <name> [--from <example>]` copies an example and sets its name;
- `build <target>`: runs the converters for the assets, then dampf (`snes`) or the compiler of
  the target (`linux`: gcc or clang; `win64`: MSVC, ADR record 39; `web`:
  `emcc`, in M8) with fixed options per target, links raylib from the kit (ready-built per target,
  pinned to one version); writes `compile_commands.json`;
- `run [target]`: builds and starts the program (`snes`: Mesen with the ROM);
- `--verbose` prints every step and call, `--dry-run` checks that everything is there and does
  nothing, `--plan` is both;
- checks the project's `superdevkit` version against its own, or uses the path the project names.

Done when: `superdevkit init`, `build` and `run` work for the examples on Linux and Windows, with
tests of the TOML reader and of the plan each command makes (`--plan` output).

### 7.5 The examples and the game on the desktop

The three examples get `superdevkit.toml` (their `build.sh` and `.args` go). The handover to the
game: Blubber Blasen's desktop build from the kit (its sound calls from `game_sfx()` to
`sdevkit_sfx_play()`, its save RAM to `sdevkit_sram()`, its debug view to registrations; its
recordings stay its own, reached through `sdevkit_dbg_action`).

## M8: the web (0.8.0)

- `web` as a target of the CLI with `emcc` and raylib's web build; `lib/web`: the page, the click
  to start (sound and keyboard focus need a click in the browser), save RAM in IndexedDB.
- The game is compiled on its own as on the desktop (ADR record 34, `tools/build_desktop.sh`):
  `-nostdinc` with dampf's include folder, then `tools/check_game_symbols.py` on its objects
  (`emcc` writes LLVM objects; `llvm-nm` reads them) before the link.
- `superdevkit run web` serves the build on a local port and opens the browser.
- The examples in the browser; Blubber Blasen's web build from the kit, and then the game drops
  `src/snes`, `src/platform` and its web build: its folder holds its game, its assets, its debug
  registrations and `superdevkit.toml`.

## Not scheduled

macOS (raylib runs there; nobody can test it now), `bundle`/`deploy` (one folder with every
target), the desktop running the ROM in an emulator, Mesen as the desktop, SPC700 sound and SNES
save RAM.
