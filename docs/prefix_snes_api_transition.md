# Prefixing the console API with `sdevkit_`

A plan for renaming superdevkit's console API so that every name a game can use starts with
`sdevkit_`, and for making sure on every platform that a game reaches nothing else. It amends
ADR records 29 and 32. Decided with the user on 2026-11-18; this document is the brief for the
implementation.

## What was decided

1. Every function, variable, type and macro that superdevkit offers to a game starts with
   `sdevkit_` (functions, variables, types) or `SDEVKIT_` (macros and constants), followed by a
   snake_case name. Example: `oamSetXY` becomes `sdevkit_oam_set_xy`, `KEY_A` becomes
   `SDEVKIT_KEY_A`.
2. Calls that only make sense with the debug view of desktop and web start with `sdevkit_dbg_`.
   They exist on every platform (empty functions on the SNES, as ADR 32 already says), and they
   never change what the game computes.
3. There is no `sdevkit_ext_` category for now. It is added only when a concrete feature needs
   it; the rule for it is in *Out of scope* below.
4. These are the only names a game can reach. raylib, libc, the pseudo SNES's internals and the
   library's own helpers are hidden, on the desktop and the web as well as on the SNES, and the
   build checks it.
5. The C standard library keeps its standard names (`memcpy`, `memset`, and whatever dampf's
   headers add later), but a game gets only what dampf's own include folder declares. A function
   that behaves differently from the standard one gets the `sdevkit_` prefix instead of the
   standard name.

Names cost nothing on the console: the linker turns them into addresses, so the ROM and the
cycles do not change with the length of a name. Only the `.s`, `.dbg` and label files on the host
get longer.

## Why

Today `lib/include/snes/snes.h` mixes three naming schemes, and ADR 32 plans a fourth:

- PVSnesLib's names (`setMode`, `bgSetScroll`, `oamSet`, the variable `oamMemory`);
- the kit's extensions (`snesRegWrite`, `snesHdmaSet`, `snesDmaFits`);
- the PC side (`snes_vblank`, `snes_render`, `snes_vram`);
- sound, save RAM and the debug view from ADR 32 (`sfxPlay`, `snesSram`, `debugToggle`), not
  built yet.

Unprefixed names already clash. raylib names its keys `KEY_UP`, `KEY_A` and so on, so
`lib/desktop/main.c` has to `#undef` all twelve joypad macros of `loop.h` after including it. A
game on the desktop is linked into one executable with raylib, libc, `lib/pseudo` and
`lib/desktop`, so any global name of the game can collide with one of theirs, and the game can
call any of their functions. On the SNES, dampf compiles the game and `lib/snes` as one program
(ADR 5), so the library's helpers with external linkage (`platform_oam_upload`,
`platform_wait_vblank`, `platform_read_pads`, `platform_reset_video`) share the namespace with
the game.

ADR 32 already states that PVSnesLib's form is no reason by itself. The cost is that code written
for PVSnesLib no longer compiles unchanged; the rename script of step 6 makes up for it.

## The naming rules

| What | Rule | Examples |
| --- | --- | --- |
| Functions and variables of the console API | `sdevkit_` + snake_case | `sdevkit_oam_set_xy`, `sdevkit_oam_memory` |
| Debug-only functions | `sdevkit_dbg_` + snake_case | `sdevkit_dbg_toggle`, `sdevkit_dbg_vram` |
| Macros and constants | `SDEVKIT_` + the rest in capitals; a PVSnesLib constant keeps the rest of its name | `SDEVKIT_KEY_A`, `SDEVKIT_BG1_ENABLE`, `SDEVKIT_CM_SUB_MAINSUB`, `SDEVKIT_RGB5` |
| Types | `sdevkit_` + snake_case, no `_t` suffix | (none in the console API today) |
| Feature macros | `SDEVKIT_HAS_` + feature | `SDEVKIT_HAS_SOUND` (ADR 29 called it `SDK_HAS_SOUND`) |
| Include guards | `SDEVKIT_` + file | `SDEVKIT_SNES_H` |
| Header paths | `lib/include/sdevkit/` | `#include "sdevkit/snes.h"`, `#include "sdevkit/loop.h"` |
| The library's internal names | `static` where possible; across files `sdevkit__` (two underscores), declared only in headers outside `lib/include` | `sdevkit__oam_upload` in `lib/snes/hw.h` |
| Callbacks the game defines | stay as they are: they are the game's names | `game_init`, `game_tick`, `game_vblank` |
| dampf's own names | stay as they are: they belong to the compiler, not the kit | `DAMPF_RANGE`, `DAMPF_BUDGET` in `<dampf.h>` |
| C standard library | standard names, only what dampf's include folder declares | `memcpy`, `memset` from dampf's `<string.h>` |

No kit name may start with `game_`, so the callbacks stay the game's.

