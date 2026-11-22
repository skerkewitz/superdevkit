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
| `lib/include/sdevkit/` | the console API (`snes.h`), the frame rhythm (`loop.h`), sound, save RAM and debug registrations; every name `sdevkit_`/`SDEVKIT_` (ADR-6F3CK2AR); the old names' header `lib/include/snes/` went when the game switched (2026-11-19) |
| `lib/snes/` | the SNES: the API in C, the main loop |
| `lib/rachael/` | [Rachael](../lib/README.md#rachael), the pseudo SNES, for the desktop and the web: video memory, registers, the renderer, portable C (from Blubber Blasen's `src/snes`) |
| `lib/desktop/` | the frontend of `win64` and `linux` (raylib): window, scaling, input, sound, save file, debug view, screenshots |
| `lib/web/` | what the web build adds to it: the page, IndexedDB for save RAM, the click to start |
| `tools/` | tools for game developers (the BRR Lab, the rename script) |
| `cli/` | the `superdevkit` command (Java), with the converters (`png`, `sfx`) |
| `examples/` | `hello`, `catchme`, `steinschlag`: projects in the shape above |

## The milestones

| Milestone | Version | Goal |
| --- | --- | --- |
| M5 | 0.6.0 (planned as 0.5.0) | Blubber Blasen builds its SNES ROM with the dev kit from its own repository; the examples build for the SNES |
| M7 | 0.7.0 | Windows and Linux: Rachael and the desktop frontend in the kit, the CLI with `init`, `build`, `run`; the examples and Blubber Blasen run on the desktop from the kit |
| M8 | 0.8.0 | The web: the same games in the browser; Blubber Blasen's web build from the kit (its own desktop frontend goes with M10, the roadmap) |

The milestones planned before (the optimizer, language and platform, timing and debugging, fine
tuning) move from M7–M10 to M9–M12 (ADR-6F3CJT1P on renumbering). macOS, `bundle`/`deploy`, the
desktop running the ROM in an emulator and Mesen as the desktop are not scheduled.

## M5: the SNES (0.6.0)

Concluded on 2026-11-19 as version 0.6.0 (ADR
[ADR-6F60ZDMF](adr/6F60ZDMF-m5-is-concluded-as-060-and-a-version-between.md),
[roadmap](roadmap.md#m5)): Blubber Blasen's ROM, built with the kit, plays its recordings with
the PC's checksums. Steps 5.1, 5.2, 5.3 (but the getting started), 5.4's miscompilation and 5.6
are done. What is left moved: the rest of 5.4 to M9, `hello` as the getting started and 5.5 to the
book before the release, 5.7 until there is hardware.

### 5.1 dampf can be called by a game

Built: argument files, every error of a run, `<string.h>` ([roadmap](roadmap.md#m5)).

### 5.2 The converter

Built: `tools/png2snes.py`, tested by `ToolsTest`; in M7 it became Java, `superdevkit png`.

### 5.3 The examples on the SNES

Each example is a project: `README.md`, `src/`, `assets/` (PNG, drawn for the example), `build/`;
until the CLI of M7 an argument file (`<name>.args`) and a `build.sh` that ran the converter and
dampf, since then `superdevkit.toml`. Each runs in Mesen with a script of scripted input
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

- **The miscompilation with inlining up to 80** found and fixed, with an execution test (done
  2026-11-19: `exec/regress_branch_phis.c`).
- **Random programs that reach the optimizer**, and execution tests for its patterns.

### 5.5 The book (ADR-6F3CJYGP)

dampf's pages into `reference/compiler/` and `inside/compiler/`, `doc-specs/` following; the user
guide becomes the dev kit's (getting started from `hello`); `reference/api/` for the console API.

### 5.6 The handover to the game

In the game's findings (forwarded to it), for the game's agent: a build of the SNES ROM with the
dev kit next to the PVSnesLib one (an argument file in the game's repository, `-I lib/include`,
`lib/snes/*.c`), the data as plain C (`map2c`, `gfx2c` without `--snes`), `BB_FAST` with a
`__DAMPF__` branch, an ADR of the game's own; the console API's new names (ADR-6F3CK2AR,
finding 8: `tools/sdevkit_rename.py`), after which the kit drops `lib/include/snes/` (done
2026-11-19: the game uses the new names).

### 5.7 On a console (needs the hardware)

The playable ROM on a real SNES or flash cartridge, and the DMA margin `SETUP_LINES` measured
again.

On a PAL console too, from the same ROM, with NTSC's limits; on PAL the game runs a sixth slower
for now ([ADR-6F60Z4AZ](adr/6F60Z4AZ-one-rom-for-ntsc-and-pal-with-ntsc-s-limits-and.md)).
Checked in Mesen with the region set to PAL before the console: the speed, and no byte lost.

## M7: Windows and Linux (0.7.0)

Concluded on 2026-11-19 as version 0.7.0 ([roadmap](roadmap.md#m7)); Blubber Blasen's switch to the
kit's desktop build is with the game (its finding 1).

### 7.1 Rachael, the pseudo SNES, in the kit

Built (during M5): `lib/rachael`, with 16 x 16 background tiles and the picture's rows as scanlines
one on, so that it matches the SNES pixel for pixel. Its tests came from the game's
`tests/test_snes.c` (2026-11-19, `compiler/src/test/c/rachael_test.c`), after the game had moved
them to Rachael and her line offset itself.

### 7.2 Sound, save RAM and the debug registrations in the API

New headers in `lib/include/sdevkit/`, with the names of ADR-6F3CK2AR, built in `lib/rachael` and
`lib/desktop`, stubs in `lib/snes`, rows in `lib/README.md`'s table:

- Sound effects: built on 2026-11-19, otherwise than planned here, on both platforms
  ([sound.md](sound.md), ADR-6F60ZC7C): `sdevkit_sfx_init` and `sdevkit_sfx_play` in
  `sdevkit/snes.h`, the effects from `assets/sfx/sfx.json` through `superdevkit sfx`, an SPC700
  driver on the SNES and Rachael's DSP on the desktop, both playing the same BRR. Music
  (`sdevkit_music_play(track)`, `sdevkit_music_stop()`): built on the desktop the same day, from
  the files `superdevkit.toml` lists (`[sound] music`, `music.h`), a stub on the SNES
  ([sound.md](sound.md#music)).
- Save RAM: built on 2026-11-19, in `sdevkit/snes.h` rather than a header of its own:
  `sdevkit_sram()` (the block) and `SDEVKIT_SRAM_SIZE`, from `dampf --sram BYTES` on the SNES
  (the cartridge's battery RAM at `$70:0000`, announced in the header) and `-DSDEVKIT_SRAM_SIZE`
  on the desktop (`<game>.srm` next to the program, written when it changed and on exit). Checks
  and recordings name the save RAM they start with (`sram H`), dampf's emulator and Mesen's script
  load it; CatchMe keeps its high score there.
- The debug registrations: built on 2026-11-19, in `sdevkit/snes.h`: `sdevkit_dbg_toggle(name,
  &flag)`, `sdevkit_dbg_range(name, &value, lo, hi)`, `sdevkit_dbg_color(name, &colour)` and
  `sdevkit_dbg_action(name, &pressed)` once in `game_init`, `sdevkit_dbg_box(x, y, w, h, kind)` per
  tick. The action is a flag the view sets and the game clears in its next tick, not a function:
  dampf has no function pointers, and the game's state changes only inside a tick. Empty on the
  SNES; `DebugCallsTest` checks that dampf removes the calls (the cycles of a tick with and without
  them), which needed a pass of its own for the loops left empty (`DeadLoops`). CatchMe registers
  three and draws its actors' boxes.

Done when: `LibApiTest` covers the new functions; a test that the stubs cost nothing on the SNES.
Done 2026-11-19, with music on the SNES left for later.

### 7.3 The desktop frontend

Built for Linux (during M5): `lib/desktop/main.c` with a window, the keyboard and a gamepad, the
frame rhythm, F12 for a screenshot, and `--check` without a window; built by scripts until the CLI
(`superdevkit build linux`, and `win64` with MSVC, from WSL too, ADR-6F60Z709); the CRT filter of Blubber Blasen's frontend (`lib/desktop/crt.c`, Tab, `--no-crt`);
every session recorded, F11 saves it, `--replay` with pause and single steps, the recording
played on the SNES side by `dev/replay.sh` (and `dev/replay_example.sh` for an example, with Mesen).
Built during M7: sound effects (Rachael's DSP through raylib's audio stream), save RAM
(`<game>.srm`), and the debug view (`lib/desktop/view.c`: F1, beside the picture; drawn with raylib
alone, no GUI library: the registrations, the boxes, pause, a tick, slow motion, fast, CGRAM, VRAM's
tiles, the sprites), and music from files. The plan as it was:

`lib/desktop` in C with raylib: the window (256 x 224 scaled to whole multiples), the frame
rhythm of `loop.h` (`game_tick`, then `sdevkit__vblank`, `game_vblank`, `sdevkit__render`),
keyboard and gamepad to `SDEVKIT_KEY_*`, sound and music from files, save RAM in a file next to the program, a
screenshot key, the debug view (Dear ImGui through its C binding, or raylib's own immediate mode
GUI, decided in the step): pause, step, slow motion, the registered switches, ranges, colours and
actions, the boxes as overlays, views of VRAM, palettes and sprites. The CRT filter of Blubber
Blasen's frontend as an option.

Done when: CatchMe runs on Linux and Windows from a build of its own sources and the kit's.

### 7.4 The CLI

Built on 2026-11-19 (`cli/src/main/java/superdevkit/project`), as planned below. The PNG converter
became Java (`superdevkit png`, the same bytes as the Python one), the symbol check reads ELF and
COFF objects itself, raylib is fetched and unpacked in Java, and a SNES build also writes dampf's
argument file of what it compiled (`build/snes/<name>.args`). `run snes` starts Mesen (`MESEN`, or
the kit's `.mesen/`). The plan:

`cli/`, Java, in the same distribution as dampf, launcher `superdevkit`:

- reads `superdevkit.toml` with a TOML reader of its own (the subset the project file uses);
- `init <name> [--from <example>]` copies an example and sets its name;
- `build <target>`: runs the converters for the assets (in Java, part of the CLI, before the
  first public release: ADR-6F60Z9QJ), then dampf (`snes`) or the compiler of
  the target (`linux`: gcc or clang; `win64`: MSVC, ADR-6F60Z709; `web`:
  `emcc`, in M8) with fixed options per target, links raylib from the kit (ready-built per target,
  pinned to one version); writes `compile_commands.json`;
- `run [target]`: builds and starts the program (`snes`: Mesen with the ROM);
- `--verbose` prints every step and call, `--dry-run` checks that everything is there and does
  nothing, `--plan` is both;
- checks the project's `superdevkit` version against its own, or uses the path the project names.

Done when: `superdevkit init`, `build` and `run` work for the examples on Linux and Windows, with
tests of the TOML reader and of the plan each command makes (`--plan` output).

### 7.5 The examples and the game on the desktop

Built on 2026-11-19: the seven examples have `superdevkit.toml` (their `build.sh` and `.args` went;
the ROMs the command builds are the scripts', byte for byte), and the tests build them through the
CLI's classes. The handover, the game's finding 1 (sent on 2026-11-19), to the
game: Blubber Blasen's desktop build from the kit (its sound calls from `game_sfx()` to
`sdevkit_sfx_play()`, its save RAM to `sdevkit_sram()`, its debug view to registrations; its
recordings stay its own, reached through `sdevkit_dbg_action`).

## M8: the web (0.8.0)

Built on 2026-11-22 ([ADR-6FCVAA30](adr/6FCVAA30-the-web-runs-the-desktop-s-frontend-which-waits.md)):
`web` as a target of the CLI (`superdevkit.project.Web`) with `emcc` and raylib's prebuilt web
package, the game compiled on its own as on the desktop and its objects checked (`Symbols` reads
WebAssembly objects: emcc writes wasm, not LLVM bitcode), `lib/web` (the page with the click to
start, the browser's frames for the desktop's loops, save RAM in IndexedDB, F11 and F12 as
downloads), and `superdevkit run web` (`Serve`: the JDK's HTTP server on 127.0.0.1, the browser
opened). CatchMe plays in headless Chromium, its save RAM kept across page loads. The examples
in the browser: `ExamplesTest` builds each for the web and plays its `check.txt` in headless
Chromium (`tools/web_check`, Playwright; the web's `main` takes `--check`), the checksums of its
ticks, its sound and its recording against the SNES's. What is left:

- Blubber Blasen's web build from the kit. With the game: finding 3 in
  [game-findings.md](game-findings.md) (2026-11-22). The game builds for the web with the kit
  unchanged and plays its two checks (3,600 ticks each) in the browser with every checksum the
  recorded one; it is asked to keep its debug switches out of the web and to drop its CMake web
  build after release 0.8.0. `src/snes` is gone already. `src/platform` stays for the game's
  desktop until the kit's window has the game's development features (the game's ADR-0010),
  planned for M10 in the [roadmap](roadmap.md#m10-language-platform-and-usability-0100).

## Not scheduled

macOS (raylib runs there; nobody can test it now), `bundle`/`deploy` (one folder with every
target), the desktop running the ROM in an emulator, Mesen as the desktop, SPC700 sound and SNES
save RAM.
