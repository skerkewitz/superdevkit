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
| `brr_lab.html` | A page for trying sound effects: drop a WAV, choose the sample rate (the DSP's pitch register is shown), a treble boost and a gain, and hear the original, the resampled sound and the SNES's: encoded to BRR (every filter and shift tried per block, against the real decoder) and decoded and interpolated as the S-DSP does, with the chip's Gaussian table. Shows the size in bytes and the share of the 64 KB audio RAM, adds effects up to a budget, and saves the `.brr`. Envelopes, volume, echo and the analog filter are not modelled. A browser page, so not Python; no network, no libraries. | open `tools/brr_lab.html` in a browser | the `.brr` file, on request |
| `sdevkit_rename.py` | Converts a game from the old names (PVSnesLib's, the `snesXxx` extensions, `KEY_*`, `u8`...) to the `sdevkit_` ones (ADR record 34): identifiers only, not strings or comments (`--comments` changes those too), and `"snes/snes.h"` to `"sdevkit/snes.h"`. A file that includes `raylib.h` (a frontend, with raylib's own `KEY_*`) is skipped with a warning. | `tools/sdevkit_rename.py [--dry-run] [--comments] folder_or_file...` | the files, in place; prints each file and how many names changed |

Their tests are in `compiler/src/test/python`: `test_png2snes.py` tests the converter (round trips of
tiles and colours) and `test_sdevkit_rename.py` the rename script; `ToolsTest` runs both in the
Gradle build.
