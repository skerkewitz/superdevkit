# tools

Tools of the dev kit for game developers ([ADR-6F3CJX8T](../docs/adr/6F3CJX8T-superdevkit-is-the-project-and-dampf-is-its.md)).
The converters are part of the `superdevkit` command, in Java (ADR-6F60Z9QJ: building needs nothing
but Java and, for the desktop, a C compiler): `superdevkit png` for pictures, `superdevkit sfx` for
the sound effects, both run by `superdevkit build` from a project's `superdevkit.toml`. Here are the
rest: a page for the browser and a script in Python 3 (no libraries beyond the standard ones). The
scripts for developing the dev kit itself are in [dev/](../dev/README.md).

| Tool | What it does | Call | Writes |
| --- | --- | --- | --- |
| `brr_lab.html` | The editor of a game's sound effects, `assets/sfx/sfx.json` ([docs/sound.md](../docs/sound.md)): opens the folder (a new one starts with one effect per WAV file; WAV files from elsewhere are copied into it), shows the effects as cards (id, priority, the sizes of WAV and BRR, their ratio, the length; sortable) and the samples of the chosen one (the WAV file, which one is active, rate, treble, gain, trim, volume; one button sets the rate of all), plays each sample as the original and as the SNES plays it, the variants one after the other, and the effects with the voices and priorities of the file; shows the total against the audio RAM and the errors the build would stop on. Its conversion is the build's (`superdevkit sfx`), in JavaScript, with integers only: the same bytes, which `SfxTest` checks under Node. A browser page, so not Python; no network, no libraries. | open `tools/brr_lab.html` in a browser | `sfx.json` into the folder (Chrome, Edge) or as a download; a sample's `.brr` on request |
| `sdevkit_rename.py` | Converts a game from the old names (PVSnesLib's, the `snesXxx` extensions, `KEY_*`, `u8`...) to the `sdevkit_` ones (ADR-6F3CK2AR): identifiers only, not strings or comments (`--comments` changes those too), and `"snes/snes.h"` to `"sdevkit/snes.h"`. A file that includes `raylib.h` (a frontend, with raylib's own `KEY_*`) is skipped with a warning. | `tools/sdevkit_rename.py [--dry-run] [--comments] folder_or_file...` | the files, in place; prints each file and how many names changed |

The picture converter, `superdevkit png tiles|tiles2|palette|mode7 picture.png NAME out_dir [FIRST]`
(`cli/src/main/java/superdevkit/gfx`): `tiles` cuts an image of up to 16 colours (indexed, or
RGB/RGBA with transparent pixels as colour 0) into 8 x 8 tiles of 4 bits per pixel, in reading order
(a sheet 128 pixels wide has the order of VRAM, so a 16 x 16 sprite at tile n is n, n + 1, n + 16,
n + 17), with its palette in BGR 5:5:5; `tiles2` the same with 2 bits per pixel (4 colours, for BG3
of mode 1); `palette` reads only the colours of the first row of a strip; `mode7` makes a picture for
mode 7 from an indexed PNG 1024 wide (one byte a pixel, palette index i as CGRAM colour FIRST - 1 + i,
identical 8 x 8 tiles once, at most 256, the map of 128 tiles a row). It writes `NAME.c` and `NAME.h`:
`NAME_tiles[]`, `NAME_pal[]`, `NAME_TILE_COUNT`, `NAME_TILES_SIZE`, `NAME_PAL_SIZE`, and for mode 7
`NAME_map[]`, `NAME_MAP_SIZE`, `NAME_PAL_FIRST`. A project names its pictures in `superdevkit.toml`
(`[graphics]`). The sound converter is `superdevkit sfx` ([docs/sound.md](../docs/sound.md)).

The rename script's tests are in `compiler/src/test/python` (`test_sdevkit_rename.py`, run by
`ToolsTest`); the converters' are Java (`Png2SnesTest`, `SfxTest`). The BRR Lab's tests are Playwright tests in a browser, in
[`lab_test`](lab_test/README.md) (`BrrLabTest` runs them). [`web_check`](web_check/README.md)
plays an example's `check.txt` in its web build in headless Chromium, for `ExamplesTest`.