The typedefs `u8`, `u16`, `s16` and `u32` leave the public header: they are names in the game's
namespace without a prefix. The console API uses `uint8_t`, `uint16_t`, `int16_t` and `uint32_t`
from `<stdint.h>`. The examples use `u8` in a few places (`examples/catchme/src/game.c`,
`examples/hello/src/hello.c`, `examples/steinschlag/src/game.c`); they switch to `<stdint.h>`
types. The compatibility header of step 1 keeps the typedefs for Blubber Blasen.

The PC side of `snes.h` (`snes_reset`, `snes_vblank`, `snes_render`, `snes_display`,
`snes_stats`, `snes_vram`, `snes_cgram`, `snes_oam`, `snes_reg`, `SnesStats`, `SNES_W`, `SNES_H`,
`SNES_VRAM_WORDS`, `SNES_VBLANK_DMA_BYTES`) is the interface between the pseudo SNES and the
frontends, not for games. It moves out of the public header into `lib/pseudo/pseudo.h` with
`sdevkit__` names. The exception is `snes_vram()`: Blubber Blasen's `video.c` calls it for a debug
check (`snapshots/blubberblasen/src/game/video.c:593`), and it exists on the SNES too. It becomes
the public debug call `sdevkit_dbg_vram()`.

## The rename table

### Functions and variables

