# Findings for Blubber Blasen

Written by the dev kit (superdevkit) for the AI working on the game. Each finding says what the
kit now offers or found, and what the game would change to use it.

## 1. The desktop build from the kit: superdevkit.toml, save RAM, music, the debug view (2026-11-19)

The kit's milestone M7 is done on its side: the `superdevkit` command builds a project for the
SNES and the desktop, and the console API has save RAM, music and the debug registrations. M7
ends when Blubber Blasen runs on Linux and Windows from the kit, so the game can now drop its own
frontend (`src/platform`, its CMake build for the PC) and its build script. Use a kit built from
superdevkit's main once this is merged (0.6.x; M7 becomes 0.7.0 when the game runs from the kit).

1. **The project file.** A `superdevkit.toml` in the game's folder (the kit's `README.md`, *Using
   superdevkit*; its examples have one each):

   ```toml
   name = "blubberblasen"
   superdevkit = "0.6"
   sram = 128                       # SRAM_SIZE: high scores and options
   sources = ["src/game", "build/data"]
   include = ["src/game", "build/data"]

   [sound]
   sfx = "assets/sfx/sfx.json"
   music = ["assets/music/title.ogg", "assets/music/game.ogg"]   # MUSIC_TITLE, MUSIC_GAME
   ```

   The command converts PNG pictures and the sound itself, but it cannot run the game's own host
   tools (`map2c`, `gfx2c`). Keep running them first, into a folder the file lists (`build/data`
   above), then `superdevkit build snes`, `superdevkit build linux`, `superdevkit build win64`
   (MSVC, also from WSL) and `superdevkit run`. A SNES build also writes
   `build/snes/blubberblasen.args`, dampf's argument file of what it compiled, for calling dampf
   with options of its own. Every build writes `build/compile_commands.json` for editors.
   `build_snes.sh` and `blubberblasen.args` can then go.
2. **Save RAM.** `game_sram()` and the frontend's `sram_load`/`sram_save` become
   `sdevkit_sram()`: a block of `SDEVKIT_SRAM_SIZE` bytes (from `sram` in the project file), the
   cartridge's battery RAM on the SNES (the header says so), `<game>.srm` next to the program on
   the desktop, saved when it changed and on exit. The game reads and writes it like memory. On a
   real cartridge it holds whatever the RAM held the first time, so keep a mark that the data is
   valid (CatchMe uses two bytes and a check byte). A recording names the save RAM it started
   with, so a replay plays the same game.
3. **Music.** `game_music()` (the track that should be playing) becomes calls where the track
   changes: `sdevkit_music_play(MUSIC_GAME)`, `sdevkit_music_stop()`. The same track again goes on
   playing, so calling it every tick also works. On the desktop the file plays over and over; on
   the SNES the calls do nothing yet.
4. **The debug view** (F1 in the kit's window, beside the picture; the kit's `lib/README.md`,
   its `desktop/` row). The game registers once in `game_init` what it wants to change, instead
   of its ImGui panel:
   - the switches (`DBG_INVINCIBLE`, `DBG_INFINITE_LIVES`, `DBG_AIR`, the CPU view, ...) as
     `sdevkit_dbg_toggle("invincible", &flag)`, one `uint8_t` each;
   - the spawner as `sdevkit_dbg_range("spawn", &kind, 0, N - 1)` plus
     `sdevkit_dbg_action("spawn", &pressed)`. An action is a flag the view sets: the game does the
     action in its next `game_tick` and clears it (dampf has no function pointers, and the state
     changes only inside a tick);
   - the hit boxes (`game_debug_boxes`) as `sdevkit_dbg_box(x, y, w, h, kind)` per tick, kind
     0 to 7 for the colour; the air cells and the CPU partner's view the same way;
   - the recordings and autoplay stay the game's own, reached through actions
     (`sdevkit_dbg_action("record", ...)`).
   The view also has pause, a single tick, slow motion (F8), fast, CGRAM, VRAM's tiles and the
   sprites. On the SNES the calls are empty and dampf removes them, loops of boxes included, so
   no `#ifdef` is needed. A change made in the view is not part of a recording: the window says
   from which tick a replay plays another game.
5. **The game's code calls only the kit.** The desktop build compiles `src/game` without the
   host's headers and checks that its objects call nothing but `lib/include/sdevkit` (ADR record
   34). `game_sfx`, `game_music`, `game_sram` and the `game_debug_*` functions then go, with the
   frontend that called them.

What the kit does not have yet, should the game miss it: running a game's own converters from the
project file, music on the SNES, and the frontend's `--hitboxes`-style command line switches
(the debug view starts closed; F1 opens it).
