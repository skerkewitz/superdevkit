# dev

Scripts for developing dampf and the dev kit: the examples' benchmark, the examples in Mesen,
Mesen itself, the examples' art. Run them from anywhere; each changes to the repository root
first. The shell scripts that compile call `./gradlew -q installDist` and use
`build/install/dampf/bin/dampf`. (Blubber Blasen's benchmark, playable ROM, replays and snapshot
left with the game's code, ADR record 40.)

The scripts that run Mesen take `MESEN=<path>` for the
executable (default: MesenCE in `.mesen/`, the Linux build before the Windows one; `mesen.sh`).
`fetch_mesen.sh` puts it there ([ADR record 33](../docs/adr/0033-mesence-is-the-emulator-for-the-console-checks.md)).
The script copies the ROM and a Lua script into Mesen's folder, runs `--testrunner` there and
moves the result into `build/`.

| Script | What it does | Arguments and variables | Needs | Writes |
| --- | --- | --- | --- | --- |
| `bench_examples.sh` | The examples' benchmark: builds every example that has a `check.txt` with dampf, with the options given (a code generator option, a tuned parameter), plays its check in dampf's emulator (`dampf.emu.ExampleBench`) and prints a table: ticks, frames per tick, the work per tick (master cycles without the main loop's waiting) on average and in the busiest 60 ticks, and the share of dampf's runtime. With `BASELINE=<an earlier table>` also the ratio of the work to it, per example and as the geometric mean. | options for `dampf build`; `BASELINE` | Python 3 (the examples' converters) | `build/bench-examples/examples.txt`, the ROMs beside it |
| `fetch_mesen.sh` | Puts MesenCE (a pinned version) into `.mesen/`: the Linux and the Windows build, portable, with the settings the scripts and tests need (SNES joypads, Lua file access, no frame skipping); on Arch also the libraries the Linux build needs, into `.mesen/lib`. | | curl, unzip, Python 3 | `.mesen/`, `build/mesen/` |
| `mesen.sh` | Sourced by the scripts that run Mesen: picks `MESEN` and runs the test runner in its folder (`mesen_run`). | `MESEN` | | |
| `check_example.sh` | Plays an example (`examples/<name>`) headless in Mesen with the input of its `check.txt` (`example_check.lua`): bytes written to VRAM, CGRAM or OAM outside a vertical blank, screenshots, the checksum of every tick (`hashes.txt`). Fails when a byte is lost. | `<name>`; `NOBUILD=1` skips the example's `build.sh`; `MESEN` | Mesen, Python 3 | `examples/<name>/build/check/` |
| `compare_example.sh` | Plays an example on the SNES (Mesen, `check_example.sh`) and on the desktop (its headless check) with the same input and compares the screenshots pixel for pixel (`compare_shots.py`) and the checksums of every tick (`sdevkit_dbg_hash`): dampf's code and gcc's, `lib/snes` and [Rachael](../lib/README.md#rachael) must compute and show the same. | `<name>` | Mesen, gcc, raylib, Python 3 | `examples/<name>/build/check/`, `build/desktop-check/` |
| `compare_shots.py` | Compares two folders of screenshots at the SNES's 5 bits per colour. | `snes_dir desktop_dir` | Python 3 | standard output |
| `example_check.lua` | Mesen script for `check_example.sh`: the input (`ticks N`, `press T1 T2 buttons`, `shot T`, `splash N` (frame N of the splash screen, before the first tick) lines of `check.txt`, counted in ticks so that a tick longer than a frame does not change the run), the count of lost bytes and of ticks; the checksum of every tick, from the ports the kit's main loop writes (`$21FD`: a tick ended; `$21FC`: memory named by `sdevkit_dbg_hash`, read and hashed here). | | Mesen | `example_check.txt`, `example_hashes.txt` and screenshots in Mesen's folder |
| `art/` | How the examples' graphics were drawn, by program: `pix.py` (a small raster: shapes with shading, outlines, PNG output), one script per example (`catchme.py`, `ertuep.py`, ...). They write the PNG files of the examples' `assets/`. | `python3 dev/art/catchme.py examples/catchme/assets` | Python 3 | the PNG files |
| `subset_scan.py` | M0 scanner: counts which C constructs a code base uses, from clang's JSON AST. | `[--details] -I<dir> ... file.c ...` | clang, Python 3 | standard output |