| Today | New |
| --- | --- |
| `setMode` | `sdevkit_set_mode` |
| `setBrightness` | `sdevkit_set_brightness` |
| `setScreenOn` | `sdevkit_set_screen_on` |
| `setScreenOff` | `sdevkit_set_screen_off` |
| `setColorEffect` | `sdevkit_set_color_effect` |
| `setColorIntensity` | `sdevkit_set_color_intensity` |
| `setPaletteColor` | `sdevkit_set_palette_color` |
| `dmaCopyVram` | `sdevkit_dma_copy_vram` |
| `dmaCopyCGram` | `sdevkit_dma_copy_cgram` |
| `dmaClearVram` | `sdevkit_dma_clear_vram` |
| `bgSetGfxPtr` | `sdevkit_bg_set_gfx_ptr` |
| `bgSetMapPtr` | `sdevkit_bg_set_map_ptr` |
| `bgSetScroll` | `sdevkit_bg_set_scroll` |
| `bgSetEnable` | `sdevkit_bg_set_enable` |
| `bgSetDisable` | `sdevkit_bg_set_disable` |
| `bgSetEnableSub` | `sdevkit_bg_set_enable_sub` |
| `bgSetDisableSub` | `sdevkit_bg_set_disable_sub` |
| `bgInitTileSet` | `sdevkit_bg_init_tile_set` |
| `bgInitMapSet` | `sdevkit_bg_init_map_set` |
| `oamInit` | `sdevkit_oam_init` |
| `oamInitGfxSet` | `sdevkit_oam_init_gfx_set` |
| `oamInitGfxAttr` | `sdevkit_oam_init_gfx_attr` |
| `oamSet` | `sdevkit_oam_set` |
| `oamSetXY` | `sdevkit_oam_set_xy` |
| `oamSetEx` | `sdevkit_oam_set_ex` |
| `oamSetVisible` | `sdevkit_oam_set_visible` |
| `oamClear` | `sdevkit_oam_clear` |
| `oamMemory` | `sdevkit_oam_memory` |
| `snesRegWrite` | `sdevkit_reg_write` |
| `snesHdmaSet` | `sdevkit_hdma_set` |
| `snesHdmaEnable` | `sdevkit_hdma_enable` |
| `snesDmaFits` | `sdevkit_dma_fits` |
| `snes_vram` (game's debug check) | `sdevkit_dbg_vram` |

The calls ADR 32 plans get the new names from the start: `sdevkit_sfx_play`,
`sdevkit_music_play`, `sdevkit_music_stop`, `sdevkit_sram` with `SDEVKIT_SRAM_SIZE`,
`sdevkit_dbg_toggle`, `sdevkit_dbg_range`, `sdevkit_dbg_color`, `sdevkit_dbg_action`,
`sdevkit_dbg_box`.

### Macros and constants

Every macro of `snes.h` and `loop.h` gets `SDEVKIT_` in front and keeps the rest of its name:
`RGB5`, `RGB8`, `BG_MODE1`, `BG3_MODE1_PRIORITY_HIGH`, `BG1_ENABLE` to `BG4_ENABLE`,
`OBJ_ENABLE`, `SC_32x32` to `SC_64x64`, `BG_4COLORS`, `BG_16COLORS`, `BG_TIL_NUM`, `BG_TIL_PAL`,
`BG_TIL_PRIO`, `BG_TIL_FLIPX`, `BG_TIL_FLIPY`, the `OBJ_SIZE*` values, `OBJ_SMALL`, `OBJ_LARGE`,
`OBJ_SHOW`, `OBJ_HIDE`, `ATTR2_DISABLED`, all `CM_*`, `HDMA_CHANNEL0` to `HDMA_CHANNEL7`, all
`PPU_*`, all `KEY_*`, and `SNES_PADS` (which becomes `SDEVKIT_PADS`, the one case where a `SNES_`
in the name is dropped instead of kept).

### The library's internal names

| Today | New |
| --- | --- |
| `platform_oam_upload` | `sdevkit__oam_upload` |
| `platform_reset_video` | `sdevkit__reset_video` |
| `platform_wait_vblank` | `sdevkit__wait_vblank` |
| `platform_read_pads` | `sdevkit__read_pads` |
| `snes_write_reg` (`lib/pseudo/pseudo.h`) | `sdevkit__write_reg` |
| the PC side `snes_*` (see above) | `sdevkit__*`, in `lib/pseudo/pseudo.h` |

Any other function or variable in `lib/` with external linkage that the list misses follows the
same rule: `static` if only its own file uses it, `sdevkit__` otherwise. The naming test of step 4
finds them.

## How it is enforced

### On every platform: the headers

A game is compiled with only three include roots besides its own folders: `lib/include`
(superdevkit's public headers), dampf's include folder (`<stdint.h>`, `<string.h>`,
`<dampf.h>`), and the folder of generated assets. dampf does this already
(`gcc -E -nostdinc -undef`, ADR 7). The desktop and the web build must do the same with the host
compiler: `-nostdinc`, then `-isystem` on dampf's include folder, so `#include <stdio.h>`,
`<stdlib.h>` or `<raylib.h>` in game code fails at compile time.

Two consequences to check while building it:

- dampf's `<stdint.h>` and `<string.h>` must compile with the host compiler under `-nostdinc`.
  dampf's `memcpy` and `memset` are `static` functions in its `<string.h>` and take a `uint16_t`
  length; on the PC the game then gets these, and gcc still calls the libc `memcpy` for struct
  copies, which links fine.
- The game's include path must not contain `lib/snes`, `lib/pseudo` or `lib/desktop`.
  `snapshots/blubberblasen.args` has `-I ../lib/snes` today; the library's sources are listed
  there as sources, so the include path entry should not be needed. If it is, find out why
  before removing it.

### On the desktop and the web: the symbols

A game can still write `extern void DrawText(...);` by hand. So the desktop and web builds compile
the game's sources into object files of their own, separately from `lib/pseudo` and
`lib/desktop` (or `lib/web`), and check them before linking: every undefined symbol of the
game's objects (`nm -u`) must be in an allowlist, or the build fails with the name of the symbol
and of the object file. The allowlist is

- every function and variable declared in `lib/include/sdevkit/*.h`, read from the headers, not
  written by hand;
- the symbols the host compiler emits on its own: `memcpy`, `memset`, `memmove`, and whatever
  else turns up (`__stack_chk_fail` on distributions that build with stack protection by
  default). Each one is added with a comment saying which compiler emits it and why.

`tools/build_desktop.sh` compiles everything in one `cc` call today; it needs two steps (game
objects, then the kit and the link) with the check in between.

### On the SNES: whole program

dampf sees the game and `lib/snes` as one program. The check there is a test of the library:
every definition with external linkage in `lib/` is either `sdevkit_*`, `sdevkit__*`, `main`, or
an interrupt handler of the runtime. That keeps the library from taking a name the game might
use. A game that declares `sdevkit__oam_upload` itself can still call it; that is a deliberate
act, and the double underscore says it is not part of the API.

### Tests

- `LibApiTest` reads `lib/include/sdevkit/snes.h` instead of `lib/include/snes/snes.h`. With the
  PC side moved out, it no longer needs to stop at "PC side".
- A new `LibNamesTest`: every identifier that `lib/include/sdevkit/*.h` declares or defines
  starts with `sdevkit_` or `SDEVKIT_` (except the game callbacks `game_init`, `game_tick`,
  `game_vblank`); every external definition in `lib/` follows the rule of the previous
  paragraph.
- A test of the desktop check itself: a small game that includes `<stdio.h>` must fail to
  compile, and one that declares and calls a raylib function by hand must fail the symbol check,
  each with the expected message.

## Steps

Each step leaves `./gradlew build` green, the replays passing with `-Preplays`, and is one
commit on a branch of its own (the rules for autonomous runs).

**Step 0. ADR.** Write ADR record 33 (or the next free number) with the decision and the reasons
above, amending records 29 (header layout, `SDK_HAS_SOUND`) and 32 (the names of the sound, save
RAM and debug calls). Link it from records 29 and 32 and from `docs/ADR.md`.

**Step 1. New headers, library renamed, compatibility header.** Create `lib/include/sdevkit/snes.h`
and `lib/include/sdevkit/loop.h` with the new names, the `<stdint.h>` types, and without the PC
side. Rename everything in `lib/snes`, `lib/pseudo` and `lib/desktop` to the new names and the
internal ones to `sdevkit__`. Turn `lib/include/snes/snes.h` and `lib/include/snes/loop.h` into
compatibility headers: they include the new headers and map every old name to the new one
(`#define setMode sdevkit_set_mode`, `#define KEY_A SDEVKIT_KEY_A`, `#define snes_vram
sdevkit_dbg_vram`), with the typedefs `u8` to `u32`. They exist only so that the snapshot of
Blubber Blasen, which includes `"snes/snes.h"` and must not be edited, keeps building. They are
marked deprecated in their first comment.

Check: the snapshot's ROM builds; the replays and the game's benchmark give the same checksums.
The ROM bytes may move: the code generator names each function's segment after the function
(`C_<name>`), and if the bank packing breaks ties between segments of equal size by name, the
layout changes. Cycles per tick should then stay the same; any difference goes into the commit
message with its reason (a branch that now crosses a page, say).

**Step 2. Examples.** Move `examples/hello`, `examples/catchme` and `examples/steinschlag` to
`#include "sdevkit/..."`, the new names and the `<stdint.h>` types. They must not use the
compatibility headers. Check: `dev/check_example.sh` for each example on the SNES, and
`tools/build_desktop.sh` with the `--check` run on the desktop, with the same screenshots as
before.

**Step 3. The desktop frontend.** `lib/desktop/main.c` includes the new headers and drops the
twelve `#undef KEY_*` lines, which are no longer needed: that the file compiles without them
shows the clash is gone.

**Step 4. Naming test.** `LibNamesTest` as described above, and `LibApiTest` on the new header.

**Step 5. Desktop enforcement.** Split `tools/build_desktop.sh` into game objects and the rest,
compile the game with `-nostdinc` and dampf's include folder, add the `nm -u` check with the
allowlist generated from the public headers, and add the test of the check. The web build (M8)
gets the same check when it is built; note that in its plan.

**Step 6. Rename script.** `tools/sdevkit_rename.py`: rewrites a game's C sources from the old
names to the new ones, using the table of this document (kept in one place: the script reads it
from the compatibility header or the other way round, so the two cannot drift). It changes
identifiers only, not strings or comments, and prints what it changed. It is for Blubber Blasen
and for anyone porting PVSnesLib code. Test: run it on a copy of the snapshot in a temporary
folder; the result compiles with the new headers only and gives the same replay checksums.

**Step 7. Documentation.** `lib/README.md` (the table of functions per platform with the new
names, the build line with the new include path), `lib/snes/README.md`,
`docs/superdevkit-transition.md` (the PC side and `SDK_HAS_SOUND`), the specs in `doc-specs/`
that name API functions, then `/docs-gen stale` for the book pages. Add an entry to
`docs/game-findings.md` for the AI working on Blubber Blasen: the new header path, the rename
script, and that `snes_vram` is now `sdevkit_dbg_vram`.

**Step 8. Remove the compatibility headers.** Only after Blubber Blasen uses the new names and
the snapshot has been refreshed (`dev/update_game_snapshot.sh`). Delete `lib/include/snes/`, and
change the check in `dev/update_game_snapshot.sh` that compares the game's `src/snes/snes.h` with
`lib/include/snes/snes.h`: once the game uses the kit's header, it has no copy of its own to
compare. This step waits for the game; it is not part of this run.

## Constraints

- Never edit `snapshots/blubberblasen` and never touch the game's own repository. What the game
  has to change goes into `docs/game-findings.md`.
- Never edit a book page that starts with `<!-- GENERATED`; change the spec and run `/docs-gen`.
- One fact in one place: the rename table lives in this document until step 6, then in the
  script (or the compatibility header) and this document links to it.

## Out of scope

- An `sdevkit_ext_` category for things the desktop and the web can do and the SNES cannot. If a
  feature needs one later, the rule is: it may change only what is shown (scaling, a wider view,
  more sprites per line drawn), never game state, because the replays require the same checksums
  on the PC and the SNES. A feature that changes game state does not belong in the kit.
- Building sound, save RAM and the debug view of ADR 32. This plan only fixes their names.
- Moving the compiler to `compiler/` (ADR 29).

## Open points for the implementation

Report these back rather than deciding them silently:

- Whether the ROM layout of the snapshot moved in step 1, and whether cycles per tick changed.
- The final allowlist of compiler-emitted symbols for the desktop check, per compiler (gcc,
  clang) and platform.
- Whether dampf's `<stdint.h>` needed a change to compile on the host under `-nostdinc`.
- Whether `-I ../lib/snes` in `snapshots/blubberblasen.args` could be removed.
