# tools

Tools of the dev kit for game developers (ADR [record 29](../docs/adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)).
They are Python 3 for now (one page for the browser), without libraries beyond the standard ones; whether they stay Python
or become Java is an open question of the [roadmap](../docs/roadmap.md#open-questions). The
scripts for developing the dev kit itself are in [dev/](../dev/README.md), and so are the
desktop's build scripts (`build_desktop.sh`, `build_desktop_windows.sh`, `fetch_raylib.sh`,
`check_game_symbols.py`) until the `superdevkit` command takes their place (roadmap M7).

| Tool | What it does | Call | Writes |
| --- | --- | --- | --- |
| `png2snes.py` | PNG to SNES graphics as C for dampf: `tiles` cuts an image of up to 16 colours (indexed, or RGB/RGBA with transparent pixels as colour 0) into 8 x 8 tiles of 4 bits per pixel, in reading order (`tiles2`: 2 bits per pixel, 4 colours, for BG3 of mode 1) (a sheet 128 pixels wide has the order of VRAM, so a 16 x 16 sprite at tile n is n, n + 1, n + 16, n + 17), with its palette in BGR 5:5:5; `palette` reads only the colours of the first row of a strip, for another palette of the same tiles; `mode7` makes a picture for mode 7 from an indexed PNG 1024 wide: one byte a pixel, palette index i as CGRAM colour FIRST - 1 + i, identical 8 x 8 tiles stored once (at most 256), the map of 128 tiles a row. | `tools/png2snes.py tiles sheet.png NAME out_dir`, `tools/png2snes.py palette strip.png NAME out_dir`, `tools/png2snes.py mode7 map.png NAME out_dir [FIRST]` | `NAME.c` and `NAME.h`: `NAME_tiles[]`, `NAME_pal[]`, `NAME_TILE_COUNT`, `NAME_TILES_SIZE`, `NAME_PAL_SIZE`; with `mode7` also `NAME_map[]`, `NAME_MAP_SIZE`, `NAME_PAL_FIRST` |
| `brr_lab.html` | The editor of a game's sound effects, `assets/sfx/sfx.json` ([docs/sound.md](../docs/sound.md)): opens the folder (a new one starts with one effect per WAV file; WAV files from elsewhere are copied into it), shows the effects as cards (id, priority, the sizes of WAV and BRR, their ratio, the length; sortable) and the samples of the chosen one (the WAV file, which one is active, rate, treble, gain, trim, volume; one button sets the rate of all), plays each sample as the original and as the SNES plays it, the variants one after the other, and the effects with the voices and priorities of the file; shows the total against the audio RAM and the errors the build would stop on. Its conversion is the build's (`superdevkit sfx`), in JavaScript, with integers only: the same bytes, which `SfxTest` checks under Node. A browser page, so not Python; no network, no libraries. | open `tools/brr_lab.html` in a browser | `sfx.json` into the folder (Chrome, Edge) or as a download; a sample's `.brr` on request |
| `sdevkit_rename.py` | Converts a game from the old names (PVSnesLib's, the `snesXxx` extensions, `KEY_*`, `u8`...) to the `sdevkit_` ones (ADR record 34): identifiers only, not strings or comments (`--comments` changes those too), and `"snes/snes.h"` to `"sdevkit/snes.h"`. A file that includes `raylib.h` (a frontend, with raylib's own `KEY_*`) is skipped with a warning. | `tools/sdevkit_rename.py [--dry-run] [--comments] folder_or_file...` | the files, in place; prints each file and how many names changed |

The sound converter itself is Java, in the `superdevkit` command (`superdevkit sfx`, [docs/sound.md](../docs/sound.md)).

Their tests are in `compiler/src/test/python`: `test_png2snes.py` tests the converter (round trips of
tiles and colours) and `test_sdevkit_rename.py` the rename script; `ToolsTest` runs both in the
Gradle build. The BRR Lab's tests are Playwright tests in a browser, in
[`lab_test`](lab_test/README.md) (`BrrLabTest` runs them).
