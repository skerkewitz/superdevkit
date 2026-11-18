# tools

Tools of the dev kit for game developers (ADR [record 29](../docs/adr/0029-superdevkit-is-the-project-and-dampf-is-its.md)).
They are Python 3 for now, without libraries beyond the standard ones; whether they stay Python
or become Java is an open question of the [roadmap](../docs/roadmap.md#open-questions). The
scripts for developing the dev kit itself are in [dev/](../dev/README.md).

| Tool | What it does | Call | Writes |
| --- | --- | --- | --- |
| `png2snes.py` | PNG to SNES graphics as C for dampf: `tiles` cuts an image of up to 16 colours (indexed, or RGB/RGBA with transparent pixels as colour 0) into 8 x 8 tiles of 4 bits per pixel, in reading order (a sheet 128 pixels wide has the order of VRAM, so a 16 x 16 sprite at tile n is n, n + 1, n + 16, n + 17), with its palette in BGR 5:5:5; `palette` reads only the colours of the first row of a strip, for another palette of the same tiles. | `tools/png2snes.py tiles sheet.png NAME out_dir`, `tools/png2snes.py palette strip.png NAME out_dir` | `NAME.c` and `NAME.h`: `NAME_tiles[]`, `NAME_pal[]`, `NAME_TILE_COUNT`, `NAME_TILES_SIZE`, `NAME_PAL_SIZE` |

| `fetch_raylib.sh` | Fetches raylib, ready-built and pinned to one version (5.5), for a desktop target (ADR record 31). | `tools/fetch_raylib.sh linux` | `build/raylib/linux/` (not in git) |
| `build_desktop.sh` | Builds an example for the desktop with the system's C compiler and raylib, on the pseudo SNES: the same game code as on the SNES. Until the `superdevkit` command (roadmap M7). | `tools/build_desktop.sh examples/<name>`; `CC` picks the compiler | `examples/<name>/build/linux/<name>`: run it for a window, or with `--check check.txt --out dir` |

`test_png2snes.py` tests the converter (round trips of tiles and colours); `ToolsTest` runs it in
the Gradle build.
